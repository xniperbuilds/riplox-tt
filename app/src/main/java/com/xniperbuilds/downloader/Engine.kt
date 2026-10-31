package com.xniperbuilds.downloader

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The yt-dlp engine: one place that updates it, and one place that remembers what happened.
 *
 * WHAT THE ENGINE IS ACTUALLY FOR — this is easy to get wrong, and getting it wrong sends the
 * fix to the wrong place. Since v1.1.3 a TikTok download does NOT go through yt-dlp: the
 * primary route is our own TikTokExtractor (page fetch → direct HTTP transfer). yt-dlp is the
 * fallback, plus the binary that produces MP3s. So a stale engine does not break TikTok
 * extraction — it breaks **MP3 conversion** and the fallback, and that is a real failure the
 * user experiences as "MP3 doesn't work any more".
 *
 * The four holes this closes, all of them real:
 *  1. There was no manual update anywhere in the app. Not for a non-technical user, and not
 *     for a technical one either — the only updates were invisible ones.
 *  2. The daily check ran in MainActivity only, so a user who always downloads via the TikTok
 *     share tile never opened MainActivity and never updated the engine — for months.
 *  3. The result was swallowed by `catch {}`. An update that failed every single time looked
 *     exactly like one that succeeded.
 *  4. The worker's self-heal only fired on attempt 0 and only for a fixed list of error
 *     strings, so a wording change on yt-dlp's side silently disabled it.
 */
object Engine {

    /** The outcome of an update attempt, in words a non-technical user can act on. */
    enum class Outcome { UPDATED, ALREADY_LATEST, FAILED }

    private const val TAG = "RiploxTT"

    /**
     * The library's own SharedPreferences file, and the two keys it stores the engine version
     * in. Reaching into another library's preferences is not something to do lightly — see
     * `markerIsLying` for why there is no other way to break the trap it creates.
     */
    private const val LIB_PREFS = "youtubedl-android"
    private const val KEY_VERSION = "dlpVersion"
    private const val KEY_VERSION_NAME = "dlpVersionName"

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /**
     * The version the library CLAIMS, which is all `YoutubeDL.version()` has ever returned:
     * `SharedPrefsHelper.get(context, "dlpVersion")` — a string written after a past update.
     * It is not read from yt-dlp, and it is the number that lied for weeks.
     */
    fun version(context: Context): String? = try {
        YoutubeDL.getInstance().version(context)
    } catch (e: Exception) {
        Log.w(TAG, "engine version unavailable: ${e.message}")
        null
    }

