package com.xniperbuilds.downloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.xniperbuilds.downloader.ui.theme.RiploxInk
import com.xniperbuilds.downloader.ui.theme.RiploxSteel
import com.xniperbuilds.downloader.ui.theme.SpaceGrotesk
import kotlinx.coroutines.launch
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

/**
 * The tall sheet's fixed, non-text height: 16 pad + 8 + box 250 + 25 separation + 12 + 4 + 16
 * + progress 4 + 16 + buttons 48 + 4 + link row 36 + 16 pad = 455dp. Its text — caption 16 +
 * title 24 + status 2×20 = 80sp — is added at run time in dp, so a larger system font is
 * counted rather than overflowing. The preview then takes what is left, within
 * [PREVIEW_MIN]..[PREVIEW_MAX].
 *
 * Measured against the real space between the system bars on the phone in hand — a screen
 * that cannot fit the minimum keeps the compact sheet and its bottom strip. (A first cut asked
 * for a flat 740dp and would have left a 360×780 phone — one of the commonest sizes — without
 * the box at all.)
 */
private val TALL_FIXED = 455.dp
private val TALL_TEXT = 80.sp

/** Smallest preview worth having: below this the post is a sliver and the ad outweighs the page. */
private val PREVIEW_MIN = 120.dp
private val PREVIEW_MAX = 180.dp

/** Room left above the panel, so there is still somewhere to tap to dismiss. */
private val SCRIM_HEADROOM = 16.dp

private enum class Phase { Preparing, Downloading, Saved, Failed }

/**
 * The "Riplox TT" share tile.
 *
 * WHAT CHANGED IN v1.1.0 AND WHY: this activity used to be INVISIBLE — it opened, fired a
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
 *
 * ⚠️ `android:noHistory` had to come off this activity in the manifest. It finishes an activity
 * the moment it stops being visible, which would have killed the sheet as soon as a full-screen
 * ad or the system share chooser opened over it.
 */
class QuickDownloadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start(intent)
    }

    /**
     * ⚠️ THE SECOND SHARE LANDS HERE, NOT IN onCreate — and without this override it was
     * silently thrown away.
     *
     * Measured with `adb shell am start`, sharing twice in a row:
     *
     *     Warning: Activity not started, intent has been delivered to
     *              currently running top-most instance.
     *
     * This activity has an empty taskAffinity and is the root of its own task, so Android
     * re-delivers to the live instance instead of creating a second one. In v1.0.1 that could
     * never happen, because `android:noHistory` finished the activity the moment it stopped
     * being visible — there was never an instance left to deliver to. Removing noHistory (so
     * the sheet could survive a full-screen ad) is what exposed it.
     *
     * The symptom was exactly "share karo aur sheet aati hi nahi, screen atak jati hai": the
     * old sheet stayed on screen showing the PREVIOUS download, the new link was never queued,
     * and every button on it still pointed at the old job.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        start(intent)
    }

    private fun start(intent: Intent?) {
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

        // The share-only user never opens MainActivity, so without this the consent form would
        // never be shown to them and their ad requests would stay ineligible forever. This is
        // also what starts the Ads SDK — nothing below can load an ad until it has run.
        // Preloading now means a later "Open app" tap never waits on a network round trip.
        // ⚠️ No preload here — the first "Open app" tap is completion #1, never a show slot, so
        // that ad could not be shown and expired as a wasted matched request. See AdGate.
        Consent.gather(this)

        setContent { Sheet(link, audio, first) }
    }

    @Composable
    private fun Sheet(link: String, audio: Boolean, firstId: UUID) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
            val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
            val textHeight = with(density) { TALL_TEXT.toDp() }
            // What the preview could have, if the rest of the tall panel goes in.
            val previewRoom = maxHeight - topInset - bottomInset - TALL_FIXED - textHeight - SCRIM_HEADROOM
            // The box goes in only where the whole tall panel fits — measured here, not assumed —
            // and never inside the earned ad-free window: with no ad to show there is no reason
            // to make the sheet taller.
            val boxed = Ads.ENABLED &&
                !Ads.suppressed(this@QuickDownloadActivity) &&
                previewRoom >= PREVIEW_MIN
            val previewHeight = previewRoom.coerceIn(PREVIEW_MIN, PREVIEW_MAX)
            LaunchedEffect(boxed) {
                Log.i(
                    "RiploxTT.Sheet",
                    "height=$maxHeight insets=$topInset/$bottomInset previewRoom=$previewRoom boxed=$boxed"
                )
            }

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
                    // Never taller than the space under the status bar. If a large system font
                    // pushes it past that, the panel scrolls instead of hiding its buttons.
                    .heightIn(max = maxHeight - topInset)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(SheetSurface)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                if (boxed) {
                    // ---- THE BOX (v1.3.0) ------------------------------------------------------
                    // Inside the panel, never above it. AdMob: "Ads should not float or hover over
                    // app content. This includes placing the ad over a background that covers app
                    // content" — and the dimmed TikTok behind this sheet is exactly that.
                    // One ad per sheet: with the box in, the bottom strip below is left out, and the
                    // large preview keeps the screen from carrying more ad than content.
                    Column(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(300.dp)
                    ) {
                        Text("Advertisement", color = SheetMuted, fontSize = 12.sp, lineHeight = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        BoxAd(placeholder = SheetTrack)
                    }
                    Spacer(Modifier.height(12.dp))
                    Hairline()
                    Spacer(Modifier.height(12.dp))
                }

                // ⚠️ KEYED ON THE SHARE. A second share arrives through onNewIntent → setContent,
                // which REUSES this composition: without the key every `remember` below keeps the
                // first share's job, so the sheet would go on showing the old download (and Cancel
                // would cancel it) while the new one ran unseen. The box above sits outside the
                // key on purpose — the ad already on screen stays where it is.
                key(firstId) { SheetBody(link, audio, firstId, boxed, previewHeight) }

                if (!boxed) {
                    // ---- AD SEPARATION -------------------------------------------------------
                    // AdMob's banner guidance is about accidental clicks from an ad sitting next to
                    // something people tap. 24 + a hairline + 16 is that rule made physical.
                    // Do not close this gap up.
                    Spacer(Modifier.height(24.dp))
                    Hairline()
                    Spacer(Modifier.height(16.dp))
                    BannerAd()
                }
            }
        }
    }

    @Composable
    private fun SheetBody(link: String, audio: Boolean, firstId: UUID, boxed: Boolean, previewHeight: Dp) {
        val scope = rememberCoroutineScope()

        var workId by remember { mutableStateOf(firstId) }
        var phase by remember { mutableStateOf(Phase.Preparing) }
        var pct by remember { mutableStateOf(0) }
        // ⚠️ NOT "Getting video…" — the status line right below already says that, and the
        // sheet would read "Getting video…" over "Getting the video…". A placeholder that
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

        // The share-only user never opens MainActivity, so this is the ONLY place their engine
        // ever gets refreshed (which is why MP3 quietly rotted for them).
        // ⚠️ It runs once the download has SETTLED, not at share time: dailyIfDue refuses to
        // swap the engine while a job is running, and at share time we have just enqueued one
        // — so calling it there would skip every single time.
        // ⚠️⚠️ FAILED COUNTS TOO, and that is the whole point. This used to fire on Saved
        // alone, which made the app unfixable in the one case that needed fixing: a stale
        // engine fails every download, a download that never succeeds never reaches Saved, so
        // the update that would have repaired the engine never ran. Measured 10-Sep-2026 — a
        // share-sheet user could stay broken forever. A failure is precisely when a fresh
        // engine is worth fetching.
        LaunchedEffect(phase) {
            if (phase == Phase.Saved || phase == Phase.Failed) {
                // Now that nothing is competing for the main thread, earn TikTok's cookies. On a
                // brand-new install this is what makes the NEXT download (or the Retry button
                // right here) work instead of timing out.
                try {
                    TtCookiePrimer.primeIfNeeded(this@QuickDownloadActivity)
                } catch (_: Exception) {
                }
                try {
                    Engine.dailyIfDue(applicationContext)
                } catch (e: Exception) {
                    // Never surfaced here: this is upkeep, and the sheet has already said
                    // whatever it had to say about the download itself.
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
                    try {
                        Engine.update(applicationContext)
                    } catch (e: Exception) {
                        // A failed update is not a reason to refuse the retry — the retry
                        // itself will report whatever goes wrong next.
                    }
                }
                pct = 0
                title = "TikTok video"
                phase = Phase.Preparing
                busy = false
                workId = DownloadQueue.enqueue(this@QuickDownloadActivity, link, audio)
            }
        }

        val done = phase == Phase.Saved || phase == Phase.Failed
        val headline = when (phase) {
            Phase.Saved -> "Saved to gallery"
            Phase.Failed -> "Download failed"
            else -> title
        }
        val status = when (phase) {
            // Says what is happening AND that waiting is the right thing to do. "Getting the
            // video…" left people wondering whether it had actually started.
            Phase.Preparing ->
                if (queuedOnly) "Waiting for network — the download will start on its own"
                else "Please wait — the download is starting…"
            Phase.Downloading ->
                if (pct >= 99) "Finishing — merging & saving…" else "$pct%"
            // The post's title, not the file name — the record stores the saved filename, which
            // reads like machine output on screen.
            Phase.Saved ->
                if (title != "TikTok video") title
                else saved?.title?.takeIf { it.isNotBlank() } ?: "Done"
            Phase.Failed -> failure.ifBlank { "Something went wrong" }
        }

        if (boxed) {
            // Tall sheet: the post itself, large. This is publisher content, and it is what keeps
            // a 300×250 ad from outweighing everything else on the screen. Not clickable — it
            // sits just under the ad, and the Open button exists for that.
            Preview(thumb, previewHeight)
            Spacer(Modifier.height(12.dp))
            // Both text blocks get an EXACT height (counted in TALL_TEXT), so the panel — and the
            // ad at its top — never moves as the phase changes. minLines alone was not enough:
            // measured on the YAL-L21, the bold 18sp "Download failed" stood ~4dp taller than the
            // 16sp "TikTok video" before it, and the whole panel rose by that much on failure.
            val density = LocalDensity.current
            Text(
                text = headline,
                color = SheetText,
                fontSize = if (done) 18.sp else 16.sp,
                fontFamily = if (done) SpaceGrotesk else null,
                fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(with(density) { 24.sp.toDp() })
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = status,
                color = SheetMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(with(density) { 40.sp.toDp() })
            )
        } else {
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
                        text = headline,
                        color = SheetText,
                        fontSize = if (done) 18.sp else 16.sp,
                        fontFamily = if (done) SpaceGrotesk else null,
                        fontWeight = if (done) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = status,
                        color = SheetMuted,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // The tall sheet keeps the progress row's space even once it is done (drawn invisible),
        // for the same reason the status is two lines tall: a panel that changes height moves
        // the ad at its top.
        if (!done || boxed) {
            Spacer(Modifier.height(16.dp))
            // Hand-drawn rather than LinearProgressIndicator: an exact 4dp pill, exact
            // colours, and no dependence on which Material3 progress API ships here.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .alpha(if (done) 0f else 1f)
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

                    // The one-tap answer for a non-technical user, who otherwise has no
                    // way at all to refresh the engine.
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

        // The tall sheet reserves this row from the start (empty until the download settles);
        // the compact one adds it when done, as it always has.
        if (done || boxed) {
            Spacer(Modifier.height(if (boxed) 4.dp else 8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (boxed) 36.dp else 40.dp),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Text(
                        text = "Open Riplox TT",
                        color = SheetMuted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .clickable { openApp() }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

/** The thin rule between an ad and everything else on the sheet. */
@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SheetLine)
    )
}

/**
 * The post, large — on the tall sheet only. A reel cover is 9:16 and a feed post 1:1 or 4:5,
 * so a wide card cannot crop either without cutting the subject off: the whole cover is fitted
 * in the middle, over a blurred, darkened copy of itself that fills the rest. (`blur` is a
 * no-op before Android 12; the darkened copy alone still looks deliberate there.)
 */
@Composable
private fun Preview(thumb: String?, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(SheetTrack)
    ) {
        if (thumb == null) {
            // No cover yet — or none at all, when TikTok refuses the post. A
            // bare dark slab under an ad reads as a broken page; a quiet media glyph reads as
            // "this is where the post goes".
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = SheetMuted.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        } else {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(24.dp)
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}
