package com.xniperbuilds.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 * TIKTOK EXTRACTOR — the real fix for "the download never starts"
 * ============================================================================
 *
 * PROBLEM: on a phone, yt-dlp cannot pull video data out of TikTok at all —
 *   "attempting impersonation, but no impersonate target is available"
 *   → "Unable to extract webpage video data"
 * Why: desktop yt-dlp ships `curl_cffi` (Chrome impersonation), but
 * `youtubedl-android` (0.18.1, the latest) does not bundle it. The result is a
 * download that dies at 0% and never actually starts. This is the same yt-dlp
 * issue #15653 the Seal app hit, closed as "external-issue / not planned".
 * (Instagram applies no such gate, which is why the same engine works fine
 * there — nothing was wrong with the app's own code.)
 *
 * ⚠️ VERIFIED ON A REAL DEVICE AND NETWORK (not guesswork):
 *  · TikTok decides what to serve based on the **USER-AGENT**:
 *      MOBILE UA  → a "reflow" (open-in-app) page — the video data is simply absent
 *      DESKTOP UA → the full `webapp.video-detail` (playAddr/downloadAddr/bitrateInfo)
 *  · Cookies alone are not enough — even replaying TikTok's own cookies on a
 *    mobile UA returns no video data.
 *  · The media CDN answers 403 WITHOUT cookies, and 206 + Range/resume with them.
 *
 * SOLUTION (three routes, fastest first):
 *  1. **Plain HTTP** — fetch the page with a desktop UA + cookies and parse
 *     `__UNIVERSAL_DATA_FOR_REHYDRATION__`. Fastest (~1s), no WebView involved.
 *  2. **WebView** — if TikTok ever demands a JS challenge, the phone's own Chrome
 *     (a genuine fingerprint) loads the page and hands back the same blob.
 *  3. (in Downloader.kt) the yt-dlp fallback.
 * The bytes then come over HttpURLConnection — with cookies, desktop UA and Referer.
 *
 * Login (Connect TikTok) feeds this same cookie jar, so a connected user's private
 * or region-locked videos travel this route automatically.
 */

private const val TT_LOG = "RiploxTT"

/** Media requests pe TikTok CDN Referer dekhta hai. */
const val TT_REFERER = "https://www.tiktok.com/"

/** ⚠️ DESKTOP UA lazmi — mobile UA pe TikTok video data deta hi nahi (upar dekho). */
internal const val TT_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * `sec-ch-ua`, DERIVED from TT_DESKTOP_UA rather than written out a second time.
 *
 * ⚠️ A UA that claims Chrome 131 next to a sec-ch-ua that claims something else is an
 * inconsistent fingerprint, and on its own that is enough for TikTok's WAF to challenge the
 * request. Writing the version in two places guarantees that one day one of them gets bumped
 * and the other does not — the app would then break by itself, months later, with no change
 * to blame. Deriving it makes that impossible.
 */
private val TT_CH_UA: String by lazy {
    val major = Regex("""Chrome/(\d+)""").find(TT_DESKTOP_UA)?.groupValues?.get(1) ?: "131"
    "\"Chromium\";v=\"$major\", \"Google Chrome\";v=\"$major\", \"Not_A Brand\";v=\"24\""
}

/**
 * The full browser header set for a PAGE fetch.
 *
 * ⚠️ MEASURED AGAINST LIVE TIKTOK (2026-08-30), not assumed. Same URL, same desktop UA:
 *   UA + Accept + Accept-Language + Upgrade-Insecure-Requests → 1.4KB "Please wait…" WAF
 *                                                               challenge (this was what the
 *                                                               app had been sending)
 *   + sec-ch-ua alone / + sec-fetch-* alone                   → still the challenge
 *   full set, but a cold cookie jar                           → 44KB empty JS shell, no data
 *   full set + a warmed cookie jar                            → 417KB, webapp.video-detail,
 *                                                               playAddr + 4 bitrate variants
 * These headers are not cosmetic; they are the difference between data and no data.
 *
 * The ceiling is still real: yt-dlp reaches TikTok's web path with curl_cffi TLS impersonation,
 * which HttpURLConnection cannot do. So this route is the FAST one, not the reliable one — the
 * WebView below is the floor that always works.
 */
private fun HttpURLConnection.applyTtPageHeaders(cookie: String) {
    setRequestProperty("User-Agent", TT_DESKTOP_UA)
    setRequestProperty(
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )
    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
    setRequestProperty("Upgrade-Insecure-Requests", "1")
    setRequestProperty("sec-ch-ua", TT_CH_UA)
    setRequestProperty("sec-ch-ua-mobile", "?0")
    setRequestProperty("sec-ch-ua-platform", "\"Windows\"")
    setRequestProperty("sec-fetch-dest", "document")
    setRequestProperty("sec-fetch-mode", "navigate")
    setRequestProperty("sec-fetch-site", "none")
    setRequestProperty("sec-fetch-user", "?1")
    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
}

/**
 * What TikTok actually answered. These used to collapse into one "No page data" error, which
 * sent every case down the same path and told the user nothing true.
 *  BLOB      — the SSR data is there; parse it
 *  CHALLENGE — WAF JS challenge; only a real browser passes it → hand over to the WebView
 *  SHELL     — an empty JS app shell; the fingerprint was refused → hand over to the WebView
 */
/**
 * The last cookie string we managed to read off the main thread.
 *
 * Exists so a busy main thread can never stall an extraction: the reader takes the fresh value
 * when it can get one quickly, and this when it cannot.
 */
internal object TtCookieCache {
    @Volatile
    private var value: String = ""

    fun put(v: String) {
        if (v.isNotBlank()) value = v
    }

    fun get(): String = value
}

private enum class TtPage { BLOB, CHALLENGE, SHELL, UNKNOWN }

private fun pageShape(html: String): TtPage = when {
    html.contains("__UNIVERSAL_DATA_FOR_REHYDRATION__") -> TtPage.BLOB
    html.contains("_wafchallengeid") || html.contains("SlardarWAF") -> TtPage.CHALLENGE
    html.contains("__LOADABLE_REQUIRED_CHUNKS__") -> TtPage.SHELL
    else -> TtPage.UNKNOWN
}

/**
 * Collect a `ttwid` from the home page before asking for a video.
 *
 * ⚠️ With a cold jar the video page comes back as an empty shell even with perfect headers —
 * TikTok wants to have handed you a cookie first. A fresh install has no jar at all, so without
 * this the very first download a new user tries is the one most likely to fail.
 */
private fun warmUpCookies(fresh: MutableMap<String, String>, jarCookie: String, onBeat: () -> Unit) {
    if (mergeCookies(jarCookie, fresh).contains("ttwid=")) return
    val conn = (URL(TT_REFERER).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 20_000
        applyTtPageHeaders(mergeCookies(jarCookie, fresh))
    }
    try {
        conn.responseCode
        onBeat()
        collectCookies(conn, fresh)
        Log.i(TT_LOG, "cookie warm-up got ${fresh.keys}")
    } catch (e: Exception) {
        // Not fatal: the page fetch still gets its turn, and the WebView after that.
        Log.w(TT_LOG, "cookie warm-up failed: ${e.message}")
    } finally {
        try { conn.disconnect() } catch (_: Exception) {}
    }
}

/**
 * Set-Cookie headers → the running jar.
 *
 * ⚠️ Read by INDEX and compared case-insensitively, not looked up as `headerFields["Set-Cookie"]`.
 * Over HTTP/2 the header names arrive lower-cased, and how the map handles that differs by
 * platform — a missed Set-Cookie here is completely invisible, because it looks exactly like a
 * server that sent none. That mattered: on this device the jar stayed empty on every attempt,
 * which is the difference between a download working and not.
 */
private fun collectCookies(conn: HttpURLConnection, fresh: MutableMap<String, String>) {
    var i = 0
    while (i < 100) {
        val value = conn.getHeaderField(i) ?: break
        val key = conn.getHeaderFieldKey(i) // null for the status line
        if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
            val kv = value.substringBefore(';').trim()
            val eq = kv.indexOf('=')
            if (eq > 0) fresh[kv.substring(0, eq)] = kv.substring(eq + 1)
        }
        i++
    }
}

