package com.fastbrowser.gologin.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fastbrowser.gologin.browser.WebViewProfileEngine
import com.fastbrowser.gologin.model.BrowserTab
import com.fastbrowser.gologin.model.ServerNode
import com.fastbrowser.gologin.ui.theme.DangerRed
import com.fastbrowser.gologin.ui.theme.Emerald
import com.fastbrowser.gologin.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import java.net.URLEncoder

fun cleanDomain(url: String): String {
    if (url.isBlank()) return ""
    return url.removePrefix("https://").removePrefix("http://")
        .removePrefix("www.").substringBefore("/").substringBefore("?").ifBlank { url }
}

fun flagFor(countryCode: String): String = when (countryCode.uppercase()) {
    "SE" -> "🇸🇪"
    "US" -> "🇺🇸"
    "FR" -> "🇫🇷"
    "DE" -> "🇩🇪"
    "GB" -> "🇬🇧"
    "NL" -> "🇳🇱"
    else -> "🌐"
}

fun latencyColor(rttMs: Long?): Color = when {
    rttMs == null -> DangerRed
    rttMs < 80 -> Emerald
    rttMs <= 180 -> WarningAmber
    else -> DangerRed
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = viewModel(),
    onOpenSettings: () -> Unit,
    /** Back out of the browser to the Profiles home (GoLogin/Orbita-style shell). */
    onExitBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settings by viewModel.settings.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val latency by viewModel.latency.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    val recentUrls by viewModel.recentUrls.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val destroyedTabIds by viewModel.destroyedTabIds.collectAsState()

    val activeTab = tabs.firstOrNull { it.id == activeTabId }
    val activeServer = servers.firstOrNull { it.id == settings.activeServerId }
    val activeProfile = profiles.firstOrNull { it.id == settings.activeProfileId }
    // Incognito tabs resolve to the ephemeral profile, not the settings one.
    val displayProfile = activeTab?.let { viewModel.profileForTab(it) } ?: activeProfile
    val needsPasswordServer = viewModel.needsPasswordServer

    // tabId -> WebView map: tab state survives server switches and tab switches.
    val webViews = remember { mutableStateMapOf<String, WebView>() }

    var showServerSheet by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<ServerNode?>(null) }
    var showProfilesSheet by remember { mutableStateOf(false) }
    var showTabSwitcher by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var urlFocused by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var showCopyMenu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    // Destroy WebViews for closed tabs.
    LaunchedEffect(destroyedTabIds) {
        destroyedTabIds.forEach { id ->
            webViews.remove(id)?.destroy()
        }
    }

    // Keep the omni-bar text in sync with navigation (but never fight the user while typing).
    LaunchedEffect(activeTab?.id, activeTab?.url) {
        if (!urlFocused) urlInput = activeTab?.url ?: ""
    }

    // Snackbar events from the ViewModel (switch confirmations, errors, ...).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.Snack -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel
                    )
                    if (result == SnackbarResult.ActionPerformed) event.onAction?.invoke()
                }
            }
        }
    }

    val activeWebView = activeTab?.let { webViews[it.id] }
    val canGoBack = activeTab?.canGoBack == true

    // Back closes overlays first, then goes back in the page, then exits the
    // browser to the Profiles home — never out of the app by accident.
    BackHandler(enabled = true) {
        when {
            showTabSwitcher -> showTabSwitcher = false
            showServerSheet -> { showServerSheet = false; serverToEdit = null }
            showProfilesSheet -> showProfilesSheet = false
            showFindBar -> { showFindBar = false; activeWebView?.clearMatches() }
            showBookmarks -> showBookmarks = false
            showHistory -> showHistory = false
            canGoBack -> activeWebView?.goBack()
            else -> onExitBrowser()
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!settings.bottomAddressBar) {
                OmniBar(
                    activeServer = activeServer,
                    killSwitchOn = settings.killSwitchEnabled,
                    latencyMs = activeServer?.let { latency[it.id]?.rttMs },
                    isPinging = activeServer?.let { pinging.contains(it.id) } ?: false,
                    profileName = displayProfile?.name ?: "Profile",
                    tabCount = tabs.size,
                    urlInput = urlInput,
                    onUrlInputChange = { urlInput = it },
                    urlFocused = urlFocused,
                    onUrlFocusChange = { urlFocused = it },
                    focusRequester = focusRequester,
                    onNavigate = { viewModel.navigateActiveTab(it); urlFocused = false },
                    onLatencyPillClick = { showServerSheet = true },
                    onProfileClick = { showProfilesSheet = true },
                    onTabCounterClick = { showTabSwitcher = true },
                    onMenuClick = { showMenu = true },
                    onCopyUrl = {
                        clipboard.setText(AnnotatedString(activeTab?.url ?: ""))
                        viewModel.snack("URL copied")
                    },
                    onShareUrl = {
                        val url = activeTab?.url.orEmpty()
                        if (url.isNotBlank()) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share link"))
                        }
                    },
                    showCopyMenu = showCopyMenu,
                    onCopyMenuDismiss = { showCopyMenu = false },
                    onLongPressUrl = { showCopyMenu = true },
                    displayUrl = activeTab?.url ?: ""
                )
                if (activeTab?.isLoading == true) {
                    LinearProgressIndicator(
                        progress = { (activeTab?.progress ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Emerald,
                        trackColor = Color.Transparent
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (activeTab != null) {
                    val tab = activeTab
                    val profile = viewModel.profileForTab(tab)
                        ?: profiles.firstOrNull()
                    if (profile != null) {
                        val serverForTab = servers.firstOrNull { it.id == settings.activeServerId }
                        AndroidView(
                            factory = { ctx ->
                                val wv = WebView(ctx.applicationContext)
                                webViews[tab.id] = wv
                                WebViewProfileEngine.configureWebView(
                                    context = ctx,
                                    webView = wv,
                                    profile = profile,
                                    settings = settings,
                                    timezoneOverride = serverForTab?.timezoneOverride ?: "",
                                    desktopMode = tab.desktopMode,
                                    onPageStarted = { url ->
                                        webViews[tab.id]?.tag = url
                                        viewModel.updateTab(tab.id) {
                                            it.copy(url = url, isLoading = true, loadError = null)
                                        }
                                    },
                                    onPageFinished = { url, title ->
                                        viewModel.updateTab(tab.id) {
                                            it.copy(
                                                url = url,
                                                title = title.ifBlank { cleanDomain(url) },
                                                isLoading = false,
                                                progress = 100,
                                                canGoBack = webViews[tab.id]?.canGoBack() == true,
                                                canGoForward = webViews[tab.id]?.canGoForward() == true
                                            )
                                        }
                                    },
                                    onProgressChanged = { progress ->
                                        viewModel.updateTab(tab.id) {
                                            it.copy(progress = progress, isLoading = progress < 100)
                                        }
                                    },
                                    onReceivedError = { message ->
                                        viewModel.onTabError(tab.id, message)
                                    },
                                    onDownloadStarted = { fileName ->
                                        viewModel.snack("Downloading $fileName")
                                    }
                                )
                                if (tab.url.isNotBlank()) {
                                    wv.tag = tab.url
                                    wv.loadUrl(tab.url)
                                }
                                wv
                            },
                            update = { wv ->
                                // Runtime settings that can change without recreating the WebView.
                                if (wv.settings.textZoom != (settings.textScale * 100f).toInt()) {
                                    WebViewProfileEngine.applyRuntimeSettings(wv, settings)
                                }
                                // Desktop-mode toggle: swap UA and reload.
                                val expectedUa = WebViewProfileEngine.resolveUserAgent(profile, tab.desktopMode)
                                if (wv.settings.userAgentString != expectedUa) {
                                    wv.settings.userAgentString = expectedUa
                                    if (tab.url.isNotBlank()) wv.reload()
                                }
                                // Navigate when the ViewModel says so (omni-bar, quick links, NTP).
                                val lastLoaded = wv.tag as? String
                                if (tab.url.isNotBlank() && lastLoaded != tab.url) {
                                    wv.tag = tab.url
                                    wv.loadUrl(tab.url)
                                }
                                // Keep back/forward state fresh — only write when it changed,
                                // otherwise this update block would loop forever.
                                val newBack = wv.canGoBack()
                                val newForward = wv.canGoForward()
                                if (newBack != tab.canGoBack || newForward != tab.canGoForward) {
                                    viewModel.updateTab(tab.id) {
                                        it.copy(canGoBack = newBack, canGoForward = newForward)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // New-tab page overlay (real page, not a blank screen).
                if (activeTab != null && activeTab.url.isBlank() && settings.showNewTabPage) {
                    NewTabPage(
                        servers = servers,
                        latency = latency,
                        pinging = pinging,
                        recentUrls = recentUrls,
                        onOpenUrl = { viewModel.navigateActiveTab(it) },
                        onServerClick = { showServerSheet = true },
                        onPingAll = { viewModel.pingAll() }
                    )
                }

                // Kill-switch / connection error page.
                if (activeTab != null && activeTab.loadError != null && activeTab.url.isNotBlank()) {
                    ConnectionErrorPage(
                        message = activeTab.loadError ?: "Connection failed",
                        killSwitchOn = settings.killSwitchEnabled,
                        activeServerName = activeServer?.name,
                        onRetry = {
                            viewModel.clearTabError(activeTab.id)
                            activeWebView?.reload()
                        },
                        onSwitchServer = { showServerSheet = true }
                    )
                }

                // Blank-password guard: never a black page — say exactly what is
                // missing and open the server editor. (Passwords were entered
                // in v1.0.0 testing, so this is a safety net, not the v1.0.0 cause.)
                if (activeTab != null && needsPasswordServer != null) {
                    PasswordRequiredCard(
                        server = needsPasswordServer,
                        onEnterPassword = {
                            serverToEdit = needsPasswordServer
                            showServerSheet = true
                        }
                    )
                }

                // Find-in-page bar.
                if (showFindBar) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = {
                                findQuery = it
                                activeWebView?.findAllAsync(it)
                            },
                            singleLine = true,
                            placeholder = { Text("Find in page") },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { activeWebView?.findNext(false) }) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous")
                        }
                        IconButton(onClick = { activeWebView?.findNext(true) }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next")
                        }
                        IconButton(onClick = { showFindBar = false; findQuery = ""; activeWebView?.clearMatches() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close find")
                        }
                    }
                }

                // Visual tab switcher overlay.
                if (showTabSwitcher) {
                    TabSwitcherOverlay(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onSelect = { viewModel.selectTab(it); showTabSwitcher = false },
                        onClose = { viewModel.closeTab(it) },
                        onNewTab = { viewModel.newTab(); showTabSwitcher = false },
                        onDismiss = { showTabSwitcher = false }
                    )
                }
            }

            if (settings.bottomAddressBar) {
                if (activeTab?.isLoading == true) {
                    LinearProgressIndicator(
                        progress = { (activeTab?.progress ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Emerald,
                        trackColor = Color.Transparent
                    )
                }
                OmniBar(
                    activeServer = activeServer,
                    killSwitchOn = settings.killSwitchEnabled,
                    latencyMs = activeServer?.let { latency[it.id]?.rttMs },
                    isPinging = activeServer?.let { pinging.contains(it.id) } ?: false,
                    profileName = displayProfile?.name ?: "Profile",
                    tabCount = tabs.size,
                    urlInput = urlInput,
                    onUrlInputChange = { urlInput = it },
                    urlFocused = urlFocused,
                    onUrlFocusChange = { urlFocused = it },
                    focusRequester = focusRequester,
                    onNavigate = { viewModel.navigateActiveTab(it); urlFocused = false },
                    onLatencyPillClick = { showServerSheet = true },
                    onProfileClick = { showProfilesSheet = true },
                    onTabCounterClick = { showTabSwitcher = true },
                    onMenuClick = { showMenu = true },
                    onCopyUrl = {
                        clipboard.setText(AnnotatedString(activeTab?.url ?: ""))
                        viewModel.snack("URL copied")
                    },
                    onShareUrl = {
                        val url = activeTab?.url.orEmpty()
                        if (url.isNotBlank()) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share link"))
                        }
                    },
                    showCopyMenu = showCopyMenu,
                    onCopyMenuDismiss = { showCopyMenu = false },
                    onLongPressUrl = { showCopyMenu = true },
                    displayUrl = activeTab?.url ?: ""
                )
            }
        }

        // 3-dot menu (Chrome/Orbita-style — every item works for real).
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("New tab") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { showMenu = false; viewModel.newTab() }
            )
            DropdownMenuItem(
                text = { Text("New Incognito tab") },
                leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                onClick = { showMenu = false; viewModel.newIncognitoTab() }
            )
            DropdownMenuItem(
                text = { Text("History") },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                onClick = { showMenu = false; showHistory = true }
            )
            DropdownMenuItem(
                text = { Text("Downloads") },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                onClick = {
                    showMenu = false
                    try {
                        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                    } catch (_: Exception) {
                        viewModel.snack("No Downloads app on this device")
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("Bookmarks") },
                leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                onClick = { showMenu = false; showBookmarks = true }
            )
            DropdownMenuItem(
                text = { Text("Recent tabs") },
                leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null) },
                onClick = { showMenu = false; showTabSwitcher = true }
            )
            DropdownMenuItem(
                text = { Text("Share…") },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    showMenu = false
                    val url = activeTab?.url.orEmpty()
                    if (url.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share link"))
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("Find in page") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                onClick = { showMenu = false; showFindBar = true }
            )
            DropdownMenuItem(
                text = { Text("Translate…") },
                leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                onClick = {
                    showMenu = false
                    val url = activeTab?.url.orEmpty()
                    if (url.isNotBlank()) {
                        viewModel.navigateActiveTab(
                            "https://translate.google.com/translate?sl=auto&tl=en&u=" +
                                URLEncoder.encode(url, "UTF-8")
                        )
                    } else {
                        viewModel.snack("Open a page to translate it")
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(if (activeTab?.desktopMode == true) "✓ Desktop site" else "Desktop site") },
                leadingIcon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null) },
                onClick = {
                    showMenu = false
                    activeTab?.let { viewModel.toggleDesktopMode(it.id) }
                }
            )
            DropdownMenuItem(
                text = { Text("Reload") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = { showMenu = false; activeWebView?.reload() }
            )
            DropdownMenuItem(
                text = { Text("Forward") },
                leadingIcon = { Icon(Icons.Filled.ArrowForward, contentDescription = null) },
                enabled = activeTab?.canGoForward == true,
                onClick = { showMenu = false; activeWebView?.goForward() }
            )
            DropdownMenuItem(
                text = { Text("Quick IP check") },
                leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null) },
                onClick = {
                    showMenu = false
                    viewModel.navigateActiveTab("https://browserleaks.com/ip")
                }
            )
            DropdownMenuItem(
                text = { Text("Home") },
                leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                onClick = {
                    showMenu = false
                    viewModel.navigateActiveTab(settings.homePageUrl)
                }
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                onClick = { showMenu = false; onOpenSettings() }
            )
        }

        if (showServerSheet) {
            ServerSwitcherSheet(
                viewModel = viewModel,
                onDismiss = { showServerSheet = false; serverToEdit = null },
                editServer = serverToEdit
            )
        }
        if (showProfilesSheet) {
            ProfilesSheet(
                viewModel = viewModel,
                onDismiss = { showProfilesSheet = false }
            )
        }
        if (showBookmarks) {
            BookmarksSheet(
                bookmarks = bookmarks,
                currentUrl = activeTab?.url.orEmpty(),
                currentTitle = activeTab?.title.orEmpty(),
                isBookmarked = activeTab?.url?.let { viewModel.isBookmarked(it) } == true,
                onToggleBookmark = {
                    val url = activeTab?.url.orEmpty()
                    if (url.isNotBlank()) {
                        if (viewModel.isBookmarked(url)) viewModel.removeBookmark(url)
                        else viewModel.addBookmark(url, activeTab?.title.orEmpty())
                    }
                },
                onOpen = { url -> viewModel.navigateActiveTab(url); showBookmarks = false },
                onDelete = { url -> viewModel.removeBookmark(url) },
                onDismiss = { showBookmarks = false }
            )
        }
        if (showHistory) {
            HistorySheet(
                recentUrls = recentUrls,
                onOpen = { url -> viewModel.navigateActiveTab(url); showHistory = false },
                onClear = { viewModel.clearHistory() },
                onDismiss = { showHistory = false }
            )
        }
    }
}

@Composable
private fun LatencyPill(
    activeServer: ServerNode?,
    killSwitchOn: Boolean,
    latencyMs: Long?,
    isPinging: Boolean,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "pillPulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "pillAlpha"
    )
    val (dotColor, label) = when {
        activeServer == null && killSwitchOn -> DangerRed to "Kill-Switch"
        activeServer == null -> Color.Gray to "Direct"
        latencyMs == null -> Color.Gray to "${activeServer.name} • …"
        else -> latencyColor(latencyMs) to "${activeServer.name} • ${latencyMs}ms"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (isPinging) alpha else 1f)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OmniBar(
    activeServer: ServerNode?,
    killSwitchOn: Boolean,
    latencyMs: Long?,
    isPinging: Boolean,
    profileName: String,
    tabCount: Int,
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    urlFocused: Boolean,
    onUrlFocusChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    onNavigate: (String) -> Unit,
    onLatencyPillClick: () -> Unit,
    onProfileClick: () -> Unit,
    onTabCounterClick: () -> Unit,
    onMenuClick: () -> Unit,
    onCopyUrl: () -> Unit,
    onShareUrl: () -> Unit,
    showCopyMenu: Boolean,
    onCopyMenuDismiss: () -> Unit,
    onLongPressUrl: () -> Unit,
    displayUrl: String
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            LatencyPill(
                activeServer = activeServer,
                killSwitchOn = killSwitchOn,
                latencyMs = latencyMs,
                isPinging = isPinging,
                onClick = onLatencyPillClick
            )
            Spacer(Modifier.width(6.dp))
            // URL field: clean domain when idle, full editor when focused.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            ) {
                if (urlFocused) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = onUrlInputChange,
                        singleLine = true,
                        placeholder = { Text("Search or type URL") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = { onUrlInputChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onNavigate(urlInput) }),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .onFocusChanged { onUrlFocusChange(it.isFocused) }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                onClick = {
                                    onUrlFocusChange(true)
                                    scope.launch {
                                        focusRequester.requestFocus()
                                    }
                                },
                                onLongClick = onLongPressUrl
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = if (displayUrl.isBlank()) "Search or type URL" else cleanDomain(displayUrl),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (displayUrl.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = showCopyMenu,
                        onDismissRequest = onCopyMenuDismiss
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy URL") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { onCopyMenuDismiss(); onCopyUrl() }
                        )
                        DropdownMenuItem(
                            text = { Text("Share URL") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = { onCopyMenuDismiss(); onShareUrl() }
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            // Profile badge chip.
            Surface(
                onClick = onProfileClick,
                shape = CircleShape,
                color = Emerald,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        profileName.firstOrNull()?.uppercase() ?: "P",
                        color = Color.Black,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            // Tab counter.
            Surface(
                onClick = onTabCounterClick,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("$tabCount", style = MaterialTheme.typography.labelLarge)
                }
            }
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
        }
        // Paste-and-go chip while the address field is focused.
        if (urlFocused) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                val clipText = clipboard.getText()?.text.orEmpty()
                if (clipText.isNotBlank()) {
                    OutlinedButton(onClick = { onNavigate(clipText) }) {
                        Text("Paste & Go: ${clipText.take(24)}")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onNavigate(urlInput) }) {
                    Text("Go")
                }
            }
        }
    }
}

