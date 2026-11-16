package com.xniperbuilds.downloader

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * QUIET DOWNLOADS — "the sound is much lower after downloading".
 *
 * Measured 16-Nov-2026 (21 files, 6 videos, every rendition the worker offers, ffmpeg ebur128):
 *   · TikTok's files run from -6.9 to -26 LUFS. The TikTok player evens that out while it plays;
 *     the file itself carries none of it, so a saved video can sound far quieter than in the app.
 *   · One video's h264 rendition sat at -26.0 LUFS while its h265 ones were -14.4 — 11.6 dB
 *     quieter for the same clip.
 *
 * So after a download, the audio is measured, and if it is quiet it is raised towards -14 LUFS
 * (what the big apps play at). Only the audio is re-encoded — the picture is copied untouched.
 * Loud enough files are not touched at all.
 *
 * ⚠️ ffmpeg post-processing once HUNG on Android (stuck at "finishing"), so every run here has a
 * hard timeout, and EVERY failure — missing binary, missing filter, timeout, bad output — leaves
 * the original file exactly as it was. A quiet video is better than no video.
 *
 * The ffmpeg used is the one youtubedl-android already ships and unpacks (FFmpeg.init in
 * XniperApp): binary = nativeLibraryDir/libffmpeg.so, its libraries under
 * noBackupFilesDir/youtubedl-android/packages/{python,ffmpeg}/usr/lib — the same layout the
 * library itself uses when it hands yt-dlp `--ffmpeg-location`.
 */
object Loudness {
    private const val LOG = "RiploxTT"
    /** Where a boosted file lands. */
    private const val TARGET_LUFS = -14.0
    /** Quieter than this gets boosted; anything louder is left alone. */
    private const val QUIET_LUFS = -16.0
    /** A boost smaller than this is not worth a re-encode. */
    private const val MIN_GAIN_DB = 1.5
    /** Never more than this — a near-silent clip should not be turned into hiss. */
    private const val MAX_GAIN_DB = 15.0
    /** Sample peak may not go above this after a plain (non-limited) gain. */
    private const val PEAK_CEILING_DB = -1.0

    data class Level(val lufs: Double, val peakDb: Double)

    private fun ffmpeg(c: Context): File? =
        File(c.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.exists() }

    private fun libPath(c: Context): String {
        val pk = File(File(c.noBackupFilesDir, "youtubedl-android"), "packages")
        return listOf(File(pk, "python/usr/lib"), File(pk, "ffmpeg/usr/lib"))
            .joinToString(":") { it.absolutePath }
    }

