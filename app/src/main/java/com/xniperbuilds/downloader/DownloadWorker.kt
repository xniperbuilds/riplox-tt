package com.xniperbuilds.downloader

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import coil.ImageLoader
import coil.request.ImageRequest
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// Riplox TT — fixed policy (no settings): 3 total attempts, 3 parallel downloads.
private const val MAX_ATTEMPTS = 3
private const val PARALLEL = 3

/**
 * WorkManager-based download (the SAME battle-tested rules as the parent Riplox app):
 * keeps going with the app closed or across a reboot, auto-retry + backoff, a foreground
 * notification with live progress and Cancel, a stall watchdog, and the airlock FGS lock.
 */
class DownloadWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val progId get() = 4000 + ((id.hashCode() and 0x7FFF) shl 1) // live progress notif

    // Stall watchdog: reset on EVERY bit of output. No output for this long means the
    // process is HUNG on the network → kill → retry. (A slot can never jam.)
    @Volatile private var lastBeat = 0L

    // Whether the FGS lock is held — a field so the late-retry coroutine can update it too
    // and every progress update carries the CURRENT value (door signal + protection status).
    @Volatile private var fgLocked = false

    // The last download percentage seen — this is how the watchdog knows we are in the
    // merge/finishing phase (where yt-dlp goes SILENT and needs a longer stall window).
    @Volatile private var lastPct = 0

    // Did the watchdog cancel this download over a stall? (Needed to tell it apart from a
    // user Cancel — a stall is a retryable failure, a user Cancel just ends quietly.)
    @Volatile private var stalled = false

    // When this attempt started — for the first-progress deadman (the LIVELOCK fix: on
    // TikTok vt.* links yt-dlp would sometimes keep emitting output from inside its retry
    // loop, which kept the silence watchdog happy while progress never moved off 0% →
    // stuck on "starting…" for hours, holding a slot. No % within 15 min = dead link.)
    @Volatile private var attemptStart = 0L

    private companion object {
        const val STALL_MS = 5 * 60 * 1000L          // 5 min no-output = stalled
        const val FINISH_STALL_MS = 20 * 60 * 1000L  // merge/save phase (it is silent) — 20 min
        const val FIRST_PROGRESS_MS = 15 * 60 * 1000L // no movement off 0% in 15 min = dead link
        const val WATCH_EVERY_MS = 30_000L           // check interval
    }

    /** Expedited work (Android 12 se neeche) ke liye WM isay khud call karta hai. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(progId, "⬇ Downloading…", "starting…", null)

    override suspend fun doWork(): Result {
        val link = inputData.getString("link") ?: return Result.failure()
        val audio = inputData.getBoolean("audio", false)
        val nm = NotificationManagerCompat.from(ctx)
        val doneId = progId + 1 // final (success/fail) notif
        val pid = "wk_$id" // yt-dlp process id — Cancel kills the process through this

        // Title/thumbnail arrive IN PARALLEL with the download (serial would add 15-40s to each).
        var title = "Downloading…"
        var bmp: Bitmap? = null

        // FOREGROUND LOCK — while a door (the share activity/app) is open, the FGS attaches
        // immediately, protecting the download from system quotas, deferral and OEM killers.
        fgLocked = try {
            setForeground(foregroundInfo(progId, "⬇ Downloading…", "starting…", null))
            true
        } catch (e: Exception) {
            Log.w("RiploxTT", "FGS lock denied (bg start?) — running unlocked", e)
            false
        }

        // With the screen off, XOS would put the CPU/Wi-Fi to sleep and a running download
        // stalled part-way. Hold a partial wake lock + wifi lock for the whole download
        // (6h safety cap; released in `finally` — and if the process is killed the system
        // releases them anyway).
        val wake = try {
            (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RiploxTT:dl")?.also {
                    it.setReferenceCounted(false)
                    it.acquire(6 * 60 * 60 * 1000L)
                }
        } catch (e: Exception) {
            null
        }
        @Suppress("DEPRECATION")
        val wifi = try {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RiploxTT:dl")?.also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
        } catch (e: Exception) {
            null
        }

        return try {
            setProgressAsync(workDataOf("fg" to fgLocked))
            coroutineScope {
                // LATE-LOCK RETRY: on a background start, Android 12+ denies the FGS
                // (fgLocked=false → the download is unprotected → an XOS freeze, i.e. the
                // "it only runs while the app is open" problem). Once the escort FGS lands
                // or the app is opened, the next retry gets a REAL FGS — and from that
                // point the download survives the app being closed.
                // First try at 3s (the escort usually covers it instantly), then every 10s.
                val fgRetry = if (!fgLocked) launch {
                    repeat(60) { // ~10 min window
                        kotlinx.coroutines.delay(if (it == 0) 3_000L else 10_000L)
                        if (fgLocked) return@launch
                        try {
                            setForeground(foregroundInfo(progId, "⬇ $title", "downloading…", bmp))
                            fgLocked = true
                            setProgressAsync(workDataOf("fg" to true, "title" to title))
                            Log.i("RiploxTT", "FGS lock acquired late (retry)")
                        } catch (_: Exception) {
                        }
                    }
                } else null
                try {
                    val where = DownloadGate.withSlot(PARALLEL) {
                        // Cancel must kill the yt-dlp PROCESS too, not just the coroutine.
                        val killer = launch {
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Throwable) {}
                                }
                            }
                        }
                        lastBeat = System.currentTimeMillis()
                        attemptStart = System.currentTimeMillis() // only AFTER the slot is granted, so queue wait is not counted by the deadman
                        // The download runs in its own async so that on a stall the watchdog can
                        // BOTH kill the process AND cancel this. It used to only kill the
                        // process: if the hang was on the Kotlin side (a MediaStore save, say)
                        // the coroutine never returned → the slot jammed FOREVER → new downloads
                        // sat on "starting…" (the "won't start even with the app open" bug).
                        val dl = async(Dispatchers.IO) {
                            runDownload(
                                ctx, link, audio, pid,
                                // The temp folder is keyed on THIS JOB's id, which survives a
                                // retry → the partial file resumes and never collides with another job.
                                workKey = id.toString(),
                                onBeat = { lastBeat = System.currentTimeMillis() },
                                onSave = { sp ->
                                    // Chunk beats from the gallery copy: reset the watchdog + live "Saving…"
                                    lastBeat = System.currentTimeMillis()
                                    if (sp % 5 == 0 || sp == 100) {
                                        safeNotify(nm, progId, buildNotif("⬇ $title", "Saving to gallery… $sp%", true, bmp))
                                    }
                                },
                                // Title/thumbnail come from the extractor (this used to run a
                                // SEPARATE yt-dlp "getPreview" process, which by now always
                                // failed on TikTok and wasted ~30s doing it).
                                onMeta = { t, thumbUrl ->
                                    if (t.isNotBlank()) title = t
                                    launch(Dispatchers.IO) {
                                        if (thumbUrl != null && bmp == null) bmp = loadThumb(thumbUrl)
                                        safeNotify(nm, progId, buildNotif("⬇ $title", "downloading…", true, bmp))
                                        setProgressAsync(workDataOf("title" to title, "fg" to fgLocked))
                                    }
                                }
                            ) { p ->
                                lastPct = p
                                // 100% means the transfer is done, but the ffmpeg merge and the
                                // gallery save STILL follow — say so, or "100%" looks stuck.
                                val txt = if (p >= 99) "Finishing — merging & saving…" else "$p%"
                                safeNotify(nm, progId, buildNotif("⬇ $title", txt, true, bmp))
                                setProgressAsync(workDataOf("pct" to p, "title" to title, "fg" to fgLocked))
                            }
                        }
                        // Watchdog — kill on a stall (→ retry). In the merge/finishing phase
                        // (pct ≥ 99) yt-dlp is LEGITIMATELY silent (ffmpeg produces no output),
                        // so that phase gets a 20-min window; otherwise a healthy 100% merge
                        // got killed and restarted from 0 = the "stuck at 100%" loop.
                        val watchdog = launch(Dispatchers.IO) {
                            while (true) {
                                kotlinx.coroutines.delay(WATCH_EVERY_MS)
                                val now = System.currentTimeMillis()
                                val limit = if (lastPct >= 99) FINISH_STALL_MS else STALL_MS
                                // Deadman #2: output keeps coming (a retry-loop livelock) but
                                // nothing moves off 0% for 15 min = the link/extractor is dead.
                                // The silence check never caught this one (hours on "starting…").
                                val neverStarted = lastPct == 0 && now - attemptStart > FIRST_PROGRESS_MS
                                if (now - lastBeat > limit || neverStarted) {
                                    Log.w("RiploxTT", "watchdog: ${if (neverStarted) "no progress ${FIRST_PROGRESS_MS / 1000}s" else "no output ${limit / 1000}s"} — killing $pid")
                                    stalled = true
                                    try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Throwable) {}
                                    dl.cancel()
                                    break
                                }
                            }
                        }
                        try {
                            dl.await()
                        } catch (e: CancellationException) {
                            // A watchdog stall → retryable failure. A user/WM cancel → rethrow as is.
                            if (stalled) throw Exception("Stalled — no data received for too long") else throw e
                        } finally {
                            watchdog.cancel()
                            killer.cancel()
                        }
                    }
                    // Done-notif: thumbnail bada + tap = file kholo (system player)
                    val rec = withContext(Dispatchers.IO) {
                        try { History.all(ctx).firstOrNull() } catch (e: Exception) { null }
                    }
                    safeNotify(
                        nm, doneId,
                        buildNotif("✓ $title", where, false, bmp, bigPicture = true, record = rec)
                    )
                } finally {
                    fgRetry?.cancel()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e // the user pressed Cancel — no retry, no failure notification
        } catch (e: Exception) {
            Log.e("RiploxTT", "worker fail (attempt $runAttemptCount)", e)
            val raw = e.message ?: ""
            // TikTok keeps changing its extractor/API, so a stale yt-dlp returns "No video
            // formats" / "unable to extract" — especially on a fresh install, where the
            // bundled engine is old. On the first failure, update the engine on the NIGHTLY
            // channel and let WorkManager retry with the fresh one: a day-1 install heals
            // itself, with no visible failure for the user (the second attempt works).
            val looksStale = runAttemptCount == 0 && listOf(
                "no video formats", "unable to extract", "not available",
                "confirm you are on the latest", "requested format", "no formats"
            ).any { raw.contains(it, ignoreCase = true) }
            if (looksStale) {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
                    }
                    Log.i("RiploxTT", "engine updated (nightly) after extractor fail — retrying")
                } catch (ue: Exception) { Log.w("RiploxTT", "engine update failed", ue) }
                Result.retry()
            } else if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                val msg = friendlyError(e.message)
                // With no preview the title is still "Downloading…", which reads oddly on a failed card
                val failTitle = if (title == "Downloading…") "TT video" else title
                // FailedStore backs the home Failed card + Retry with reliable data (independent of WM pruning)
                try { FailedStore.add(ctx, link, failTitle, audio, msg) } catch (_: Exception) {}
                safeNotify(nm, doneId, buildNotif("❌ Download failed", msg, false, null, failedLink = link))
                Result.failure()
            }
        } finally {
            // Cancel the ongoing progress notification on EVERY path (success/fail/retry/
            // cancel) — in the unlocked (no-FGS) case nothing removed it, so a "stuck at
            // 100%" notification stayed forever with the ✓ done one buried underneath.
            try { nm.cancel(progId) } catch (_: Exception) {}
            try { if (wake?.isHeld == true) wake.release() } catch (_: Exception) {}
            try { if (wifi?.isHeld == true) wifi.release() } catch (_: Exception) {}
        }
    }

    private suspend fun loadThumb(url: String): Bitmap? = try {
        val loader = ImageLoader(ctx)
        val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).build()
        (loader.execute(req).drawable as? BitmapDrawable)?.bitmap
    } catch (e: Exception) {
        null
    }

    private fun buildNotif(
        title: String,
        text: String,
        ongoing: Boolean,
        largeIcon: Bitmap?,
        bigPicture: Boolean = false,
        record: DownloadRecord? = null,
        failedLink: String? = null
    ): Notification {
        val icon = if (ongoing) android.R.drawable.stat_sys_download
        else android.R.drawable.stat_sys_download_done
        val b = NotificationCompat.Builder(ctx, XniperApp.CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (bigPicture && largeIcon != null) {
            b.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(largeIcon)
                    .bigLargeIcon(null as Bitmap?)
            )
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        if (largeIcon != null) b.setLargeIcon(largeIcon)
        if (ongoing) {
            b.addAction(0, "Cancel", WorkManager.getInstance(ctx).createCancelPendingIntent(id))
        }
        if (!ongoing) {
            try {
                // Tap: done → file kholo (system player); fail → app kholo.
                val tapIntent = if (record != null) {
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                            Uri.parse(record.location),
                            if (record.isAudio) "audio/*" else "video/*"
                        )
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                } else {
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pi = PendingIntent.getActivity(
                    ctx, (record?.location ?: title).hashCode(),
                    tapIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                b.setContentIntent(pi)
                b.setAutoCancel(true)
                if (record != null) b.addAction(0, "▶ Play", pi)
                // FAIL → a "Copy link" action, so the link is never lost
                if (failedLink != null) {
                    val cp = Intent(ctx, CopyLinkReceiver::class.java).putExtra("link", failedLink)
                    val cpi = PendingIntent.getBroadcast(
                        ctx, failedLink.hashCode(), cp,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    b.addAction(0, "Copy link", cpi)
                }
            } catch (_: Exception) {
            }
        }
        return b.build()
    }

    private fun foregroundInfo(notifId: Int, title: String, text: String, bmp: Bitmap?): ForegroundInfo {
        val n = buildNotif(title, text, true, bmp)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    private fun safeNotify(nm: NotificationManagerCompat, notifId: Int, n: Notification) {
        try {
            nm.notify(notifId, n)
        } catch (e: SecurityException) {
        }
    }
}

/** Fail-notif ka "Copy link" — link clipboard me (background se WRITE allowed hai). */
class CopyLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val link = intent.getStringExtra("link") ?: return
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("TT link", link))
            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}

