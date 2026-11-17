package com.fastbrowser.gologin

import android.app.Application
import com.fastbrowser.gologin.network.LocalProxyBridge

/**
 * Starts the local loopback proxy bridge before any WebView is created.
 * The bridge is a process-wide singleton; the ViewModel hot-swaps its upstream
 * server atomically via updateUpstreamServer().
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        localPort = bridge.start()
    }

    companion object {
        val bridge = LocalProxyBridge()

        @Volatile
        var localPort: Int = 0
            private set
    }
}
