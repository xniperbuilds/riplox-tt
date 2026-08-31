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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.google.android.gms.ads.MobileAds
import com.xniperbuilds.downloader.ui.theme.SpaceGrotesk
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
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

        // AdMob init (background thread — UI block na ho) + pehla interstitial preload
        if (Ads.ENABLED) {
            Thread { try { MobileAds.initialize(this) } catch (_: Exception) {} }.start()
            Ads.preloadInterstitial(this)
        }

        setContent {
            com.xniperbuilds.downloader.ui.theme.XniperDownloaderTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Home(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(activity: ComponentActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var url by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(Prefs.quality(context)) }
    var audioMode by remember { mutableStateOf(Prefs.audioMode(context)) }
    var history by remember { mutableStateOf(History.all(context)) }
    var histQuery by remember { mutableStateOf("") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(FailedStore.all(context)) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DownloadRecord?>(null) }
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
            tt.size == 1 -> {
                val id = DownloadQueue.enqueue(context, tt.first(), audioMode)
                DownloadQueue.awaitStart(activity, id) { started ->
                    Toast.makeText(
                        context,
                        if (started) "⬇ Download started — progress in notification"
                        else "⬇ Queued — starts as soon as network allows",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                url = ""
            }
            else -> {
                tt.forEach { DownloadQueue.enqueue(context, it, audioMode) }
                toast(
                    "⬇ ${tt.size} downloads queued" +
                        if (skipped > 0) " · $skipped non-TT link skipped" else ""
                )
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

    // Update notice — ONLY on a Play install (Play In-App Updates): a "Restart to finish"
    // card once the FLEXIBLE download completes, or Google's full-screen IMMEDIATE screen
    // when it is urgent. Sideload/GitHub builds run no update check. See AppUpdates.kt.
    val updates = remember { AppUpdates(activity) }
    DisposableEffect(Unit) {
        updates.check { updateNotice = it }
        onDispose { updates.dispose() }
    }

    // Refresh Recent/Failed whenever download state changes, and check the clipboard on resume
    DisposableEffect(Unit) {
        val wmLive = WorkManager.getInstance(context).getWorkInfosByTagLiveData(DownloadQueue.TAG)
        val seenDone = HashSet<java.util.UUID>()
        var primed = false
        val wmObs = Observer<List<WorkInfo>> { infos ->
            refresh()
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

    Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        item {
            // Riplox-style home header: ⓘ in the corner, logo + name CENTRED just below.
            // The extra top padding keeps ⓘ clear of the status bar / punch-hole camera.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { showAbout = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "About", tint = Color(0xFF9AA6B8))
                }
            }
            Spacer(Modifier.height(22.dp))
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RiploxTile(64)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Riplox TT",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    letterSpacing = (-1).sp,
                    color = Color(0xFFDDE4EF)
                )
            }
            Spacer(Modifier.height(28.dp))
        }

        // ---- Update notice (Play FLEXIBLE "restart to finish" / GitHub new release) ----
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

        // ---- Connect TikTok (login/cookies) — private / region-locked / age-restricted ----
        item {
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
            Spacer(Modifier.height(16.dp))
        }

        // ---- Link input ----
        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a TT link…", color = Color(0xFF5B6E8C)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
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
            Spacer(Modifier.height(12.dp))
        }

        // ---- Quality + MP3 chips ----
        item {
            // ⚠️ Five chips did not fit the width, so the last one wrapped and "MP3" rendered as
            // "MP" over "3". softWrap=false stops a label breaking mid-word, and the tighter gap
            // is what actually makes all five fit at 375dp.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("best" to "Best", "1080" to "1080p", "720" to "720p", "480" to "480p").forEach { (v, label) ->
                    FilterChip(
                        selected = !audioMode && quality == v,
                        enabled = !audioMode,
                        onClick = {
                            quality = v
                            Prefs.setQuality(context, v)
                        },
                        label = { Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false) }
                    )
                }
                FilterChip(
                    selected = audioMode,
                    onClick = {
                        audioMode = !audioMode
                        Prefs.setAudioMode(context, audioMode)
                    },
                    label = { Text("MP3", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // ---- Download button ----
        item {
            GradientButton("Download") { startDownload() }
            Spacer(Modifier.height(18.dp))
        }

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

        // ---- Failed downloads (Copy link + Retry — core requirement) ----
        if (failed.isNotEmpty()) {
            item {
                Text(
                    "Failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE57373)
                )
                Spacer(Modifier.height(8.dp))
            }
            items(failed, key = { "f${it.id}" }) { f ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0E0E))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
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
                        Text(f.error, color = Color(0xFF9AA6B8), fontSize = 12.sp, maxLines = 3)
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
            item { Spacer(Modifier.height(10.dp)) }
        }

        // ---- Recent ----
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFDDE4EF),
                    modifier = Modifier.weight(1f)
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = { confirmClearHistory = true }) {
                        Text("Delete all", color = Color(0xFF9AA6B8), fontSize = 13.sp)
                    }
                }
            }
            // The search box only appears once the list is long enough to need one — a filter
            // over three items is clutter, not a feature.
            if (history.size >= 5) {
                OutlinedTextField(
                    value = histQuery,
                    onValueChange = { histQuery = it },
                    singleLine = true,
                    placeholder = {
                        Text("Search downloads", color = Color(0xFF5B6E8C), fontSize = 13.sp)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        val shownHistory =
            if (histQuery.isBlank()) history
            else history.filter { it.title.contains(histQuery.trim(), ignoreCase = true) }
        if (history.isEmpty()) {
            item {
                Text(
                    "Downloads appear here.\nShare from the TT app — or paste a link above.",
                    color = Color(0xFF5B6E8C),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            }
        } else if (shownHistory.isEmpty()) {
            // A search that matches nothing must say so — an empty list under a filled search
            // box reads as "your downloads are gone".
            item {
                Text(
                    "Nothing matches \"${histQuery.trim()}\".",
                    color = Color(0xFF5B6E8C),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            }
        }
        items(shownHistory, key = { it.id }) { r ->
            var menuOpen by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { openRecord(r) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF10151F)),
                    contentAlignment = Alignment.Center
                ) {
                    if (r.isAudio) {
                        Icon(Icons.Default.MusicNote, null, tint = Color(0xFF8CA0BE))
                    } else {
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
                        "${timeAgo(r.time)} · ${if (r.isAudio) "MP3" else "Video"}",
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
    }
        BannerAd()
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
                        "Recent and deletes the saved files. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = History.deleteAll(context)
                    history = History.all(context)
                    histQuery = ""
                    confirmClearHistory = false
                    toast("Deleted $removed file${if (removed == 1) "" else "s"}")
                }) { Text("Delete all") }
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
