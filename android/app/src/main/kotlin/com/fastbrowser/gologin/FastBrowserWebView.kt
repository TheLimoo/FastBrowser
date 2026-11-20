package com.fastbrowser.gologin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.ByteArrayInputStream

/**
 * The native WebView host — a real `android.webkit.WebView` per tab, driven
 * from Dart over a MethodChannel + EventChannel.
 *
 * Why not `webview_flutter`: Flutter's plugin has no API for per-profile
 * WebViews (AndroidX MULTI_PROFILE, separate cookie jars), the
 * ProxyController proxy override, document-start JavaScript injection,
 * download interception, find-in-page, text zoom or algorithmic darkening.
 * A pure-plugin rewrite would silently share one cookie jar across all
 * profiles. This file is a direct mirror of the v1.0.7
 * WebViewProfileEngine.kt — same settings, same profile isolation, same
 * ad-block rule — with Dart owning the script text, the blocklist and all
 * state, and the download running in Dart (ProxyDownloader port) instead
 * of here.
 *
 * Creation params (from Dart's WebViewConfig.toCreationParams):
 *   profileId, userAgent, blockWebRtc, canvasNoise, timezone, language,
 *   desktopMode, textScale, blockImages, hardwareAcceleration, forceDark,
 *   blockThirdPartyCookies, adBlockEnabled, adBlockDomains, antiLeakScript,
 *   initialUrl.
 */
class FastBrowserWebViewFactory(
    private val messenger: BinaryMessenger,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        @Suppress("UNCHECKED_CAST")
        val params = (args as? Map<String, Any?>) ?: emptyMap()
        return FastBrowserWebView(context, messenger, viewId, params)
    }
}

