package com.xniperbuilds.downloader

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.xniperbuilds.downloader.ui.theme.SpaceGrotesk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Up while the app-open splash covers the screen — see AppOpen.kt. */
    private val cover = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Notification permission (Android 13+) — this is where download progress shows
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }

        // Android 8/9 (API < 29): saving to the gallery needs the storage permission
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1002
            )
        }

        // Consent first, then the Ads SDK — Consent.gather() starts MobileAds off the main
        // thread in both its success and failure paths (see Consent.kt).
        // ⚠️ Deliberately NO interstitial preload here. Fetching one on app open was measured
        // (1–7 Sep 2026) as the main cause of a 29% show rate: most sessions never reach the
        // 2nd completed download a full-screen ad needs, so the ad expired unshown and counted
        // as a matched request with no impression. The fetch now happens one completion before
        // a show slot instead — see AdGate.
        // The rewarded ad (check-in, ad-free time, accents) IS fetched once consent resolves.
        // The LaunchedEffect further down asks too, but on a first launch it runs before the
        // consent state exists, gets refused by canRequestAds(), and never asks again — so a new
        // user's first check-in could never offer the ad. loadRewarded() ignores a second call
        // while one ad is held or in flight.
        Consent.gather(this) { Ads.loadRewarded(this) }

        setContent {
            com.xniperbuilds.downloader.ui.theme.XniperDownloaderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // On a cold open with the splash up, Home is not composed at all until the
                    // splash goes — its startup work (clipboard, update check, rewarded fetch)
                    // waits a moment instead of running unseen. Once composed it stays, and a
                    // later splash simply covers it.
                    var homeComposed by remember { mutableStateOf(!cover.value) }
                    LaunchedEffect(cover.value) { if (!cover.value) homeComposed = true }
                    Box(Modifier.fillMaxSize()) {
                        if (homeComposed) Home(this@MainActivity, hideBanner = cover.value)
                        if (cover.value) OpenSplash()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // super.onStart() has just run XniperApp's lifecycle callbacks, so whether this start is
        // a real open of the app is already known.
        if (AppOpen.takeOpenEvent()) AppOpen.onOpen(this, cover)
    }
}

/**
 * The app-open splash: brand on black, and nothing to press. It swallows every touch, because
 * Google requires the app-open ad to "appear before the user can interact with the app's content".
 */
