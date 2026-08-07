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

    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var completedCount = 0

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
        if (completedCount % SHOW_EVERY_N != 0 || ad == null) {
            preloadInterstitial(activity)
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { interstitial = null; preloadInterstitial(activity) }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { interstitial = null; preloadInterstitial(activity) }
        }
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