@Composable
private fun NewTabPage(
    servers: List<ServerNode>,
    latency: Map<String, com.fastbrowser.gologin.network.ServerLatencyResult>,
    pinging: Set<String>,
    recentUrls: List<String>,
    onOpenUrl: (String) -> Unit,
    onServerClick: () -> Unit,
    onPingAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("FastBrowser", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pick a server — every tab routes through it. Tap a card to switch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        servers.forEach { server ->
            val result = latency[server.id]
            Card(
                onClick = onServerClick,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(flagFor(server.countryCode), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${server.host}:${server.port} • ${server.protocol}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val rtt = result?.rttMs
                    Text(
                        text = when {
                            pinging.contains(server.id) -> "pinging…"
                            rtt == null -> "offline"
                            else -> "$rtt ms"
                        },
                        color = latencyColor(rtt),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        OutlinedButton(onClick = onPingAll) {
            Icon(Icons.Filled.Speed, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Ping all servers")
        }
        Text("Quick links", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "IP check" to "https://browserleaks.com/ip",
                "Google" to "https://www.google.com",
                "DuckDuckGo" to "https://duckduckgo.com"
            ).forEach { (label, url) ->
                OutlinedButton(onClick = { onOpenUrl(url) }) { Text(label) }
            }
        }
        if (recentUrls.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleMedium)
            recentUrls.take(5).forEach { url ->
                TextButton(onClick = { onOpenUrl(url) }) {
                    Text(cleanDomain(url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ConnectionErrorPage(
    message: String,
    killSwitchOn: Boolean,
    activeServerName: String?,
    onRetry: () -> Unit,
    onSwitchServer: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(48.dp))
            Text(
                if (killSwitchOn) "Blocked by Kill-Switch" else "Connection failed",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = if (killSwitchOn) {
                    "The ${activeServerName ?: "selected server"} could not be reached, so no traffic " +
                        "was sent — your real IP never leaked. Check the server or switch."
                } else {
                    message
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
                OutlinedButton(onClick = onSwitchServer) { Text("Switch Server") }
            }
        }
    }
}

@Composable
private fun PasswordRequiredCard(
    server: ServerNode,
    onEnterPassword: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(48.dp))
            Text("Password needed", style = MaterialTheme.typography.titleLarge)
            Text(
                "Enter the proxy password for ${server.name} (${server.host}:${server.port}) to browse. " +
                    "It is stored only on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onEnterPassword,
                colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)
            ) { Text("Enter password") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<com.fastbrowser.gologin.model.Bookmark>,
    currentUrl: String,
    currentTitle: String,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
            if (currentUrl.isNotBlank()) {
                OutlinedButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isBookmarked) "Remove this page from bookmarks" else "Bookmark this page")
                }
            }
            if (bookmarks.isEmpty()) {
                Text("No bookmarks yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        Card(
                            onClick = { onOpen(bookmark.url) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(bookmark.title.ifBlank { bookmark.url }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(cleanDomain(bookmark.url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onDelete(bookmark.url) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(
    recentUrls: List<String>,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear all") }
            }
            if (recentUrls.isEmpty()) {
                Text("No pages visited yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recentUrls) { url ->
                        TextButton(
                            onClick = { onOpen(url) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(cleanDomain(url), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabSwitcherOverlay(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tabs (${tabs.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    Card(
                        onClick = { onSelect(tab.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (tab.id == activeTabId)
                                MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .then(
                                if (tab.id == activeTabId)
                                    Modifier.border(1.dp, Emerald, RoundedCornerShape(12.dp))
                                else Modifier
                            )
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tab.title.ifBlank { "New Tab" },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onClose(tab.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                cleanDomain(tab.url),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNewTab,
                colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New Tab")
            }
        }
    }
}
