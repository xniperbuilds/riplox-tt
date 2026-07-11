package com.xniperbuilds.downloader

import android.content.Context

/** Chhoti settings (SharedPreferences) — Riplox TT me bas 4 kaam ki cheezen. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("riploxtt_prefs", Context.MODE_PRIVATE)

    /** Video quality: "best" | "1080" | "720" | "480". Default 1080p. */
    fun quality(c: Context): String = sp(c).getString("quality", "1080") ?: "1080"
    fun setQuality(c: Context, v: String) = sp(c).edit().putString("quality", v).apply()

    /** true = MP3 (audio-only) — home chip + share tile dono isi se chalte hain. */
    fun audioMode(c: Context) = sp(c).getBoolean("audioMode", false)
    fun setAudioMode(c: Context, v: Boolean) = sp(c).edit().putBoolean("audioMode", v).apply()

    /** Engine last auto-update kis din hua (yyyyMMdd) — din me ek dafa update. */
    fun lastUpdateDay(c: Context): String = sp(c).getString("lastUpdateDay", "") ?: ""
    fun setLastUpdateDay(c: Context, v: String) = sp(c).edit().putString("lastUpdateDay", v).apply()

    /** Clipboard se aakhri auto-fill link (same link dobara suggest na ho). */
    fun lastClip(c: Context): String = sp(c).getString("lastClip", "") ?: ""
    fun setLastClip(c: Context, v: String) = sp(c).edit().putString("lastClip", v).apply()
}
