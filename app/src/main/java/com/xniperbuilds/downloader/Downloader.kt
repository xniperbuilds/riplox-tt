package com.xniperbuilds.downloader

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Share ke text me se pehla http(s) link nikaalo. */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val found = Regex("https?://\\S+").find(text)?.value ?: text.trim()
    // strip punctuation stuck to the end of a link in shared text
    return found.trimEnd('.', ',', ')', ']', '!', '?', ';', '"', '\'')
}

/** TT-LOCK — Riplox TT only accepts TikTok links (for both Play policy and product scope).
 * Every variant works: www./m./vm./vt. tiktok.com. */
fun isTikTokUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return try {
        val host = Uri.parse(url.trim()).host?.lowercase() ?: return false
        host == "tiktok.com" || host.endsWith(".tiktok.com")
    } catch (e: Exception) {
        false
    }
}

/** Preview info (thumbnail + title) — notification/card ke liye. */
data class Preview(val title: String, val uploader: String, val thumbnail: String?, val duration: Long)

fun getPreview(context: Context, link: String): Preview {
    val req = YoutubeDLRequest(link)
    req.addOption("--no-playlist")
    req.addOption("--no-warnings")
    req.addOption("--socket-timeout", "30")
    req.applyCommon(context)
    val info = YoutubeDL.getInstance().getInfo(req)
    val dur = try { info.duration.toLong() } catch (e: Exception) { 0L }
    return Preview(info.title ?: "—", info.uploader ?: "—", info.thumbnail, dur)
}

/** Quality choice → yt-dlp format string (for the fallback path).
 * ⚠️ TikTok is VERTICAL (heights 1090/1364/2044), so `height<=1080` never matches — filter
 * on WIDTH instead, with an unfiltered fallback at the end. On the native path the quality
 * comes from `TtInfo.pickVideo()`. */
fun formatFor(quality: String): String = when (quality) {
    "1080" -> "bv*[width<=1080]+ba/b[width<=1080]/bv*+ba/b"
    "720" -> "bv*[width<=720]+ba/b[width<=720]/bv*+ba/b"
    "480" -> "bv*[width<=540]+ba/b[width<=540]/bv*+ba/b"
    else -> "bv*+ba/b"
}

// ============================================================================
// COOKIES / CONNECT TIKTOK — the user signs in to TikTok inside an in-app WebView
// (CookieLoginActivity); those cookies (a) stay in the same WebView jar that
// TikTokExtractor reads from, and (b) are written to a Netscape cookies.txt that
// reaches the yt-dlp fallback via --cookies. Together this unlocks private /
// region-locked / age-restricted videos.
// ⚠️ Logging in does not fix the EXTRACTION problem (that one is about fingerprinting,
// see TikTokExtractor.kt) — this is only for login-gated content.
// ============================================================================

/** Fixed path of the saved cookies.txt (app-private). */
fun cookiesFile(context: Context): File = File(context.filesDir, "cookies.txt")

/** Are any cookies saved (file exists and is not empty)? */
fun hasCookies(context: Context): Boolean {
    val f = cookiesFile(context)
    return f.exists() && f.length() > 0
}

/** Drop every cookie. (Lesson learned: a normal "Disconnect" does NOT call this — the login
 * is meant to be durable and is only refreshed via Re-login.) */
fun clearCookies(context: Context): Boolean {
    val f = cookiesFile(context)
    return if (f.exists()) f.delete() else true
}

/** Per site: (cookie domain -> the URLs to collect cookies from). The TT app = TikTok. */
fun cookieGroupsFor(site: String, customUrl: String? = null): List<Pair<String, List<String>>> = when (site) {
    "tiktok" -> listOf(".tiktok.com" to listOf("https://www.tiktok.com", "https://tiktok.com"))
    else -> {
        val host = try { Uri.parse(customUrl).host ?: "" } catch (e: Exception) { "" }
        val root = host.removePrefix("www.").removePrefix("m.")
        val domain = if (root.isNotEmpty()) ".$root" else host
        listOf(domain to listOf(customUrl ?: "https://$host"))
    }
}

