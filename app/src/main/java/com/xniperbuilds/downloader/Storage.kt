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

/** MediaStore me file likho (IS_PENDING flow) — fail ho to adhoori row delete (orphan na bache). */
private fun insertMedia(
    context: Context,
    contentUri: Uri,
    temp: File,
    mime: String,
    relPath: String
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
            temp.inputStream().use { input -> input.copyTo(out) }
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
fun saveVideoToGallery(context: Context, temp: File): String = insertMedia(
    context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, temp,
    mimeForExt(temp.extension, audioOnly = false), "${Environment.DIRECTORY_MOVIES}/$BRAND_DIR"
)

/** API 29+ : audio → Music/RiploxTT/. */
fun saveAudioToMusic(context: Context, temp: File): String = insertMedia(
    context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, temp,
    mimeForExt(temp.extension, audioOnly = true), "${Environment.DIRECTORY_MUSIC}/$BRAND_DIR"
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
 * Public save — HAR Android version pe file mehfooz rahe.
 * API 29+ = MediaStore. API 26–28 = public folder me seedha copy + media scan
 * (WRITE permission na ho to app ke apne external folder me — file phir bhi bachti hai).
 */
fun savePublic(context: Context, temp: File, audioOnly: Boolean): String =
    if (Build.VERSION.SDK_INT >= 29) {
        if (audioOnly) saveAudioToMusic(context, temp)
        else saveVideoToGallery(context, temp)
    } else {
        saveLegacyPublic(
            context, temp,
            if (audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        )
    }

/** API 26–28: RELATIVE_PATH nahi hota — file copy + MediaScanner. Kabhi delete-without-copy nahi. */
@Suppress("DEPRECATION")
fun saveLegacyPublic(context: Context, temp: File, publicDirType: String): String {
    val canWrite = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
    val dir = if (canWrite) {
        File(Environment.getExternalStoragePublicDirectory(publicDirType), BRAND_DIR)
    } else {
        // Permission nahi mili → app ka apna external folder (file manager se milta hai, delete nahi hoti)
        File(context.getExternalFilesDir(publicDirType), BRAND_DIR)
    }
    dir.mkdirs()
    var dest = File(dir, temp.name)
    var i = 1
    while (dest.exists()) {
        dest = File(dir, "${temp.nameWithoutExtension}_$i.${temp.extension}")
        i++
    }
    temp.copyTo(dest, overwrite = false)
    temp.delete()
    try {
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
    } catch (_: Exception) {
    }
    return dest.absolutePath
}
