package com.xniperbuilds.downloader

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdMob — bottom banner (home) + interstitial (on every 2nd completed download).
 *
 * The IDs now live in **build.gradle.kts** (BuildConfig + manifest placeholder):
 *   release build → the real ids
 *   qa build      → Google's TEST ids (assembleQa)
 * ⚠️ Always test on your own phone via **assembleQa** (the `Riplox TT QA` app) — clicking
 *    your own real ads counts as AdMob invalid traffic / a policy violation.
 */
object Ads {
    const val ENABLED = true

    // Supplied by the build type — no more swapping ids by hand
    val BANNER_ID: String = BuildConfig.AD_BANNER_ID
    val INTERSTITIAL_ID: String = BuildConfig.AD_INTERSTITIAL_ID

    // How many completed downloads between full-screen ads
    private const val SHOW_EVERY_N = 2

    /**
     * A hard floor between two full-screen ads, on top of the every-2 rule.
     *
     * ⚠️ The every-2 counter alone is NOT enough once a batch of links can be queued at once:
     * five downloads finishing within a few seconds of each other would fire two interstitials
     * back to back, and AdMob disallows exactly that ("Placing an interstitial ad immediately
     * after another interstitial ad was shown to and closed by the user"). One timestamp,
     * shared by every full-screen entry point, is what actually prevents it.
     */
    private const val MIN_GAP_MS = 60_000L

    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var completedCount = 0
    private var lastFullScreenAt = 0L

    private fun canShowFullScreen(): Boolean =
        android.os.SystemClock.elapsedRealtime() - lastFullScreenAt >= MIN_GAP_MS

    private fun markFullScreenShown() {
        lastFullScreenAt = android.os.SystemClock.elapsedRealtime()
    }

    fun preloadInterstitial(ctx: Context) {
        if (!ENABLED || interstitial != null || loading) return
        loading = true
        InterstitialAd.load(
            ctx, INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad; loading = false }
                override fun onAdFailedToLoad(e: LoadAdError) { interstitial = null; loading = false }
            }
        )
    }

    /**
     * A download finished — show an interstitial on every 2nd one (provided the app is in
     * the foreground and an ad is ready). Otherwise skip quietly and preload for next time.
     */
    fun onDownloadComplete(activity: Activity) {
        if (!ENABLED) return
        completedCount++
        val ad = interstitial
        if (completedCount % SHOW_EVERY_N != 0 || ad == null || !canShowFullScreen()) {
            preloadInterstitial(activity)
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; preloadInterstitial(activity) }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { interstitial = null; preloadInterstitial(activity) }
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
        if (!ENABLED) { then(); return }
        completedCount++
        val ad = interstitial
        if (completedCount % SHOW_EVERY_N != 0 || ad == null || !canShowFullScreen()) {
            preloadInterstitial(activity)
            then()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; then() }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { interstitial = null; then() }
        }
        markFullScreenShown()
        ad.show(activity)
    }
}

/** Home ke bottom me chhoti banner strip. Ads OFF ho to kuch render nahi hota. */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    if (!Ads.ENABLED) return
    Box(modifier = modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = Ads.BANNER_ID
                loadAd(AdRequest.Builder().build())
            }
        })
    }
}
