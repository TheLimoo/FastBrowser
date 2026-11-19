package com.fastbrowser.gologin.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WebViewProfileEngine {

    private val directExecutor = Executor { runnable -> runnable.run() }

    /** Runs ProxyDownloader jobs — downloads must not block the UI thread. */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Binds WebView globally to our local 127.0.0.1 ProxyBridge.
     * Because the bridge routes dynamically to Stockholm or Ohio, this only needs to be
     * called once when the bridge starts.
     *
     * v1.0.7 — FAIL CLOSED: if WebView cannot apply a proxy override
     * (PROXY_OVERRIDE unsupported) and the kill-switch is on, onApplied is
     * NOT called and onProxyUnsupported fires instead — the app must not
     * silently browse directly and leak the real IP. With the kill-switch
     * off the user explicitly allows direct traffic, so onApplied still
     * fires (direct browsing). MainActivity shows a blocked screen in the
     * fail-closed case, with a one-tap escape (turn the kill-switch off).
     */
    fun bindWebViewToLocalBridge(
        localPort: Int,
        killSwitchEnabled: Boolean = true,
        onApplied: () -> Unit = {},
        onProxyUnsupported: () -> Unit = {}
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (killSwitchEnabled) onProxyUnsupported() else onApplied()
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
        /** v1.0.7: the local bridge port downloads route through (ProxyDownloader). Required — no default, so a caller cannot silently forget it. */
        localProxyPort: Int,
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

        // 4. Document-Start Anti-Leak & Timezone Injection Script (v1.0.7:
        // the inline string building is replaced by AntiLeakScriptBuilder.
        // The app's UserAgentPreset names already match the builder's
        // expected names (DESKTOP_WINDOWS / DESKTOP_MAC / IPHONE_SAFARI /
        // ANDROID_CHROME), so the enum name is passed straight through;
        // desktop mode overrides with the desktop preset, as before.)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val shieldScript = AntiLeakScriptBuilder.build(
                profileId = profile.id,
                uaPresetName = if (desktopMode) UserAgentPreset.DESKTOP_WINDOWS.name
                else profile.userAgentPreset.name,
                canvasNoiseEnabled = profile.canvasNoiseEnabled,
                blockWebRtc = settings.blockWebRtcGlobal || profile.blockWebRtcLeaks,
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

        // Real downloads (v1.0.7): fetched through the LOCAL proxy bridge
        // (ProxyDownloader) with the profile's cookies — NOT Android's
        // DownloadManager, which downloads DIRECTLY (bypassing the proxy
        // and leaking the real IP) and without the profile's cookies.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val cookieManager = profileCookieManager(profile)
            downloadScope.launch {
                val result = ProxyDownloader.download(
                    context = context,
                    url = url,
                    userAgent = userAgent,
                    fileName = fileName,
                    mimeType = mimetype,
                    localProxyPort = localProxyPort,
                    profileCookieManager = cookieManager
                )
                result.fold(
                    onSuccess = { savedName -> onDownloadStarted(savedName) },
                    onFailure = { onDownloadStarted("Download failed to start") }
                )
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

    /**
     * The CookieManager for this profile's cookie store (v1.0.7): downloads
     * must send the same cookies the WebView sent, and with MULTI_PROFILE
     * those live in the profile's store, not the default one. Falls back to
     * the default manager when multi-profile is unsupported, the profile
     * does not exist yet, or the lookup fails.
     */
    private fun profileCookieManager(profile: BrowserProfile): CookieManager {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().getProfile(profile.id)?.cookieManager?.let { return it }
            } catch (_: Exception) {
            }
        }
        return CookieManager.getInstance()
    }
}
