package com.xniperbuilds.downloader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Earn TikTok's `ttwid` cookie ONCE, using a WebView that is attached to a real window.
 *
 * ============================ WHY THIS EXISTS ============================
 * Measured on a real device (Infinix X6871 / Android 15, 2026-08-30), not reasoned about:
 *
 *  · TikTok answers this app's plain HTTP request with a 1462-byte "Please wait…" WAF
 *    challenge — every time, on every retry.
 *  · It sends NO Set-Cookie with that refusal. The response headers were logged and checked:
 *    there is no cookie to collect, so "retry until you earn a ttwid over HTTP" cannot work
 *    here, however many attempts it is given.
 *  · The WebView route CAN pass the challenge — but the worker's WebView is never attached to
 *    a window, and Chromium throttles the JS timers of a view it considers invisible. Cold, it
 *    burned its full 45-second budget and failed. Once cookies existed it took 4 seconds.
 *
 *  Net effect before this: a NEW user's first downloads failed for minutes. An existing user
 *  never saw it, because their cookie jar had been warm for months — which is exactly the kind
 *  of bug that survives all your own testing and greets every new install.
 *
 * So: let a WebView that IS on screen do the one thing only a browser can do. It costs one
 * page load, once, and afterwards the fast HTTP route works on its own.
 *
 * ⚠️ AIRLOCK IS UNTOUCHED. This does not sit between the share and the queue — the link is
 * already enqueued before this runs, and the download does not wait for it.
 */
object TtCookiePrimer {

    private const val TAG = "RiploxTT"
    private const val GIVE_UP_MS = 60_000L
    private const val POLL_MS = 700L

    @Volatile
    private var running = false

    /** Does the shared cookie jar already hold the cookie TikTok's CDN insists on? */
    fun hasTtwid(): Boolean = try {
        CookieManager.getInstance().getCookie(TT_REFERER)?.contains("ttwid=") == true
    } catch (e: Exception) {
        false
    }

    /**
     * Load tiktok.com in a 1×1 attached WebView until a ttwid appears, then remove it.
     * Safe to call on every activity start: it returns immediately when the jar is already warm
     * or a prime is already in flight.
     *
     * Must be called on the main thread (WebView and CookieManager both require it).
     */
    fun primeIfNeeded(activity: Activity) {
        if (running || hasTtwid()) return
        running = true
        val started = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "cookie primer: starting (no ttwid yet)")

        val web: WebView
        try {
            web = WebView(activity)
        } catch (e: Exception) {
            Log.w(TAG, "cookie primer: no WebView available (${e.message})")
            running = false
            return
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Desktop UA, exactly as the extractor uses — a jar earned under one identity and
            // spent under another is how you get challenged all over again.
            userAgentString = TT_DESKTOP_UA
        }

        // 1×1 and attached ON PURPOSE. Attached is the whole point: an unattached WebView gets
        // its timers throttled, and the challenge script never finishes. One pixel is enough to
        // count as visible, and small enough that nobody sees it.
        try {
            activity.addContentView(web, ViewGroup.LayoutParams(1, 1))
        } catch (e: Exception) {
            Log.w(TAG, "cookie primer: could not attach (${e.message})")
            running = false
            try { web.destroy() } catch (_: Exception) {}
            return
        }

        web.loadUrl(TT_REFERER)

        val handler = Handler(Looper.getMainLooper())
        fun cleanUp(result: String) {
            running = false
            Log.i(TAG, "cookie primer: $result after ${android.os.SystemClock.elapsedRealtime() - started}ms")
            try {
                web.stopLoading()
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            } catch (_: Exception) {
            }
        }

        val poll = object : Runnable {
            override fun run() {
                if (hasTtwid()) {
                    // Hand the jar straight to the extractor's cache. Without this the worker
                    // would have to hop onto the main thread to discover what we just earned —
                    // and that hop is exactly what it cannot rely on.
                    try {
                        CookieManager.getInstance().getCookie(TT_REFERER)?.let { TtCookieCache.put(it) }
                    } catch (_: Exception) {
                    }
                    cleanUp("got ttwid")
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() - started > GIVE_UP_MS) {
                    // Not fatal, and not worth telling the user about: the worker still has its
                    // own WebView route, this just makes it far more likely to be quick.
                    cleanUp("gave up")
                    return
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.postDelayed(poll, POLL_MS)
    }
}
