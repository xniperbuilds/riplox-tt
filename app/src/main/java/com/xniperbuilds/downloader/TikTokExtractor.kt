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
private const val TT_DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
        val jarCookie = readTtCookies()
        // 1) Plain HTTP — 95% of links resolve here (~1s, no WebView)
        try {
            val info = withContext(Dispatchers.IO) { ttHttpExtract(link, jarCookie, onBeat) }
            Log.i(TT_LOG, "ttExtract HTTP ok id=${info.id} variants=${info.variants.size} images=${info.images.size}")
            return info
        } catch (e: TtStatusException) {
            throw e // a definitive answer (private/deleted) — the WebView would say the same
        } catch (e: Exception) {
            Log.w(TT_LOG, "HTTP extract failed (${e.message}) — trying WebView")
        }
        // 2) WebView — the real Chrome, which also solves a JS challenge on its own
        val info = ttWebViewExtract(context, link, timeoutMs, onBeat)
        Log.i(TT_LOG, "ttExtract WebView ok id=${info.id} variants=${info.variants.size}")
        return info
    } finally {
        suppressed?.let { restoreTtLogin(it) }
    }
}

// ---------------------------------------------------------------------------
// RASTA 1 — SEEDHA HTTP (desktop UA)
// ---------------------------------------------------------------------------

private fun ttHttpExtract(link: String, jarCookie: String, onBeat: () -> Unit): TtInfo {
    var url = link
    var html: String? = null
    val fresh = LinkedHashMap<String, String>()

    // manual redirect-follow for vt./vm. short links (cookies are carried across every hop)
    for (hop in 0 until 5) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", TT_DESKTOP_UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Upgrade-Insecure-Requests", "1")
            val merged = mergeCookies(jarCookie, fresh)
            if (merged.isNotBlank()) setRequestProperty("Cookie", merged)
        }
        try {
            val code = conn.responseCode
            onBeat()
            // the cookies TikTok hands out (ttwid/tt_chain_token) are the ones the CDN requires
            conn.headerFields["Set-Cookie"]?.forEach { sc ->
                val kv = sc.substringBefore(';').trim()
                val eq = kv.indexOf('=')
                if (eq > 0) fresh[kv.substring(0, eq)] = kv.substring(eq + 1)
            }
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw Exception("redirect without Location")
                url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                continue
            }
            if (code !in 200..299) throw Exception("HTTP $code")
            html = conn.inputStream.bufferedReader().use { it.readText() }
            break
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    val body = html ?: throw Exception("Too many redirects")
    val root = blobFromHtml(body) ?: throw Exception("No page data (TikTok didn't return this video's data)")
    val item = itemStructOf(root)
    return parseItemStruct(item, TT_DESKTOP_UA, mergeCookies(jarCookie, fresh))
        ?: throw Exception("TikTok didn't return this video's data")
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
            w.loadUrl(link)
        }

        val web = wv ?: throw Exception("WebView unavailable")
        val start = System.currentTimeMillis()
        var statusErr: String? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(600)
            onBeat() // tell the watchdog we are still alive
            val raw = decodeJsString(withContext(Dispatchers.Main) { web.evalAwait(JS_GRAB) })
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
