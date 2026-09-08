package com.xniperbuilds.downloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.xniperbuilds.downloader.ui.theme.RiploxInk
import com.xniperbuilds.downloader.ui.theme.RiploxSteel
import com.xniperbuilds.downloader.ui.theme.SpaceGrotesk
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ---------------------------------------------------------------------------
// DESIGN TOKENS — measured, not picked by feel (xniper-design, mobile lane)
//
// Canvas 375 · body 16 · small 14 · headline 18 · buttons 48 tall with a 16 label · gap 8/16
// · padding 16 · weight 400 with Bold for emphasis (the 500/600 middle is the generated-UI
// tell on mobile).
//
// ⚠️ CONTRAST WAS COMPUTED, NOT ASSUMED — the one failure a screenshot will not show you:
//     text  #E8EEF5 on ink #0A101B ........... far above 4.5:1  OK
//     muted #7C8DA6 on ink .................... 5.64:1          OK
//     ink   #0A101B on accent #8CA0BE ......... 7.14:1          OK
//     WHITE on accent #8CA0BE ................. 2.66:1          FAILS
// Hence the accent button carries INK text. That is not a style choice.
// ---------------------------------------------------------------------------
private val SheetSurface = Color(0xFF121A27)
private val SheetText = Color(0xFFE8EEF5)
private val SheetMuted = Color(0xFF7C8DA6)
private val SheetLine = Color(0xFF1E2938)
private val SheetTrack = Color(0xFF223046)

private enum class Phase { Preparing, Downloading, Saved, Failed }

