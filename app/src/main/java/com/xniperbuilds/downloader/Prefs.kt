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

    // ---- Daily streak, unlocked accents and the ad-free window (see Streak.kt) ----

    /** Day of the last check-in, `yyyy-MM-dd` in the device's own timezone. "" = never. */
    fun lastCheckIn(c: Context): String = sp(c).getString("lastCheckIn", "") ?: ""
    fun streakDays(c: Context) = sp(c).getInt("streakDays", 0)
    fun bestStreak(c: Context) = sp(c).getInt("bestStreak", 0)

    /** Written together so a crash between them cannot leave the day and count disagreeing. */
    fun setCheckIn(c: Context, day: String, streak: Int, best: Int) =
        sp(c).edit()
            .putString("lastCheckIn", day)
            .putInt("streakDays", streak)
            .putInt("bestStreak", best)
            .apply()

    /** Epoch millis until which no ads are shown. 0 = none. */
    fun adFreeUntil(c: Context) = sp(c).getLong("adFreeUntil", 0L)
    fun setAdFreeUntil(c: Context, v: Long) = sp(c).edit().putLong("adFreeUntil", v).apply()

    /** Accents bought with a rewarded ad rather than earned by streak (milestone ints). */
    fun boughtAccents(c: Context): Set<Int> =
        (sp(c).getStringSet("boughtAccents", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toSet()

    fun addBoughtAccent(c: Context, milestone: Int) =
        sp(c).edit().putStringSet(
            "boughtAccents",
            boughtAccents(c).plus(milestone).map { it.toString() }.toSet()
        ).apply()

    /** The accent the user is actually using. 0 = the default Riplox look. */
    fun accent(c: Context) = sp(c).getInt("accent", 0)
    fun setAccent(c: Context, v: Int) = sp(c).edit().putInt("accent", v).apply()
}