/** The site's login/start URL (the first page the WebView loads).
 * ⚠️ `/login` (the desktop modal) renders COMPLETELY BLANK in a WebView — seen on a real
 * device. Going straight to the email/phone form URL does render. */
fun cookieSiteUrl(site: String, customUrl: String? = null): String = when (site) {
    "tiktok" -> "https://www.tiktok.com/login/phone-or-email/email"
    else -> customUrl ?: "https://www.tiktok.com/login/phone-or-email/email"
}

/** Use the DEVICE's own (mobile) UA for the TikTok login — on a desktop UA the login page
 * comes up blank.
 * NOTE: EXTRACTION uses a different UA (there a DESKTOP one is mandatory, see
 * TikTokExtractor.kt). Cookies are not bound to a UA, so each side having its own is fine. */
fun useDesktopUa(site: String): Boolean = false

// ---------- REAL login detection (guest cookies ≠ connected) ----------
// TikTok hands out guest cookies (ttwid/msToken) the moment the WebView opens, and showing
// "connected ✓" off those would be WRONG. Connected means a real session cookie is present.
private val LOGIN_COOKIE_NAMES = mapOf(
    "TikTok" to listOf("sessionid", "sessionid_ss", "sid_tt")
)

private fun siteNameOfDomain(d: String): String = when {
    "tiktok" in d -> "TikTok"
    else -> d.removePrefix("www.")
}

/** Sirf wo sites jinki REAL login-cookie file me hai (✓ badge isi se). */
fun connectedSites(context: Context): List<String> {
    if (!hasCookies(context)) return emptyList()
    return try {
        val names = LinkedHashSet<String>()
        cookiesFile(context).readLines().forEach { l ->
            if (l.isBlank() || l.startsWith("#")) return@forEach
            val parts = l.split('\t')
            if (parts.size < 7) return@forEach
            val d = parts[0].removePrefix(".").lowercase()
            val cookieName = parts[5]
            val value = parts[6]
            val site = siteNameOfDomain(d)
            val loginNames = LOGIN_COOKIE_NAMES[site]
            if (loginNames == null) {
                if (d.isNotBlank() && value.isNotBlank()) names.add(site)
            } else if (cookieName in loginNames && value.isNotBlank() && value != "\"\"") {
                names.add(site)
            }
        }
        names.toList()
    } catch (e: Exception) {
        emptyList()
    }
}

/** TikTok connected hai? (home ka badge/guard isi se.) */
fun tiktokConnected(context: Context): Boolean = connectedSites(context).contains("TikTok")

/**
 * After a login in the in-app WebView, write its cookies out to a Netscape cookies.txt
 * (yt-dlp reads that via --cookies; the extractor uses the WebView's own jar directly).
 */
fun saveCookiesFromWebView(context: Context, groups: List<Pair<String, List<String>>>): Int {
    val cm = CookieManager.getInstance()
    try { cm.flush() } catch (_: Exception) {}
    val siteDomains = groups.map { it.first }.toSet()
    val kept = if (hasCookies(context)) {
        cookiesFile(context).readLines().filter { line ->
            line.isNotBlank() && !line.startsWith("#") &&
                siteDomains.none { d -> line.startsWith("$d\t") }
        }
    } else emptyList()

    val seen = HashSet<String>()
    val fresh = ArrayList<String>()
    val expiry = (System.currentTimeMillis() / 1000) + 10L * 365 * 24 * 3600 // ~10 saal
    for ((domain, urls) in groups) {
        for (url in urls) {
            val raw = cm.getCookie(url) ?: continue
            for (part in raw.split(";")) {
                val kv = part.trim()
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val name = kv.substring(0, eq).trim()
                val value = kv.substring(eq + 1).trim()
                if (!seen.add("$domain\t$name")) continue
                fresh.add("$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value")
            }
        }
    }
    if (fresh.isEmpty() && kept.isEmpty()) return 0
    val sb = StringBuilder("# Netscape HTTP Cookie File\n# Riplox TT\n")
    kept.forEach { sb.append(it).append("\n") }
    fresh.forEach { sb.append(it).append("\n") }
    cookiesFile(context).writeText(sb.toString())
    return fresh.size
}

