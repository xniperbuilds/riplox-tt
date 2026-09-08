package com.xniperbuilds.downloader

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * AdMob — bottom banner (home) + interstitial (on every 2nd completed download).
 *
 * The IDs now live in **build.gradle.kts** (BuildConfig + manifest placeholder):
 *   release build → the real ids
 *   qa build      → Google's TEST ids (assembleQa)
 * ⚠️ Always test on your own phone via **assembleQa** (the `Riplox TT QA` app) — clicking
 *    your own real ads counts as AdMob invalid traffic / a policy violation.
 *
 * **Every decision about when to fetch and when to show lives in [AdGate], not here.** This
 * object only talks to the SDK and holds the state. That split exists because a real bug hid
 * in these rules for the whole life of the app — see the header of [AdGate] for the
 * measurement and the cause.
 */
object Ads {
    const val ENABLED = true

    // Supplied by the build type — no more swapping ids by hand
    val BANNER_ID: String = BuildConfig.AD_BANNER_ID
    val INTERSTITIAL_ID: String = BuildConfig.AD_INTERSTITIAL_ID

    /**
     * Rewarded unit for the ad-free window and accent unlocks.
     *
     * ⚠️ Blank on a build whose id has not been created in the AdMob console yet. Everything
     * rewarded checks [rewardedConfigured] first and simply does not offer the option — the
     * app must never show a button that cannot deliver its reward, because AdMob requires that
     * publishers "deliver the promised reward(s) to the user upon completion of the required
     * action(s)".
     */
    val REWARDED_ID: String = BuildConfig.AD_REWARDED_ID

    val rewardedConfigured: Boolean get() = REWARDED_ID.isNotBlank()

    /**
     * Is the user inside their earned ad-free window?
     *
     * Every ad path in the app goes through this. The window is a real reward — it silences
     * the banner AND the interstitial — because a promise of "ad-free" that still shows a
     * full-screen ad is the kind of thing that costs a rating, not just an impression.
     */
    fun suppressed(ctx: Context): Boolean =
        Streak.adFree(Prefs.adFreeUntil(ctx), System.currentTimeMillis())

    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var completedCount = 0
    private var lastFullScreenAt = AdGate.NEVER

    /** When the cached [interstitial] was loaded, so a stale one can be dropped before showing. */
    private var loadedAt = AdGate.NEVER

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    // ---- Rewarded: the ad-free window and accent unlocks (see Streak.kt) ----

    private var rewarded: RewardedAd? = null
    private var rewardedLoading = false

    /**
     * Readiness is Compose STATE, not a plain field.
     *
     * ⚠️ It has to be. The rewarded ad arrives asynchronously, long after the card that offers
     * it has been composed. With a plain `rewarded != null` check nothing ever invalidated that
     * composition, so the button stayed hidden even once an ad was in hand — measured on device,
     * 8 Sep 2026. Reading a MutableState subscribes the caller, so the button appears the moment
     * the ad lands.
     */
    private val rewardedReadyState = mutableStateOf(false)

    /** Is a rewarded ad in hand right now? Drives whether the button is offered at all. */
    fun rewardedReady(): Boolean = rewardedReadyState.value

