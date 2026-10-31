package com.xniperbuilds.downloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the full-screen ad rules.
 *
 * The one that matters most is [appOpenNeverPreloads] — that is the bug this whole class was
 * written for. AdMob measured 159 requests → 87 matched → 25 impressions (a 29% show rate,
 * 0% on 1–2 Sep) because an ad was fetched on every app open and on every share open, while
 * a show needs an even slot plus the 60s gap. Those ads expired unshown.
 */
class AdGateTest {

    private val T0 = 1_000_000L
    private fun fresh(now: Long) = now - 1_000L

    // ---------- show slots ----------

    @Test
    fun showSlotIsEveryNthCompletion() {
        assertFalse("nothing completed yet is never a slot", AdGate.isShowSlot(0))
        assertFalse(AdGate.isShowSlot(1))
        assertTrue(AdGate.isShowSlot(2))
        assertFalse(AdGate.isShowSlot(3))
        assertTrue(AdGate.isShowSlot(4))
        assertFalse(AdGate.isShowSlot(5))
        assertTrue(AdGate.isShowSlot(6))
    }

    // ---------- the regression this class exists for ----------

    @Test
    fun appOpenNeverPreloads() {
        // App/share open = nothing completed yet. Fetching here was the 29% show-rate bug:
        // completion #1 is not a slot, so the ad could not be shown and expired unused.
        assertFalse(AdGate.shouldPreload(completedCount = 0, hasAd = false, loading = false))
    }

    @Test
    fun preloadsExactlyOneCompletionBeforeASlot() {
        // After the odd completions — the next one is a slot, so fetch now (covers the
        // measured 0.78-4.02s load latency).
        assertTrue(AdGate.shouldPreload(1, hasAd = false, loading = false))
        assertTrue(AdGate.shouldPreload(3, hasAd = false, loading = false))
        assertTrue(AdGate.shouldPreload(5, hasAd = false, loading = false))
    }

    @Test
    fun doesNotPreloadRightAfterShowing() {
        // Just showed at #2; the next slot is #4, two completions away. Fetching now would
        // leave the ad sitting long enough to expire — that was the second half of the bug.
        assertFalse(AdGate.shouldPreload(2, hasAd = false, loading = false))
        assertFalse(AdGate.shouldPreload(4, hasAd = false, loading = false))
    }

    @Test
    fun doesNotPreloadWhenOneIsAlreadyHeldOrInFlight() {
        assertFalse("already have one", AdGate.shouldPreload(1, hasAd = true, loading = false))
        assertFalse("already fetching", AdGate.shouldPreload(1, hasAd = false, loading = true))
    }

    // ---------- staleness ----------

    @Test
    fun anAdWithNoLoadTimeIsStale() {
        assertTrue(AdGate.isStale(AdGate.NEVER, T0))
    }

    @Test
    fun adGoesStaleAtTheCutoffNotBefore() {
        val loadedAt = T0
        assertFalse(AdGate.isStale(loadedAt, T0 + AdGate.MAX_AD_AGE_MS - 1))
        assertTrue(AdGate.isStale(loadedAt, T0 + AdGate.MAX_AD_AGE_MS))
        assertTrue(AdGate.isStale(loadedAt, T0 + AdGate.MAX_AD_AGE_MS + 60_000L))
    }

    @Test
    fun staleCutoffStaysUnderAdMobsOneHourExpiry() {
        assertTrue(
            "must leave margin under AdMob's ~1h interstitial expiry",
            AdGate.MAX_AD_AGE_MS < 60L * 60L * 1000L
        )
    }

    // ---------- the 60s floor ----------

    @Test
    fun gapIsClearWhenNoAdHasEverBeenShown() {
        assertTrue(AdGate.gapElapsed(AdGate.NEVER, T0))
    }

    @Test
    fun gapBlocksUntilTheFloorIsReached() {
        val shownAt = T0
        assertFalse(AdGate.gapElapsed(shownAt, T0 + AdGate.MIN_GAP_MS - 1))
        assertTrue(AdGate.gapElapsed(shownAt, T0 + AdGate.MIN_GAP_MS))
    }

    // ---------- show decision: all four conditions ----------

    @Test
    fun showsOnASlotWithAFreshAdAndTheGapClear() {
        val now = T0 + AdGate.MIN_GAP_MS
        assertTrue(
            AdGate.shouldShow(
                completedCount = 2, hasAd = true,
                loadedAt = fresh(now), lastShownAt = T0, now = now,
            )
        )
    }

    @Test
    fun neverShowsOffSlot() {
        val now = T0 + AdGate.MIN_GAP_MS
        assertFalse(
            AdGate.shouldShow(
                completedCount = 3, hasAd = true,
                loadedAt = fresh(now), lastShownAt = T0, now = now,
            )
        )
    }

