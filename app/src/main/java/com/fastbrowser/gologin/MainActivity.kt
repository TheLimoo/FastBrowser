package com.fastbrowser.gologin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
        // v1.0.7 — FAIL CLOSED: if this device's WebView cannot apply a
        // proxy override and the kill-switch is on, the bind reports
        // "unsupported" instead of pretending it worked — the old code
        // called onApplied() anyway and the app browsed DIRECTLY, leaking
        // the real IP. proxyBlocked shows an explanatory screen (with a
        // one-tap escape: turn the kill-switch off) instead of the spinner.
        var proxyReady by mutableStateOf(false)
        var proxyBlocked by mutableStateOf(false)
        fun bindProxy(killSwitchEnabled: Boolean) {
            WebViewProfileEngine.bindWebViewToLocalBridge(
                App.localPort,
                killSwitchEnabled = killSwitchEnabled,
                onApplied = {
                    App.bridge.markProxyOverrideApplied()
                    proxyReady = true
                },
                onProxyUnsupported = { proxyBlocked = true }
            )
        }
        bindProxy(viewModel.settings.value.killSwitchEnabled)

        setContent {
            val settings by viewModel.settings.collectAsState()
            val servers by viewModel.servers.collectAsState()
            var tab by remember { mutableStateOf(HomeTab.PROFILES) }
            var browserOpen by remember { mutableStateOf(false) }
            var showAddProfile by remember { mutableStateOf(false) }
            var editingProfile by remember { mutableStateOf<BrowserProfile?>(null) }

            FastBrowserTheme(amoledBlack = settings.amoledBlackTheme) {
                if (proxyBlocked) {
                    // v1.0.7 fail-closed screen: the WebView cannot be
                    // proxied on this device and the kill-switch is on, so
                    // no page may load. The escape hatch turns the
                    // kill-switch off and re-binds (direct browsing, the
                    // user's explicit choice) instead of leaving the app
                    // stuck on the spinner.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "Browsing blocked",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "This device's WebView cannot route through the proxy, " +
                                    "and the kill-switch is on — so no traffic is sent " +
                                    "rather than leaking your real IP.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    viewModel.updateSettings { it.copy(killSwitchEnabled = false) }
                                    proxyBlocked = false
                                    bindProxy(killSwitchEnabled = false)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Emerald,
                                    contentColor = Color.Black
                                )
                            ) { Text("Turn off kill-switch & continue") }
                        }
                    }
                } else if (!proxyReady) {
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
                        // v1.0.5: the FAB belongs to the Profiles tab only.
                        // It used to show on every home tab, floating over
                        // Settings' "Reset All Settings" button, the
                        // Diagnostics "Test Connection" button, the text-size
                        // slider and the "Default search engine" field.
                        floatingActionButton = {
                            if (tab == HomeTab.PROFILES) {
                                ExtendedFloatingActionButton(
                                    onClick = { showAddProfile = true },
                                    containerColor = Emerald,
                                    contentColor = Color.Black,
                                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                    text = { Text("Add profile") }
                                )
                            }
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