    /** Runs ffmpeg; returns (exit code, stderr) or null on timeout / launch failure. */
    private fun run(c: Context, args: List<String>, timeoutS: Long, onBeat: () -> Unit): Pair<Int, String>? {
        val bin = ffmpeg(c) ?: return null
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = libPath(c)
            val p = pb.start()
            val out = StringBuilder()
            // Drain the output on its own thread — a full pipe would block ffmpeg forever.
            val reader = thread(isDaemon = true) {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { if (out.length < 64_000) out.append(line).append('\n') }
                    }
                } catch (_: Exception) {
                }
            }
            val deadline = System.currentTimeMillis() + timeoutS * 1000
            while (!p.waitFor(1, TimeUnit.SECONDS)) {
                onBeat() // keep the stall watchdog fed while ffmpeg works
                if (System.currentTimeMillis() > deadline) {
                    p.destroy()
                    Log.w(LOG, "loudness: ffmpeg timed out after ${timeoutS}s — keeping original")
                    return null
                }
            }
            reader.join(2_000)
            p.exitValue() to synchronized(out) { out.toString() }
        } catch (e: Exception) {
            Log.w(LOG, "loudness: ffmpeg could not run (${e.message})")
            null
        }
    }

    /** Integrated loudness + sample peak of the file's audio, or null if it cannot tell. */
    fun measure(c: Context, f: File, onBeat: () -> Unit = {}): Level? {
        val r = run(
            c, listOf("-hide_banner", "-nostats", "-i", f.absolutePath, "-vn", "-sn", "-dn",
                "-af", "ebur128=peak=sample", "-f", "null", "-"),
            timeoutS = 60, onBeat = onBeat
        ) ?: return null
        if (r.first != 0) {
            Log.w(LOG, "loudness: measure exit ${r.first}: ${r.second.takeLast(200)}")
            return null
        }
        // The summary block comes last; take the last values printed.
        val i = Regex("""I:\s+(-?[\d.]+) LUFS""").findAll(r.second).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
        val pk = Regex("""Peak:\s+(-?[\d.]+|-inf) dBFS""").findAll(r.second).lastOrNull()?.groupValues?.get(1)
            ?.toDoubleOrNull()
        if (i == null || i < -70) return null // silence or unreadable — nothing sensible to do
        return Level(i, pk ?: 0.0)
    }

    /**
     * The audio filter that brings [lvl] up to the target, or null when it is loud enough.
     * Plain gain while the peak allows it; past that, gain plus a limiter so the loud moments
     * do not clip.
     */
    fun filterFor(lvl: Level): String? {
        if (lvl.lufs >= QUIET_LUFS) return null
        val want = min(TARGET_LUFS - lvl.lufs, MAX_GAIN_DB)
        if (want < MIN_GAIN_DB) return null
        val g = "%.1f".format(java.util.Locale.US, want)
        return if (lvl.peakDb + want <= PEAK_CEILING_DB) "volume=${g}dB"
        else "volume=${g}dB,alimiter=limit=0.89:level=false"
    }

    /** Plain gain only, capped by the peak — the fallback if the limiter is unavailable. */
    private fun safeFilter(lvl: Level): String? {
        val g = min(TARGET_LUFS - lvl.lufs, min(MAX_GAIN_DB, PEAK_CEILING_DB - lvl.peakDb))
        return if (g >= MIN_GAIN_DB) "volume=${"%.1f".format(java.util.Locale.US, g)}dB" else null
    }

    /**
     * Boost a quiet VIDEO in place. Returns true if the file was changed. Never throws.
     * The video stream is copied; only the audio is re-encoded (AAC 160k).
     */
    fun boostVideo(c: Context, f: File, onBeat: () -> Unit = {}): Boolean {
        try {
            val lvl = measure(c, f, onBeat) ?: return false
            val first = filterFor(lvl)
            if (first == null) {
                Log.i(LOG, "loudness: ${"%.1f".format(lvl.lufs)} LUFS — loud enough, untouched")
                return false
            }
            for (af in listOfNotNull(first, safeFilter(lvl).takeIf { it != first })) {
                val out = File(f.parentFile, "${f.nameWithoutExtension}.loud.mp4")
                out.delete()
                val r = run(
                    c, listOf("-hide_banner", "-nostats", "-y", "-i", f.absolutePath,
                        "-map", "0:v:0", "-map", "0:a:0", "-c:v", "copy",
                        "-af", af, "-c:a", "aac", "-b:a", "160k",
                        "-map_metadata", "0", "-movflags", "+faststart", out.absolutePath),
                    timeoutS = 120, onBeat = onBeat
                )
                // A real file of a plausible size, or it does not replace anything.
                if (r != null && r.first == 0 && out.length() > f.length() / 4) {
                    // Swap through a backup, so no failure in between can lose the download.
                    val bak = File(f.parentFile, "${f.name}.orig")
                    bak.delete()
                    if (f.renameTo(bak)) {
                        if (out.renameTo(f)) {
                            bak.delete()
                            Log.i(LOG, "loudness: ${"%.1f".format(lvl.lufs)} LUFS → boosted ($af)")
                            return true
                        }
                        bak.renameTo(f) // put the original back
                    }
                }
                Log.w(LOG, "loudness: '$af' failed (exit ${r?.first}) ${r?.second?.takeLast(200)}")
                out.delete()
            }
        } catch (e: Exception) {
            Log.w(LOG, "loudness: skipped (${e.message})")
        }
        return false
    }

    /** The extra yt-dlp ExtractAudio args for an MP3 made from [src], or null if loud enough. */
    fun mp3Filter(c: Context, src: File, onBeat: () -> Unit = {}): String? = try {
        measure(c, src, onBeat)?.let { filterFor(it) }
    } catch (e: Exception) {
        null
    }
}