    /**
     * Fetch a rewarded ad, if one is not already held or in flight.
     *
     * Unlike the interstitial this IS loaded ahead of time on the screen that offers it —
     * a rewarded ad is opt-in, so an ad we fetch and never show costs nothing but a request,
     * and a user who taps the button must not be made to wait on the network.
     */
    fun loadRewarded(ctx: Context) {
        if (!ENABLED || !rewardedConfigured) return
        if (!Consent.canRequestAds(ctx)) return
        if (rewarded != null || rewardedLoading) return
        rewardedLoading = true
        RewardedAd.load(
            ctx, REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    rewardedReadyState.value = true
                    rewardedLoading = false
                }

                override fun onAdFailedToLoad(e: LoadAdError) {
                    rewarded = null
                    rewardedReadyState.value = false
                    rewardedLoading = false
                }
            }
        )
    }

    /**
     * Show the rewarded ad. [onReward] runs ONLY if the user actually earned it.
     *
     * ⚠️ AdMob requires that we "deliver the promised reward(s) to the user upon completion of
     * the required action(s)" — so the grant hangs off `OnUserEarnedRewardListener`, never off
     * dismissal. Closing the ad early earns nothing, which is the correct behaviour.
     * [onFinished] always runs, earned or not, so the caller can refresh its UI.
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onFinished: () -> Unit = {}) {
        val ad = rewarded
        if (ad == null) { onFinished(); return }
        rewarded = null
        rewardedReadyState.value = false
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                if (earned) onReward()
                onFinished()
                loadRewarded(activity)
            }

            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                onFinished()
                loadRewarded(activity)
            }
        }
        ad.show(activity) { earned = true }
    }

    /**
     * Fetch an interstitial **only if one is actually going to be shown next.**
     *
     * ⚠️ Do not call this on app open, on a share open, or straight after showing an ad. That
     * was the 29%-show-rate bug: in each of those cases the next show is either two
     * completions away or never arrives at all, and the fetched ad expires unshown — counting
     * as a matched request with no impression. [AdGate.shouldPreload] enforces the rule, so
     * this method is safe to call after any completion.
     */
    fun maybePreload(ctx: Context) {
        if (!ENABLED) return
        // Inside the earned ad-free window there is nothing to fetch — see [suppressed].
        if (suppressed(ctx)) return
        // ⚠️ Consent first. In the EEA/UK/Switzerland a request made before the form has been
        // answered cannot serve a personalised ad — see Consent.kt. Everywhere else
        // canRequestAds() is true from the very first call, so this costs one local check.
        if (!Consent.canRequestAds(ctx)) return
        if (!AdGate.shouldPreload(completedCount, interstitial != null, loading)) return
        loading = true
        InterstitialAd.load(
            ctx, INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loadedAt = now()
                    loading = false
                }

                override fun onAdFailedToLoad(e: LoadAdError) {
                    clearAd()
                    loading = false
                }
            }
        )
    }

    private fun clearAd() {
        interstitial = null
        loadedAt = AdGate.NEVER
    }

    /**
     * Take the cached ad if it is still worth showing for this completion, else `null`.
     *
     * A stale ad is dropped here rather than handed to `show()`: AdMob expires a cached
     * interstitial after about an hour, and showing an expired one fails through
     * `onAdFailedToShowFullScreenContent` — burning the slot without an impression.
     */
    private fun takeShowableAd(): InterstitialAd? {
        val ad = interstitial ?: return null
        if (AdGate.isStale(loadedAt, now())) {
            clearAd()
            return null
        }
        val ok = AdGate.shouldShow(
            completedCount = completedCount,
            hasAd = true,
            loadedAt = loadedAt,
            lastShownAt = lastFullScreenAt,
            now = now(),
        )
        if (!ok) return null
        // Hand the ad over and forget it here — the caller owns it from this point.
        clearAd()
        return ad
    }

    private fun markFullScreenShown() {
        lastFullScreenAt = now()
    }

    /**
     * A download finished — show an interstitial on every 2nd one (provided the app is in
     * the foreground and an ad is ready). Otherwise skip quietly and, if the *next* completion
     * is a show slot, fetch for it.
     */
    fun onDownloadComplete(activity: Activity) {
        if (!ENABLED || suppressed(activity)) return
        completedCount++
        val ad = takeShowableAd()
        if (ad == null) {
            maybePreload(activity)
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {}
            override fun onAdFailedToShowFullScreenContent(e: AdError) {}
        }
        markFullScreenShown()
        ad.show(activity)
    }

    /**
     * The share sheet's "Open app" — the ONE moment in the share flow where a full-screen ad is
     * legitimate, because the user chose to navigate *into* the app, which is a break between
     * pages of app content.
     *
     * Everything else in that flow is explicitly disallowed and must stay that way:
     *   · the sheet appearing            = app load          ("Do not place interstitial ads on
     *                                                          app load")
     *   · the download finishing         = the user did not act ("show unexpectedly, typically
     *                                                          when the user has chosen to do
     *                                                          something else")
     *   · opening the saved file         = leaving the app   (exit)
     *
     * `completedCount` is deliberately SHARED with onDownloadComplete, so the "no more than one
     * interstitial after every two user actions" limit holds across both entry points instead of
     * each keeping its own count.
     *
     * Navigation is never delayed for an ad: with nothing ready this calls `then` immediately.
     */
    fun onEnterAppFromShare(activity: Activity, then: () -> Unit) {
        if (!ENABLED || suppressed(activity)) { then(); return }
        completedCount++
        val ad = takeShowableAd()
        if (ad == null) {
            maybePreload(activity)
            then()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { then() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { then() }
        }
        markFullScreenShown()
        ad.show(activity)
    }
}

