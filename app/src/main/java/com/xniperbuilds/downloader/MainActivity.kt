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

        // Notification permission (Android 13+) — download progress isi me dikhti hai
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }

        // Android 8/9 (API < 29): gallery-save ke liye storage permission chahiye
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1002
            )
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
    var failed by remember { mutableStateOf(FailedStore.all(context)) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DownloadRecord?>(null) }

    // Recent thumbnails — video file se frame nikaalne wala loader (coil-video)
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
        val link = extractUrl(url)
        when {
            link.isNullOrBlank() -> toast("Paste a TT link first")
            !isTikTokUrl(link) -> toast("Only TT links work here — paste a TT video link")
            else -> {
                val id = DownloadQueue.enqueue(context, link, audioMode)
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

    // Engine auto-update — din me ek dafa, chalti download ke waqt nahi
    LaunchedEffect(Unit) {
        try {
            val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            if (Prefs.lastUpdateDay(context) != today) {
                val busy = withContext(Dispatchers.IO) { DownloadQueue.hasActive(context) }
                if (!busy) {
                    withContext(Dispatchers.IO) {
                        YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                    }
                    Prefs.setLastUpdateDay(context, today)
                }
            }
        } catch (_: Exception) {
        }
    }

    // Pehli open pe clipboard check (thoda delay — window focus ke liye)
    LaunchedEffect(Unit) {
        delay(400)
        checkClipboard()
    }

    // Downloads ki state badle to Recent/Failed refresh + resume pe clipboard check
    DisposableEffect(Unit) {
        val wmLive = WorkManager.getInstance(context).getWorkInfosByTagLiveData(DownloadQueue.TAG)
        val wmObs = Observer<List<WorkInfo>> { refresh() }
        wmLive.observe(lifecycleOwner, wmObs)
        val lifeObs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                refresh()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        item {
            // Riplox-style home header: ⓘ corner pe, logo + naam CENTER me thora niche
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("best" to "Best", "1080" to "1080p", "720" to "720p", "480" to "480p").forEach { (v, label) ->
                    FilterChip(
                        selected = !audioMode && quality == v,
                        enabled = !audioMode,
                        onClick = {
                            quality = v
                            Prefs.setQuality(context, v)
                        },
                        label = { Text(label, fontSize = 13.sp) }
                    )
                }
                FilterChip(
                    selected = audioMode,
                    onClick = {
                        audioMode = !audioMode
                        Prefs.setAudioMode(context, audioMode)
                    },
                    label = { Text("MP3", fontSize = 13.sp) }
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // ---- Download button ----
        item {
            GradientButton("Download") { startDownload() }
            Spacer(Modifier.height(18.dp))
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
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }

        // ---- Recent ----
        item {
            Text("Recent", style = MaterialTheme.typography.titleMedium, color = Color(0xFFDDE4EF))
            Spacer(Modifier.height(8.dp))
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "Downloads appear here.\nShare from the TT app — or paste a link above.",
                    color = Color(0xFF5B6E8C),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 18.dp)
                )
            }
        }
        items(history, key = { it.id }) { r ->
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
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                        Intent.EXTRA_TEXT,
                        "Riplox TT — fast, clean TT video downloader.\nhttps://xniperbuilds.com"
                    )
                    context.startActivity(Intent.createChooser(share, "Share Riplox TT"))
                }) { Text("Share this app") }
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
