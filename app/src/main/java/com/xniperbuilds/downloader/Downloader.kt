package com.xniperbuilds.downloader

import android.content.Context
import android.net.Uri
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** Share ke text me se pehla http(s) link nikaalo. */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val found = Regex("https?://\\S+").find(text)?.value ?: text.trim()
    // share-text me link ke aakhir me lagi punctuation hata do
    return found.trimEnd('.', ',', ')', ']', '!', '?', ';', '"', '\'')
}

/** TT-LOCK — Riplox TT sirf TikTok links leta hai (Play policy + product scope dono ke liye).
 * Sab variants chalte hain: www./m./vm./vt. tiktok.com. */
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
    val info = YoutubeDL.getInstance().getInfo(req)
    val dur = try { info.duration.toLong() } catch (e: Exception) { 0L }
    return Preview(info.title ?: "—", info.uploader ?: "—", info.thumbnail, dur)
}

/** Quality choice → yt-dlp format string (max height cap, merged best video+audio). */
fun formatFor(quality: String): String = when (quality) {
    "1080" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"
    "720" -> "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
    "480" -> "bestvideo[height<=480]+bestaudio/best[height<=480]/best"
    else -> "best"
}

/** yt-dlp ke lambe raw ERROR ko chhota, kaam-ka message banao.
 * NOTE: TT app me koi login/connect nahi hai — advice hamesha Retry/Copy-link tak. */
fun friendlyError(raw: String?): String {
    val m = (raw ?: "").lowercase()
    return when {
        "http error 403" in m || "status code 0" in m || "ip address is blocked" in m ->
            "TT blocked this request. Retry in a bit — or copy the link and use the full video link from the TT app (not the short vt.tiktok.com one)."
        ("private" in m && ("login" in m || "available" in m)) || "this post may not be comfortable" in m ->
            "This video is private or restricted — it can't be downloaded."
        "unsupported url" in m -> "This link isn't a downloadable TT video."
        "file not found" in m ->
            "Couldn't find a downloadable video at that link — retry, or copy the link and open the full video in the TT app."
        "unable to extract" in m || "not available" in m ->
            "Video not available — it may be deleted, region-locked, or private. Retry or check the link."
        "no space left" in m -> "Phone storage is full."
        ("timed out" in m || "unable to connect" in m || "network is unreachable" in m) ->
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

/** Startup-safai ka SAFE variant — sirf 24h+ purane dl_* folders (naam me timestamp hai).
 * ⚠️ Poora clearTempFiles startup pe race karta tha: ENQUEUED job app-open pe usi second
 * RUNNING hoti thi aur uska taaza temp folder cleanup uDa deta tha → download "chalti"
 * par file gayab → File not found / 100% stuck. Age-check se race namumkin. */
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

/**
 * Poora yt-dlp download + gallery save, live progress. DownloadWorker isay
 * foreground WorkManager job me chalata hai (XOS-killer proof).
 * ⚠️ LESSON (Riplox 2026-07-05): --user-agent/--referer KABHI add nahi karna —
 * yt-dlp ka TikTok extractor apne headers use karta hai, override karne se TOOTTA hai.
 */
fun runDownload(
    context: Context,
    link: String,
    audioOnly: Boolean,
    processId: String? = null,   // Cancel ke liye — YoutubeDL.destroyProcessById(processId)
    onBeat: () -> Unit = {},     // har yt-dlp output pe fire — stall-watchdog ka signal
    onSave: (Int) -> Unit = {},  // gallery-save copy progress (0–100) — "Finishing" phase visible + beats
    onProgress: (Int) -> Unit
): String {
    Log.i("RiploxTT", "runDownload audio=$audioOnly ${link.take(50)}")
    val base = context.getExternalFilesDir("temp") ?: context.filesDir
    val work = File(base, "dl_${System.currentTimeMillis()}_${(0..9999).random()}")
    work.mkdirs()
    try {
        val req = YoutubeDLRequest(link)
        req.addOption("-o", "${work.absolutePath}/%(uploader)s_%(id)s_RiploxTT.%(ext)s")
        if (audioOnly) {
            req.addOption("-f", "bestaudio/best")
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
            req.addOption("--embed-thumbnail")
            req.addOption("--embed-metadata")
        } else {
            req.addOption("-f", formatFor(Prefs.quality(context)))
            req.addOption("--merge-output-format", "mp4")
            req.addOption("--embed-metadata")
            req.addOption("--embed-thumbnail")
        }
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--restrict-filenames")
        req.addOption("--socket-timeout", "30")
        req.addOption("--concurrent-fragments", "8")

        var maxP = 0
        YoutubeDL.getInstance().execute(req, processId) { progress, _, _ ->
            onBeat() // process zinda hai — watchdog timer reset
            val p = progress.toInt()
            if (p in 0..100 && p > maxP) {
                maxP = p
                onProgress(maxP)
            }
        }

        // SAB downloaded files uthao — partial/temp files nahi
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
                // Save-copy ke "beats": har chunk pe watchdog reset + pct-change pe onSave —
                // bade file ki gallery-copy ab na watchdog se marti hai na "100% stuck" dikhti.
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
                val location = savePublic(context, file, audioOnly, onCopy)
                History.add(context, link, fname, "TT", location, audioOnly)
                if (saved == 0) firstDisplay = "${if (audioOnly) "Music" else "Movies"}/RiploxTT/$fname"
                saved++
            }
            return if (saved == 1) firstDisplay
            else "$saved files → ${if (audioOnly) "Music" else "Movies"}/RiploxTT/"
        }

        // TT PHOTO POST — video nahi mili lekin images hain → Pictures me save
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
        throw Exception("File not found")
    } finally {
        work.deleteRecursively()
    }
}