class FastBrowserWebView(
    context: Context,
    messenger: BinaryMessenger,
    private val viewId: Int,
    private val params: Map<String, Any?>,
) : PlatformView {

    private val webView: WebView = WebView(context)
    private val methodChannel = MethodChannel(messenger, "fastbrowser/webview_$viewId")
    private val eventChannel = EventChannel(messenger, "fastbrowser/webview_$viewId/events")
    private var eventSink: EventChannel.EventSink? = null

    private val profileId: String = params["profileId"] as? String ?: "default_profile"
    private val adBlockEnabled: Boolean = params["adBlockEnabled"] as? Boolean ?: true
    private val adBlockDomains: Set<String> =
        (params["adBlockDomains"] as? List<*>)?.mapNotNull { it as? String }?.toSet()
            ?: emptySet()

    init {
        methodChannel.setMethodCallHandler(::onMethodCall)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
        configure()
        val initialUrl = params["initialUrl"] as? String ?: ""
        if (initialUrl.isNotEmpty()) webView.loadUrl(initialUrl)
    }

    override fun getView(): View = webView

    override fun dispose() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        try {
            webView.stopLoading()
        } catch (_: Exception) {
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
    }

    private fun emit(event: Map<String, Any?>) {
        try {
            eventSink?.success(event)
        } catch (_: Exception) {
        }
    }

    // ----- configuration (port of WebViewProfileEngine.configureWebView) ---

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        // 1. Multi-Profile Storage Isolation (cookies, localStorage, cache
        // per profile). Must be set before any URL loads.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(webView, profileId)
        }

        // Match the layered dark theme's near-black background.
        webView.setBackgroundColor(Color.parseColor("#0A0C10"))

        val settings = webView.settings
        val textScale = (params["textScale"] as? Number)?.toFloat() ?: 1.0f
        val blockImages = params["blockImages"] as? Boolean ?: false
        val hardwareAcceleration = params["hardwareAcceleration"] as? Boolean ?: true
        val forceDark = params["forceDark"] as? Boolean ?: false
        val blockThirdPartyCookies = params["blockThirdPartyCookies"] as? Boolean ?: false

        webView.setLayerType(
            if (hardwareAcceleration) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE,
            null,
        )
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView, !blockThirdPartyCookies,
        )

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = !blockImages
        settings.blockNetworkImage = blockImages
        settings.textZoom = (textScale * 100f).toInt().coerceIn(50, 200)
        settings.userAgentString = params["userAgent"] as? String ?: ""

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, forceDark)
        }

        // Document-start anti-leak script (text built in Dart).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = params["antiLeakScript"] as? String ?: ""
            if (script.isNotEmpty()) {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                if (adBlockEnabled && !request.isForMainFrame) {
                    val host = url.host?.lowercase() ?: return null
                    if (isBlockedDomain(host)) {
                        return WebResourceResponse(
                            "text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(
                view: WebView?, url: String?, favicon: android.graphics.Bitmap?,
            ) {
                super.onPageStarted(view, url, favicon)
                if (url != null) {
                    emit(mapOf("type" to "pageStarted", "url" to url))
                    emitNavState()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    emit(
                        mapOf(
                            "type" to "pageFinished",
                            "url" to url,
                            "title" to (view?.title ?: url),
                        ),
                    )
                    emitNavState()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && error != null) {
                    emit(
                        mapOf(
                            "type" to "error",
                            "message" to (error.description?.toString() ?: "Connection failed"),
                        ),
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                emit(mapOf("type" to "progress", "value" to newProgress))
            }
        }

        // Downloads go to Dart (through the local proxy bridge with the
        // profile's cookies) — never Android's DownloadManager, which
        // downloads DIRECTLY and leaks the real IP.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            emit(
                mapOf(
                    "type" to "download",
                    "url" to url,
                    "userAgent" to userAgent,
                    "contentDisposition" to contentDisposition,
                    "mimeType" to mimeType,
                    "fileName" to URLUtil.guessFileName(url, contentDisposition, mimeType),
                ),
            )
        }
    }

    private fun emitNavState() {
        emit(
            mapOf(
                "type" to "state",
                "canGoBack" to webView.canGoBack(),
                "canGoForward" to webView.canGoForward(),
            ),
        )
    }

    /** Port of AdBlockFilter.isBlockedDomain (same suffix matching). */
    private fun isBlockedDomain(host: String): Boolean {
        if (adBlockDomains.contains(host)) return true
        var dotIdx = host.indexOf('.')
        while (dotIdx != -1) {
            if (adBlockDomains.contains(host.substring(dotIdx + 1))) return true
            dotIdx = host.indexOf('.', dotIdx + 1)
        }
        return false
    }

    // ----- Dart -> native calls ---------------------------------------------

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "loadUrl" -> {
                val url = call.argument<String>("url") ?: ""
                if (url.isNotEmpty()) webView.loadUrl(url)
                result.success(null)
            }
            "reload" -> {
                webView.reload()
                result.success(null)
            }
            "stopLoading" -> {
                webView.stopLoading()
                result.success(null)
            }
            "goBack" -> {
                if (webView.canGoBack()) webView.goBack()
                result.success(null)
            }
            "goForward" -> {
                if (webView.canGoForward()) webView.goForward()
                result.success(null)
            }
            "findAll" -> {
                val query = call.argument<String>("query") ?: ""
                if (query.isNotEmpty()) webView.findAllAsync(query)
                result.success(null)
            }
            "findNext" -> {
                webView.findNext(call.argument<Boolean>("forward") ?: true)
                result.success(null)
            }
            "clearMatches" -> {
                webView.clearMatches()
                result.success(null)
            }
            "applySettings" -> {
                applySettings(call)
                result.success(null)
            }
            "getCookie" -> {
                val url = call.argument<String>("url") ?: ""
                result.success(profileCookie(url))
            }
            "destroy" -> {
                dispose()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    /** Port of WebViewProfileEngine.applyRuntimeSettings + UA swap. */
    private fun applySettings(call: MethodCall) {
        val settings = webView.settings
        (call.argument<String>("userAgent"))?.let { settings.userAgentString = it }
        val textScale = (call.argument<Double>("textScale") ?: 1.0).toFloat()
        settings.textZoom = (textScale * 100f).toInt().coerceIn(50, 200)
        val blockImages = call.argument<Boolean>("blockImages") ?: false
        settings.loadsImagesAutomatically = !blockImages
        settings.blockNetworkImage = blockImages
        val hardwareAcceleration = call.argument<Boolean>("hardwareAcceleration") ?: true
        webView.setLayerType(
            if (hardwareAcceleration) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE,
            null,
        )
        val blockThirdPartyCookies = call.argument<Boolean>("blockThirdPartyCookies") ?: false
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView, !blockThirdPartyCookies,
        )
        val forceDark = call.argument<Boolean>("forceDark") ?: false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, forceDark)
        }
    }

    /** The CookieManager for this profile's store (port of profileCookieManager). */
    private fun profileCookie(url: String): String? {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                val manager = androidx.webkit.ProfileStore.getInstance()
                    .getProfile(profileId)?.cookieManager
                if (manager != null) return manager.getCookie(url)
            } catch (_: Exception) {
            }
        }
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }
    }
}