/**
 * Home ke bottom me banner strip. Ads OFF ho to kuch render nahi hota.
 *
 * ⚠️ WHY THIS IS NOT A THREE-LINE COMPOSABLE ANY MORE — measured in the AdMob console,
 * 1–7 Sep 2026:
 *
 *     41 requests → match rate 41.46%      (and 43 → 44.19%, 42 → 42.86%)
 *
 * On every day with real volume, more than half the requests came back with no bid at all,
 * while every quiet day sat at 100%. The bid columns rule out a fill problem on our side:
 * `Win rate` was **100%** on every single row and `Bids in auction` equalled
 * `Matched requests` — whenever a bid arrived we won it. The shortage was of bids, which is
 * what a narrow demand pool looks like.
 *
 * The old version also called `loadAd()` exactly once, inside the AndroidView factory, with no
 * listener attached. So the FIRST no-fill left the slot **blank until the composable was
 * destroyed and rebuilt** — in practice, until the app was reopened. Google said "nothing right
 * now", and nothing ever asked again.
 *
 * Four things this now does that it did not (ported from Riplox IG, where it is already live):
 *  1. **Retries on failure**, backing off 5s → 10s → 20s → 40s → 60s and holding there. The
 *     ceiling matches AdMob's own default auto-refresh rate, so a filled banner and a retrying
 *     one make the same number of requests — this is not extra load, and it is not abusive.
 *  2. **Adaptive size** instead of a fixed 320×50. The fixed size draws from the smallest
 *     demand pool there is, so it no-fills more often AND earns less per impression. Adaptive
 *     anchored is what Google recommends for exactly this slot.
 *  3. **Destroys the AdView** when the slot goes away. Every share used to leak one.
 *  4. **Pauses with the lifecycle.** A banner left refreshing behind a backgrounded app burns
 *     impressions nobody sees, which drags the whole unit's viewability — and its eCPM — down.
 */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    if (!Ads.ENABLED) return
    val context = LocalContext.current
    // The earned ad-free window silences this slot completely — no AdView is even created,
    // so nothing is requested and nothing is billed against viewability.
    if (Ads.suppressed(context)) return
    val lifecycleOwner = LocalLifecycleOwner.current
    val widthDp = LocalConfiguration.current.screenWidthDp

    val adSize = remember(widthDp) {
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
    }

    // ONE AdView for the life of this slot. Recreating it on recomposition would fire a fresh
    // ad request every time the screen changed, which is how you get throttled.
    val adView = remember {
        AdView(context).apply {
            setAdSize(adSize)
            adUnitId = Ads.BANNER_ID
        }
    }

    // `attempt` drives the backoff; `tick` is what actually re-triggers a load. They are
    // separate because a successful load resets `attempt` to 0, and that alone must NOT count
    // as a reason to request another ad.
    var attempt by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(adView) {
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() { attempt = 0 }
            override fun onAdFailedToLoad(error: LoadAdError) {
                // Code 3 is NO_FILL and is by far the common one. Everything else (network,
                // internal error) is retried the same way — none of them are permanent, and a
                // banner that gives up is indistinguishable to the user from a broken app.
                attempt += 1
                tick += 1
            }
        }
        onDispose {
            adView.adListener = object : AdListener() {}
            adView.destroy()
        }
    }

    LaunchedEffect(tick) {
        if (attempt > 0) {
            val step = (attempt - 1).coerceAtMost(3)
            kotlinx.coroutines.delay(minOf(60_000L, 5_000L shl step))
        }
        adView.loadAd(AdRequest.Builder().build())
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> adView.pause()
                Lifecycle.Event.ON_RESUME -> adView.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(
        modifier = modifier.fillMaxWidth().height(adSize.height.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(factory = { adView })
    }
}