/**
 * Create an off-screen IFRAME, inside an already-loaded tiktok.com page, pointing at the post.
 *
 * ⚠️ THIS IS THE MEASURED RECIPE, and the distinction is exact (2026-08-30, real Chromium):
 *     fetch('https://www.tiktok.com/@i/video/<id>')  → 44,061 bytes, an empty JS shell.
 *                                                      No challenge, but no data either.
 *     an IFRAME to the same URL                     → 414,591 bytes, with
 *                                                      __UNIVERSAL_DATA_FOR_REHYDRATION__,
 *                                                      playAddr and 4 CodecType entries,
 *                                                      in about 1.4 seconds.
 * TikTok server-renders the post for a real NAVIGATION and not for a fetch. An iframe is a real
 * navigation; and because the parent document is already on tiktok.com, it is same-origin, so
 * its contentDocument can simply be read.
 *
 * This replaces "point the WebView at the video page and hope it server-rendered", which
 * produced clean video on only 2 of 5 fresh runs.
 */
private const val JS_IFRAME_START = """
(function(){
  try{
    if(document.getElementById('rx_probe')){return 'exists';}
    if(!document.body){return 'nobody';}
    var f=document.createElement('iframe');
    f.id='rx_probe';
    f.style.cssText='position:fixed;left:-9999px;top:0;width:1280px;height:2000px;opacity:0';
    f.src='%URL%';
    document.body.appendChild(f);
    return 'created';
  }catch(x){return 'err';}
})()
"""

/** Read the SSR blob out of that iframe (same-origin, so this just works). */
private const val JS_IFRAME_GRAB = """
(function(){
  try{
    var f=document.getElementById('rx_probe');
    if(!f||!f.contentDocument){return '';}
    var e=f.contentDocument.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
    if(!e){return '';}
    return e.textContent||e.innerHTML||'';
  }catch(x){return '';}
})()
"""

/** WebView me se blob nikalne wali JS (SSR blob initial HTML me hi hota hai). */
private const val JS_GRAB = """
(function(){
  try{
    var e=document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
    if(!e){return '';}
    return e.textContent||e.innerHTML||'';
  }catch(x){return '';}
})()
"""

/** Ek video quality (TikTok ke `bitrateInfo` se). */
data class TtVariant(
    val gear: String,
    val url: String,
    val width: Int,
    val height: Int,
    val bitrate: Long
)

