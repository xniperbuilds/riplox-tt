package com.xniperbuilds.downloader

/**
 * Daily check-in streak, the rewards it unlocks, and the ad-free window.
 *
 * Pure on purpose — no Context, no clock, no AdMob. Everything is passed in, so every rule
 * below is unit-testable. Dates are plain `yyyy-MM-dd` strings in the device's own timezone;
 * the caller formats them.
 *
 * ## What this is for
 *
 * Riplox is a utility: people open it, grab a video, and leave. That gives it very few
 * sessions per user, and sessions are what ad revenue is made of. A streak gives someone a
 * reason to open the app on a day they have nothing to download.
 *
 * ## The two rules that constrain every reward here
 *
 * 1. **Nothing that is free today may be taken away.** Every quality, MP3, batch paste and
 *    the optional login stay exactly as they are. A reward may only ever be something NEW.
 * 2. **A rewarded ad must state what it gives.** AdMob's policy names the failure mode
 *    outright: publishers "must not include any text or icons, other than to describe the
 *    reward(s) offered, to mislead or incentivize users towards a particular choice (such as
 *    by indicating 'watch this ad to support our business')." So every button here is phrased
 *    as the thing the user gets — never as a favour to us.
 */
object Streak {

    /** How long one rewarded view buys. */
    const val AD_FREE_MS = 2L * 60L * 60L * 1000L

    /** Ceiling on banked ad-free time, so repeated views cannot bank a week of it. */
    const val AD_FREE_CAP_MS = 24L * 60L * 60L * 1000L

    /** Reaching this streak grants a full day ad-free, with no ad to watch. */
    const val MILESTONE_FREE_DAY = 7

    const val NEVER = 0L

    /** Streak lengths that unlock an accent, in ascending order. */
    val THEME_MILESTONES = listOf(3, 7, 14, 30)

    /**
     * The streak after checking in on [today], given the last check-in day and count.
     *
     * Same day → unchanged (checking in twice must not inflate it). Yesterday → +1.
     * Anything older, or no history at all → the streak restarts at 1.
     */
    fun nextStreak(lastCheckIn: String, today: String, yesterday: String, current: Int): Int = when {
        lastCheckIn == today -> current.coerceAtLeast(1)
        lastCheckIn == yesterday -> current + 1
        else -> 1
    }

    /** Has today's check-in already happened? */
    fun isCheckedIn(lastCheckIn: String, today: String): Boolean = lastCheckIn == today

    /** Every accent unlocked by reaching [streak]. */
    fun unlockedBy(streak: Int): List<Int> = THEME_MILESTONES.filter { streak >= it }

    /** The next milestone the user is working towards, or null once they are all unlocked. */
    fun nextMilestone(streak: Int): Int? = THEME_MILESTONES.firstOrNull { it > streak }

    /** Days left until [nextMilestone]. Null when there is nothing left to reach. */
    fun daysToNextMilestone(streak: Int): Int? = nextMilestone(streak)?.minus(streak)

    /** Does reaching [streak] today grant the free ad-free day? */
    fun grantsFreeDay(streak: Int, previousStreak: Int): Boolean =
        streak >= MILESTONE_FREE_DAY && previousStreak < MILESTONE_FREE_DAY

    /** Is the ad-free window currently open? */
    fun adFree(adFreeUntil: Long, now: Long): Boolean = adFreeUntil > now

    /** Milliseconds of ad-free time left (0 when none). */
    fun adFreeRemaining(adFreeUntil: Long, now: Long): Long =
        (adFreeUntil - now).coerceAtLeast(0L)

    /**
     * The new `adFreeUntil` after granting [grant] more milliseconds.
     *
     * Time already banked is extended rather than replaced — a user who watches a second ad
     * an hour in should not lose the hour they still had. The total is capped at
     * [AD_FREE_CAP_MS] from now.
     */
    fun extendAdFree(adFreeUntil: Long, now: Long, grant: Long): Long {
        val base = if (adFreeUntil > now) adFreeUntil else now
        return minOf(base + grant, now + AD_FREE_CAP_MS)
    }
}
