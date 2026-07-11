package com.xniperbuilds.downloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

/**
 * App-level class. Engine init + notification channel.
 * NOTE: engine auto-update yahan NAHI hota (share-popup ke waqt download se takrata tha) —
 * wo MainActivity kholne par hota hai (din me ek dafa).
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // Startup-safai (background thread — UI block nahi):
        // pruneWork = finished WM jobs ka DB kachra saaf. Temp-clear = crashed
        // downloads ke orphan dl_* folders — sirf tab jab KOI download active na ho.
        Thread {
            try {
                androidx.work.WorkManager.getInstance(this).pruneWork()
                if (!DownloadQueue.hasActive(this)) clearTempFiles(this)
            } catch (t: Throwable) {
                Log.e("XniperApp", "startup cleanup failed", t)
            }
        }.start()
    }

    companion object {
        const val CHANNEL_ID = "downloads"
    }
}
