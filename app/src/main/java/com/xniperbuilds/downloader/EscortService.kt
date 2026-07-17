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
 * AIRLOCK v3 — "ESCORT": door (share-activity) ke saath ek chhota REAL foreground
 * service jo har naye download ko uske APNE FGS-lock tak escort karta hai.
 *
 * Masla jo ye hal karta hai (Riplox 2026-07-16): door 10s me band ho jata tha — agar WM
 * job us window me RUNNING+fg tak na pahunchi (JobScheduler defer / bucket / XOS), to app
 * background — worker ka setForeground() Android 12+ pe DENY — download unprotected —
 * XOS freeze = "app kholo to hi chale". Escort ke hote hue:
 *   1. App ka procstate FGS-level rehta hai → JobScheduler ENQUEUED job FORAN chalata hai.
 *   2. App "background" me ginti hi nahi (active FGS = not-background) → worker ki
 *      apni setForeground() kabhi deny nahi hoti.
 *   3. Worker ka fg=true aate hi escort khud stopSelf() — koi lamba battery cost nahi.
 * Ye WM/enqueue architecture ko chheDe bagair sirf ek "saya" add karta hai.
 * ⚠️ setExpedited YAHAN BHI KABHI NAHI (Riplox 2026-07-09 lesson — quota job, FGS nahi).
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

    // Har start() pe naya watch NAHI — onCreate ka loop hi kaafi (service single-instance).
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
        private const val NOTIF_ID = 3999           // progId range (4000+) se neeche
        private const val POLL_MS = 3_000L
        private const val CAP_MS = 10 * 60 * 1000L  // battery-safety hard cap

        /** Koi download escort maang rahi hai? ENQUEUED (scheduler start kare) ya
         * RUNNING jiska apna FGS-lock (progress "fg") abhi confirm nahi. */
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

        /** Escort chalao (idempotent). Background se deny ho to chup-chaap ignore —
         * worker ki late-FGS-retry wahan cover karti hai. */
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(
                    ctx.applicationContext, Intent(ctx.applicationContext, EscortService::class.java)
                )
            } catch (e: Exception) {
                Log.w("RiploxTT", "escort start denied (bg?)", e)
            }
        }

        /** App khulne pe: koi phansi/pending job ho to escort se dhakka do
         * (stale ENQUEUED jobs ka "app open pe bhi start nahi" fix). */
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
