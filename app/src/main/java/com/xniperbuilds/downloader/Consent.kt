package com.xniperbuilds.downloader

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The privacy form, via Google's User Messaging Platform.
 *
 * WHY THIS EXISTS — it is a revenue fix, not a checkbox. Since **16 January 2024** AdMob has
 * required a Google-certified consent management platform in order to serve PERSONALISED ads
 * to users in the EEA, the UK and Switzerland. Google's own wording: traffic from a
 * non-certified CMP "may be eligible for non-personalized ads or limited ads". Riplox TT
 * shipped with no consent flow at all, so every European install has been monetising at the
 * floor — silently, with nothing in the console that looks like an error.
 *
 * WHAT IT DOES NOTHING ABOUT: users outside those regions. `canRequestAds()` is true for them
 * on the very first call and no form is ever shown, so this costs the rest of the world one
 * cheap local check. That matters here: Riplox TT's traffic is ~92% Pakistan (measured 1–7 Sep
 * 2026), so this change is about not leaving the European tail on the floor, not about the
 * bulk of the users.
 *
 * ⚠️ The app side is only half of it. The message itself is authored in the AdMob console
 * (Privacy & messaging → GDPR) and must be PUBLISHED there, or `loadAndShowConsentFormIfRequired`
 * has nothing to show and quietly does nothing at all.
 *
 * Ported from Riplox IG, where this shipped in v1.1.0. Nothing here is IG-specific.
 */
object Consent {

    private const val TAG = "RiploxTT"

    /** MobileAds.initialize is expensive and must happen exactly once per process. */
    private val adsStarted = AtomicBoolean(false)

    private var info: ConsentInformation? = null

    private fun info(context: Context): ConsentInformation =
        info ?: UserMessagingPlatform.getConsentInformation(context.applicationContext)
            .also { info = it }

    /**
     * May we ask AdMob for an ad yet?
     *
     * Outside the EEA/UK/Switzerland this is true immediately. Inside, it turns true once the
     * user has answered the form — or once UMP decides no form is required. Every ad request in
     * the app goes through this.
     *
     * Deliberately fail-OPEN on an exception: a crash in the consent library must never take
     * the app's whole revenue offline. The SDK still applies its own regional restrictions.
     */
    fun canRequestAds(context: Context): Boolean = try {
        info(context).canRequestAds()
    } catch (e: Exception) {
        Log.w(TAG, "consent state unavailable — allowing the request: ${e.message}")
        true
    }

    /**
     * Called once from each activity's onCreate: refresh the consent state, show the form if one
     * is required, then start the Ads SDK.
     *
     * ⚠️ MobileAds.initialize runs on a background thread. On a real phone doing it on the main
     * thread costs a visible freeze at launch, and this app's first screen is a text field the
     * user wants to paste into.
     *
     * ⚠️ Ads are started from BOTH callbacks, success and failure. An early version of this
     * only started them on success, which meant one flaky network call at launch turned the ads
     * off for the whole session.
     *
     * ⚠️ `onReady` is NOT the place to preload an interstitial. See AdGate — fetching one before
     * a show slot is reachable is what produced a 29% show rate.
     */
    fun gather(activity: Activity, onReady: () -> Unit = {}) {
        if (!Ads.ENABLED) return
        val ci = try {
            info(activity)
        } catch (e: Exception) {
            Log.w(TAG, "UMP unavailable: ${e.message}")
            startAds(activity); onReady(); return
        }
        try {
            ci.requestConsentInfoUpdate(
                activity,
                ConsentRequestParameters.Builder().build(),
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        if (formError != null) {
                            Log.w(TAG, "consent form: ${formError.errorCode} ${formError.message}")
                        }
                        startAds(activity)
                        onReady()
                    }
                },
                { requestError ->
                    Log.w(TAG, "consent update failed: ${requestError.errorCode} ${requestError.message}")
                    startAds(activity)
                    onReady()
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "consent flow threw: ${e.message}")
            startAds(activity)
            onReady()
        }
    }

    /** Start the Ads SDK once, off the main thread. Safe to call from anywhere, any number of times. */
    fun startAds(context: Context) {
        if (!Ads.ENABLED || !adsStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread {
            try {
                MobileAds.initialize(app)
            } catch (e: Exception) {
                Log.w(TAG, "MobileAds init failed: ${e.message}")
            }
        }.start()
    }
}