/** Page se nikla poora media-info. */
data class TtInfo(
    val id: String,
    val title: String,
    val uploader: String,
    val cover: String?,
    val variants: List<TtVariant>,   // sab video qualities
    val playAddr: String?,           // default stream
    val downloadAddr: String?,       // TikTok ka apna "download" stream
    val images: List<String>,        // photo/slideshow post
    val musicUrl: String?,
    val ua: String,
    val cookie: String
) {
    val isPhotoPost: Boolean get() = images.isNotEmpty() && variants.isEmpty() && playAddr.isNullOrBlank()

    /**
     * Quality chip → the actual URL.
     * ⚠️ TikTok is VERTICAL: heights run 1090/1364/2044, so the old yt-dlp selector
     * `bestvideo[height<=1080]` never matched anything and always fell through to
     * `best` (the chips did nothing). The chips mean WIDTH here (540/720/1080).
     */
    fun pickVideo(quality: String): String? {
        if (variants.isEmpty()) return playAddr ?: downloadAddr
        val sorted = variants.sortedWith(compareByDescending<TtVariant> { it.width }.thenByDescending { it.bitrate })
        if (quality == "best") return sorted.first().url
        val cap = quality.toIntOrNull() ?: return sorted.first().url
        // largest at or below the cap; if none qualifies, the smallest available
        return (sorted.firstOrNull { it.width <= cap } ?: sorted.last()).url
    }
}

/** TikTok gave a definitive refusal during extraction (private/deleted/region) — retrying is pointless. */
class TtStatusException(message: String) : Exception(message)

/** Extraction SUCCEEDED, but fetching the media bytes broke (timeout/abort).
 * On this kind of failure the yt-dlp fallback is useless (it cannot extract at all)
 * and the partial file should be kept for resume — hence a separate exception. */
class TtTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ---------------------------------------------------------------------------
// PUBLIC ENTRY
// ---------------------------------------------------------------------------

/**
 * TikTok link → media info.
 * @param useLogin false = TEMPORARILY strip the login cookies for this extraction
 *                 (guest mode / session-failure retry). They are restored afterwards.
 */
suspend fun ttExtract(
    context: Context,
    link: String,
    useLogin: Boolean = true,
    timeoutMs: Long = 45_000L,
    onBeat: () -> Unit = {}
): TtInfo {
    val suppressed = if (!useLogin) suppressTtLogin() else null
    try {
        // ⚠️ CookieManager (the WebView provider) must always be touched from the MAIN
        // thread — on a fresh install, where no WebView has been created in this process
        // yet, initialising it from a background thread can fail (and without cookies the
        // CDN answers 403).
        //
        // ⚠️ BUT NEVER WAIT ON IT FOREVER. Measured on device: with a WebView loading
        // tiktok.com on the main thread ("Skipped 83 frames"), this hop simply never came
        // back, and the whole download sat silent for a minute — no progress, no error, no
        // retry. A download that FAILS gets retried; a download that HANGS does not. The
        // cached copy is what the last successful read saw, and stale cookies are a far
        // better answer here than an unbounded wait.
        /*
         * 0) THE WORKER — tried first, and twice, because it is the only route that returns a
         * video without TikTok's watermark. Measured: ~7 in 10 on the first call; a second
         * attempt a moment later usually catches the rest, because the gate is stochastic
         * rather than a permanent refusal.
         *
         * If it is unreachable the app carries on down the on-device routes rather than
         * failing — a server that is down must not be a phone that cannot download.
         */
        for (attempt in 0 until 2) {
            try {
                val info = withContext(Dispatchers.IO) { ttWorkerExtract(link, onBeat) }
                Log.i(TT_LOG, "ttExtract WORKER ok id=${info.id} variants=${info.variants.size}")
                return info
            } catch (e: Exception) {
                Log.w(TT_LOG, "worker attempt $attempt failed (${e.message})")
                if (attempt == 0) delay(1_200)
            }
        }

        val jarCookie = withTimeoutOrNull(3_000) { readTtCookies() }
            ?.also { TtCookieCache.put(it) }
            ?: TtCookieCache.get().also {
                Log.w(TT_LOG, "cookie read timed out — using cached jar (${it.length} chars)")
            }
        // 1) Plain HTTP — 95% of links resolve here (~1s, no WebView)
        try {
            val info = withContext(Dispatchers.IO) { ttHttpExtract(link, jarCookie, onBeat) }
            Log.i(TT_LOG, "ttExtract HTTP ok id=${info.id} variants=${info.variants.size} images=${info.images.size}")
            return info
        } catch (e: TtStatusException) {
            throw e // a definitive answer (private/deleted) — the WebView would say the same
        } catch (e: Exception) {
            Log.w(TT_LOG, "HTTP extract failed (${e.message})")
        }

        /*
         * 2) WebView — the real Chrome, which can pass the JS challenge.
         *
         * ⚠️ THE ORDER HERE IS THE WHOLE POINT, and it is about WATERMARKS, not speed.
         * Compared frame by frame on the same video (2026-08-30):
         *     main-page data (bitrateInfo)  → 720x1280 HEVC, 1.83 MB, **clean**
         *     embed page <video src>        → 576x1024 H.264, 4.42 MB, **TikTok logo and the
         *                                     @handle burned into the picture**
         * The app promises "no watermark", so the clean stream has to be tried first and the
         * embed can only ever be the safety net. A bigger file is not a better one.
         */
        try {
            Log.i(TT_LOG, "handing over to the browser")
            val info = ttWebViewExtract(context, link, timeoutMs, onBeat)
            Log.i(TT_LOG, "ttExtract WebView ok id=${info.id} variants=${info.variants.size}")
            return info
        } catch (e: TtStatusException) {
            throw e
        } catch (e: Exception) {
            Log.w(TT_LOG, "WebView failed (${e.message}) — falling back to the embed page")
        }

        /*
         * 3) THE EMBED PAGE — never challenged, so it always answers. Watermarked, which is why
         * it is last: a watermarked video beats no video, but only just.
         */
        val info = withContext(Dispatchers.IO) {
            ttEmbedExtract(link, resolveTtLink(link), onBeat)
        }
        Log.i(TT_LOG, "ttExtract EMBED ok (watermarked fallback) id=${info.id}")
        return info
    } finally {
        suppressed?.let { restoreTtLogin(it) }
    }
}

