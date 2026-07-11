package com.xniperbuilds.downloader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * "Riplox TT" share tile — ZERO popup, zero friction (TT app se share → seedha download).
 *
 * AIRLOCK pattern (double-door) v2:
 *  Door 1: ye invisible activity khulti hai → app FOREGROUND me (system download rok nahi sakta)
 *  Chamber: link queue me → intezar sirf itna ke worker apni FOREGROUND-SERVICE lock laga le
 *  Door 2: lock CONFIRM hote hi activity band. Net na ho to 10s me band + "queued" message.
 */
class QuickDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        } else null

        if (link.isNullOrBlank() || !link.startsWith("http")) {
            Toast.makeText(this, "No link found in the shared text", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // TT-LOCK — ye app sirf TikTok links download karti hai
        if (!isTikTokUrl(link)) {
            Toast.makeText(this, "Riplox TT downloads TT videos only", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val workId = DownloadQueue.enqueue(this, link, Prefs.audioMode(this))
        DownloadQueue.awaitStart(this, workId) { started ->
            Toast.makeText(
                this,
                if (started) "⬇ Download started — progress in notification"
                else "⬇ Queued — starts as soon as network allows",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