/**
 * The "Riplox TT" share tile.
 *
 * WHAT CHANGED IN v1.1.5 AND WHY: this activity used to be INVISIBLE — it opened, fired a
 * toast and finished. Two consequences, both bad:
 *  · a user who only ever shares from TikTok got no progress, no Cancel and no error — the
 *    exact "progress clear nahi" complaint the testers raised;
 *  · and because `Ads.onDownloadComplete` only runs while MainActivity is alive, that user
 *    generated **no ad impressions at all**. A million downloads, zero revenue.
 *
 * AIRLOCK IS NOT WEAKENED BY THIS. The double-door works exactly as before — the link goes on
 * the queue immediately and `awaitStart` still waits for the worker's own FGS lock. A VISIBLE
 * foreground activity is strictly stronger than an invisible one, and the sheet now stays up
 * instead of racing to finish. Back, or a tap outside, dismisses it and the download carries on
 * in the background — the old behaviour, preserved.
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

        // TT-LOCK — this app only downloads TikTok links
        if (!isTikTokUrl(link)) {
            Toast.makeText(this, "Riplox TT downloads TT videos only", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val audio = Prefs.audioMode(this)
        val first = DownloadQueue.enqueue(this, link, audio)

        // Consent + Ads SDK start. ⚠️ Deliberately NO preload here. This used to fetch an
        // interstitial on every share open "so a later Open app tap never waits on a network
        // round trip" — but the first Open app tap is completion #1, which is never a show
        // slot, so that ad could not be shown and expired as a wasted matched request
        // (measured 1–7 Sep 2026). Nothing is lost: onEnterAppFromShare never delays
        // navigation, and it fetches for the next slot itself. See AdGate.
        Consent.gather(this)

        // ⚠️ The cookie primer is deliberately NOT started here. Measured on device: loading
        // tiktok.com in a WebView on the main thread starves the very main-thread hop the
        // extractor needs to read cookies ("Skipped 83 frames"), and the download went silent
        // for a minute instead of failing fast. It runs once this download has settled instead
        // — see the LaunchedEffect below.

        setContent { Sheet(link, audio, first) }
    }

    @Composable
    private fun Sheet(link: String, audio: Boolean, firstId: UUID) {
        val scope = rememberCoroutineScope()

        var workId by remember { mutableStateOf(firstId) }
        var phase by remember { mutableStateOf(Phase.Preparing) }
        var pct by remember { mutableStateOf(0) }
        // ⚠️ NOT "Getting video…" — the status line right below already says that, and on a real
        // device the sheet read "Getting video…" over "Getting the video…". A placeholder that
        // names the thing, with the status underneath, says something instead of stuttering.
        var title by remember { mutableStateOf("TikTok video") }
        var thumb by remember { mutableStateOf<String?>(null) }
        var saved by remember { mutableStateOf<DownloadRecord?>(null) }
        var failure by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var queuedOnly by remember { mutableStateOf(false) }

        // The AIRLOCK handoff still runs — it just drives a line of text now instead of a toast
        // followed by finish().
        DisposableEffect(workId) {
            DownloadQueue.awaitStart(this@QuickDownloadActivity, workId) { started ->
                queuedOnly = !started
            }
            onDispose {}
        }

        DisposableEffect(workId) {
            val live = WorkManager.getInstance(applicationContext).getWorkInfoByIdLiveData(workId)
            val obs = Observer<WorkInfo?> { info ->
                if (info == null) return@Observer
                info.progress.getString("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                info.progress.getString("thumb")?.takeIf { it.isNotBlank() }?.let { thumb = it }
                val p = info.progress.getInt("pct", -1)
                if (p >= 0) {
                    pct = p
                    if (phase == Phase.Preparing) phase = Phase.Downloading
                }
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        saved = try {
                            History.all(this@QuickDownloadActivity).firstOrNull()
                        } catch (e: Exception) {
                            null
                        }
                        phase = Phase.Saved
                    }
                    WorkInfo.State.FAILED -> {
                        // The real reason, from our own store — WorkManager's FAILED info gets
                        // pruned and carries nothing useful.
                        failure = try {
                            FailedStore.all(this@QuickDownloadActivity)
                                .firstOrNull { it.link == link }?.error.orEmpty()
                        } catch (e: Exception) {
                            ""
                        }
                        phase = Phase.Failed
                    }
                    else -> {}
                }
            }
            live.observe(this@QuickDownloadActivity, obs)
            onDispose { live.removeObserver(obs) }
        }

        // The share-only user never opens MainActivity, so this is the ONLY place their engine
        // ever gets refreshed (which is why MP3 quietly rotted for them).
        // ⚠️ It runs once the download has LANDED, not at share time: dailyIfDue deliberately
        // refuses to swap the engine while a job is active, and at share time we have just
        // enqueued one — so calling it there would skip every single time.
        // Earn ttwid EARLY — an empty jar is what forces every download onto the WebView route,
        // and that route is a coin flip: measured 3s one run, a 45s timeout the next. With a
        // cookie in hand the fast HTTP route just works.
        //
        // ⚠️ The 1.5s delay is the whole trick. Starting the primer at onCreate put a full
        // tiktok.com page load on the main thread at the exact moment the extractor needed it to
        // read cookies, and the download went silent for a minute. Letting that first hop clear
        // first costs nothing and cannot collide.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            try {
                TtCookiePrimer.primeIfNeeded(this@QuickDownloadActivity)
            } catch (_: Exception) {
            }
        }

        LaunchedEffect(phase) {
            if (phase == Phase.Saved || phase == Phase.Failed) {
                // Now that nothing is competing for the main thread, earn TikTok's cookies. On a
                // brand-new install this is what makes the NEXT download (or the Retry button
                // right here) work instead of timing out.
                try {
                    TtCookiePrimer.primeIfNeeded(this@QuickDownloadActivity)
                } catch (_: Exception) {
                }
            }
            if (phase == Phase.Saved) {
                try {
                    Engine.dailyIfDue(applicationContext)
                } catch (e: Exception) {
                    // Never surfaced here: the download already succeeded, and this is upkeep.
                }
            }
        }

        fun openApp() {
            // The one legitimate interstitial moment in this flow — see Ads.onEnterAppFromShare.
            Ads.onEnterAppFromShare(this@QuickDownloadActivity) {
                startActivity(
                    Intent(this@QuickDownloadActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
            }
        }

        fun retry(updateEngine: Boolean) {
            busy = true
            scope.launch {
                if (updateEngine) {
                    withContext(Dispatchers.IO) {
                        try {
                            YoutubeDL.getInstance()
                                .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                        } catch (e: Exception) {
                            // A failed update is not a reason to refuse the retry — the retry
                            // itself will report whatever goes wrong next.
                        }
                    }
                }
                pct = 0
                title = "TikTok video"
                phase = Phase.Preparing
                busy = false
                workId = DownloadQueue.enqueue(this@QuickDownloadActivity, link, audio)
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Tapping outside dismisses. No ripple — this is a scrim, not a control.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { finish() }
            )

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(SheetSurface)
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                val done = phase == Phase.Saved || phase == Phase.Failed

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (thumb != null) {
                        AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 56.dp, height = 72.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SheetTrack)
                        )
                    } else {
                        Box(
                            Modifier
                                .size(width = 56.dp, height = 72.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SheetTrack)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (phase) {
                                Phase.Saved -> "Saved to gallery"
                                Phase.Failed -> "Download failed"
                                else -> title
                            },
                            color = SheetText,
                            fontSize = if (done) 18.sp else 16.sp,
                            fontFamily = if (done) SpaceGrotesk else null,
                            fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when (phase) {
                                // Says what is happening AND that waiting is the right thing to
                                // do. "Getting the video…" left people wondering whether it had
                                // actually started.
                                Phase.Preparing ->
                                    if (queuedOnly) "Waiting for network — the download will start on its own"
                                    else "Please wait — the download is starting…"
                                Phase.Downloading ->
                                    if (pct >= 99) "Finishing — merging & saving…" else "$pct%"
                                // The video's title, not the file name — the record stores the
                                // saved filename, which reads like machine output on screen.
                                Phase.Saved ->
                                    if (title != "TikTok video") title
                                    else saved?.title?.takeIf { it.isNotBlank() } ?: "Done"
                                Phase.Failed -> failure.ifBlank { "Something went wrong" }
                            },
                            color = SheetMuted,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!done) {
                    Spacer(Modifier.height(16.dp))
                    // Hand-drawn rather than LinearProgressIndicator: an exact 4dp pill, exact
                    // colours, and no dependence on which Material3 progress API ships here.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(SheetTrack)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction = pct.coerceIn(0, 100) / 100f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(RiploxSteel)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (phase) {
                        Phase.Preparing, Phase.Downloading -> {
                            OutlinedButton(
                                onClick = {
                                    WorkManager.getInstance(applicationContext).cancelWorkById(workId)
                                    finish()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(50)
                            ) { Text("Cancel", color = SheetText, fontSize = 16.sp) }

                            OutlinedButton(
                                onClick = { openApp() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(50)
                            ) { Text("Open app", color = SheetText, fontSize = 16.sp) }
                        }

                        Phase.Saved -> {
                            // The accent goes on what the USER wants (open the video), never on
                            // the button that happens to trigger an ad.
                            Button(
                                onClick = {
                                    saved?.let { r ->
                                        try {
                                            startActivity(
                                                Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(
                                                        Uri.parse(r.location),
                                                        if (r.isAudio) "audio/*" else "video/*"
                                                    )
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            )
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                this@QuickDownloadActivity,
                                                "Can't open — file may be deleted",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    finish()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RiploxSteel,
                                    contentColor = RiploxInk
                                )
                            ) { Text("Open", fontSize = 16.sp) }

                            OutlinedButton(
                                onClick = {
                                    saved?.let { r ->
                                        try {
                                            startActivity(
                                                Intent.createChooser(
                                                    Intent(Intent.ACTION_SEND)
                                                        .setType(if (r.isAudio) "audio/*" else "video/*")
                                                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(r.location))
                                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                                    "Share"
                                                )
                                            )
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                this@QuickDownloadActivity,
                                                "Can't share this file",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(50)
                            ) { Text("Share", color = SheetText, fontSize = 16.sp) }
                        }

                        Phase.Failed -> {
                            OutlinedButton(
                                onClick = { if (!busy) retry(updateEngine = false) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(50)
                            ) { Text("Retry", color = SheetText, fontSize = 16.sp) }

                            // B5 lives here: the one-tap answer for a non-technical user, who
                            // otherwise has no way at all to refresh the engine.
                            Button(
                                onClick = { if (!busy) retry(updateEngine = true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RiploxSteel,
                                    contentColor = RiploxInk
                                )
                            ) { Text(if (busy) "Updating…" else "Update & retry", fontSize = 16.sp) }
                        }
                    }
                }

                if (done) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Open Riplox TT",
                        color = SheetMuted,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable { openApp() }
                            .padding(8.dp)
                    )
                }

                // ---- AD SEPARATION -------------------------------------------------------
                // AdMob: "banner ads should not be placed next to interactive buttons".
                // 24 + a hairline + 16 is that rule made physical. Do not close this gap up.
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(SheetLine)
                )
                Spacer(Modifier.height(16.dp))
                BannerAd()
            }
        }
    }
}
