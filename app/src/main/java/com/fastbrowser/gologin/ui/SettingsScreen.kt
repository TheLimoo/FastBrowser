package com.fastbrowser.gologin.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fastbrowser.gologin.App
import com.fastbrowser.gologin.model.SearchEngine
import com.fastbrowser.gologin.ui.theme.DangerRed
import com.fastbrowser.gologin.ui.theme.Emerald
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val latency by viewModel.latency.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showServerSheet by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<String?>(null) }
    var exportJson by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var homepageDraft by remember(settings.homePageUrl) { mutableStateOf(settings.homePageUrl) }
    var homepageError by remember { mutableStateOf<String?>(null) }
    var engineExpanded by remember { mutableStateOf(false) }
    var sliderValue by remember(settings.textScale) { mutableStateOf(settings.textScale) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ------------------------------------------------ 1. Servers & Routing
            SettingsSection("Servers & Routing")
            SettingsRow(
                title = "Manage servers",
                subtitle = "${servers.size} servers • active: ${servers.firstOrNull { it.id == settings.activeServerId }?.name ?: "Direct"}",
                onClick = { showServerSheet = true }
            )
            SettingsToggle(
                title = "Strict Kill-Switch",
                subtitle = "Block all traffic if the selected server disconnects — your real IP never leaks.",
                checked = settings.killSwitchEnabled,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(killSwitchEnabled = enabled) } }
            )
            SettingsToggle(
                title = "Auto-select fastest server",
                subtitle = "Ping all servers on launch and switch to the lowest RTT.",
                checked = settings.autoSelectFastestServer,
                onCheckedChange = { enabled ->
                    viewModel.updateSettings { it.copy(autoSelectFastestServer = enabled) }
                    if (enabled) viewModel.autoSelectFastestServer()
                }
            )
            OutlinedButton(
                onClick = {
                    viewModel.pingAll()
                    testResults = "Pinging ${servers.size} servers… results appear below."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Test Both Servers Now") }
            if (pinging.isNotEmpty()) {
                Text("Pinging…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (latency.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        servers.forEach { server ->
                            val r = latency[server.id]
                            Text(
                                "${flagFor(server.countryCode)} ${server.name}: " +
                                    if (r?.isOnline == true) "${r.rttMs} ms" else "offline",
                                style = MaterialTheme.typography.bodyMedium,
                                color = latencyColor(r?.rttMs)
                            )
                        }
                    }
                }
            }
            testResults?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            // ------------------------------------------------ 2. Privacy & Security
            SettingsSection("Privacy & Security")
            SettingsToggle(
                title = "WebRTC IP leak blocker",
                subtitle = "Strip STUN/ICE candidates so sites cannot see your real carrier or Wi-Fi IP.",
                checked = settings.blockWebRtcGlobal,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(blockWebRtcGlobal = enabled) } }
            )
            SettingsToggle(
                title = "Block third-party cookies",
                subtitle = "Stops cross-site trackers from following you between sites.",
                checked = settings.blockThirdPartyCookies,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(blockThirdPartyCookies = enabled) } }
            )
            SettingsRow(
                title = "Clear active profile data",
                subtitle = "Wipe cookies, storage and cache for the current profile now.",
                onClick = { showClearConfirm = true }
            )
            Text(
                "Per-profile WebRTC shield, canvas noise, timezone and language overrides live in the Profiles sheet (tap the profile badge in the address bar).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ------------------------------------------------ 3. Appearance
            SettingsSection("Appearance")
            SettingsToggle(
                title = "AMOLED pure black",
                subtitle = "True #000000 background — saves battery on OLED screens.",
                checked = settings.amoledBlackTheme,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(amoledBlackTheme = enabled) } }
            )
            SettingsToggle(
                title = "Force dark mode on websites",
                subtitle = "Algorithmically darken web pages.",
                checked = settings.forceDarkWebPages,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(forceDarkWebPages = enabled) } }
            )
            Text("Address bar position", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.updateSettings { it.copy(bottomAddressBar = false) } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!settings.bottomAddressBar) Emerald else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!settings.bottomAddressBar) Color.Black else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Top") }
                Button(
                    onClick = { viewModel.updateSettings { it.copy(bottomAddressBar = true) } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (settings.bottomAddressBar) Emerald else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (settings.bottomAddressBar) Color.Black else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text("Bottom") }
            }
            Text("Text size: ${(sliderValue * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    viewModel.updateSettings { it.copy(textScale = sliderValue) }
                },
                valueRange = 0.5f..2.0f,
                steps = 29,
                modifier = Modifier.fillMaxWidth()
            )

            // ------------------------------------------------ 4. Speed & Performance
            SettingsSection("Speed & Performance")
            SettingsToggle(
                title = "Ad & tracker blocker",
                subtitle = "Blocks ad networks at the request layer for faster loads.",
                checked = settings.adAndTrackerBlocker,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(adAndTrackerBlocker = enabled) } }
            )
            SettingsToggle(
                title = "Data Saver (block images)",
                subtitle = "Load text-only pages instantly on slow connections.",
                checked = settings.blockImagesDataSaver,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(blockImagesDataSaver = enabled) } }
            )
            SettingsToggle(
                title = "Hardware acceleration",
                subtitle = "GPU rendering for pages. Turn off if you see rendering glitches.",
                checked = settings.hardwareAcceleration,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(hardwareAcceleration = enabled) } }
            )
            SettingsToggle(
                title = "Desktop site by default",
                subtitle = "New tabs request the desktop version of sites.",
                checked = settings.desktopSiteDefault,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(desktopSiteDefault = enabled) } }
            )

            // ------------------------------------------------ 5. Search & Home
            SettingsSection("Search & Home")
            ExposedDropdownMenuBox(expanded = engineExpanded, onExpandedChange = { engineExpanded = it }) {
                OutlinedTextField(
                    value = settings.searchEngine.label,
                    onValueChange = {}, readOnly = true,
                    label = { Text("Default search engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                    SearchEngine.entries.forEach { engine ->
                        DropdownMenuItem(
                            text = { Text(engine.label) },
                            onClick = {
                                viewModel.updateSettings { it.copy(searchEngine = engine) }
                                engineExpanded = false
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = homepageDraft,
                onValueChange = { homepageDraft = it; homepageError = null },
                label = { Text("Homepage URL") },
                singleLine = true,
                isError = homepageError != null,
                supportingText = homepageError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (homepageDraft.startsWith("http://") || homepageDraft.startsWith("https://")) {
                        viewModel.updateSettings { it.copy(homePageUrl = homepageDraft.trim()) }
                    } else {
                        homepageError = "URL must start with http:// or https://"
                    }
                }),
                modifier = Modifier.fillMaxWidth()
            )
            SettingsToggle(
                title = "Show new-tab page",
                subtitle = "Server status cards, quick links and recent tabs on empty tabs.",
                checked = settings.showNewTabPage,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(showNewTabPage = enabled) } }
            )

            // ------------------------------------------------ 6. Backup & Portability
            SettingsSection("Backup & Portability")
            OutlinedButton(
                onClick = { viewModel.exportJson { exportJson = it } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export Servers & Profiles (JSON)") }
            Text(
                "The export contains your proxy passwords. Keep it private — never share or post it.",
                style = MaterialTheme.typography.bodySmall,
                color = DangerRed
            )
            OutlinedButton(
                onClick = { showImport = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import Configuration (JSON)") }
            OutlinedButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset All Settings") }

            // ------------------------------------------------ 7. About
            SettingsSection("About")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("FastBrowser 1.0.0", style = MaterialTheme.typography.titleMedium)
                    Text("Local bridge: 127.0.0.1:${App.localPort}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Active server: ${servers.firstOrNull { it.id == settings.activeServerId }?.let { "${it.name} (${it.host}:${it.port})" } ?: "Direct (no proxy)"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Servers: ${servers.joinToString { "${it.name} ${it.host}:${it.port}" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "How switching works: WebView always talks to a tiny proxy running on your phone " +
                            "(127.0.0.1). Tapping a server swaps the bridge's upstream connection atomically " +
                            "— under 1 ms, no reload, no re-login. All browsing traffic goes through the " +
                            "selected server; DNS is resolved on the server, never on the phone. With the " +
                            "kill-switch on, a dead server means no traffic at all instead of a leak.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showServerSheet) {
        ServerSwitcherSheet(viewModel = viewModel, onDismiss = { showServerSheet = false })
    }

    exportJson?.let { json ->
        AlertDialog(
            onDismissRequest = { exportJson = null },
            title = { Text("Export (contains passwords)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Copy this JSON to back up your servers, profiles and settings. It contains proxy passwords — do not share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DangerRed
                    )
                    OutlinedTextField(
                        value = json,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(json))
                        viewModel.snack("Backup JSON copied")
                        exportJson = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, json)
                        putExtra(Intent.EXTRA_SUBJECT, "FastBrowser backup")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share backup"))
                }) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importMessage = null },
            title = { Text("Import Configuration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it; importMessage = null },
                        label = { Text("Paste backup JSON") },
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                    importMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Backup imported")) Emerald else DangerRed)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importJson(importText) { _, message ->
                            importMessage = message
                            if (message.startsWith("Backup imported")) {
                                importText = ""
                            }
                        }
                    },
                    enabled = importText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importMessage = null }) { Text("Close") }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all settings?") },
            text = { Text("Servers return to the pre-seeded Stockholm + Ohio entries (passwords cleared), profiles and settings go back to defaults.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.resetAll(); showResetConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear active profile data?") },
            text = { Text("Cookies, storage and cache for the current profile are wiped immediately.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearActiveProfileData(); showClearConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = Emerald,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