@Composable
private fun OpenSplash() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RiploxTile(72)
            Spacer(Modifier.height(16.dp))
            Text(
                "Riplox TT",
                color = Color(0xFFE8EEF5),
                fontSize = 24.sp,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(activity: ComponentActivity, hideBanner: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var url by remember { mutableStateOf("") }
    var audioMode by remember { mutableStateOf(Prefs.audioMode(context)) }
    var history by remember { mutableStateOf(History.all(context)) }
    var failed by remember { mutableStateOf(FailedStore.all(context)) }
    // Live jobs for the Downloading list (toActive says why each job has its own memory).
    var active by remember { mutableStateOf<List<ActiveDl>>(emptyList()) }
    val activeMeta = remember { HashMap<java.util.UUID, ActiveMeta>() }
    var showAllRecent by remember { mutableStateOf(false) }
    // Which tab under the link box is open. It follows the work: a new job opens Downloading,
    // and when the last one settles the list moves to where it went — Downloaded, or Failed.
    var tab by remember { mutableStateOf(HomeTab.DONE) }
    var lastActive by remember { mutableStateOf(0) }
    var failedMark by remember { mutableStateOf(0) }
    LaunchedEffect(active.size) {
        if (active.size > lastActive) {
            if (lastActive == 0) failedMark = failed.size
            tab = HomeTab.ACTIVE
        } else if (active.isEmpty() && lastActive > 0 && tab == HomeTab.ACTIVE) {
            // FailedStore is written before the job reports FAILED, so the count is current here.
            tab = if (failed.size > failedMark) HomeTab.FAILED else HomeTab.DONE
        }
        lastActive = active.size
    }
    var showAbout by remember { mutableStateOf(false) }
    var showTtSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var histQuery by remember { mutableStateOf("") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    // XOS/hibernation-style killers: without a battery exemption, background/share
    // downloads freeze — and on XOS the exemption alone is NOT ENOUGH (auto-start and the
    // recents lock are needed too). The banner stays until the user marks the setup "Done",
    // or reappears if the exemption is taken away.
    var bgRisk by remember {
        mutableStateOf(!BgGuard.batteryExempt(context) || !Prefs.bgSetupDone(context))
    }
    var showBgSetup by remember { mutableStateOf(false) }
    // Connect TikTok (login/cookies) — for private / region-locked / age-restricted videos
    var ttConnected by remember { mutableStateOf(tiktokConnected(context)) }
    var useLogin by remember { mutableStateOf(Prefs.cookiesEnabled(context)) }

    // The "a new version is out" notice — Play In-App Updates on a Play build only
    // (details and the Play-policy reasoning are in AppUpdates.kt).
    var updateNotice by remember { mutableStateOf<UpdateNotice?>(null) }

    // Recent thumbnails — a loader that pulls a frame out of the video file (coil-video)
    val videoLoader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

    fun refresh() {
        history = History.all(context)
        failed = FailedStore.all(context)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun checkClipboard() {
        if (url.isNotBlank()) return
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()
            val link = extractUrl(clip)
            if (isTikTokUrl(link) && link != Prefs.lastClip(context)) {
                url = link!!
                Prefs.setLastClip(context, link)
                toast("📋 TT link pasted from clipboard")
            }
        } catch (_: Exception) {
        }
    }

    fun startDownload() {
        // Paste one link or twenty — every TT link in the text gets queued. Anything that is
        // not a TT link is counted and reported rather than silently dropped.
        val links = extractUrls(url)
        val tt = links.filter { isTikTokUrl(it) }
        val skipped = links.size - tt.size
        when {
            links.isEmpty() -> toast("Paste a TT link first")
            tt.isEmpty() -> toast("Only TT links work here — paste a TT video link")
            tt.size > 1 -> {
                tt.forEach { DownloadQueue.enqueue(context, it, audioMode) }
                toast(
                    "⬇ ${tt.size} downloads queued" +
                        if (skipped > 0) " · $skipped non-TT link skipped" else ""
                )
                url = ""
            }
            else -> {
                val id = DownloadQueue.enqueue(context, tt.first(), audioMode)
                DownloadQueue.awaitStart(activity, id) { started ->
                    Toast.makeText(
                        context,
                        if (started) "⬇ Download started"
                        else "⬇ Queued — starts as soon as network allows",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                url = ""
            }
        }
    }

    fun openRecord(r: DownloadRecord) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(r.location), if (r.isAudio) "audio/*" else "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(i)
        } catch (e: Exception) {
            toast("Can't open — file may be deleted")
        }
    }

    fun copyLink(link: String) {
        try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("TT link", link))
            toast("Link copied")
        } catch (_: Exception) {
        }
    }

    // Engine auto-update — once a day, never while a download is running. The logic moved into
    // Engine so the share tile runs the SAME check: a user who only ever shares from TikTok
    // never opens this screen, and used to go months without an engine update.
    LaunchedEffect(Unit) {
        // Earn TikTok's ttwid while the user is looking at the home screen, so their first
        // download does not have to. No-op once the jar is warm.
        try {
            TtCookiePrimer.primeIfNeeded(activity)
        } catch (_: Exception) {
        }
        try {
            Engine.dailyIfDue(context)
        } catch (_: Exception) {
        }
    }

    // Clipboard check on first open (a short delay, to let the window take focus)
    LaunchedEffect(Unit) {
        delay(400)
        checkClipboard()
    }

    // Refresh Recent/Failed whenever download state changes, and check the clipboard on resume
    DisposableEffect(Unit) {
        val wmLive = WorkManager.getInstance(context).getWorkInfosByTagLiveData(DownloadQueue.TAG)
        val seenDone = HashSet<java.util.UUID>()
        var primed = false
        val wmObs = Observer<List<WorkInfo>> { infos ->
            refresh()
            active = toActive(infos, activeMeta)
            if (!primed) {
                // The first emission is old completed downloads at app open — no ad for those
                infos.forEach { if (it.state == WorkInfo.State.SUCCEEDED) seenDone.add(it.id) }
                primed = true
            } else {
                infos.forEach { wi ->
                    if (wi.state == WorkInfo.State.SUCCEEDED && seenDone.add(wi.id)) {
                        Ads.onDownloadComplete(activity)
                    }
                }
            }
        }
        wmLive.observe(lifecycleOwner, wmObs)
        val lifeObs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                refresh()
                // Refresh status on the way back from Settings (banner follows the exemption)
                bgRisk = !BgGuard.batteryExempt(context) || !Prefs.bgSetupDone(context)
                // Update the badge on return from the Connect screen
                ttConnected = tiktokConnected(context)
                // Nudge stale/stuck downloads — with the app open the escort FGS is allowed,
                // so an ENQUEUED job runs immediately and the worker takes its own FGS lock
                // (the cure for "it won't start even with the app open").
                EscortService.kickIfNeeded(context)
                scope.launch {
                    delay(400)
                    checkClipboard()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifeObs)
        onDispose {
            wmLive.removeObserver(wmObs)
            lifecycleOwner.lifecycle.removeObserver(lifeObs)
        }
    }

    // Update notice — ONLY on a Play install (Play In-App Updates): a "Restart to finish"
    // card once the FLEXIBLE download completes, or Google's full-screen IMMEDIATE screen
    // when it is urgent. Sideload/GitHub builds run no update check. See AppUpdates.kt.
    val updates = remember { AppUpdates(activity) }
    DisposableEffect(Unit) {
        updates.check { updateNotice = it }
        onDispose { updates.dispose() }
    }

    // Rewarded is opt-in, so unlike the interstitial it IS fetched ahead of time: the button
    // is only offered once an ad is in hand, and a user who taps must not wait on the network.
    LaunchedEffect(Unit) { Ads.loadRewarded(context) }
    // Bumped when a reward lands, so the banner slot re-evaluates Ads.suppressed().
    var adsEpoch by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ---- Top bar: the name small on the left, ⓘ on the right ----
        // There used to be a big centred logo here. It pushed the link box half a screen down,
        // and the link box is what every visit to this screen is for.
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Riplox TT",
                    modifier = Modifier.weight(1f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    letterSpacing = (-0.5).sp,
                    color = Color(0xFFDDE4EF)
                )
                IconButton(onClick = { showAbout = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "About", tint = Color(0xFF9AA6B8))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // ---- Update notice (Play FLEXIBLE "restart to finish") ----
        updateNotice?.let { n ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF14251F))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            n.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFDDE4EF)
                        )
                        Text(n.body, fontSize = 12.sp, color = Color(0xFF9AA6B8))
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = n.onClick, modifier = Modifier.weight(1f)) { Text(n.cta) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { updateNotice = null }) { Text("Later", fontSize = 13.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        // ---- Link box, format, Download — one panel at the top ----
        // Video is always Best quality (see Prefs.quality). The third button is the TikTok
        // account, which used to be a whole card in the middle of the screen. Download only
        // shows once there is a link to download.
        item {
            val accent = Accent.current(context)
            val panel = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(panel)
                    .background(Brush.verticalGradient(listOf(Color(0xFF121A29), Color(0xFF0A0F18))))
                    .border(1.dp, Color(0xFF1D283B), panel)
                    .padding(12.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste a TT link…", color = Color(0xFF5B6E8C)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (url.isNotBlank()) {
                            IconButton(onClick = { url = "" }) {
                                Icon(Icons.Default.Close, "Clear", tint = Color(0xFF9AA6B8))
                            }
                        } else {
                            IconButton(onClick = {
                                try {
                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                    val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                                    val link = extractUrl(clip)
                                    if (link != null) url = link else toast("Clipboard is empty")
                                } catch (_: Exception) {
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, "Paste", tint = Color(0xFF9AA6B8))
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FormatOption(
                        "Best quality", Icons.Default.HighQuality, selected = !audioMode, accent = accent,
                        modifier = Modifier.weight(1f)
                    ) {
                        audioMode = false
                        Prefs.setAudioMode(context, false)
                    }
                    FormatOption(
                        "MP3", Icons.Default.MusicNote, selected = audioMode, accent = accent,
                        modifier = Modifier.weight(1f)
                    ) {
                        audioMode = true
                        Prefs.setAudioMode(context, true)
                    }
                    AccountButton(connected = ttConnected) { showTtSheet = true }
                }
                AnimatedVisibility(visible = url.isNotBlank()) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        GradientButton(if (audioMode) "Download MP3" else "Download", verticalPadding = 13.dp) {
                            startDownload()
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- Tabs under the link box: Downloading · Downloaded · Failed ----
        item {
            HomeTabs(tab, active = active.size, done = history.size, failed = failed.size) { tab = it }
            Spacer(Modifier.height(8.dp))
        }
        when (tab) {
            HomeTab.ACTIVE -> {
                if (active.isEmpty()) {
                    item { EmptyNote("Nothing downloading right now.\nPaste a link above — or share from the TT app.") }
                }
                items(active, key = { "a${it.id}" }) { d ->
                    ActiveRow(d) { WorkManager.getInstance(context).cancelWorkById(d.id) }
                }
            }
            HomeTab.DONE -> {
                // The search box and "Delete all" only appear once the list is long enough to
                // need them — a filter over three items is clutter, not a feature.
                if (history.size >= RECENT_PREVIEW) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = histQuery,
                                onValueChange = { histQuery = it },
                                singleLine = true,
                                placeholder = {
                                    Text("Search downloads", color = Color(0xFF5B6E8C), fontSize = 13.sp)
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { confirmClearHistory = true }) {
                                Text("Delete all", color = Color(0xFF9AA6B8), fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                val shownHistory =
                    if (histQuery.isBlank()) history
                    else history.filter { it.title.contains(histQuery.trim(), ignoreCase = true) }
                if (history.isEmpty()) {
                    item { EmptyNote("Downloads appear here.\nShare from the TT app — or paste a link above.") }
                } else if (shownHistory.isEmpty()) {
                    // A search that matches nothing must say so — an empty list under a filled
                    // search box reads as "your downloads are gone".
                    item { EmptyNote("Nothing matches \"${histQuery.trim()}\".") }
                }
                // A search shows every match; otherwise the newest few, with "Show all".
                val rows = if (showAllRecent || histQuery.isNotBlank()) shownHistory else shownHistory.take(RECENT_PREVIEW)
                items(rows, key = { it.id }) { r ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { openRecord(r) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF10151F)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (r.isAudio) {
                                Icon(Icons.Default.MusicNote, null, tint = Color(0xFF8CA0BE))
                            } else {
                                // Under the picture, not instead of it: some phones (a Huawei
                                // Kirin, measured on IG's VP9 files) cannot pull a frame out of a
                                // video at all, and the row used to show an empty dark square.
                                Icon(
                                    if (r.title.startsWith("TT photo post")) Icons.Default.Image else Icons.Default.Movie,
                                    null,
                                    tint = Color(0xFF5B6E8C),
                                    modifier = Modifier.size(20.dp)
                                )
                                AsyncImage(
                                    model = r.location,
                                    imageLoader = videoLoader,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                r.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = Color(0xFFDDE4EF),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${timeAgo(r.time)} · ${when {
                                    r.isAudio -> "MP3"
                                    r.title.startsWith("TT photo post") -> "Photos"
                                    else -> "Video"
                                }}",
                                color = Color(0xFF5B6E8C),
                                fontSize = 12.sp
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, "Menu", tint = Color(0xFF9AA6B8))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                                    menuOpen = false
                                    copyLink(r.url)
                                })
                                DropdownMenuItem(text = { Text("Download again") }, onClick = {
                                    menuOpen = false
                                    val id = DownloadQueue.enqueue(context, r.url, r.isAudio)
                                    DownloadQueue.awaitStart(activity, id) { }
                                    toast("⬇ Downloading again…")
                                })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                    menuOpen = false
                                    confirmDelete = r
                                })
                            }
                        }
                    }
                }
                // The newest few only, with "Show all": the cards under the tabs (background
                // fix, streak) would otherwise sit below every download ever made.
                if (histQuery.isBlank() && history.size > RECENT_PREVIEW) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { showAllRecent = !showAllRecent }) {
                                Text(if (showAllRecent) "Show less" else "Show all ${history.size}", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            HomeTab.FAILED -> {
                if (failed.isEmpty()) {
                    item { EmptyNote("No failed downloads.") }
                }
                items(failed, key = { "f${it.id}" }) { f ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0E0E))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    f.title.ifBlank { "TT video" },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFDDE4EF),
                                    fontSize = 14.sp
                                )
                                IconButton(onClick = {
                                    FailedStore.remove(context, f.id)
                                    refresh()
                                }) {
                                    Icon(Icons.Default.Close, "Dismiss", tint = Color(0xFF9AA6B8))
                                }
                            }
                            // The whole message: it says what to DO, and it was being cut off mid-sentence.
                            Text(f.error, color = Color(0xFF9AA6B8), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { copyLink(f.link) }) { Text("Copy link") }
                                TextButton(onClick = {
                                    FailedStore.remove(context, f.id)
                                    val id = DownloadQueue.enqueue(context, f.link, f.isAudio)
                                    DownloadQueue.awaitStart(activity, id) { }
                                    refresh()
                                    toast("⬇ Retrying…")
                                }) { Text("Retry") }
                                // Plain Retry runs the SAME engine that just failed. This is the
                                // one-tap answer for a user who cannot be expected to know that an
                                // engine exists, let alone that it went stale.
                                TextButton(onClick = {
                                    toast("Updating engine…")
                                    scope.launch {
                                        Engine.update(context)
                                        FailedStore.remove(context, f.id)
                                        val id = DownloadQueue.enqueue(context, f.link, f.isAudio)
                                        DownloadQueue.awaitStart(activity, id) { }
                                        refresh()
                                        toast("⬇ Retrying with a fresh engine…")
                                    }
                                }) { Text("Update & retry") }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }

        // ---- Background-freeze guard (XOS/Infinix/Tecno and similar) ----
        // These phones FREEZE the app in the background, so share-tile downloads neither
        // start nor finish unless the app is opened. The banner + 3-step setup fixes it for good.
        if (bgRisk) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141B29))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "⚠️ Background downloads may pause",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFDDE4EF)
                        )
                        Text(
                            "Your phone freezes apps in the background, so shared downloads can stall until you open Riplox TT. A 1-minute setup fixes it for good.",
                            fontSize = 12.sp,
                            color = Color(0xFF9AA6B8)
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showBgSetup = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Fix background downloads")
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        // ---- Daily check-in: streak, ad-free window, accent unlocks (StreakCard.kt) ----
        item {
            StreakCard(activity) { adsEpoch++ }
            Spacer(Modifier.height(14.dp))
        }

    }
        // Out of the composition while the app-open splash is up: Google forbids showing that ad
        // "on top of other ads (for example, content with banner ads)".
        if (!hideBanner) key(adsEpoch) { BannerAd() }
    }

    // ---- TikTok account sheet — the account button next to Best quality / MP3 ----
    // Connect, re-login, and the "use my login" switch: the same controls the
    // home card had, one tap away instead of taking a third of the screen.
    if (showTtSheet) {
        ModalBottomSheet(onDismissRequest = { showTtSheet = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp)
            ) {
                if (ttConnected) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF10251A))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "✓ TikTok connected",
                                    color = Color(0xFF7FD1A0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "🔒 Your login is safe — saved only on this phone",
                                    color = Color(0xFF6E8B7C),
                                    fontSize = 10.5.sp
                                )
                            }
                            // Re-login (expired session / account switch). Cookies are NOT deleted —
                            // the login is durable; this only refreshes or switches it.
                            TextButton(onClick = {
                                try {
                                    context.startActivity(
                                        Intent(context, CookieLoginActivity::class.java)
                                            .putExtra("site", "tiktok")
                                            .putExtra("label", "TikTok")
                                    )
                                } catch (_: Exception) {
                                }
                            }) { Text("Re-login", fontSize = 13.sp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Use my login for downloads",
                                    color = Color(0xFFB6C6D6),
                                    fontSize = 12.sp
                                )
                                Text(
                                    if (useLogin) "Needed for private & region-locked videos"
                                    else "Guest mode — public videos only",
                                    color = Color(0xFF6E8B7C),
                                    fontSize = 10.5.sp
                                )
                            }
                            Switch(
                                checked = useLogin,
                                onCheckedChange = {
                                    useLogin = it
                                    Prefs.setCookiesEnabled(context, it)
                                }
                            )
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141B29))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                "🔗 Connect TikTok (optional)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFDDE4EF)
                            )
                            Text(
                                "Public videos work without this. Log in once for private, region-locked or age-restricted videos.",
                                fontSize = 12.sp,
                                color = Color(0xFF9AA6B8)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "🔒 100% safe: Riplox never sees or stores your password. You sign in on TikTok's own page, and your login stays only on this phone — never on any server.",
                                fontSize = 11.sp,
                                color = Color(0xFF8FA6C4)
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(context, CookieLoginActivity::class.java)
                                                .putExtra("site", "tiktok")
                                                .putExtra("label", "TikTok")
                                        )
                                    } catch (_: Exception) {
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Connect TikTok") }
                        }
                    }
                }
            }
        }
    }

    // ---- Background-setup dialog (opens from both the banner and About) ----
    if (showBgSetup) {
        BgSetupDialog {
            showBgSetup = false
            bgRisk = !BgGuard.batteryExempt(context) || !Prefs.bgSetupDone(context)
        }
    }

    // ---- Delete confirm ----
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this download?") },
            text = { Text("The saved file is deleted from your phone too.") },
            confirmButton = {
                TextButton(onClick = {
                    History.delete(context, r.id)
                    confirmDelete = null
                    refresh()
                }) { Text("Delete", color = Color(0xFFE57373)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }

    // ---- "Delete all" confirmation ----
    // The files go too, and that cannot be undone — so it asks first, and says exactly how
    // many things it is about to remove.
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Delete all downloads?") },
            text = {
                Text(
                    "This removes ${history.size} item${if (history.size == 1) "" else "s"} from " +
                        "Downloaded and deletes the saved files. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = History.deleteAll(context)
                    history = History.all(context)
                    histQuery = ""
                    confirmClearHistory = false
                    toast("Deleted $removed file${if (removed == 1) "" else "s"}")
                }) { Text("Delete all", color = Color(0xFFE57373)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    // ---- About sheet ----
    if (showAbout) {
        val version = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            } catch (e: Exception) {
                "1.0.0"
            }
        }
        ModalBottomSheet(onDismissRequest = { showAbout = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 34.dp)
            ) {
                RiploxHeader(subtitle = "v$version")
                Spacer(Modifier.height(16.dp))
                Text(
                    "Fast, clean TT video downloader — no watermark, straight to your gallery.",
                    color = Color(0xFF9AA6B8),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))

                // ---- ENGINE STATUS + MANUAL UPDATE ----------------------------------------
                // Until now the app had no manual update anywhere: a user whose MP3s had
                // quietly stopped working had literally nothing to press, and no way to see
                // whether the engine was fresh or months old.
                var engineLine by remember { mutableStateOf(Engine.statusLine(context)) }
                var engineBusy by remember { mutableStateOf(false) }
                Text(engineLine, color = Color(0xFF9AA6B8), fontSize = 13.sp)
                TextButton(
                    enabled = !engineBusy,
                    onClick = {
                        engineBusy = true
                        scope.launch {
                            val outcome = Engine.update(context)
                            engineLine = Engine.statusLine(context)
                            engineBusy = false
                            toast(
                                when (outcome) {
                                    Engine.Outcome.UPDATED -> "Engine updated"
                                    Engine.Outcome.ALREADY_LATEST -> "Already the latest engine"
                                    Engine.Outcome.FAILED -> "Update failed — check your internet"
                                }
                            )
                        }
                    }
                ) { Text(if (engineBusy) "Updating…" else "Update engine now") }

                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                        Intent.EXTRA_TEXT,
                        "Riplox TT — fast, clean TT video downloader.\nhttps://xniperbuilds.com"
                    )
                    context.startActivity(Intent.createChooser(share, "Share Riplox TT"))
                }) { Text("Share this app") }
                // The PERMANENT route — the home banner disappears once "Done" is tapped
                // (users then asked where the card went), but this entry is always here.
                TextButton(onClick = {
                    showAbout = false
                    showBgSetup = true
                }) { Text("🛡 Fix background downloads") }
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://xniperbuilds.com/riplox-tt/privacy"))
                        )
                    } catch (_: Exception) {
                    }
                }) { Text("Privacy policy") }
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://xniperbuilds.com"))
                        )
                    } catch (_: Exception) {
                    }
                }) { Text("More from XniperBuilds") }
            }
        }
    }
}