/** Background download queue — every download is a WorkManager task (guaranteed + retry + persistent). */
object DownloadQueue {
    const val TAG = "dl"

    fun enqueue(context: Context, link: String, audio: Boolean): java.util.UUID {
        // Network constraint: with no connection, attempts are not burned — WM waits for one.
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("link" to link, "audio" to audio))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // ⚠️ NEVER MARK THIS EXPEDITED (hard-won lesson): on Android 12+ expedited means
            // a quota job (not an FGS) — the start would hang and the download STOPPED once
            // the app closed. The airlock (door in foreground → job RUNNING → a REAL dataSync
            // FGS lock) is the correct route.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(req)
        // AIRLOCK v3: an ESCORT alongside the door — a tiny FGS that escorts the job as far
        // as its own FGS lock (JobScheduler runs it immediately and the worker's
        // setForeground is not denied). It stops itself the moment fg=true arrives, so the
        // download stays protected even if the door closes after 10s.
        EscortService.start(context)
        return req.id
    }

    /**
     * "AIRLOCK" v2 — keep the share-tile/home activity alive until the worker has taken its
     * own FOREGROUND-SERVICE LOCK (confirmed by progress "fg"=true). RUNNING alone is not
     * enough (that was the old race/stuck bug). The timeout means we do not hang when there
     * is no network (the job is safely enqueued in WM and runs as soon as one appears).
     */
    fun awaitStart(
        activity: androidx.activity.ComponentActivity,
        id: java.util.UUID,
        timeoutMs: Long = 10_000,
        onDone: (started: Boolean) -> Unit
    ) {
        var fired = false
        fun fire(started: Boolean) {
            if (!fired) {
                fired = true
                onDone(started)
            }
        }
        try {
            WorkManager.getInstance(activity.applicationContext)
                .getWorkInfoByIdLiveData(id)
                .observe(activity) { info ->
                    if (info == null) return@observe
                    // fg=true confirms the FGS lock; isFinished means it ended that fast, so
                    // there is nothing left to wait for. Do NOT report "started" as TRUE on
                    // FAILED/CANCELLED (that produced a misleading toast).
                    if (info.progress.getBoolean("fg", false)) {
                        fire(true)
                    } else if (info.state.isFinished) {
                        fire(info.state == androidx.work.WorkInfo.State.SUCCEEDED)
                    }
                }
        } catch (e: Exception) {
            fire(false)
            return
        }
        android.os.Handler(activity.mainLooper).postDelayed({ fire(false) }, timeoutMs)
    }

    /** Is any background download RUNNING/PENDING? (guard for engine-update / temp-clear)
     * ⚠️ ENQUEUED COUNTS too — a pending job can go RUNNING in any given second, and with a
     * RUNNING-only check the engine update / temp clear collided with it. */
    fun hasActive(context: Context): Boolean = try {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTag(TAG).get()
            .any {
                it.state == androidx.work.WorkInfo.State.RUNNING ||
                    it.state == androidx.work.WorkInfo.State.ENQUEUED
            }
    } catch (e: Exception) {
        false
    }
}

/**
 * Gate on simultaneous downloads — only N at a time (fixed at 3 in the TT app).
 * Without touching WorkManager or the manifest: take a semaphore permit, download, release.
 */
object DownloadGate {
    @Volatile private var permits = -1
    @Volatile private var sem = Semaphore(3)

    @Synchronized private fun semFor(n: Int): Semaphore {
        if (n != permits) {
            permits = n
            sem = Semaphore(n)
        }
        return sem
    }

    suspend fun <T> withSlot(n: Int, block: suspend () -> T): T {
        val s = semFor(n.coerceIn(1, 5))
        s.acquire()
        try {
            return block()
        } finally {
            s.release()
        }
    }
}
