package com.fastbrowser.gologin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.fastbrowser.gologin.browser.WebViewProfileEngine
import com.fastbrowser.gologin.ui.BrowserScreen
import com.fastbrowser.gologin.ui.BrowserViewModel
import com.fastbrowser.gologin.ui.OnboardingScreen
import com.fastbrowser.gologin.ui.SettingsScreen
import com.fastbrowser.gologin.ui.theme.FastBrowserTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: BrowserViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[BrowserViewModel::class.java]

        // Bind WebView to the local bridge BEFORE the first WebView exists.
        // Content is only shown once the proxy override is applied.
        var proxyReady by mutableStateOf(false)
        WebViewProfileEngine.bindWebViewToLocalBridge(App.localPort) {
            proxyReady = true
        }

        setContent {
            val settings by viewModel.settings.collectAsState()
            var showSettings by remember { mutableStateOf(false) }
            FastBrowserTheme(amoledBlack = settings.amoledBlackTheme) {
                if (!proxyReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (!settings.onboardingDone) {
                    OnboardingScreen(viewModel = viewModel)
                } else if (showSettings) {
                    SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
                } else {
                    BrowserScreen(
                        viewModel = viewModel,
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Per-profile "clear cookies on exit" is honored here.
        if (::viewModel.isInitialized) viewModel.clearCookiesOnExitIfNeeded()
        super.onDestroy()
    }
}