/** Options applied to every yt-dlp request.
 * ⚠️ NEVER --user-agent/--referer (hard-won lesson: yt-dlp's TikTok extractor uses its own
 * headers, and overriding them BREAKS it). */
private fun YoutubeDLRequest.applyCommon(context: Context, useCookies: Boolean = true) {
    if (useCookies && Prefs.cookiesEnabled(context) && hasCookies(context)) {
        addOption("--cookies", cookiesFile(context).absolutePath)
    }
    addOption("--concurrent-fragments", "8")
}

/**
 * Does this failure look like it came from the login/session? (A device-verified pattern.)
 * When it does, we retry ONCE without the login — otherwise EVERY download by a connected
 * user keeps dying with no way out.
 */
fun looksLikeSessionFailure(raw: String?): Boolean {
    val m = (raw ?: "").lowercase()
    return listOf(
        "failed to parse json", "jsondecodeerror", "empty media response",
        "login required", "not logged in", "login_required",
        "checkpoint", "challenge_required", "csrf", "verify",
        "please wait a few minutes", "http error 401", "http error 429",
        "status 10222", "status 10204", "refused the media link"
    ).any { it in m }
}

/** Turn a long raw yt-dlp/extractor ERROR into a short, useful message. */
fun friendlyError(raw: String?): String {
    val m = (raw ?: "").lowercase()
    return when {
        // TT sometimes answers a request with an empty/truncated body (a soft rate-limit),
        // and the extractor throws "Failed to parse JSON". The same link works fine a bit later.
        "failed to parse json" in m || "jsondecodeerror" in m ||
            "http error 429" in m || "please wait a few minutes" in m ->
            "TT is rate-limiting right now — it didn't return this video's data. Wait a minute and retry."
        "didn't return this video" in m || "impersonat" in m || "unable to extract webpage" in m ->
            "TT didn't hand over this video's data. Retry — if it keeps failing, open Riplox TT → Connect TikTok and try again."
        "status 10204" in m || "status 10231" in m ->
            "This video isn't available anymore — it may be deleted or region-locked."
        "status 10222" in m || "status 10223" in m ->
            "This video is private or restricted. Connect a TikTok account that can see it (Riplox TT → Connect TikTok)."
        "http error 403" in m || "refused the media link" in m || "status code 0" in m ||
            "ip address is blocked" in m ->
            "TT blocked this request. Retry in a bit — or copy the link and use the full video link from the TT app (not the short vt.tiktok.com one)."
        ("private" in m && ("login" in m || "available" in m)) || "this post may not be comfortable" in m ->
            "This video is private or restricted — it can't be downloaded."
        "unsupported url" in m -> "This link isn't a downloadable TT video."
        "file not found" in m ->
            "Couldn't find a downloadable video at that link — retry, or copy the link and open the full video in the TT app."
        "unable to extract" in m || "not available" in m ->
            "Video not available — it may be deleted, region-locked, or private. Retry or check the link."
        "no space left" in m -> "Phone storage is full."
        // On a large file over slow mobile data, the TikTok CDN drops the connection
        // part-way. The partial file is kept and the next attempt resumes from there.
        "connection abort" in m || "connection reset" in m || "unexpected end of stream" in m ||
            "incomplete transfer" in m ->
            "Connection dropped while downloading — it will pick up from where it stopped. Wi-Fi is more reliable for big videos."
        ("timed out" in m || "timeout" in m || "unable to connect" in m || "network is unreachable" in m) ->
            "Network problem — check internet and retry."
        else -> (raw ?: "Unknown error").take(220)
    }
}

