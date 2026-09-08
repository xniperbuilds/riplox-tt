package com.xniperbuilds.downloader

/**
 * When may Riplox ask for a full-screen ad, and when may it show one.
 *
 * This is deliberately a **pure** object — no Context, no AdMob types, no clock of its own.
 * Every input is passed in. That is what makes it testable, and the reason it exists at all:
 * the rules below used to live inside [Ads] tangled up with the SDK, and a bug hid in them
 * for the whole life of the app.
 *
 * ## The bug this was extracted to fix (measured, 1–7 Sep 2026)
 *
 * AdMob reported **159 requests → 87 matched → 25 impressions**: a show rate of 29%. On 1 and
 * 2 Sep it was **0%** — six ads were fetched and not one was shown.
 *
 * The cause was that a preload fired on every app open (`MainActivity`) and on every share
 * open (`QuickDownloadActivity`), while a *show* needs two conditions that most sessions
 * never reach: an even [SHOW_EVERY_N] slot and [MIN_GAP_MS] since the last full-screen ad.
 * Anyone who opened Riplox and did not finish two downloads had an ad fetched on their
 * behalf that could never be shown; it counted as a matched request and then expired.
 *
 * The rule that replaces it is one line long: **do not ask for an ad you do not intend to
 * show.** [shouldPreload] fetches exactly one completion ahead of a show slot — early enough
 * to cover the measured 0.78–4.02s load latency, late enough that the ad is still fresh when
 * its turn comes.
 *
 * ⚠️ This does **not** show more ads than before. The show conditions are unchanged; only
 * the wasted requests are gone. Impressions stay the same or rise (a fresher ad is likelier
 * to still be valid), while requests fall.
 */
object AdGate {

    /** Show a full-screen ad on every Nth completed user action. */
    const val SHOW_EVERY_N = 2

    /**
     * A hard floor between two full-screen ads, on top of the every-N rule.
     *
     * ⚠️ The every-N counter alone is NOT enough once a batch of links can be queued at once:
     * five downloads finishing within a few seconds of each other would fire two interstitials
     * back to back, and AdMob disallows exactly that ("Placing an interstitial ad immediately
     * after another interstitial ad was shown to and closed by the user").
     */
    const val MIN_GAP_MS = 60_000L

    /**
     * How long a loaded interstitial may sit before we stop trusting it.
     *
     * AdMob expires a cached interstitial about an hour after it loads; showing an expired one
     * fails through `onAdFailedToShowFullScreenContent` and burns the slot without an
     * impression. 50 minutes leaves a margin under that hour.
     */
    const val MAX_AD_AGE_MS = 50L * 60L * 1000L

    /** Sentinel for "no ad is loaded" — used for both `loadedAt` and `lastShownAt`. */
    const val NEVER = 0L

    /** Is this completion one of the every-Nth slots where an ad may be shown? */
    fun isShowSlot(completedCount: Int): Boolean =
        completedCount > 0 && completedCount % SHOW_EVERY_N == 0

    /** Has this cached ad been sitting long enough that AdMob may already have expired it? */
    fun isStale(loadedAt: Long, now: Long): Boolean =
        loadedAt == NEVER || now - loadedAt >= MAX_AD_AGE_MS

    /** Have [MIN_GAP_MS] passed since the last full-screen ad? True when none has been shown. */
    fun gapElapsed(lastShownAt: Long, now: Long): Boolean =
        lastShownAt == NEVER || now - lastShownAt >= MIN_GAP_MS

    /**
     * May we show the cached ad for the completion numbered [completedCount]?
     *
     * All four conditions must hold — an even slot, an ad in hand, that ad still fresh, and
     * the 60s floor cleared.
     */
    fun shouldShow(
        completedCount: Int,
        hasAd: Boolean,
        loadedAt: Long,
        lastShownAt: Long,
        now: Long,
    ): Boolean =
        isShowSlot(completedCount) &&
            hasAd &&
            !isStale(loadedAt, now) &&
            gapElapsed(lastShownAt, now)

    /**
     * May we fetch an ad now, having just handled completion [completedCount]?
     *
     * Only when the **next** completion is a show slot. That is the whole fix: at
     * [SHOW_EVERY_N] = 2 this means we fetch after the odd completions (1, 3, 5…) and never
     * on app open, never on a share open, and never immediately after showing one — because
     * in each of those cases the next show is either two completions away or may never
     * arrive at all.
     */
    fun shouldPreload(completedCount: Int, hasAd: Boolean, loading: Boolean): Boolean =
        !hasAd && !loading && isShowSlot(completedCount + 1)
}