/** The 3-step background-setup dialog — opens from BOTH the home banner and the About sheet
 * (the banner disappears after "Done", the About route is ALWAYS there). The battery step's
 * live ✓ status refreshes when you come back to the dialog (ON_RESUME). */
@Composable
fun BgSetupDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var battOk by remember { mutableStateOf(BgGuard.batteryExempt(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) battOk = BgGuard.batteryExempt(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Background setup (one time)") },
        text = {
            Column {
                Text(
                    "Do these 3 steps so downloads keep running with the app closed:",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { BgGuard.requestBatteryExempt(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (battOk) "1 · Battery ✓ already allowed" else "1 · Allow battery (tap → Allow)") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!BgGuard.openAutoStart(context)) {
                            Toast.makeText(context, "Couldn't open — enable Auto-start in phone settings", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("2 · Turn ON Auto-start for Riplox TT") }
                Text(
                    "No Auto-start list on your phone? Then: App info → Battery → Allow Background Usage (ON).",
                    fontSize = 10.5.sp,
                    color = Color(0xFF9AA6B8)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "3 · Open Recent apps, hold the Riplox TT card and tap the 🔒 lock — this stops the phone from killing it.",
                    fontSize = 12.sp,
                    color = Color(0xFF9AA6B8)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.setBgSetupDone(context, true)
                onClose()
            }) { Text("Done") }
        }
    )
}

/** How many Downloaded rows show before "Show all". */
private const val RECENT_PREVIEW = 5

/** One running or waiting download, as the Downloading list draws it. */
data class ActiveDl(
    val id: java.util.UUID,
    val title: String?,
    val thumb: String?,
    /** -1 = no number yet. */
    val pct: Int,
    /** ENQUEUED/BLOCKED — not running right this moment. */
    val waiting: Boolean,
    val retry: Boolean
)

/** What the list remembers about a job between WorkManager updates. */
class ActiveMeta {
    val seen = System.nanoTime()
    var title: String? = null
    var thumb: String? = null
    var pct = -1
    var attempt = 0
}

/**
 * The jobs still in flight, newest first.
 *
 * ⚠️ WorkManager REPLACES a job's progress Data on every setProgress, and only one of the
 * worker's updates carries the thumbnail (the title-only ones carry no percent). Read straight
 * off each update, the picture would vanish at 1% and the bar would jump back to "Starting…"
 * whenever a title update landed — so each job keeps the last value it saw. A retry starts the
 * percent over, which is why it is reset when the attempt number changes.
 */
fun toActive(infos: List<WorkInfo>, meta: HashMap<java.util.UUID, ActiveMeta>): List<ActiveDl> {
    val live = infos.filter {
        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.BLOCKED
    }
    meta.keys.retainAll(live.map { it.id }.toSet())
    return live.map { wi ->
        val m = meta.getOrPut(wi.id) { ActiveMeta() }
        if (wi.runAttemptCount != m.attempt) {
            m.attempt = wi.runAttemptCount
            m.pct = -1
        }
        val p = wi.progress
        // "Downloading…" is the worker's own placeholder until the real title arrives.
        p.getString("title")?.takeIf { it.isNotBlank() && it != "Downloading…" }?.let { m.title = it }
        p.getString("thumb")?.takeIf { it.isNotBlank() }?.let { m.thumb = it }
        val pct = p.getInt("pct", -1)
        if (pct >= 0) m.pct = pct
        val waiting = wi.state != WorkInfo.State.RUNNING
        m to ActiveDl(wi.id, m.title, m.thumb, if (waiting) -1 else m.pct, waiting, wi.runAttemptCount > 0)
    }.sortedByDescending { it.first.seen }.map { it.second }
}

/** The three tabs under the link box. */
enum class HomeTab { ACTIVE, DONE, FAILED }

/**
 * Downloading · Downloaded · Failed, as one segmented control.
 *
 * The count sits ABOVE each label instead of beside it: "Downloading" plus a count pill does not
 * fit a third of a 360dp phone, and a clipped tab name is worse than a taller tab.
 */
@Composable
private fun HomeTabs(selected: HomeTab, active: Int, done: Int, failed: Int, onSelect: (HomeTab) -> Unit) {
    val accent = Accent.current(LocalContext.current)
    val outer = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(Color(0xFF0D121C))
            .border(1.dp, Color(0xFF1D283B), outer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            Triple(HomeTab.ACTIVE, "Downloading", active),
            Triple(HomeTab.DONE, "Downloaded", done),
            Triple(HomeTab.FAILED, "Failed", failed)
        ).forEach { (t, label, n) ->
            val on = t == selected
            val cell = RoundedCornerShape(10.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(cell)
                    .background(if (on) accent.primary.copy(alpha = 0.30f) else Color.Transparent)
                    .border(1.dp, if (on) accent.primary else Color.Transparent, cell)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(t) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "$n",
                    color = when {
                        t == HomeTab.FAILED && n > 0 -> Color(0xFFE57373)
                        on -> accent.onPrimary
                        else -> Color(0xFFDDE4EF)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    maxLines = 1
                )
                Text(
                    label,
                    color = if (on) accent.onPrimary else Color(0xFF8592A6),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** What an empty tab says. */
@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        color = Color(0xFF5B6E8C),
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp)
    )
}

/** One of the two format choices under the link box — Best quality video, or MP3. */
@Composable
private fun FormatOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Accent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val fg = if (selected) accent.onPrimary else Color(0xFF9AA6B8)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(if (selected) accent.primary.copy(alpha = 0.30f) else Color(0xFF0D121C))
            .border(1.dp, if (selected) accent.primary else Color(0xFF1F2A3C), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        // One weight for both states, so the label never shifts when the choice changes.
        Text(
            label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The third button beside Best quality / MP3: the TikTok account (connect, re-login, use-my-login
 * switch all live in the sheet it opens). A green dot shows a connected login.
 */
@Composable
private fun AccountButton(connected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 40.dp)
            .clip(shape)
            .background(Color(0xFF0D121C))
            .border(1.dp, Color(0xFF1F2A3C), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (connected) "TikTok connected" else "Connect TikTok"
            },
        contentAlignment = Alignment.Center
    ) {
        NoteGlyph(22.dp)
        if (connected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CC38A))
            )
        }
    }
}

/** A music note with a cyan and a pink shadow either side — the short-video look, no logo. */
@Composable
private fun NoteGlyph(size: Dp) {
    Box(modifier = Modifier.size(size)) {
        Icon(
            Icons.Default.MusicNote, null, tint = Color(0xFF25F4EE),
            modifier = Modifier.fillMaxSize().offset(x = (-1.5).dp, y = (-1).dp)
        )
        Icon(
            Icons.Default.MusicNote, null, tint = Color(0xFFFE2C55),
            modifier = Modifier.fillMaxSize().offset(x = 1.5.dp, y = 1.dp)
        )
        Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.fillMaxSize())
    }
}

