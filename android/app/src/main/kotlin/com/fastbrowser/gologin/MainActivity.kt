package com.fastbrowser.gologin

import android.os.Bundle
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

/**
 * MainActivity — registers the native WebView platform view + the
 * `fastbrowser/proxy` and `fastbrowser/native` channels, installs the
 * native-side crash handler (same `last_crash.txt` format as Dart's
 * CrashReporter, shown in Settings → Diagnostics), and deletes the
 * incognito profile on destroy as a second net for the Dart exit wipe.
 */
class MainActivity : FlutterActivity() {

    private lateinit var nativeChannels: NativeChannels

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "fastbrowser/webview",
            FastBrowserWebViewFactory(messenger),
        )
        nativeChannels = NativeChannels(applicationContext)
        MethodChannel(messenger, "fastbrowser/proxy").setMethodCallHandler { call, result ->
            nativeChannels.handleProxy(call, result)
        }
        MethodChannel(messenger, "fastbrowser/native").setMethodCallHandler { call, result ->
            nativeChannels.handleNative(call, result)
        }
    }

    override fun onDestroy() {
        // Second net for the Dart exit wipe: the incognito profile's data
        // must not survive the app closing.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().deleteProfile("incognito")
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /**
     * Native-side crashes (e.g. inside the WebView host) — Dart's
     * CrashReporter cannot see them. Same file and line format as the
     * Dart reporter so Settings → Diagnostics shows either one.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Same directory as Dart's getApplicationDocumentsDirectory()
                // (path_provider: context.getDir("flutter", MODE_PRIVATE)),
                // so Dart's CrashReporter reads this file back.
                val dir = getDir("flutter", MODE_PRIVATE)
                val file = File(dir, "last_crash.txt")
                val text = buildString {
                    appendLine(System.currentTimeMillis())
                    appendLine(throwable.javaClass.simpleName)
                    appendLine(throwable.message ?: "")
                    appendLine(
                        throwable.stackTrace
                            .take(25)
                            .joinToString("\n") { it.toString() },
                    )
                }
                file.writeText(text)
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