// ---------------------------------------------------------------------------
// RASTA 0 — EXTRACTOR WORKER (clean video, and the only route that reliably gets one)
// ---------------------------------------------------------------------------

/**
 * Ask our own Worker to do the extraction.
 *
 * ⚠️ WHY A SERVER AT ALL, when the app can obviously make HTTP requests itself: TikTok's gate
 * is not about the route, it is about who is asking, and it is stochastic. Measured in the
 * same minute: this phone got a watermarked fallback on 4 of 5 fresh installs (~55s each),
 * while the Worker returns the clean stream in 2–3s. And browser JavaScript cannot send
 * `sec-fetch-mode: navigate` — a forbidden header — which is precisely the header TikTok
 * server-renders for. A server can send it; a phone cannot.
 *
 * This route is FIRST because it is the only one that yields a video without TikTok's
 * watermark burned into it. Everything below it is a fallback for when the Worker is
 * unreachable — the app must never depend on the Worker being up.
 */
private fun ttWorkerExtract(link: String, onBeat: () -> Unit): TtInfo {
    val base = BuildConfig.TT_WORKER_URL.trimEnd('/')
    if (base.isBlank()) throw Exception("worker not configured")

    val url = URL("$base/tt?url=" + java.net.URLEncoder.encode(link, "UTF-8"))
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 25_000
        // A plain library UA gets a 403 from Cloudflare's bot filter before our Worker ever runs.
        setRequestProperty("User-Agent", "RiploxTT/${BuildConfig.VERSION_NAME} (Android)")
        if (BuildConfig.TT_WORKER_TOKEN.isNotBlank()) {
            setRequestProperty("X-Riplox-App", BuildConfig.TT_WORKER_TOKEN)
        }
    }
    val body = try {
        val code = conn.responseCode
        onBeat()
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("worker HTTP $code ${text.take(80)}")
        text
    } finally {
        try { conn.disconnect() } catch (_: Exception) {}
    }

    val o = JSONObject(body)
    o.optString("error").takeIf { it.isNotBlank() }?.let { throw Exception("worker: $it") }

    val variants = mutableListOf<TtVariant>()
    o.optJSONArray("variants")?.let { arr ->
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val vu = v.optString("url").takeIf { it.startsWith("http") } ?: continue
            variants += TtVariant(
                gear = v.optString("codec", ""),
                url = vu,
                width = v.optInt("width", 0),
                height = v.optInt("height", 0),
                bitrate = v.optLong("bitrate", 0L)
            )
        }
    }

    // Photo / carousel posts come back as a list of pictures with no video at all. Treating a
    // post with images as "no media" is exactly why shared carousels refused to download.
    val images = mutableListOf<String>()
    o.optJSONArray("images")?.let { arr ->
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.startsWith("http") }?.let { images += it }
        }
    }

    val h = o.optJSONObject("headers")
    val best = o.optString("best").takeIf { it.startsWith("http") }
    if (best == null && variants.isEmpty() && images.isEmpty()) {
        throw Exception("worker returned no media")
    }

    Log.i(TT_LOG, "WORKER ok id=${o.optString("id")} variants=${variants.size}")
    return TtInfo(
        id = o.optString("id", ttVideoId(link) ?: "tt"),
        title = o.optString("title", "").ifBlank { "TikTok video" },
        uploader = o.optString("author", ""),
        cover = o.optString("cover").takeIf { it.startsWith("http") },
        variants = variants,
        playAddr = best ?: variants.firstOrNull()?.url,
        downloadAddr = null,
        images = images,
        musicUrl = o.optString("music").takeIf { it.startsWith("http") },
        // The CDN wants the same identity the Worker used when it earned these URLs.
        ua = h?.optString("User-Agent")?.takeIf { it.isNotBlank() } ?: TT_DESKTOP_UA,
        cookie = h?.optString("Cookie") ?: ""
    )
}

// ---------------------------------------------------------------------------
// RASTA 1b — EMBED PAGE (the reliable one)
// ---------------------------------------------------------------------------

/**
 * A mobile UA, used ONLY for the embed page. The embed player is served to phones, and asking
 * for it as a desktop browser is the kind of mismatch TikTok's WAF exists to notice.
 */
private const val TT_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/**
 * Turn a vt./vm. short link into the real one, cheaply — HEAD requests, no body downloaded.
 * A link that already carries its id is returned untouched.
 */
private fun resolveTtLink(link: String): String {
    if (ttVideoId(link) != null) return link
    var url = link
    for (hop in 0 until 5) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", TT_MOBILE_UA)
        }
        try {
            val code = conn.responseCode
            if (code !in 300..399) return url
            val loc = conn.getHeaderField("Location") ?: return url
            url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
            if (ttVideoId(url) != null) return url
        } catch (e: Exception) {
            return url
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
    return url
}

/** The numeric post id out of any TikTok URL form we accept. */
private fun ttVideoId(url: String): String? =
    Regex("""/(?:video|photo)/(\d{6,})""").find(url)?.groupValues?.get(1)

/** `@handle` out of a full TikTok URL — only used to keep filenames looking the same. */
private fun ttHandle(url: String): String? =
    Regex("""tiktok\.com/@([A-Za-z0-9._]+)/""").find(url)?.groupValues?.get(1)