    /**
     * The version yt-dlp reports about ITSELF — obtained by running it.
     *
     * The only source of truth about what is actually on disk. Costs one short process spawn,
     * and is only consulted after an update claims there was nothing to do.
     */
    fun realVersion(context: Context): String? = try {
        val req = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        YoutubeDL.getInstance().execute(req).out
            .lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "engine self-report failed: ${e.message}")
        null
    }

    /** `YYYY.MM.DD` out of a version string — the part both channels always agree on. */
    private fun dateOf(v: String): String = v.trim().take(10)

    /**
     * ⚠️⚠️ THE TRAP THIS EXISTS TO BREAK — measured on Nazim's phone, 10-Sep-2026.
     *
     * The library decides "already up to date" by comparing its SharedPreferences marker with
     * GitHub's latest tag. It never looks at the yt-dlp on disk. So if the marker is ever
     * written while the files are NOT (an interrupted copy, a restore, a kill mid-update), the
     * app is trapped forever: every check answers ALREADY_UP_TO_DATE and downloads nothing,
     * while the engine underneath stays whatever it was.
     *
     * That is exactly what happened here. The app logged
     * `engine update → ALREADY_LATEST (2026.08.30.232658)` while the engine actually running
     * was the bundled `2025.11.12` — proved by the error text it produced, a string that
     * exists ONLY in the old extractor (Riplox IG). On Instagram that is fatal rather than cosmetic: the
     * old extractor has no logged-out code path at all, so every download without a connected
     * account failed, and the daily update that would have fixed it kept answering "nothing to
     * do". The same phone's other app, on a real 2026 engine, downloaded the same links fine.
     *
     * Compared on the date part only: nightly tags carry a time suffix (`2026.08.30.232658`)
     * that a stable build does not, and an exact-match test would force a re-download forever.
     */
    private fun markerIsLying(context: Context): Boolean {
        val claimed = version(context)?.trim().orEmpty()
        val real = realVersion(context)?.trim().orEmpty()
        // Either side unreadable → say nothing. A wrong "yes" here costs a pointless download
        // on every single check, which is worse than missing one stale engine.
        if (claimed.isBlank() || real.isBlank()) return false
        val lying = dateOf(real) < dateOf(claimed)
        if (lying) Log.w(TAG, "engine marker says $claimed but yt-dlp says $real — forcing update")
        return lying
    }

    /**
     * Drop the library's version marker so its next update cannot short-circuit.
     *
     * lazy: this writes another library's SharedPreferences by name. The ceiling is that a
     * future youtubedl-android could rename the file or the keys, and the force-update would
     * quietly stop working (downloads would still work — this only re-arms the update). The
     * upgrade path is a real API: youtubedl-android would have to expose either a force flag
     * on `updateYoutubeDL` or a version read that comes from yt-dlp itself.
     */
    private fun clearVersionMarker(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_VERSION)
                .remove(KEY_VERSION_NAME)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "could not clear engine version marker: ${e.message}")
        }
    }

    /**
     * Update on the NIGHTLY channel and RECORD the result.
     *
     * NIGHTLY on purpose: TikTok keeps moving, fixes land in nightly first, and the stable
     * channel ran weeks behind — which showed up as "No video formats" on a fresh install.
     */
    suspend fun update(context: Context): Outcome = withContext(Dispatchers.IO) {
        val outcome = try {
            // Matched on the NAME rather than the enum constant: the library has renamed these
            // before, and an update that works must not be reported as a failure because a
            // constant moved.
            fun runUpdate(): Boolean = YoutubeDL.getInstance()
                .updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
                ?.name?.contains("ALREADY", ignoreCase = true) == true

            var alreadyLatest = runUpdate()
            // ⚠️ "Nothing to do" is only believed once it has been checked against the engine
            // on disk. Without this the app can be trapped on a stale engine permanently —
            // see markerIsLying for the measured case that put it here.
            if (alreadyLatest && markerIsLying(context)) {
                clearVersionMarker(context)
                alreadyLatest = runUpdate()
                if (alreadyLatest) {
                    // The marker is gone, so the library had to compare against GitHub afresh.
                    // Still nothing? Then the download itself is failing, not the check.
                    Log.w(TAG, "forced update still reported ALREADY — engine files not replaced")
                }
            }
            if (alreadyLatest) Outcome.ALREADY_LATEST else Outcome.UPDATED
        } catch (e: Exception) {
            Log.w(TAG, "engine update failed: ${e.message}")
            Outcome.FAILED
        }
        // Recorded from yt-dlp itself where it can be reached, so About shows what is RUNNING
        // rather than what the library believes. The claim is the fallback, not the source.
        val shown = realVersion(context)?.takeIf { it.isNotBlank() } ?: version(context).orEmpty()
        Prefs.setEngineResult(context, outcome.name, shown)
        if (outcome != Outcome.FAILED) Prefs.setLastUpdateDay(context, today())
        Log.i(TAG, "engine update → $outcome ($shown)")
        outcome
    }

    /**
     * The once-a-day check, called from EVERY entry point — the home screen and the share
     * sheet both. Never runs while a download is RUNNING (an engine swap mid-download is how
     * you break a running job).
     *
     * The day marker is only written on success, so a failed attempt is retried at the next
     * opportunity rather than being treated as "done for today".
     *
     * ⚠️ "Busy" no longer includes a job parked on the retry backoff ladder — see hasActive.
     * A stale engine is what makes downloads fail, so treating failing-and-retrying downloads
     * as a reason not to update meant the fix was skipped precisely when it was needed.
     */
    suspend fun dailyIfDue(context: Context) {
        if (Prefs.lastUpdateDay(context) == today()) return
        val busy = withContext(Dispatchers.IO) { DownloadQueue.hasActive(context) }
        if (busy) return
        update(context)
    }

    /**
     * Is this failure the kind a fresh engine could fix?
     *
     * Deliberately broader than the old fixed string list: anything about extraction, formats
     * or an out-of-date engine counts. A missed self-heal costs a user a failed download; an
     * unnecessary one costs a few seconds. The asymmetry decides it.
     */
    fun looksStale(raw: String?): Boolean {
        val s = raw?.lowercase() ?: return false
        return listOf(
            "no video formats", "unable to extract", "not available", "requested format",
            "no formats", "confirm you are on the latest", "unsupported url", "extractor",
            "failed to parse", "unable to download webpage", "out of date", "update"
        ).any { it in s }
    }

    /** A line the About sheet can show as-is. */
    fun statusLine(context: Context): String {
        val v = Prefs.engineVersion(context).ifBlank { version(context).orEmpty() }
        val day = Prefs.lastUpdateDay(context)
        val when_ = if (day.length == 8) "${day.substring(6, 8)}/${day.substring(4, 6)}/${day.substring(0, 4)}" else "never"
        val outcome = when (Prefs.engineOutcome(context)) {
            Outcome.UPDATED.name -> "updated"
            Outcome.ALREADY_LATEST.name -> "already latest"
            Outcome.FAILED.name -> "last check failed"
            else -> "not checked yet"
        }
        return if (v.isBlank()) "Engine: unknown — $outcome" else "Engine $v — $outcome, $when_"
    }
}
