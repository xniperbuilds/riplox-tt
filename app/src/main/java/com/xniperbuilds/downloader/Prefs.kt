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

    /** Engine last auto-update kis din hua (yyyyMMdd) — din me ek dafa update.
     * ⚠️ Sirf KAMYAB update pe likha jata hai, warna nakaam koshish "aaj ho chuka" ban jati. */
    fun lastUpdateDay(c: Context): String = sp(c).getString("lastUpdateDay", "") ?: ""
    fun setLastUpdateDay(c: Context, v: String) = sp(c).edit().putString("lastUpdateDay", v).apply()

    /** Engine ke aakhri update ka NATIJA + version — pehle ye `catch {}` me kho jata tha, aur
     * hamesha nakaam hone wala update bilkul kamyab jaisa dikhta tha. */
    fun engineOutcome(c: Context): String = sp(c).getString("engineOutcome", "") ?: ""
    fun engineVersion(c: Context): String = sp(c).getString("engineVersion", "") ?: ""
    fun setEngineResult(c: Context, outcome: String, version: String) =
        sp(c).edit().putString("engineOutcome", outcome).putString("engineVersion", version).apply()

    /** Clipboard se aakhri auto-fill link (same link dobara suggest na ho). */
    fun lastClip(c: Context): String = sp(c).getString("lastClip", "") ?: ""
    fun setLastClip(c: Context, v: String) = sp(c).edit().putString("lastClip", v).apply()

    /** Has the user finished the background-setup guide (battery + auto-start + recents-lock)?
     * XOS-style phones still freeze downloads without auto-start EVEN AFTER a battery
     * exemption, so the banner is independent of that exemption and keeps showing until
     * this flag is set. */
    fun bgSetupDone(c: Context) = sp(c).getBoolean("bgSetupDone", false)
    fun setBgSetupDone(c: Context, v: Boolean) = sp(c).edit().putBoolean("bgSetupDone", v).apply()

    /** Master switch for Connect TikTok (login/cookies). Default ON — the login only matters
     * when a video is private / region-locked / age-restricted. OFF means guest mode (public
     * videos) and is the escape hatch from a bad session. Cookies are only ever sent if some
     * have been saved. */
    fun cookiesEnabled(c: Context) = sp(c).getBoolean("cookiesEnabled", true)
    fun setCookiesEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("cookiesEnabled", v).apply()
}