/** A Downloading row: thumbnail, title, a live bar and what it is doing, and Cancel. */
@Composable
private fun ActiveRow(d: ActiveDl, onCancel: () -> Unit) {
    val accent = Accent.current(LocalContext.current)
    val bar = if (accent.id == 0) Color(0xFF8CA0BE) else accent.primary
    val track = Color(0xFF1C2638)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E141F))
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF151D2B)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF5B6E8C), modifier = Modifier.size(20.dp))
            if (!d.thumb.isNullOrBlank()) {
                AsyncImage(
                    model = d.thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                d.title ?: "Getting video info…",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFFDDE4EF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            val barMod = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
            when {
                // Waiting is not progress: an empty bar, not a moving one.
                d.waiting -> LinearProgressIndicator(progress = { 0f }, modifier = barMod, color = bar, trackColor = track)
                d.pct in 1..98 -> LinearProgressIndicator(progress = { d.pct / 100f }, modifier = barMod, color = bar, trackColor = track)
                else -> LinearProgressIndicator(modifier = barMod, color = bar, trackColor = track)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    d.waiting && d.retry -> "Retrying soon…"
                    d.waiting -> "Waiting to start…"
                    d.pct < 0 -> "Starting…"
                    // 100% means the transfer is done; the merge and the gallery save still follow.
                    d.pct >= 99 -> "Finishing — merging & saving…"
                    else -> "${d.pct}%"
                },
                color = Color(0xFF7D8BA0),
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = "Cancel download", tint = Color(0xFF9AA6B8))
        }
    }
}

/** "2m ago" style chhota time label. */
fun timeAgo(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val min = diff / 60000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 24 * 60 -> "${min / 60}h ago"
        else -> "${min / (24 * 60)}d ago"
    }
}
