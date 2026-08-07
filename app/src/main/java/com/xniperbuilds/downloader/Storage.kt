package com.xniperbuilds.downloader

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

// Riplox TT — sab downloads ek hi brand folder me: Movies|Music|Pictures/RiploxTT/
private const val BRAND_DIR = "RiploxTT"

fun mimeForExt(ext: String, audioOnly: Boolean): String = when (ext.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> if (audioOnly) "audio/webm" else "video/webm"
    "weba" -> "audio/webm"
    "mkv" -> "video/x-matroska"
    "mov" -> "video/quicktime"
    "3gp" -> "video/3gpp"
    "ts" -> "video/mp2t"
    "avi" -> "video/x-msvideo"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "opus", "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    else -> if (audioOnly) "audio/mpeg" else "video/mp4"
}

/** Chunked copy that calls onCopy(totalBytesCopied) per chunk — the "beats" of the save phase.
 * Without them, copying a large file looked to the watchdog/notification like a silent death
 * at 100%. */
private fun copyChunked(input: java.io.InputStream, out: java.io.OutputStream, onCopy: (Long) -> Unit) {
    val buf = ByteArray(256 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        onCopy(total)
    }
    out.flush()
}

/** MediaStore me file likho (IS_PENDING flow) — fail ho to adhoori row delete (orphan na bache). */
private fun insertMedia(
    context: Context,
    contentUri: Uri,
    temp: File,
    mime: String,
    relPath: String,
    onCopy: (Long) -> Unit = {}
): String {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, temp.name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(contentUri, values) ?: throw Exception("MediaStore insert failed")
    try {
        resolver.openOutputStream(uri)?.use { out ->
            temp.inputStream().use { input -> copyChunked(input, out, onCopy) }
        } ?: throw Exception("Output stream null")
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
    } catch (e: Exception) {
        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
        throw e
    }
    temp.delete()
    return uri.toString()
}

/** API 29+ : video → Movies/RiploxTT/ (gallery-visible). */
fun saveVideoToGallery(context: Context, temp: File, onCopy: (Long) -> Unit = {}): String = insertMedia(
    context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, temp,
    mimeForExt(temp.extension, audioOnly = false), "${Environment.DIRECTORY_MOVIES}/$BRAND_DIR", onCopy
)

/** API 29+ : audio → Music/RiploxTT/. */
fun saveAudioToMusic(context: Context, temp: File, onCopy: (Long) -> Unit = {}): String = insertMedia(
    context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, temp,
    mimeForExt(temp.extension, audioOnly = true), "${Environment.DIRECTORY_MUSIC}/$BRAND_DIR", onCopy
)

/** TT photo-posts ki images → Pictures/RiploxTT/. */
fun saveImageToPictures(context: Context, temp: File): String {
    if (Build.VERSION.SDK_INT < 29) {
        return saveLegacyPublic(context, temp, Environment.DIRECTORY_PICTURES)
    }
    val mime = when (temp.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    return insertMedia(
        context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, temp, mime,
        "${Environment.DIRECTORY_PICTURES}/$BRAND_DIR"
    )
}

/**
 * Public save — the file survives on EVERY Android version.
 * API 29+ uses MediaStore. API 26–28 copies straight into the public folder and runs a media
 * scan (without the WRITE permission it goes to the app's own external folder — the file is
 * still kept either way).
 */
fun savePublic(context: Context, temp: File, audioOnly: Boolean, onCopy: (Long) -> Unit = {}): String =
    if (Build.VERSION.SDK_INT >= 29) {
        if (audioOnly) saveAudioToMusic(context, temp, onCopy)
        else saveVideoToGallery(context, temp, onCopy)
    } else {
        saveLegacyPublic(
            context, temp,
            if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES,
            onCopy
        )
    }

/** API 26–28: there is no RELATIVE_PATH — copy the file, then MediaScanner. Never delete without copying. */
@Suppress("DEPRECATION")
fun saveLegacyPublic(context: Context, temp: File, publicDirType: String, onCopy: (Long) -> Unit = {}): String {
    val canWrite = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
    val dir = if (canWrite) {
        File(Environment.getExternalStoragePublicDirectory(publicDirType), BRAND_DIR)
    } else {
        // Permission denied → the app's own external folder (reachable from a file manager, not deleted)
        File(context.getExternalFilesDir(publicDirType), BRAND_DIR)
    }
    dir.mkdirs()
    var dest = File(dir, temp.name)
    var i = 1
    while (dest.exists()) {
        dest = File(dir, "${temp.nameWithoutExtension}_$i.${temp.extension}")
        i++
    }
    dest.outputStream().use { out ->
        temp.inputStream().use { input -> copyChunked(input, out, onCopy) }
    }
    temp.delete()
    try {
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
    } catch (_: Exception) {
    }
    return dest.absolutePath
}
