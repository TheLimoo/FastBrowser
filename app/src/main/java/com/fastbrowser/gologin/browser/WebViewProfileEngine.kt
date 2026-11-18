package com.fastbrowser.gologin.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.fastbrowser.gologin.model.AppSettings
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.model.UserAgentPreset
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor

object WebViewProfileEngine {

    private val directExecutor = Executor { runnable -> runnable.run() }

    /**
     * Binds WebView globally to our local 127.0.0.1 ProxyBridge.
     * Because the bridge routes dynamically to Stockholm or Ohio, this only needs to be
     * called once when the bridge starts.
     */
    fun bindWebViewToLocalBridge(localPort: Int, onApplied: () -> Unit = {}) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onApplied()
            return
        }
        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:$localPort")
            .build()

        ProxyController.getInstance().setProxyOverride(proxyConfig, directExecutor) {
            onApplied()
        }
    }

    /**
     * Configures a WebView instance for maximum speed, isolated profile storage,
     * User-Agent spoofing, and WebRTC IP-leak neutralization.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(
        context: Context,
        webView: WebView,
        profile: BrowserProfile,
        settings: AppSettings,
        timezoneOverride: String = "",
        desktopMode: Boolean = false,
        onPageStarted: (String) -> Unit,
        onPageFinished: (String, String) -> Unit,
        onProgressChanged: (Int) -> Unit = {},
        onReceivedError: (String) -> Unit = {},
        onDownloadStarted: (String) -> Unit = {}
    ) {
        // 1. Multi-Profile Storage Isolation (Cookies, LocalStorage, Cache per profile)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(webView, profile.id)
        }

        // v1.0.5: match the layered dark theme's near-black background
        // (was a hardcoded #0A0A0C) so a page's empty area blends with the
        // app instead of sitting as a different black rectangle.
        webView.setBackgroundColor(Color.parseColor("#0A0C10"))

        // Hardware acceleration toggle (real behavior, not a dead switch)
        webView.setLayerType(
            if (settings.hardwareAcceleration) android.view.View.LAYER_TYPE_HARDWARE
            else android.view.View.LAYER_TYPE_SOFTWARE,
            null
        )

        // Third-party cookie policy
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView, !settings.blockThirdPartyCookies
        )

        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.mediaPlaybackRequiresUserGesture = false
        ws.loadsImagesAutomatically = !settings.blockImagesDataSaver
        ws.blockNetworkImage = settings.blockImagesDataSaver
        ws.textZoom = (settings.textScale * 100f).toInt().coerceIn(50, 200)

        // 2. User-Agent Preset / Custom UA (desktop mode overrides with the desktop preset)
        ws.userAgentString = resolveUserAgent(profile, desktopMode)

        // 3. Algorithmic Dark Mode on web content
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, settings.forceDarkWebPages)
        }

        // 4. Document-Start Anti-Leak & Timezone Injection Script
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val shieldScript = buildAntiLeakScript(
                blockWebRtc = settings.blockWebRtcGlobal || profile.blockWebRtcLeaks,
                canvasNoise = profile.canvasNoiseEnabled,
                timezone = timezoneOverride
            )
            WebViewCompat.addDocumentStartJavaScript(webView, shieldScript, setOf("*"))
        }

        // 5. Fast Request Interceptor (Ad/Tracker Blocker) + progress + error reporting
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (settings.adAndTrackerBlocker && !request.isForMainFrame) {
                    val host = url.host?.lowercase() ?: return null
                    if (AdBlockFilter.isBlockedDomain(host)) {
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) onPageStarted(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    onPageFinished(url, view?.title ?: url)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && error != null) {
                    onReceivedError(error.description?.toString() ?: "Connection failed")
                }
            }
        }

        // Load progress is a WebChromeClient callback, not a WebViewClient one.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressChanged(newProgress)
            }
        }

        // Real downloads: hand off to Android's DownloadManager (visible in the
        // system Downloads UI), not a fake menu entry.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                onDownloadStarted(fileName)
            } catch (_: Exception) {
                onDownloadStarted("Download failed to start")
            }
        }
    }

    /** Applies a changed text scale / image / dark-mode setting to a live WebView without recreating it. */
    fun applyRuntimeSettings(webView: WebView, settings: AppSettings) {
        val ws = webView.settings
        ws.textZoom = (settings.textScale * 100f).toInt().coerceIn(50, 200)
        ws.loadsImagesAutomatically = !settings.blockImagesDataSaver
        ws.blockNetworkImage = settings.blockImagesDataSaver
        webView.setLayerType(
            if (settings.hardwareAcceleration) android.view.View.LAYER_TYPE_HARDWARE
            else android.view.View.LAYER_TYPE_SOFTWARE,
            null
        )
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView, !settings.blockThirdPartyCookies
        )
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, settings.forceDarkWebPages)
        }
    }

    fun resolveUserAgent(profile: BrowserProfile, desktopMode: Boolean): String {
        if (desktopMode) return UserAgentPreset.DESKTOP_WINDOWS.uaString
        return profile.customUserAgent.ifBlank { profile.userAgentPreset.uaString }
    }

    fun wipeProfileData(profileId: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().deleteProfile(profileId)
            } catch (_: Exception) {
            }
        }
        try {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }
    }

    private fun buildAntiLeakScript(
        blockWebRtc: Boolean,
        canvasNoise: Boolean,
        timezone: String
    ): String = buildString {
        append("(function() {\n")
        if (blockWebRtc) {
            // Strip local/STUN ICE candidates and disable non-proxied WebRTC peer connections
            append("""
                const origPC = window.RTCPeerConnection || window.webkitRTCPeerConnection;
                if (origPC) {
                    const PatchedPC = function(config, constraints) {
                        const safeConfig = Object.assign({}, config, { iceServers: [], iceTransportPolicy: 'relay' });
                        const pc = new origPC(safeConfig, constraints);
                        const origCreateOffer = pc.createOffer.bind(pc);
                        pc.createOffer = function(opts) {
                            return origCreateOffer(opts);
                        };
                        return pc;
                    };
                    PatchedPC.prototype = origPC.prototype;
                    window.RTCPeerConnection = PatchedPC;
                    if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = PatchedPC;
                }
                if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
                    navigator.mediaDevices.enumerateDevices = () => Promise.resolve([]);
                }
            """.trimIndent())
            append("\n")
        }
        if (timezone.isNotBlank()) {
            append("""
                try {
                    const origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
                    Intl.DateTimeFormat.prototype.resolvedOptions = function() {
                        const res = origResolved.apply(this, arguments);
                        res.timeZone = "$timezone";
                        return res;
                    };
                } catch(e) {}
            """.trimIndent())
            append("\n")
        }
        if (canvasNoise) {
            append("""
                try {
                    const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                    HTMLCanvasElement.prototype.toDataURL = function() {
                        const ctx = this.getContext('2d');
                        if (ctx) {
                            const shift = (Math.floor(Math.random() * 2) - 1);
                            ctx.fillStyle = 'rgba(255,255,255,0.01)';
                            ctx.fillRect(0, 0, 1, 1);
                        }
                        return origToDataURL.apply(this, arguments);
                    };
                } catch(e) {}
            """.trimIndent())
            append("\n")
        }
        append("})();")
    }
}
