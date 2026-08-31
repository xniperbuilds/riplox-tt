package com.xniperbuilds.downloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xniperbuilds.downloader.ui.theme.XniperDownloaderTheme

/**
 * In-app "Connect TikTok" — TikTok opens in a WebView INSIDE the app. The user signs in
 * on TikTok's OWN page and then taps "✓ Connect", at which point that WebView's cookies
 * (a) stay in the same jar TikTokExtractor reads from, and (b) are written to a Netscape
 * cookies.txt (for the yt-dlp fallback).
 *
 * The app NEVER sees or stores the password — the sign-in happens on TikTok's page.
 * Cookies belonging to another app (Chrome/TikTok) are unreachable because of the Android
 * sandbox, which is why the login has to happen here.
 *
 * ⚠️ Logging in does NOT fix the "download never starts" problem (that was TikTok's
 * fingerprint gate — see TikTokExtractor.kt). This is for private / region-locked /
 * age-restricted videos.
 */
/**
 * A WebView that asks the keyboard NOT to use a composing region.
 *
 * ⚠️ THE BUG THIS FIXES, reported from real use: typing `xniper` into TikTok's login field
 * produced `repinx` — every new character landed at the start. The device is en-US (so not an
 * RTL layout issue) and the keyboard is SwiftKey, which keeps the word you are typing in the
 * IME's *composing* region and rewrites it on each keystroke. TikTok's login input is a
 * JS-controlled field that re-sets its value and puts the caret back to 0 on every change, so
 * each rewrite got inserted at the front. Neither side is wrong on its own; together they
 * reverse the word.
 *
 * NO_SUGGESTIONS makes IMEs commit characters directly instead of composing them, which
 * removes the rewrite that the page keeps mishandling. Losing autocorrect inside a login form
 * costs nothing — you do not want it there anyway.
 */
private class NoComposeWebView(context: android.content.Context) : WebView(context) {
    override fun onCreateInputConnection(
        outAttrs: android.view.inputmethod.EditorInfo
    ): android.view.inputmethod.InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = outAttrs.inputType or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = outAttrs.imeOptions or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return ic
    }
}

class CookieLoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val site = intent.getStringExtra("site") ?: "tiktok"
        val startUrl = intent.getStringExtra("url") ?: cookieSiteUrl(site)
        val label = intent.getStringExtra("label") ?: "TikTok"

        // NOTE: the TikTok login used to be forced to LANDSCAPE because the DESKTOP-UA
        // login modal came up blank in portrait. It now uses the mobile UA and goes
        // straight to the email-login page (the desktop one rendered completely white on
        // a real device), and that page is built for portrait — so no forced orientation.

        setContent {
            XniperDownloaderTheme {
                val context = LocalContext.current
                val holder = remember { arrayOfNulls<WebView>(1) }
                var pageProgress by remember { mutableIntStateOf(0) }
                // Live domain — so the user can see for themselves they are on the real site
                var currentHost by remember {
                    mutableStateOf(android.net.Uri.parse(startUrl).host ?: "")
                }

                BackHandler(enabled = true) {
                    val w = holder[0]
                    if (w != null && w.canGoBack()) w.goBack() else finish()
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
                    Column(modifier = Modifier.padding(pad).fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Connect $label · 🔒 $currentHost",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Log in → tap Connect. Password stays on the site.",
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = { holder[0]?.reload() }) { Text("↻") }
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = {
                                // Snapshot first — if no login cookie turns up, RESTORE the
                                // file (showing "connected ✓" off guest cookies would be wrong)
                                val before = try {
                                    if (hasCookies(context)) cookiesFile(context).readText() else null
                                } catch (e: Exception) { null }
                                val n = saveCookiesFromWebView(context, cookieGroupsFor(site, startUrl))
                                val reallyConnected = if (site == "tiktok") {
                                    connectedSites(context).contains(label)
                                } else n > 0
                                if (reallyConnected) {
                                    Toast.makeText(context, "✓ $label connected", Toast.LENGTH_SHORT).show()
                                    setResult(RESULT_OK)
                                    finish()
                                } else {
                                    // rollback — guest cookies save na rahen
                                    try {
                                        if (before == null) cookiesFile(context).delete()
                                        else cookiesFile(context).writeText(before)
                                    } catch (e: Exception) {
                                    }
                                    Toast.makeText(
                                        context,
                                        "Not logged in yet — sign in to $label first, then tap Connect",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }) { Text("✓ Connect") }
                        }

                        // Show the bar while a page loads — so "blank" and "loading" look different
                        if (pageProgress in 1..99) {
                            LinearProgressIndicator(
                                progress = { pageProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            factory = { ctx ->
                                NoComposeWebView(ctx).apply {
                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)
                                    with(settings) {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        loadWithOverviewMode = true
                                        useWideViewPort = true
                                        javaScriptCanOpenWindowsAutomatically = true
                                        // TikTok's login buttons open popup windows — without
                                        // support for them a tap does nothing, or goes blank
                                        setSupportMultipleWindows(true)
                                        // A desktop page on a phone — pinch zoom is needed
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        mediaPlaybackRequiresUserGesture = false
                                        // Every login page is https — no need for mixed content (safer)
                                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        // TikTok = DESKTOP Chrome UA: the desktop login form is the
                                        // simple one, while the mobile page detects an in-app browser
                                        // and pushes "open in app" (the root of the blank screen).
                                        userAgentString = if (useDesktopUa(site)) {
                                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                                                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                                        } else {
                                            try {
                                                WebSettings.getDefaultUserAgent(ctx).replace("; wv", "")
                                            } catch (e: Exception) {
                                                userAgentString
                                            }
                                        }
                                    }
                                    // keep redirects inside the WebView (otherwise it goes blank)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView, request: android.webkit.WebResourceRequest
                                        ): Boolean = false

                                        override fun doUpdateVisitedHistory(
                                            view: WebView, url: String?, isReload: Boolean
                                        ) {
                                            url?.let {
                                                currentHost = android.net.Uri.parse(it).host ?: currentHost
                                            }
                                        }

                                        /**
                                         * ⚠️ THE REVERSED-TYPING FIX. Reported from real use:
                                         * typing `xniper` produced `repinx`.
                                         *
                                         * The first attempt blamed the keyboard (SwiftKey's
                                         * composing region) and asked the IME for
                                         * NO_SUGGESTIONS. It did not help — so the cause is not
                                         * the keyboard. What is left is the page: the login
                                         * field rewrites its own value on every keystroke and
                                         * leaves the caret at position 0, so each new character
                                         * lands in front of the last one.
                                         *
                                         * Rather than guess at TikTok's internals, this fixes
                                         * the symptom directly and generically: after any input
                                         * event, put the caret back at the end. The setTimeout
                                         * matters — it has to run AFTER the page's own handler,
                                         * which is the thing moving it.
                                         */
                                        override fun onPageFinished(view: WebView, url: String?) {
                                            view.evaluateJavascript(
                                                """
                                                (function(){
                                                  if (window.__rxCaretFix) return;
                                                  window.__rxCaretFix = 1;

                                                  // The keyboard capitalises the first letter of
                                                  // a field by default, so an email came out as
                                                  // "Xniper...". Harmless on most sites, wrong
                                                  // on a login form — say so explicitly.
                                                  function tame(el){
                                                    try {
                                                      el.setAttribute('autocapitalize','none');
                                                      el.setAttribute('autocorrect','off');
                                                      el.setAttribute('spellcheck','false');
                                                    } catch (_) {}
                                                  }
                                                  Array.prototype.forEach.call(
                                                    document.querySelectorAll('input,textarea'), tame
                                                  );
                                                  document.addEventListener('focusin', function(e){
                                                    var t = e.target;
                                                    if (t && /^(INPUT|TEXTAREA)$/.test(t.tagName || '')) tame(t);
                                                  }, true);

                                                  document.addEventListener('input', function(e){
                                                    var t = e.target;
                                                    if (!t) return;
                                                    var tag = (t.tagName || '').toUpperCase();
                                                    if (tag !== 'INPUT' && tag !== 'TEXTAREA') return;
                                                    setTimeout(function(){
                                                      try {
                                                        var n = (t.value || '').length;
                                                        if (t.selectionStart === 0 && n > 0) {
                                                          t.setSelectionRange(n, n);
                                                        }
                                                      } catch (_) {}
                                                    }, 0);
                                                  }, true);
                                                })()
                                                """.trimIndent(),
                                                null
                                            )
                                        }

                                        // On heavy pages a dead renderer used to take the whole
                                        // APP down with it — now it is handled:
                                        override fun onRenderProcessGone(
                                            view: WebView, detail: android.webkit.RenderProcessGoneDetail
                                        ): Boolean {
                                            Toast.makeText(
                                                ctx,
                                                "Page crashed — please try again",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            holder[0] = null
                                            try {
                                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                                                view.destroy()
                                            } catch (_: Exception) {
                                            }
                                            finish()
                                            return true
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: android.webkit.WebResourceRequest,
                                            error: android.webkit.WebResourceError
                                        ) {
                                            if (request.isForMainFrame) {
                                                Toast.makeText(
                                                    ctx,
                                                    "Couldn't load the page — check internet and try ↻",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                                            pageProgress = newProgress
                                        }

                                        // A login popup (window.open / target=_blank) → open it
                                        // in the same WebView, or the tap just feels dead
                                        override fun onCreateWindow(
                                            view: WebView,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: android.os.Message
                                        ): Boolean {
                                            val temp = WebView(view.context)
                                            temp.webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    v: WebView,
                                                    request: android.webkit.WebResourceRequest
                                                ): Boolean {
                                                    view.loadUrl(request.url.toString())
                                                    return true
                                                }
                                            }
                                            (resultMsg.obj as WebView.WebViewTransport).webView = temp
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }
                                    loadUrl(startUrl)
                                    holder[0] = this
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
