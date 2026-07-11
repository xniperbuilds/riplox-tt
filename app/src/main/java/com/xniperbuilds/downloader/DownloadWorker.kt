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
import android.os.Build
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// Riplox TT — fixed policy (koi settings nahi): 3 total attempts, 3 parallel downloads.
private const val MAX_ATTEMPTS = 3
private const val PARALLEL = 3

/**
 * WorkManager-based download (Riplox ke SAME battle-tested rules):
 * app band / reboot pe bhi chale, auto-retry + backoff, foreground notification
 * + live progress + Cancel, stall-watchdog, airlock FGS-lock.
 */
class DownloadWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val progId get() = 4000 + ((id.hashCode() and 0x7FFF) shl 1) // live progress notif

    // Stall-watchdog: yt-dlp ke HAR output pe reset. Itni der koi output nahi =
    // process network pe HANG hai → kill → retry. (Slot kabhi jam nahi hota.)
    @Volatile private var lastBeat = 0L

    private companion object {
        const val STALL_MS = 5 * 60 * 1000L   // 5 min no-output = stalled
        const val WATCH_EVERY_MS = 30_000L    // check interval
    }

    /** Expedited work (Android 12 se neeche) ke liye WM isay khud call karta hai. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(progId, "⬇ Downloading…", "starting…", null)

    override suspend fun doWork(): Result {
        val link = inputData.getString("link") ?: return Result.failure()
        val audio = inputData.getBoolean("audio", false)
        val nm = NotificationManagerCompat.from(ctx)
        val doneId = progId + 1 // final (success/fail) notif
        val pid = "wk_$id" // yt-dlp process id — Cancel pe isi se process kill hota hai

        // Title/thumbnail download ke PARALLEL aate hain (serial = har download +15-40s slow).
        var title = "Downloading…"
        var bmp: Bitmap? = null

        // FOREGROUND LOCK — door (share-activity/app) khula ho to FGS foran lag jati hai →
        // download system ke quota/defer/XOS killer se protected.
        val fgLocked = try {
            setForeground(foregroundInfo(progId, "⬇ Downloading…", "starting…", null))
            true
        } catch (e: Exception) {
            Log.w("RiploxTT", "FGS lock denied (bg start?) — running unlocked", e)
            false
        }
        return try {
            setProgressAsync(workDataOf("fg" to fgLocked))
            coroutineScope {
                val pvJob = launch(Dispatchers.IO) {
                    val pv = try { getPreview(ctx, link) } catch (e: Exception) { null }
                    if (pv != null) {
                        title = pv.title
                        bmp = pv.thumbnail?.let { loadThumb(it) }
                        safeNotify(nm, progId, buildNotif("⬇ $title", "downloading…", true, bmp))
                        setProgressAsync(workDataOf("title" to title, "fg" to fgLocked))
                    }
                }
                try {
                    val where = DownloadGate.withSlot(PARALLEL) {
                        // Cancel = sirf coroutine nahi, yt-dlp PROCESS bhi maro.
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
                        val watchdog = launch(Dispatchers.IO) {
                            while (true) {
                                kotlinx.coroutines.delay(WATCH_EVERY_MS)
                                if (System.currentTimeMillis() - lastBeat > STALL_MS) {
                                    Log.w("RiploxTT", "watchdog: no output ${STALL_MS / 1000}s — killing $pid")
                                    try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (_: Throwable) {}
                                    break
                                }
                            }
                        }
                        try {
                            withContext(Dispatchers.IO) {
                                runDownload(
                                    ctx, link, audio, pid,
                                    onBeat = { lastBeat = System.currentTimeMillis() }
                                ) { p ->
                                    safeNotify(nm, progId, buildNotif("⬇ $title", "$p%", true, bmp))
                                    setProgressAsync(workDataOf("pct" to p, "title" to title, "fg" to fgLocked))
                                }
                            }
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
                    pvJob.cancel()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e // user ne Cancel dabaya — retry/fail-notif nahi
        } catch (e: Exception) {
            Log.e("RiploxTT", "worker fail (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                val msg = friendlyError(e.message)
                // Preview na mila ho to title ab bhi "Downloading…" hota — failed card pe ajeeb lagta
                val failTitle = if (title == "Downloading…") "TT video" else title
                // FailedStore = home ke Failed card + Retry ka reliable data (WM-prune se azaad)
                try { FailedStore.add(ctx, link, failTitle, audio, msg) } catch (_: Exception) {}
                safeNotify(nm, doneId, buildNotif("❌ Download failed", msg, false, null, failedLink = link))
                Result.failure()
            }
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
                // FAIL → "Copy link" action (Nazim ka core requirement)
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

/** Background download queue — har download ek WorkManager task (guaranteed + retry + persistent). */
object DownloadQueue {
    const val TAG = "dl"

    fun enqueue(context: Context, link: String, audio: Boolean): java.util.UUID {
        // Network constraint: net na ho to attempts burn nahi hote — WM net aane ka wait karta.
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("link" to link, "audio" to audio))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // ⚠️ EXPEDITED MAT LAGANA (Riplox 2026-07-09 lesson): Android 12+ pe expedited =
            // quota-job (FGS nahi) — start atakta tha + app band pe download STOP. Airlock
            // (door foreground → job RUNNING → REAL dataSync FGS lock) hi sahi rasta hai.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(req)
        return req.id
    }

    /**
     * "AIRLOCK" v2 — share-tile/home activity tab tak zinda rahe jab tak worker apni
     * FOREGROUND-SERVICE LOCK laga na le (progress "fg"=true confirm). Sirf RUNNING
     * kaafi nahi (wohi purana race/stuck bug). Timeout = net na ho to bhi atko mat
     * (job WM me safe enqueued hai, net milte hi chalegi).
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
                    if (info.progress.getBoolean("fg", false) || info.state.isFinished) {
                        fire(true)
                    }
                }
        } catch (e: Exception) {
            fire(false)
            return
        }
        android.os.Handler(activity.mainLooper).postDelayed({ fire(false) }, timeoutMs)
    }

    /** Koi background download RUNNING hai? (engine-update / temp-clear guard ke liye) */
    fun hasActive(context: Context): Boolean = try {
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTag(TAG).get()
            .any { it.state == androidx.work.WorkInfo.State.RUNNING }
    } catch (e: Exception) {
        false
    }
}

/**
 * Simultaneous-downloads gate — ek waqt me sirf N downloads (TT app: fixed 3).
 * WorkManager/manifest ko chheDe bagair: semaphore se permit lelo, download karo, chhoD do.
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