/** Just enough entity decoding for a URL pulled out of an HTML attribute. */
private fun unescapeHtml(s: String): String =
    s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

/**
 * THE EMBED ROUTE — the one that does not depend on winning a race.
 *
 * ⚠️ MEASURED, twice, on two different videos (2026-08-30):
 *     GET https://www.tiktok.com/embed/v2/<id>   with a mobile UA and NOTHING else
 *       → HTTP 200, ~312KB, and **no WAF challenge at all**
 *       → the page server-renders a plain <video src="https://vNN.tiktokcdn.com/…mp4…">
 *       → that URL then returns HTTP 206 video/mp4 **with no cookies and no Referer**
 *
 * Compare with what it replaces: the main page is challenged on every single request even when
 * the jar holds ttwid, and the WebView that can pass the challenge succeeded on only two of
 * three identical fresh-install runs — the third polled a dead page for 45 seconds.
 *
 * Limitation, stated rather than hidden: the embed carries ONE rendition, so the quality chips
 * cannot pick a size on this route. That is why the main route still gets its (fast) turn first
 * — it is the only one that returns `bitrateInfo` variants.
 */
private fun ttEmbedExtract(link: String, resolvedUrl: String, onBeat: () -> Unit): TtInfo {
    val id = ttVideoId(resolvedUrl) ?: ttVideoId(link) ?: throw Exception("No video id in the link")
    val conn = (URL("https://www.tiktok.com/embed/v2/$id").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        instanceFollowRedirects = true
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", TT_MOBILE_UA)
        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
    }
    val body = try {
        val code = conn.responseCode
        onBeat()
        if (code !in 200..299) throw Exception("embed HTTP $code")
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        try { conn.disconnect() } catch (_: Exception) {}
    }

    if (body.contains("_wafchallengeid")) throw Exception("embed was challenged too")

    val src = (
        Regex("""<video[^>]+data-testid="play-video"[^>]+src="([^"]+)"""").find(body)
            ?: Regex("""<video[^>]+src="(https://[^"]+)"""").find(body)
        )?.groupValues?.get(1)?.let { unescapeHtml(it) }
        ?: throw Exception("embed page carried no <video> source")

    // Title and cover ride along in the page's own SSR state. Missing ones are not fatal — a
    // download with a plain title beats no download.
    var title = ""
    var cover: String? = null
    try {
        val blob = Regex("""id="__FRONTITY_CONNECT_STATE__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)
        if (blob != null) {
            val data = JSONObject(blob).optJSONObject("source")?.optJSONObject("data")
            val keys = data?.keys()
            while (keys != null && keys.hasNext()) {
                val info = data.optJSONObject(keys.next())
                    ?.optJSONObject("videoData")?.optJSONObject("itemInfos") ?: continue
                title = info.optString("text", "")
                cover = info.optJSONArray("covers")?.optString(0)?.takeIf { it.startsWith("http") }
                break
            }
        }
    } catch (e: Exception) {
        Log.w(TT_LOG, "embed blob parse skipped: ${e.message}")
    }

    val handle = ttHandle(resolvedUrl) ?: ttHandle(link) ?: ""
    Log.i(TT_LOG, "EMBED ok id=$id host=${src.substringAfter("//").substringBefore('/')}")
    return TtInfo(
        id = id,
        title = title.ifBlank { "TikTok video" },
        uploader = handle,
        cover = cover,
        variants = emptyList(),
        playAddr = src,
        downloadAddr = null,
        images = emptyList(),
        musicUrl = null,
        ua = TT_MOBILE_UA,
        // Deliberately empty: the CDN served these bytes with no cookies at all, and sending a
        // desktop jar to a URL earned under a mobile identity is how you get a 403.
        cookie = ""
    )
}

// ---------------------------------------------------------------------------
// RASTA 1 — SEEDHA HTTP (desktop UA)
// ---------------------------------------------------------------------------

/**
 * `https://www.tiktok.com/@someone/video/123` → `https://www.tiktok.com/@i/video/123`
 *
 * TikTok serves the same post under the placeholder handle `@i`, and that form is markedly
 * less likely to be challenged than the real username. Short links (vt./vm.) are left alone —
 * they have no username in them and resolve by redirect.
 */
private fun ttCanonical(link: String): String {
    val m = Regex("""^(https?://(?:www\.|m\.)?tiktok\.com)/@[^/]+/video/(\d+)""").find(link)
        ?: return link
    return "${m.groupValues[1]}/@i/video/${m.groupValues[2]}"
}

/** One page fetch, following vt./vm. redirects by hand so cookies survive every hop. */
private fun fetchTtPage(
    start: String,
    jarCookie: String,
    fresh: MutableMap<String, String>,
    onBeat: () -> Unit
): String? {
    var url = start
    for (hop in 0 until 5) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 30_000
            applyTtPageHeaders(mergeCookies(jarCookie, fresh))
        }
        try {
            val code = conn.responseCode
            onBeat()
            // The cookies TikTok hands out (ttwid/tt_chain_token) are the ones the CDN requires —
            // and the ones that make the NEXT attempt work. Collect them on every response,
            // including the ones that refuse us.
            val before = fresh.size
            collectCookies(conn, fresh)
            if (fresh.size == before) {
                // Says WHY the jar stayed empty: no Set-Cookie at all, versus one we failed to
                // read. Guessing between those two cost a whole debugging round.
                Log.i(TT_LOG, "no new cookies from $code; headers=${(0 until 30).mapNotNull { conn.getHeaderFieldKey(it) }}")
            }
            if (code in 300..399) {
                // Read by index too: a short link that silently produced nothing was the whole
                // reason vt./vm. links appeared to hang — no body, no error, no log.
                val loc = conn.getHeaderField("Location")
                    ?: (0 until 40).firstOrNull {
                        conn.getHeaderFieldKey(it)?.equals("Location", ignoreCase = true) == true
                    }?.let { conn.getHeaderField(it) }
                if (loc == null) {
                    Log.w(TT_LOG, "hop $hop: $code with NO Location; headers=${(0 until 30).mapNotNull { conn.getHeaderFieldKey(it) }}")
                    return null
                }
                Log.i(TT_LOG, "hop $hop: $code → ${loc.take(80)}")
                url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                continue
            }
            if (code !in 200..299) throw Exception("HTTP $code")
            Log.i(TT_LOG, "hop $hop: $code on ${url.take(70)}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
    return null
}

private fun ttHttpExtract(link: String, jarCookie: String, onBeat: () -> Unit): TtInfo {
    val fresh = LinkedHashMap<String, String>()
    val target = ttCanonical(link)

    // Earn a ttwid before asking for the video. Only costs a request when there is no cookie at
    // all — i.e. on a fresh install, which is exactly the case that used to fail.
    warmUpCookies(fresh, jarCookie, onBeat)

    /*
     * ⚠️ THE RETRY IS THE PROTOCOL, NOT ERROR HANDLING. This is the single thing that was
     * missing, and it is why downloads failed on a real phone:
     *
     *   measured on this device — first attempt on a cold jar → "Please wait…" WAF challenge,
     *   and the app gave up right there and handed the job to the WebView, which then burned
     *   45s and failed too. Meanwhile the refusal itself had handed us the cookies that make
     *   the next attempt work.
     *
     * The same behaviour was measured independently on the desktop build: first request on an
     * empty jar returns a 1.4 KB shell, the second — carrying the ttwid that first response
     * set — returns the real page.
     *
     * So a refusal is not the end of route 1. It is step one of route 1.
     */
    var lastShape = TtPage.UNKNOWN
    var lastSize = 0
    for (attempt in 0 until 3) {
        val cookiesBefore = fresh.size
        val body = fetchTtPage(target, jarCookie, fresh, onBeat) ?: continue
        lastShape = pageShape(body)
        lastSize = body.length
        if (lastShape != TtPage.BLOB && fresh.size == cookiesBefore && attempt > 0) {
            // Retrying only helps when the refusal handed us a cookie the next request can
            // spend. Measured on device: TikTok's challenge carries no Set-Cookie at all, so
            // further identical requests are pointless — stop hammering and let the browser
            // (which CAN pass the challenge) take over.
            Log.i(TT_LOG, "attempt $attempt → $lastShape, still no cookies — going to the browser")
            break
        }
        if (lastShape == TtPage.BLOB) {
            Log.i(TT_LOG, "page BLOB on attempt $attempt (${body.length}B)")
            val root = blobFromHtml(body)
                ?: throw Exception("No page data (TikTok didn't return this video's data)")
            val item = itemStructOf(root)
            return parseItemStruct(item, TT_DESKTOP_UA, mergeCookies(jarCookie, fresh))
                ?: throw Exception("TikTok didn't return this video's data")
        }
        Log.i(TT_LOG, "attempt $attempt → $lastShape (${body.length}B), cookies now ${fresh.keys}")
    }

    // Only now is it the browser's problem. Naming the shape matters: a WAF challenge, an empty
    // shell and a missing video used to arrive as one indistinguishable "No page data".
    throw Exception(
        when (lastShape) {
            TtPage.CHALLENGE -> "TikTok served a JS challenge on every attempt — handing over to the browser"
            TtPage.SHELL -> "TikTok served an empty page (fingerprint refused) — handing over to the browser"
            else -> "Unrecognised TikTok response ($lastSize bytes)"
        }
    )
}

private fun mergeCookies(jar: String, fresh: Map<String, String>): String {
    val out = LinkedHashMap<String, String>()
    for (part in jar.split(";")) {
        val kv = part.trim()
        val eq = kv.indexOf('=')
        if (eq > 0) out[kv.substring(0, eq)] = kv.substring(eq + 1)
    }
    out.putAll(fresh) // taaza values purani pe bhaari
    return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

// ---------------------------------------------------------------------------
// RASTA 2 — WEBVIEW (asli Chrome)
// ---------------------------------------------------------------------------

/** evaluateJavascript ko suspend-friendly banao. */
private suspend fun WebView.evalAwait(js: String): String? {
    val d = CompletableDeferred<String?>()
    try {
        evaluateJavascript(js) { d.complete(it) }
    } catch (e: Exception) {
        d.complete(null)
    }
    return d.await()
}

/** evaluateJavascript JSON-encoded value deta hai ("\"abc\"" / "null") — asli string nikalo. */
private fun decodeJsString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return try {
        JSONArray("[$raw]").optString(0, "")
    } catch (e: Exception) {
        ""
    }
}

@SuppressLint("SetJavaScriptEnabled")
private suspend fun ttWebViewExtract(
    context: Context,
    link: String,
    timeoutMs: Long,
    onBeat: () -> Unit
): TtInfo {
    val app = context.applicationContext
    var wv: WebView? = null
    try {
        withContext(Dispatchers.Main) {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val w = WebView(app)
            cm.setAcceptThirdPartyCookies(w, true)
            with(w.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                // ⚠️ DESKTOP UA — with the device's own (mobile) UA, TikTok serves the
                // "open in app" reflow page, which contains no video data (device-verified).
                userAgentString = TT_DESKTOP_UA
            }
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean = false // keep vt./vm. short-link redirects inside this WebView
            }
            // Give the offscreen WebView a layout — without one, Chromium can treat the
            // page as "invisible" and throttle it.
            w.layout(0, 0, 1280, 2000)
            wv = w
            // Land on tiktok.com FIRST, not on the post. Two things follow from that: the jar
            // gets its cookies, and the post can then be opened as a SAME-ORIGIN iframe, whose
            // document we are allowed to read.
            w.loadUrl(TT_REFERER)
        }

        val web = wv ?: throw Exception("WebView unavailable")
        val target = resolveTtLink(link)
        val start = System.currentTimeMillis()
        var statusErr: String? = null
        var framed = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(600)
            onBeat() // tell the watchdog we are still alive

            // Once the host page has a body, open the post inside it and let TikTok
            // server-render it the way it only does for a real navigation.
            if (!framed) {
                val started = decodeJsString(
                    withContext(Dispatchers.Main) {
                        web.evalAwait(JS_IFRAME_START.replace("%URL%", target.replace("'", "%27")))
                    }
                )
                if (started == "created" || started == "exists") {
                    framed = true
                    Log.i(TT_LOG, "probe iframe opened on $target")
                }
                continue
            }

            val raw = decodeJsString(withContext(Dispatchers.Main) { web.evalAwait(JS_IFRAME_GRAB) })
                .ifBlank { decodeJsString(withContext(Dispatchers.Main) { web.evalAwait(JS_GRAB) }) }

            /*
             * ⚠️ DO NOT "RESCUE" THIS BY READING THE <video> ELEMENT'S src.
             *
             * That was tried, and it looked like it worked: the log said the player had a src,
             * the download completed, no error anywhere. The file was **2.07 seconds long,
             * 720x816, with no audio track at all** — TikTok's page plays a short preview
             * rendition, not the post.
             *
             * A silently truncated file is worse than a failure: a failure retries, a 2-second
             * clip gets saved to the gallery and called done. Only the SSR blob's playAddr /
             * bitrateInfo is the real video.
             */
            if (raw.isBlank()) continue
            val root = try { JSONObject(raw) } catch (e: Exception) { null } ?: continue
            val item = try {
                itemStructOf(root)
            } catch (e: TtStatusException) {
                statusErr = e.message
                break
            }
            val info = parseItemStruct(item, TT_DESKTOP_UA, readTtCookies())
            if (info != null) return info
        }
        if (statusErr != null) throw TtStatusException(statusErr)
        throw Exception("TikTok didn't return this video's data")
    } finally {
        withContext(Dispatchers.Main) {
            try {
                wv?.stopLoading()
                wv?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}

// ---------------------------------------------------------------------------
// PARSING
// ---------------------------------------------------------------------------

/** HTML me se `__UNIVERSAL_DATA_FOR_REHYDRATION__` ka JSON. */
private fun blobFromHtml(html: String): JSONObject? {
    val k = html.indexOf("__UNIVERSAL_DATA_FOR_REHYDRATION__")
    if (k < 0) return null
    val gt = html.indexOf('>', k)
    if (gt < 0) return null
    val end = html.indexOf("</script>", gt)
    if (end < 0) return null
    return try { JSONObject(html.substring(gt + 1, end).trim()) } catch (e: Exception) { null }
}

/** blob → itemStruct (ya TikTok ka saaf inkaar). */
private fun itemStructOf(root: JSONObject): JSONObject? {
    val scope = root.optJSONObject("__DEFAULT_SCOPE__") ?: return null
    val vd = scope.optJSONObject("webapp.video-detail") ?: return null
    val status = vd.optInt("statusCode", 0)
    if (status != 0) {
        throw TtStatusException("TikTok status $status ${vd.optString("statusMsg")}".trim())
    }
    return vd.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
}

private fun parseItemStruct(it: JSONObject?, ua: String, cookie: String): TtInfo? {
    if (it == null) return null
    val video = it.optJSONObject("video")
    val imagePost = it.optJSONObject("imagePost")
    if (video == null && imagePost == null) return null

    val variants = ArrayList<TtVariant>()
    video?.optJSONArray("bitrateInfo")?.let { arr ->
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val pa = b.optJSONObject("PlayAddr") ?: continue
            val urls = pa.optJSONArray("UrlList")
            val u = (0 until (urls?.length() ?: 0))
                .mapNotNull { k -> urls?.optString(k) }
                .firstOrNull { s -> s.startsWith("http") } ?: continue
            variants.add(
                TtVariant(
                    gear = b.optString("GearName"),
                    url = u,
                    width = pa.optInt("Width"),
                    height = pa.optInt("Height"),
                    bitrate = b.optLong("Bitrate")
                )
            )
        }
    }

    val images = ArrayList<String>()
    imagePost?.optJSONArray("images")?.let { arr ->
        for (i in 0 until arr.length()) {
            val urls = arr.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList")
            val u = (0 until (urls?.length() ?: 0))
                .mapNotNull { k -> urls?.optString(k) }
                .firstOrNull { s -> s.startsWith("http") } ?: continue
            images.add(u)
        }
    }

    val play = video?.optString("playAddr")?.takeIf { s -> s.startsWith("http") }
    val dl = video?.optString("downloadAddr")?.takeIf { s -> s.startsWith("http") }
    if (variants.isEmpty() && images.isEmpty() && play == null && dl == null) return null

    val uploader = it.optJSONObject("author")?.optString("uniqueId")?.takeIf { s -> s.isNotBlank() } ?: "tiktok"
    val desc = it.optString("desc").ifBlank { "TT video" }

    return TtInfo(
        id = it.optString("id").ifBlank { System.currentTimeMillis().toString() },
        title = desc.take(120),
        uploader = uploader,
        cover = video?.optString("cover")?.takeIf { s -> s.startsWith("http") },
        variants = variants,
        playAddr = play,
        downloadAddr = dl,
        images = images,
        musicUrl = it.optJSONObject("music")?.optString("playUrl")?.takeIf { s -> s.startsWith("http") },
        ua = ua,
        cookie = cookie
    )
}

// ---------------------------------------------------------------------------
// LOGIN-COOKIE SUPPRESS / RESTORE (guest mode + session-fail retry)
// ---------------------------------------------------------------------------
// Android offers no per-WebView incognito jar, so for guest mode we temporarily
// remove TikTok's LOGIN cookies, run the extraction, and put them straight back —
// the user's login is NEVER lost.

private val TT_LOGIN_COOKIES = listOf("sessionid", "sessionid_ss", "sid_tt", "sid_guard", "uid_tt", "uid_tt_ss")

/** TikTok cookies — ALWAYS from the main thread (a WebView-provider requirement). */
private suspend fun readTtCookies(): String = withContext(Dispatchers.Main) {
    try { CookieManager.getInstance().getCookie(TT_REFERER) ?: "" } catch (e: Exception) { "" }
}

/** Return = (name to value) jo hatai gayi hain. */
private suspend fun suppressTtLogin(): List<Pair<String, String>> = withContext(Dispatchers.Main) {
    val cm = CookieManager.getInstance()
    val raw = try { cm.getCookie(TT_REFERER) ?: "" } catch (e: Exception) { "" }
    if (raw.isBlank()) return@withContext emptyList()
    val saved = ArrayList<Pair<String, String>>()
    for (part in raw.split(";")) {
        val kv = part.trim()
        val eq = kv.indexOf('=')
        if (eq <= 0) continue
        val name = kv.substring(0, eq).trim()
        if (name !in TT_LOGIN_COOKIES) continue
        saved.add(name to kv.substring(eq + 1).trim())
        try {
            cm.setCookie(TT_REFERER, "$name=; Domain=.tiktok.com; Path=/; Max-Age=0")
        } catch (_: Exception) {
        }
    }
    if (saved.isNotEmpty()) {
        try { cm.flush() } catch (_: Exception) {}
        Log.i(TT_LOG, "login cookies suppressed for guest extraction (${saved.size})")
    }
    saved
}

private suspend fun restoreTtLogin(saved: List<Pair<String, String>>) = withContext(Dispatchers.Main) {
    if (saved.isEmpty()) return@withContext
    val cm = CookieManager.getInstance()
    val expiry = "Max-Age=" + (10L * 365 * 24 * 3600)
    for ((name, value) in saved) {
        try {
            cm.setCookie(TT_REFERER, "$name=$value; Domain=.tiktok.com; Path=/; Secure; $expiry")
        } catch (_: Exception) {
        }
    }
    try { cm.flush() } catch (_: Exception) {}
    Log.i(TT_LOG, "login cookies restored (${saved.size})")
}

// ---------------------------------------------------------------------------
// MEDIA DOWNLOAD (Android TLS + TikTok cookies)
// ---------------------------------------------------------------------------

/**
 * Fetch one media URL into `dest` — Range/resume, live progress, beats.
 * ⚠️ The TikTok CDN answers 403 WITHOUT cookies (the URL carries `tk=tt_chain_token`),
 * so cookies + UA + Referer are all three mandatory.
 */
fun ttHttpDownload(
    url: String,
    dest: File,
    ua: String,
    cookie: String,
    onBeat: () -> Unit = {},
    onProgress: (Int) -> Unit = {}
) {
    var attempt = 0
    var lastError: Exception? = null
    // 6 attempts, each one picking up exactly where the last stopped (Range) — on mobile
    // data the TikTok CDN goes quiet part-way through a large file (10MB+); observed on a
    // device: attempt 1 timed out, attempt 2 resumed from 9.8MB. A 60s readTimeout was
    // too tight, hence 120s.
    while (attempt < 6) {
        attempt++
        val have = if (dest.exists()) dest.length() else 0L
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Referer", TT_REFERER)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "identity") // raw bytes, so progress stays accurate
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                throw Exception("HTTP $code — TikTok refused the media link")
            }
            if (code == 416) { // "Range not satisfiable" = the file is already complete
                onProgress(100)
                return
            }
            if (code !in 200..299) throw Exception("HTTP $code")

            val resuming = code == 206 && have > 0
            val body = conn.contentLengthLong
            val total = if (body > 0) body + (if (resuming) have else 0L) else -1L
            if (!resuming && have > 0) dest.delete() // server refused the resume → start over

            var written = if (resuming) have else 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(dest, resuming).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onBeat()
                        if (total > 0) {
                            val p = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (p != lastPct) {
                                lastPct = p
                                onProgress(p)
                            }
                        }
                    }
                    out.flush()
                }
            }
            if (total > 0 && written < total) throw Exception("Incomplete transfer ($written/$total)")
            onProgress(100)
            return
        } catch (e: Exception) {
            lastError = e
            val msg = e.message ?: ""
            // 403/401 = the signed URL expired or the cookies are wrong — retrying won't help
            if ("HTTP 403" in msg || "HTTP 401" in msg) throw e
            Log.w(TT_LOG, "media download attempt $attempt failed: $msg")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
    throw lastError ?: Exception("Download failed")
}