/** Temp/cache files delete karo — freed bytes return. */
fun clearTempFiles(context: Context): Long {
    val base = context.getExternalFilesDir("temp") ?: return 0L
    var freed = 0L
    base.listFiles()?.forEach { f ->
        freed += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        f.deleteRecursively()
    }
    return freed
}

/** The SAFE variant of the startup cleanup — only dl_* folders older than 24h (the name
 * carries a timestamp).
 * ⚠️ A full clearTempFiles raced at startup: an ENQUEUED job went RUNNING in the same second
 * the app opened, and the cleanup deleted its brand-new temp folder → the download appeared
 * to run but its file was gone → File not found / stuck at 100%. The age check makes that
 * race impossible. */
fun clearStaleTempFiles(context: Context, olderThanMs: Long = 24 * 60 * 60 * 1000L): Long {
    val base = context.getExternalFilesDir("temp") ?: return 0L
    val cutoff = System.currentTimeMillis() - olderThanMs
    var freed = 0L
    base.listFiles()?.forEach { f ->
        val ts = f.name.removePrefix("dl_").substringBefore('_').toLongOrNull()
        val stale = if (ts != null) ts < cutoff else f.lastModified() < cutoff
        if (stale) {
            freed += f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            f.deleteRecursively()
        }
    }
    return freed
}

/** File-name safe banao (yt-dlp ke --restrict-filenames jaisa). */
private fun safeName(s: String): String =
    s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifBlank { "TT" }

/** Gallery/Music save + save-phase "beats" (bade file ki copy watchdog/notif ke liye zinda dikhe). */
private fun saveWithBeats(
    context: Context,
    file: File,
    audioOnly: Boolean,
    onBeat: () -> Unit,
    onSave: (Int) -> Unit
): String {
    val total = file.length().coerceAtLeast(1)
    var lastSaveP = -1
    val onCopy: (Long) -> Unit = { copied ->
        onBeat()
        val sp = ((copied * 100) / total).toInt().coerceIn(0, 100)
        if (sp != lastSaveP) {
            lastSaveP = sp
            onSave(sp)
        }
    }
    return savePublic(context, file, audioOnly, onCopy)
}

/**
 * Downloaded video → MP3. yt-dlp can post-process a local file (`--enable-file-urls` + `-x`)
 * and ffmpeg is already bundled with the app, so the MP3 comes out exactly as it did before
 * — with no new dependency.
 * (Verified on desktop: a 3MB mp4 → a 568KB mp3.)
 */
private fun toMp3(
    src: File,
    outDir: File,
    processId: String?,
    onBeat: () -> Unit
): File {
    outDir.mkdirs()
    val req = YoutubeDLRequest("file://${src.absolutePath}")
    req.addOption("--enable-file-urls")
    req.addOption("-x")
    req.addOption("--audio-format", "mp3")
    req.addOption("--audio-quality", "0")
    req.addOption("--no-warnings")
    req.addOption("-o", "${outDir.absolutePath}/${src.nameWithoutExtension}.%(ext)s")
    YoutubeDL.getInstance().execute(req, processId) { _, _, _ -> onBeat() }
    return outDir.walkTopDown().filter { it.isFile && it.extension.equals("mp3", true) }.firstOrNull()
        ?: throw Exception("MP3 convert failed")
}

