package com.xniperbuilds.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakTest {

    private val today = "2026-09-08"
    private val yesterday = "2026-09-07"
    private val lastWeek = "2026-09-01"
    private val T0 = 1_000_000_000L

    // ---------- the streak counter ----------

    @Test
    fun firstEverCheckInStartsAtOne() {
        assertEquals(1, Streak.nextStreak("", today, yesterday, 0))
    }

    @Test
    fun checkingInTheNextDayIncrements() {
        assertEquals(4, Streak.nextStreak(yesterday, today, yesterday, 3))
    }

    @Test
    fun checkingInTwiceInOneDayDoesNotInflate() {
        assertEquals(3, Streak.nextStreak(today, today, yesterday, 3))
    }

    @Test
    fun aMissedDayResetsToOne() {
        assertEquals(1, Streak.nextStreak(lastWeek, today, yesterday, 12))
    }

    @Test
    fun sameDayOnAZeroStreakStillCountsAsOne() {
        // Defensive: stored day is today but the count was never written.
        assertEquals(1, Streak.nextStreak(today, today, yesterday, 0))
    }

    @Test
    fun checkedInIsTrueOnlyForToday() {
        assertTrue(Streak.isCheckedIn(today, today))
        assertFalse(Streak.isCheckedIn(yesterday, today))
        assertFalse(Streak.isCheckedIn("", today))
    }

    // ---------- milestones ----------

    @Test
    fun accentsUnlockAtTheirMilestoneAndStayUnlocked() {
        assertEquals(emptyList<Int>(), Streak.unlockedBy(2))
        assertEquals(listOf(3), Streak.unlockedBy(3))
        assertEquals(listOf(3, 7), Streak.unlockedBy(8))
        assertEquals(listOf(3, 7, 14, 30), Streak.unlockedBy(30))
        assertEquals(listOf(3, 7, 14, 30), Streak.unlockedBy(365))
    }

    @Test
    fun nextMilestoneCountsForward() {
        assertEquals(3, Streak.nextMilestone(0))
        assertEquals(7, Streak.nextMilestone(3))
        assertEquals(14, Streak.nextMilestone(7))
        assertNull("nothing left after the last one", Streak.nextMilestone(30))
    }

    @Test
    fun daysToNextMilestoneIsTheGap() {
        assertEquals(3, Streak.daysToNextMilestone(0))
        assertEquals(1, Streak.daysToNextMilestone(2))
        assertEquals(4, Streak.daysToNextMilestone(3))
        assertNull(Streak.daysToNextMilestone(30))
    }

    @Test
    fun theFreeDayIsGrantedOnceOnCrossingSeven() {
        assertTrue("crossed 6 -> 7", Streak.grantsFreeDay(streak = 7, previousStreak = 6))
        assertFalse("already past it", Streak.grantsFreeDay(streak = 8, previousStreak = 7))
        assertFalse("not there yet", Streak.grantsFreeDay(streak = 6, previousStreak = 5))
    }

    // ---------- the ad-free window ----------

    @Test
    fun adFreeIsClosedByDefault() {
        assertFalse(Streak.adFree(Streak.NEVER, T0))
        assertEquals(0L, Streak.adFreeRemaining(Streak.NEVER, T0))
    }

    @Test
    fun adFreeClosesExactlyAtItsDeadline() {
        val until = T0 + 1000
        assertTrue(Streak.adFree(until, T0))
        assertFalse("closed the instant it expires", Streak.adFree(until, until))
        assertFalse(Streak.adFree(until, until + 1))
    }

    @Test
    fun oneRewardedViewBuysTwoHours() {
        val until = Streak.extendAdFree(Streak.NEVER, T0, Streak.AD_FREE_MS)
        assertEquals(T0 + Streak.AD_FREE_MS, until)
        assertEquals(Streak.AD_FREE_MS, Streak.adFreeRemaining(until, T0))
    }

    @Test
    fun watchingAgainExtendsRatherThanReplaces() {
        // An hour into a 2h window, a second view must not throw away the hour still owed.
        val first = Streak.extendAdFree(Streak.NEVER, T0, Streak.AD_FREE_MS)
        val anHourLater = T0 + 60L * 60L * 1000L
        val second = Streak.extendAdFree(first, anHourLater, Streak.AD_FREE_MS)
        assertEquals(first + Streak.AD_FREE_MS, second)
        assertTrue(Streak.adFreeRemaining(second, anHourLater) > Streak.AD_FREE_MS)
    }

    @Test
    fun bankedAdFreeTimeIsCapped() {
        var until = Streak.NEVER
        repeat(50) { until = Streak.extendAdFree(until, T0, Streak.AD_FREE_MS) }
        assertEquals(T0 + Streak.AD_FREE_CAP_MS, until)
        assertTrue(Streak.adFreeRemaining(until, T0) <= Streak.AD_FREE_CAP_MS)
    }

    @Test
    fun anExpiredWindowRestartsFromNowNotFromThePast() {
        val stale = T0 - 100_000L
        val until = Streak.extendAdFree(stale, T0, Streak.AD_FREE_MS)
        assertEquals(T0 + Streak.AD_FREE_MS, until)
    }
}