    @Test
    fun neverShowsWithoutAnAd() {
        val now = T0 + AdGate.MIN_GAP_MS
        assertFalse(
            AdGate.shouldShow(
                completedCount = 2, hasAd = false,
                loadedAt = fresh(now), lastShownAt = T0, now = now,
            )
        )
    }

    @Test
    fun neverShowsAStaleAd() {
        val now = T0 + AdGate.MAX_AD_AGE_MS + AdGate.MIN_GAP_MS
        assertFalse(
            AdGate.shouldShow(
                completedCount = 2, hasAd = true,
                loadedAt = T0, lastShownAt = AdGate.NEVER, now = now,
            )
        )
    }

    @Test
    fun neverShowsTwoAdsInsideTheFloor() {
        // The batch case: several queued downloads finishing seconds apart. AdMob disallows
        // an interstitial immediately after another one.
        val now = T0 + 5_000L
        assertFalse(
            AdGate.shouldShow(
                completedCount = 2, hasAd = true,
                loadedAt = fresh(now), lastShownAt = T0, now = now,
            )
        )
    }

    // ---------- the whole sequence a real user walks ----------

    @Test
    fun firstEverDownloadFetchesNothingUntilItCanBeShown() {
        // Fresh install, app opens: no fetch.
        assertFalse(AdGate.shouldPreload(0, hasAd = false, loading = false))
        // Download #1 completes: not a slot, but #2 is — fetch now.
        assertFalse(AdGate.isShowSlot(1))
        assertTrue(AdGate.shouldPreload(1, hasAd = false, loading = false))
        // Download #2 completes with that ad in hand and no previous ad: show it.
        val now = T0 + 30_000L
        assertTrue(
            AdGate.shouldShow(
                completedCount = 2, hasAd = true,
                loadedAt = fresh(now), lastShownAt = AdGate.NEVER, now = now,
            )
        )
    }

    @Test
    fun userWhoDownloadsOnceAndLeavesCostsUsNothing() {
        // This is the 1-2 Sep case: 6 requests, 6 matched, 0 impressions. Under the old rule
        // the app open fetched an ad. Now the only fetch happens after completion #1 — and if
        // the user never returns, that is one ad, not one per app open.
        assertFalse("app open", AdGate.shouldPreload(0, hasAd = false, loading = false))
        assertFalse("share open", AdGate.shouldPreload(0, hasAd = false, loading = false))
    }

    // ---------- app open ad (v1.3.0) ----------

    private val HOUR = 60L * 60L * 1000L
    private val WALL = 1_760_000_000_000L // an ordinary wall-clock instant

    /** Every condition passing — each test below breaks exactly one of them. */
    private fun appOpen(
        opens: Int = 3,
        lastAppOpenAt: Long = AdGate.NEVER,
        nowWall: Long = WALL,
        lastFullScreenAt: Long = AdGate.NEVER,
        nowElapsed: Long = T0,
        adFree: Boolean = false,
        clickedAway: Boolean = false,
    ) = AdGate.shouldShowAppOpen(
        opens, lastAppOpenAt, nowWall, lastFullScreenAt, nowElapsed, adFree, clickedAway,
    )

    @Test
    fun appOpenShowsWhenEverythingClears() {
        assertTrue(appOpen())
    }

    @Test
    fun appOpenSkipsTheFirstTwoOpens() {
        // Google: show the first one "after your users have used your app a few times".
        assertFalse("first open ever", appOpen(opens = 1))
        assertFalse("second open", appOpen(opens = 2))
        assertTrue("third open", appOpen(opens = 3))
    }

    @Test
    fun appOpenAtMostOncePerHour() {
        assertFalse("59 min after the last one", appOpen(lastAppOpenAt = WALL - 59L * 60_000L))
        assertTrue("exactly an hour", appOpen(lastAppOpenAt = WALL - HOUR))
        assertTrue("two hours", appOpen(lastAppOpenAt = WALL - 2 * HOUR))
    }

    @Test
    fun appOpenClockSetBackwardsDoesNotSilenceItForDays() {
        // The phone's clock was corrected backwards: the last ad is "in the future". Treat the
        // cap as cleared rather than waiting days for the clock to catch up.
        assertTrue(appOpen(lastAppOpenAt = WALL + 3L * 24L * HOUR))
    }

    @Test
    fun appOpenNeverInsideTheAdFreeWindow() {
        // The window promises no ads — full-screen ones included.
        assertFalse(appOpen(adFree = true))
    }

    @Test
    fun appOpenNotWhenComingBackFromAnAdClick() {
        assertFalse(appOpen(clickedAway = true))
    }

    @Test
    fun appOpenKeepsTheSixtySecondFloorToOtherFullScreenAds() {
        // "Don't display ads immediately before or after app open ads."
        assertFalse("interstitial 30s ago", appOpen(lastFullScreenAt = T0 - 30_000L))
        assertTrue("interstitial 60s ago", appOpen(lastFullScreenAt = T0 - AdGate.MIN_GAP_MS))
    }
}