/**
 * The full download + gallery save, with live progress. DownloadWorker runs this inside a
 * foreground WorkManager job (proof against aggressive OEM task killers).
 *
 * TWO ROUTES:
 *  1. **NATIVE EXTRACTOR (the real path)** — media info comes from a desktop-UA fetch (with
 *     the phone's own WebView as a safety net), then the bytes arrive over
 *     HttpURLConnection. This is what gets past TikTok's gate (see TikTokExtractor.kt for
 *     the full explanation).
 *  2. **yt-dlp (fallback)** — the original path, still here. On a phone it now usually fails
 *     for TikTok (no curl_cffi), but it was kept: if some network or link happens to work
 *     with it, that is a free second chance.
 *
 * ⚠️ LESSON: never add --user-agent/--referer to yt-dlp — its TikTok extractor uses its own
 * headers and overriding them breaks it.
 * ⚠️ BUG-HARDENING: --embed-thumbnail / --embed-metadata were REMOVED — the
 * ffmpeg/AtomicParsley post-processing step hung on Android (stuck at "finishing" on 100%).
 */
suspend fun runDownload(
    context: Context,
    link: String,
    audioOnly: Boolean,
    processId: String? = null,   // for cancelling — YoutubeDL.destroyProcessById(processId)
    workKey: String = System.currentTimeMillis().toString(), // identifies the temp folder (worker id)
    onBeat: () -> Unit = {},     // fires on every output/chunk — the stall-watchdog signal
    onSave: (Int) -> Unit = {},  // gallery-save copy progress (0–100) — "Finishing" phase visible
    onMeta: (title: String, thumbUrl: String?) -> Unit = { _, _ -> }, // notification title/thumb
    onProgress: (Int) -> Unit
): String {
    Log.i("RiploxTT", "runDownload audio=$audioOnly ${link.take(50)}")
    val base = context.getExternalFilesDir("temp") ?: context.filesDir
    // ⚠️ The temp folder is named after the WORKER ID (not a random value). On a WorkManager
    // retry the job keeps the SAME id → it finds the same partial file → the download does
    // not restart from zero. Observed on a device (13MB file, slow mobile data): the CDN kept
    // dropping the connection, each attempt advanced ~1MB, but a fresh random folder threw
    // all of it away every time. Different jobs have different ids, so two downloads can
    // never collide on one folder.
    // The 'k' prefix in the name is deliberate: clearStaleTempFiles parses `dl_<timestamp>_`,
    // and the 'k' makes that parse fail so it falls through to the safe lastModified() age check.
    val work = File(base, "dl_k$workKey")
    work.mkdirs()
    var done = false
    try {
        // ---------- ROUTE 1: the native TikTok extractor ----------
        var webViewError: String? = null
        try {
            val where = webViewDownload(context, link, audioOnly, work, processId, onBeat, onSave, onMeta, onProgress)
            done = true
            return where
        } catch (e: kotlinx.coroutines.CancellationException) {
            done = true // cancelled by the user/watchdog — no point keeping a partial file
            throw e
        } catch (e: TtTransferException) {
            // Extraction was fine, only the byte transfer broke → yt-dlp would not help.
            // Keep the partial file (done=false); the next WorkManager attempt resumes from it.
            Log.w("RiploxTT", "media transfer failed — will resume on WM retry: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.w("RiploxTT", "WebView path failed — falling back to yt-dlp: ${e.message}")
            // clear the temp folder, or the partial files collide with the fallback
            work.listFiles()?.forEach { it.deleteRecursively() }
            onProgress(0)
            // Remember the extractor's error — if the fallback fails too, THIS is the better
            // one to report (it names the real cause). Kept local on purpose: a global would
            // race across three parallel downloads.
            webViewError = e.message
        }

        // ---------- ROUTE 2: yt-dlp (the older engine) ----------
        val where = ytdlpDownload(
            context, link, audioOnly, work, processId, onBeat, onSave, onMeta, onProgress, webViewError
        )
        done = true
        return where
    } finally {
        // Clear the temp folder on success (or cancel). On FAILURE the partial file is kept
        // DELIBERATELY, so the next WorkManager attempt can resume from it with a Range
        // request; anything older than 24h is swept up by clearStaleTempFiles in XniperApp.
        if (done) {
            work.deleteRecursively()
        } else {
            Log.i("RiploxTT", "keeping partial for retry: ${work.name}")
        }
    }
}

/** RASTA 1 — WebView se extract + seedha HTTP download. */
private suspend fun webViewDownload(
    context: Context,
    link: String,
    audioOnly: Boolean,
    work: File,
    processId: String?,
    onBeat: () -> Unit,
    onSave: (Int) -> Unit,
    onMeta: (String, String?) -> Unit,
    onProgress: (Int) -> Unit
): String {
    val wantLogin = Prefs.cookiesEnabled(context) && tiktokConnected(context)
    val info = try {
        ttExtract(context, link, useLogin = wantLogin, onBeat = onBeat)
    } catch (e: Exception) {
        // If it looks session/login related, retry ONCE in GUEST mode — this is what stops
        // every download by a connected user from dying (a device-verified pattern).
        if (!wantLogin || !looksLikeSessionFailure(e.message)) throw e
        Log.w("RiploxTT", "session/cookie failure — retrying WITHOUT login", e)
        onBeat()
        ttExtract(context, link, useLogin = false, onBeat = onBeat)
    }
    onMeta(info.title, info.cover)

    val stem = "${safeName(info.uploader)}_${safeName(info.id)}_RiploxTT"

    // ---- PHOTO / SLIDESHOW POST ----
    if (info.isPhotoPost) {
        var saved = 0
        var firstLoc = ""
        info.images.forEachIndexed { i, imgUrl ->
            val tmp = File(work, "${stem}_${i + 1}.jpg")
            try {
                ttHttpDownload(imgUrl, tmp, info.ua, info.cookie, onBeat) { p ->
                    onProgress(((i * 100 + p) / info.images.size).coerceIn(0, 100))
                }
                val loc = saveImageToPictures(context, tmp)
                if (saved == 0) firstLoc = loc
                saved++
            } catch (e: Exception) {
                Log.e("RiploxTT", "image save fail", e)
            }
        }
        if (saved == 0) throw Exception("File not found")
        History.add(context, link, "TT photo post ($saved)", "TT", firstLoc, false)
        return "$saved photos → Pictures/RiploxTT/"
    }

    // ---- VIDEO (and MP3 = video → audio) ----
    // An MP3 wants the best possible audio, so always pull the best video for it.
    val mediaUrl = if (audioOnly) info.pickVideo("best") else info.pickVideo(Prefs.quality(context))
    val chosen = mediaUrl ?: info.downloadAddr ?: throw Exception("File not found")
    val videoFile = File(work, "$stem.mp4")
    try {
        ttHttpDownload(chosen, videoFile, info.ua, info.cookie, onBeat, onProgress)
    } catch (e: Exception) {
        // Mark a transfer failure distinctly: falling through to yt-dlp gains nothing, and
        // the bytes already fetched should survive for the next WorkManager attempt.
        throw TtTransferException(e.message ?: "Download failed", e)
    }
    if (!videoFile.exists() || videoFile.length() < 1024) throw Exception("File not found")

    if (audioOnly) {
        onProgress(99) // ab convert phase — "Finishing" dikhega
        val mp3 = toMp3(videoFile, File(work, "audio"), processId, onBeat)
        val location = saveWithBeats(context, mp3, true, onBeat, onSave)
        History.add(context, link, mp3.name, "TT", location, true)
        return "Music/RiploxTT/${mp3.name}"
    }

    val fname = videoFile.name
    val location = saveWithBeats(context, videoFile, false, onBeat, onSave)
    History.add(context, link, fname, "TT", location, false)
    return "Movies/RiploxTT/$fname"
}

/** RASTA 2 — purana yt-dlp engine (fallback). */
private fun ytdlpDownload(
    context: Context,
    link: String,
    audioOnly: Boolean,
    work: File,
    processId: String?,
    onBeat: () -> Unit,
    onSave: (Int) -> Unit,
    onMeta: (String, String?) -> Unit,
    onProgress: (Int) -> Unit,
    webViewError: String?
): String {
    // Preview only on the fallback (the native path supplies its own title/thumb)
    try {
        val pv = getPreview(context, link)
        onMeta(pv.title, pv.thumbnail)
    } catch (_: Exception) {
    }

    fun buildRequest(useCookies: Boolean): YoutubeDLRequest {
        val req = YoutubeDLRequest(link)
        req.addOption("-o", "${work.absolutePath}/%(uploader)s_%(id)s_RiploxTT.%(ext)s")
        if (audioOnly) {
            req.addOption("-f", "bestaudio/best")
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
            // NOTE: --embed-thumbnail/--embed-metadata were REMOVED (bug-hardening, see above).
        } else {
            req.addOption("-f", formatFor(Prefs.quality(context)))
            req.addOption("--merge-output-format", "mp4")
        }
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--restrict-filenames")
        req.addOption("--socket-timeout", "30")
        req.applyCommon(context, useCookies)
        return req
    }

    var maxP = 0
    fun execute(useCookies: Boolean) {
        YoutubeDL.getInstance().execute(buildRequest(useCookies), processId) { progress, _, _ ->
            onBeat() // the process is alive — reset the watchdog timer
            val p = progress.toInt()
            if (p in 0..100 && p > maxP) {
                maxP = p
                onProgress(maxP)
            }
        }
    }

    val hadCookies = Prefs.cookiesEnabled(context) && hasCookies(context)
    try {
        execute(hadCookies)
    } catch (e: Exception) {
        if (!hadCookies || !looksLikeSessionFailure(e.message)) throw e
        Log.w("RiploxTT", "yt-dlp session failure — retrying WITHOUT login", e)
        work.listFiles()?.forEach { it.deleteRecursively() }
        maxP = 0
        onBeat()
        execute(false)
    }

    // Collect ALL downloaded files — but not the partial/temp ones
    val all = work.walkTopDown().filter { it.isFile }.filterNot {
        val n = it.name.lowercase()
        n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".tmp") || n.endsWith(".json")
    }.toList()
    val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v", "ts", "3gp", "avi")
    val audioExts = setOf("mp3", "m4a", "opus", "ogg", "wav", "flac", "aac", "weba")
    val imageExts = setOf("jpg", "jpeg", "png", "webp")
    val media = all.filter {
        val e = it.extension.lowercase()
        if (audioOnly) e in audioExts || e in videoExts else e in videoExts
    }

    if (media.isNotEmpty()) {
        var firstDisplay = ""
        var saved = 0
        for (file in media) {
            val fname = file.name
            val location = saveWithBeats(context, file, audioOnly, onBeat, onSave)
            History.add(context, link, fname, "TT", location, audioOnly)
            if (saved == 0) firstDisplay = "${if (audioOnly) "Music" else "Movies"}/RiploxTT/$fname"
            saved++
        }
        return if (saved == 1) firstDisplay
        else "$saved files → ${if (audioOnly) "Music" else "Movies"}/RiploxTT/"
    }

    // TT PHOTO POST — no video turned up but there are images → save them to Pictures
    val images = all.filter { it.extension.lowercase() in imageExts }
    if (images.isNotEmpty()) {
        var savedImg = 0
        var firstLoc = ""
        for (img in images) {
            try {
                val loc = saveImageToPictures(context, img)
                if (savedImg == 0) firstLoc = loc
                savedImg++
            } catch (e: Exception) {
                Log.e("RiploxTT", "image save fail", e)
            }
        }
        if (savedImg > 0) {
            History.add(context, link, "TT photo post ($savedImg)", "TT", firstLoc, false)
            return "$savedImg photos → Pictures/RiploxTT/"
        }
    }
    // Both routes failed — the extractor's error is the more useful one (it names the real cause)
    throw Exception(webViewError ?: "File not found")
}
