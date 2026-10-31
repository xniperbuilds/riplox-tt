package com.xniperbuilds.downloader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.lifecycle.Lifecycle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * The app-open ad (v1.3.0): a short brand splash when the user opens Riplox TT, with the ad
 * shown over it.
 *
 * Google's rules for this format, and where each one is kept:
 *  · *"Integrate app open ads into your splash or loading screens"* and *"should appear before the
 *    user can interact with the app's content"* → the splash covers Home and swallows touches
 *    until the ad is shown or abandoned.
 *  · *"Don't display app open ads on top of other ads (for example, content with banner ads)"* →
 *    Home's banner is taken out of the composition while the splash is up.
 *  · *"should only appear when the user opens or switches back to an app"* → only on a real open
 *    of MainActivity (see [onActivityStarted]); never on the share sheet, never when the share
 *    sheet's "Open app" leads in, never coming back from one of our own screens.
 *  · first ad only *"after your users have used your app a few times"*, the hourly cap, the 60s
 *    floor to other full-screen ads, the ad-free window, the ad-click return → [AdGate.shouldShowAppOpen].
 *
 * The ad is fetched only once an open has passed the gate, and an ad that arrives after the
 * splash has given up is thrown away — by then the user can touch the app, and showing it
 * would be exactly the "surprised by an out of context ad" case Google warns about.
 */
object AppOpen {

    private const val TAG = "RiploxTT.AppOpen"

    /** The longest the splash waits for an ad before handing the app over without one. */
    const val SPLASH_MAX_MS = 3_500L

    /**
     * How long the app must have been out of sight before coming back counts as an open.
     * Shorter trips — a glance at the saved video, the share chooser, Play's update screen —
     * are not the user opening the app.
     */
    const val BACKGROUND_MIN_MS = 30_000L

    /** Started activities of this process right now (ours plus the SDK's ad activity). */
    private var started = 0

    /** Elapsed-realtime at which the last activity stopped. 0 = never, in this process. */
    private var stoppedAt = 0L

    /**
     * An open waiting for MainActivity to collect it: how long the app had been away, or
     * [Long.MAX_VALUE] for a fresh process. -1 = none.
     */
    private var pendingOpen = -1L

    /**
     * Fed by XniperApp's lifecycle callbacks, which run inside `Activity.onStart()` — so by the
     * time MainActivity's own onStart continues, [pendingOpen] is already set.
     *
     * Only MainActivity starting from NOTHING counts. The share sheet bringing the process to the
     * foreground is not an open, and MainActivity starting while something of ours is already on
     * screen (the share sheet's "Open app", a notification tapped inside the app) is not one either.
     */
    fun onActivityStarted(activity: Activity) {
        if (started == 0 && activity is MainActivity) {
            pendingOpen = if (stoppedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - stoppedAt
        }
        started++
    }

    fun onActivityStopped() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) stoppedAt = SystemClock.elapsedRealtime()
    }

    /** Read and clear: is this MainActivity start an open of the app? */
    fun takeOpenEvent(): Boolean {
        val away = pendingOpen
        pendingOpen = -1L
        return away >= BACKGROUND_MIN_MS
    }

    /**
     * An open happened. Count it, and if the gate allows, raise the splash and try for an ad.
     * [cover] drives MainActivity's splash; it is always lowered again — when the ad closes,
     * fails, or [SPLASH_MAX_MS] runs out, whichever comes first.
     */
    fun onOpen(activity: ComponentActivity, cover: MutableState<Boolean>) {
        val opens = Prefs.bumpAppOpens(activity)
        if (!Ads.ENABLED || !Ads.appOpenConfigured) return
        val allowed = AdGate.shouldShowAppOpen(
            opens = opens,
            lastAppOpenAt = Prefs.lastAppOpenAt(activity),
            nowWall = System.currentTimeMillis(),
            lastFullScreenAt = Ads.lastFullScreenAt,
            nowElapsed = SystemClock.elapsedRealtime(),
            adFree = Ads.suppressed(activity),
            clickedAway = Ads.takeClickedAway(),
        )
        // One line per real open — the only way to tell "the gate said no" apart from "this was
        // never counted as an open" when reading a phone's log.
        Log.i(TAG, "open #$opens: ${if (allowed) "eligible" else "skipped by the gate"}")
        if (!allowed) return
        // EEA/UK/Swiss before the consent form has been answered: no request at all.
        if (!Consent.canRequestAds(activity)) return

        cover.value = true
        Consent.startAds(activity)
        val loadStarted = SystemClock.elapsedRealtime()

        val main = Handler(Looper.getMainLooper())
        var settled = false
        val giveUp = Runnable {
            if (!settled) {
                settled = true
                Log.i(TAG, "no ad within ${SPLASH_MAX_MS}ms — opening without one")
                cover.value = false
            }
        }
        main.postDelayed(giveUp, SPLASH_MAX_MS)

        AppOpenAd.load(
            activity, Ads.APP_OPEN_ID, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    // Too late: the splash already gave up and the user can touch the app.
                    if (settled) return
                    settled = true
                    main.removeCallbacks(giveUp)
                    Log.i(TAG, "loaded +${SystemClock.elapsedRealtime() - loadStarted}ms")
                    // The user left during the splash — never show an ad to an app in the background.
                    if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        cover.value = false
                        return
                    }
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdShowedFullScreenContent() {
                            Prefs.setLastAppOpenAt(activity, System.currentTimeMillis())
                            Ads.markFullScreenShown()
                        }

                        override fun onAdDismissedFullScreenContent() {
                            cover.value = false
                        }

                        override fun onAdFailedToShowFullScreenContent(e: AdError) {
                            Log.i(TAG, "failed to show: ${e.code} ${e.message}")
                            cover.value = false
                        }

                        override fun onAdClicked() {
                            Ads.noteAdClick()
                        }
                    }
                    ad.show(activity)
                }

                override fun onAdFailedToLoad(e: LoadAdError) {
                    if (settled) return
                    settled = true
                    main.removeCallbacks(giveUp)
                    Log.i(TAG, "failed code=${e.code} +${SystemClock.elapsedRealtime() - loadStarted}ms")
                    cover.value = false
                }
            }
        )
    }
}
