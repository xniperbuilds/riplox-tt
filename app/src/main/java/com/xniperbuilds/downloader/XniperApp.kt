package com.xniperbuilds.downloader

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

/**
 * App-level class. Engine init + notification channel.
 * NOTE: the engine auto-update does NOT happen here (it collided with a download during the
 * share popup) — it runs when MainActivity opens, once a day.
 */
class XniperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
        } catch (e: YoutubeDLException) {
            Log.e("XniperApp", "Engine init failed", e)
        }

        // Foreground tracking for the app-open ad — which activity brought the process back, and
        // after how long. A plain counter on the platform callbacks; no extra library.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppOpen.onActivityStarted(activity)
            override fun onActivityStopped(activity: Activity) = AppOpen.onActivityStopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // Startup cleanup (on a background thread, so the UI is never blocked):
        // pruneWork clears the DB litter left by finished WM jobs. The temp clear removes
        // orphaned dl_* folders from crashed downloads (the `finally` cleanup does not run
        // on process death). ⚠️ ONLY folders older than 24h — this used to be a hasActive()
        // check that missed ENQUEUED jobs: at app open such a job went RUNNING in the same
        // second and the cleanup deleted its BRAND-NEW temp folder → "File not found"/stuck.
        Thread {
            try {
                androidx.work.WorkManager.getInstance(this).pruneWork()
                clearStaleTempFiles(this)
            } catch (t: Throwable) {
                Log.e("XniperApp", "startup cleanup failed", t)
            }
        }.start()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
    }
}
