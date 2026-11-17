package com.fastbrowser.gologin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.fastbrowser.gologin.browser.WebViewProfileEngine
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.ui.BrowserScreen
import com.fastbrowser.gologin.ui.BrowserViewModel
import com.fastbrowser.gologin.ui.HomeScreen
import com.fastbrowser.gologin.ui.OnboardingScreen
import com.fastbrowser.gologin.ui.ProfileEditDialog
import com.fastbrowser.gologin.ui.ProxiesScreen
import com.fastbrowser.gologin.ui.SettingsScreen
import com.fastbrowser.gologin.ui.theme.Emerald
import com.fastbrowser.gologin.ui.theme.FastBrowserTheme

private enum class HomeTab { PROFILES, PROXIES, SETTINGS }

/**
 * GoLogin-style shell: home is the Profiles list (play button launches the
 * browser with that profile), bottom nav is Profiles | Proxies | Settings,
 * and a green "+ Add profile" FAB opens the profile creator. The browser
 * itself is a separate screen reached by launching a profile — like Orbita
 * opening from GoLogin. (Orbita is GoLogin's own Chromium fork and cannot be
 * bundled; this copies its UX shell on top of our WebView engine.)
 */
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
            App.bridge.markProxyOverrideApplied()
            proxyReady = true
        }

        setContent {
            val settings by viewModel.settings.collectAsState()
            val servers by viewModel.servers.collectAsState()
            var tab by remember { mutableStateOf(HomeTab.PROFILES) }
            var browserOpen by remember { mutableStateOf(false) }
            var showAddProfile by remember { mutableStateOf(false) }
            var editingProfile by remember { mutableStateOf<BrowserProfile?>(null) }

            FastBrowserTheme(amoledBlack = settings.amoledBlackTheme) {
                if (!proxyReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Starting proxy bridge…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (!settings.onboardingDone) {
                    OnboardingScreen(viewModel = viewModel)
                } else if (browserOpen) {
                    BrowserScreen(
                        viewModel = viewModel,
                        onOpenSettings = {
                            browserOpen = false
                            tab = HomeTab.SETTINGS
                        },
                        onExitBrowser = { browserOpen = false }
                    )
                } else {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        bottomBar = {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                NavigationBarItem(
                                    selected = tab == HomeTab.PROFILES,
                                    onClick = { tab = HomeTab.PROFILES },
                                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                    label = { Text("Profiles") }
                                )
                                NavigationBarItem(
                                    selected = tab == HomeTab.PROXIES,
                                    onClick = { tab = HomeTab.PROXIES },
                                    icon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                                    label = { Text("Proxies") }
                                )
                                NavigationBarItem(
                                    selected = tab == HomeTab.SETTINGS,
                                    onClick = { tab = HomeTab.SETTINGS },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                    label = { Text("Settings") }
                                )
                            }
                        },
                        floatingActionButton = {
                            ExtendedFloatingActionButton(
                                onClick = { showAddProfile = true },
                                containerColor = Emerald,
                                contentColor = Color.Black,
                                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                text = { Text("Add profile") }
                            )
                        }
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        ) {
                            when (tab) {
                                HomeTab.PROFILES -> HomeScreen(
                                    viewModel = viewModel,
                                    onLaunchProfile = { profile ->
                                        viewModel.launchProfile(profile)
                                        browserOpen = true
                                    },
                                    onEditProfile = { editingProfile = it }
                                )
                                HomeTab.PROXIES -> ProxiesScreen(viewModel = viewModel)
                                HomeTab.SETTINGS -> SettingsScreen(viewModel = viewModel, onBack = null)
                            }
                        }
                    }
                }

                if (showAddProfile || editingProfile != null) {
                    ProfileEditDialog(
                        profile = editingProfile ?: viewModel.newProfileTemplate(),
                        servers = servers,
                        onDismiss = { showAddProfile = false; editingProfile = null },
                        onSave = { saved ->
                            viewModel.addOrUpdateProfile(saved)
                            showAddProfile = false
                            editingProfile = null
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Per-profile "clear cookies on exit" is honored here (incognito too).
        if (::viewModel.isInitialized) viewModel.clearCookiesOnExitIfNeeded()
        super.onDestroy()
    }
}
