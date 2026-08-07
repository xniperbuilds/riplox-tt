package com.xniperbuilds.downloader

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * AIRLOCK v3 — the "ESCORT": a small REAL foreground service that runs alongside the door
 * (the share activity) and escorts every new download as far as its OWN FGS lock.
 *
 * The problem it solves: the door closed after 10s, and if the WM job had not reached
 * RUNNING+fg within that window (JobScheduler deferral / app-standby bucket / XOS), the app
 * went to background → the worker's setForeground() was DENIED on Android 12+ → the download
 * was unprotected → XOS froze it = "it only runs while the app is open". With the escort:
 *   1. The app's procstate stays at FGS level → JobScheduler runs the ENQUEUED job AT ONCE.
 *   2. The app never counts as "background" (an active FGS = not backgrounded) → the
 *      worker's own setForeground() is never denied.
 *   3. As soon as the worker reports fg=true the escort calls stopSelf() — no lasting
 *      battery cost.
 * It adds nothing but a "shadow" — the WM/enqueue architecture is untouched.
 * ⚠️ NEVER setExpedited HERE EITHER (same lesson — that is a quota job, not an FGS).
 */
class EscortService : Service() {

    @Volatile private var stopped = false
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startedAt = SystemClock.elapsedRealtime()
        val n = NotificationCompat.Builder(this, XniperApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Riplox TT")
            .setContentText("Starting downloads…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.w("RiploxTT", "escort startForeground fail", e)
            stopSelf()
            return
        }
        watch()
    }

    // No new watch on every start() — the onCreate loop is enough (the service is single-instance).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Jab tak koi job ENQUEUED ya RUNNING-bina-fg-lock hai, zinda raho (cap tak). */
    private fun watch() {
        Thread {
            try {
                while (!stopped) {
                    if (SystemClock.elapsedRealtime() - startedAt > CAP_MS) break
                    if (!needsEscort(this)) break
                    Thread.sleep(POLL_MS)
                }
            } catch (_: Throwable) {
            }
            try {
                stopSelf()
            } catch (_: Exception) {
            }
        }.start()
    }

    override fun onDestroy() {
        stopped = true
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 3999           // just below the progId range (4000+)
        private const val POLL_MS = 3_000L
        private const val CAP_MS = 10 * 60 * 1000L  // battery-safety hard cap

        /** Is any download asking for an escort? Either ENQUEUED (waiting on the scheduler),
         * or RUNNING without its own FGS lock (progress "fg") confirmed yet. */
        fun needsEscort(ctx: Context): Boolean = try {
            WorkManager.getInstance(ctx.applicationContext)
                .getWorkInfosByTag(DownloadQueue.TAG).get()
                .any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        (it.state == WorkInfo.State.RUNNING &&
                            !it.progress.getBoolean("fg", false))
                }
        } catch (e: Exception) {
            false
        }

        /** Start the escort (idempotent). If it is denied from the background, ignore it
         * quietly — the worker's late-FGS retry covers that case. */
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(
                    ctx.applicationContext, Intent(ctx.applicationContext, EscortService::class.java)
                )
            } catch (e: Exception) {
                Log.w("RiploxTT", "escort start denied (bg?)", e)
            }
        }

        /** On app open: if a job is stuck or pending, give it a nudge via the escort
         * (the fix for stale ENQUEUED jobs that "won't start even with the app open"). */
        fun kickIfNeeded(ctx: Context) {
            val app = ctx.applicationContext
            Thread {
                try {
                    if (needsEscort(app)) start(app)
                } catch (_: Throwable) {
                }
            }.start()
        }
    }
}
