package com.fastbrowser.gologin.ui

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastbrowser.gologin.App
import com.fastbrowser.gologin.CrashInfo
import com.fastbrowser.gologin.CrashReporter
import com.fastbrowser.gologin.browser.WebViewProfileEngine
import com.fastbrowser.gologin.data.SettingsRepository
import com.fastbrowser.gologin.model.AppSettings
import com.fastbrowser.gologin.model.Bookmark
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.model.BrowserTab
import com.fastbrowser.gologin.model.ProxyProtocol
import com.fastbrowser.gologin.model.SearchEngine
import com.fastbrowser.gologin.model.ServerNode
import com.fastbrowser.gologin.network.BridgeError
import com.fastbrowser.gologin.network.LatencyMonitor
import com.fastbrowser.gologin.network.ServerLatencyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

sealed interface UiEvent {
    data class Snack(val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null) : UiEvent
}

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application.applicationContext)

    private val _servers = MutableStateFlow<List<ServerNode>>(emptyList())
    val servers: StateFlow<List<ServerNode>> = _servers.asStateFlow()

    private val _profiles = MutableStateFlow<List<BrowserProfile>>(emptyList())
    val profiles: StateFlow<List<BrowserProfile>> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _latency = MutableStateFlow<Map<String, ServerLatencyResult>>(emptyMap())
    val latency: StateFlow<Map<String, ServerLatencyResult>> = _latency.asStateFlow()

    private val _pinging = MutableStateFlow<Set<String>>(emptySet())
    val pinging: StateFlow<Set<String>> = _pinging.asStateFlow()

    private val _recentUrls = MutableStateFlow<List<String>>(emptyList())
    val recentUrls: StateFlow<List<String>> = _recentUrls.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    /** Last failure reported by the local proxy bridge (auth/connect errors). */
    val bridgeError: StateFlow<BridgeError?> = App.bridge.lastError

    /** Last uncaught crash recorded by CrashReporter (v1.0.5) — shown in
     *  Settings → Diagnostics so a crash reports its exact cause. */
    val lastCrash: StateFlow<CrashInfo?> = CrashReporter.lastCrash

    fun clearLastCrash() = CrashReporter.clear(getApplication())

    /** True once the WebView proxy override to 127.0.0.1 has been applied. */
    val proxyOverrideApplied: StateFlow<Boolean> = App.bridge.proxyOverrideApplied

    val bridgePort: Int get() = App.localPort

    /** Ephemeral incognito profile: never persisted, wiped when its tabs close. */
    val incognitoProfile = BrowserProfile(
        id = "incognito",
        name = "Incognito",
        assignedServerId = null,
        clearCookiesOnExit = true
    )

    /** The active server, when a username is set but its password is blank — browsing cannot work.
     *  Blank username AND blank password means a no-auth server (v1.0.2 test build): that is
     *  valid and must NOT block browsing. */
    val needsPasswordServer: ServerNode?
        get() = activeServer?.takeIf { it.username.isNotBlank() && it.password.isBlank() }

    /**
     * Serializes every read-modify-write of the server list.
     * BUG FOUND (v1.0.1 audit): onboarding's Launch button called addOrUpdateServer once per
     * server; each call launched its own coroutine that read the same stale _servers.value
     * (both passwords blank), and the second DataStore write overwrote the first — so only
     * the LAST server's password survived and the active (Stockholm) password was silently
     * lost. All server-list mutations now go through this mutex.
     */
    private val serversMutex = Mutex()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** Set of tab ids whose WebView was destroyed (used by the screen to clean up). */
    private val _destroyedTabIds = MutableStateFlow<Set<String>>(emptySet())
    val destroyedTabIds: StateFlow<Set<String>> = _destroyedTabIds.asStateFlow()

    val activeServer: ServerNode?
        get() = _servers.value.firstOrNull { it.id == _settings.value.activeServerId }

    val activeProfile: BrowserProfile?
        get() = _profiles.value.firstOrNull { it.id == _settings.value.activeProfileId }
            ?: _profiles.value.firstOrNull()

    val activeTab: BrowserTab?
        get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    init {
        viewModelScope.launch {
            repository.seedIfNeeded()
            // TEST BUILD (v1.0.2): strip the old auth from the two known servers on
            // existing installs, so the test build browses with no password at all.
            repository.migrateKnownServersToNoAuth()
            // v1.0.3: add the Stockholm-via-Cloudflare (WebSocket) server to
            // existing installs that only stored the two direct servers.
            repository.migrateAddCloudflareServer()
            // v1.0.6: remove the direct Stockholm server from existing
            // installs (it does not work from Iran); the active server
            // falls back to Stockholm (Cloudflare).
            repository.migrateRemoveStockholmDirect()
            // v1.0.5: switch existing installs to the new layered dark theme
            // (their stored amoledBlackTheme=true would otherwise keep the
            // flat pure-black look the user asked to replace).
            repository.migrateThemeToLayered()
            combine(
                repository.serversFlow,
                repository.profilesFlow,
                repository.settingsFlow,
                repository.recentUrlsFlow,
                repository.bookmarksFlow
            ) { servers, profiles, settings, recent, bookmarks ->
                _servers.value = servers
                _profiles.value = profiles
                _settings.value = settings
                _recentUrls.value = recent
                _bookmarks.value = bookmarks
                // Push the current upstream into the bridge (atomic hot-swap source of truth).
                val active = servers.firstOrNull { it.id == settings.activeServerId }
                App.bridge.updateUpstreamServer(active, settings.killSwitchEnabled)
                if (_tabs.value.isEmpty() && profiles.isNotEmpty()) {
                    val profile = profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.first()
                    val tab = BrowserTab(profileId = profile.id, desktopMode = settings.desktopSiteDefault)
                    _tabs.value = listOf(tab)
                    _activeTabId.value = tab.id
                }
            }.collect { }
        }
        // Initial latency probe + optional auto-select-fastest on launch.
        viewModelScope.launch {
            // Wait for the first settings/servers load before probing.
            repository.settingsFlow.first { it.activeServerId != null }
            pingAll()
            if (_settings.value.autoSelectFastestServer) {
                autoSelectFastestServer(silent = true)
            }
        }
    }

    // ------------------------------------------------------------------ servers

    /** One-tap switch: atomic bridge hot-swap (<1ms) + persisted active server +
     *  fresh ping. v1.0.4: the current tab reloads through the new server
     *  immediately — no "Reload" button to tap. */
    fun selectServer(server: ServerNode) {
        viewModelScope.launch {
            App.bridge.updateUpstreamServer(server, _settings.value.killSwitchEnabled)
            repository.updateSettings { it.copy(activeServerId = server.id) }
            if (reloadActiveTabIfLoaded()) {
                _events.emit(UiEvent.Snack("Switched to ${server.name} — reloading"))
            } else {
                _events.emit(UiEvent.Snack("Switched to ${server.name} • ${server.host}"))
            }
            pingServer(server)
        }
    }

    /** Direct (no proxy) mode — only reachable when the kill-switch is OFF. */
    fun selectDirectConnection() {
        viewModelScope.launch {
            App.bridge.updateUpstreamServer(null, killSwitch = false)
            repository.updateSettings { it.copy(activeServerId = null) }
            if (reloadActiveTabIfLoaded()) {
                _events.emit(UiEvent.Snack("Direct connection — reloading"))
            } else {
                _events.emit(UiEvent.Snack("Direct connection — traffic bypasses the proxy"))
            }
        }
    }

    fun addOrUpdateServer(server: ServerNode) {
        viewModelScope.launch {
            serversMutex.withLock {
                val current = _servers.value.toMutableList()
                val idx = current.indexOfFirst { it.id == server.id }
                if (idx >= 0) current[idx] = server else current.add(server)
                _servers.value = current
                repository.saveServers(current)
            }
            // If we edited the active server, re-push it to the bridge immediately.
            if (server.id == _settings.value.activeServerId) {
                App.bridge.updateUpstreamServer(server, _settings.value.killSwitchEnabled)
            }
            _events.emit(UiEvent.Snack("Server '${server.name}' saved"))
            pingServer(server)
        }
    }

    /**
     * Saves a whole server list in ONE DataStore write (no per-server coroutine
     * race — see the serversMutex note above). Used by onboarding, which edits
     * both pre-seeded servers at once.
     */
    fun saveServersOnce(servers: List<ServerNode>) {
        viewModelScope.launch {
            serversMutex.withLock {
                _servers.value = servers
                repository.saveServers(servers)
            }
            val active = servers.firstOrNull { it.id == _settings.value.activeServerId }
            App.bridge.updateUpstreamServer(active, _settings.value.killSwitchEnabled)
        }
    }

    fun deleteServer(server: ServerNode) {
        viewModelScope.launch {
            val remaining = serversMutex.withLock {
                val remaining = _servers.value.filterNot { it.id == server.id }
                _servers.value = remaining
                repository.saveServers(remaining)
                remaining
            }
            if (server.id == _settings.value.activeServerId) {
                val fallback = remaining.firstOrNull()
                App.bridge.updateUpstreamServer(fallback, _settings.value.killSwitchEnabled)
                repository.updateSettings { it.copy(activeServerId = fallback?.id) }
                reloadActiveTabIfLoaded()
            }
            _events.emit(UiEvent.Snack("Server '${server.name}' deleted"))
        }
    }

    /**
     * v1.0.5: a failed re-ping must NOT flip a server to "offline" and wipe
     * its last-known latency. When the fresh result is offline but a
     * previous result exists, keep the old number and mark it stale — the
     * UI shows the old value in amber instead of "offline". Fresh online
     * results always replace stale ones.
     */
    private fun mergeLatency(fresh: Map<String, ServerLatencyResult>): Map<String, ServerLatencyResult> {
        val merged = _latency.value.toMutableMap()
        fresh.forEach { (id, result) ->
            val prev = merged[id]
            merged[id] = if (result.rttMs == null && prev?.rttMs != null) {
                prev.copy(isOnline = false, stale = true)
            } else {
                result
            }
        }
        return merged
    }

    fun pingServer(server: ServerNode) {
        viewModelScope.launch {
            _pinging.value = _pinging.value + server.id
            val result = LatencyMonitor.pingServer(server)
            _latency.value = mergeLatency(mapOf(server.id to result))
            _pinging.value = _pinging.value - server.id
        }
    }

    fun pingAll() {
        viewModelScope.launch {
            val servers = _servers.value
            if (servers.isEmpty()) return@launch
            _pinging.value = servers.map { it.id }.toSet()
            val results = LatencyMonitor.pingAll(servers)
            _latency.value = mergeLatency(results)
            _pinging.value = emptySet()
        }
    }

    /** Pings every server and switches to the lowest RTT that is online. Real implementation. */
    fun autoSelectFastestServer(silent: Boolean = false) {
        viewModelScope.launch {
            val servers = _servers.value
            if (servers.size < 2) return@launch
            _pinging.value = servers.map { it.id }.toSet()
            val results = LatencyMonitor.pingAll(servers)
            _latency.value = mergeLatency(results)
            _pinging.value = emptySet()
            val fastest = results.values
                .filter { it.isOnline && it.rttMs != null }
                .minByOrNull { it.rttMs!! }
                ?.let { r -> servers.firstOrNull { it.id == r.serverId } }
            if (fastest != null && fastest.id != _settings.value.activeServerId) {
                App.bridge.updateUpstreamServer(fastest, _settings.value.killSwitchEnabled)
                repository.updateSettings { it.copy(activeServerId = fastest.id) }
                reloadActiveTabIfLoaded()
                if (!silent) {
                    _events.emit(
                        UiEvent.Snack("Fastest server: ${fastest.name} • ${results[fastest.id]?.rttMs} ms")
                    )
                }
            } else if (!silent) {
                _events.emit(UiEvent.Snack("No faster server found — staying on current server"))
            }
        }
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val next = transform(_settings.value)
            repository.saveSettings(next)
            // Kill-switch changes must reach the bridge immediately.
            if (next.killSwitchEnabled != _settings.value.killSwitchEnabled) {
                App.bridge.updateUpstreamServer(activeServer, next.killSwitchEnabled)
            }
        }
    }

    fun setOnboardingDone() {
        updateSettings { it.copy(onboardingDone = true) }
    }

    fun profileForTab(tab: BrowserTab): BrowserProfile? {
        if (tab.profileId == incognitoProfile.id) return incognitoProfile
        return _profiles.value.firstOrNull { it.id == tab.profileId }
            ?: _profiles.value.firstOrNull()
    }

    /** GoLogin-style launch: open the profile's existing tab if it has one, else a new tab. */
    fun launchProfile(profile: BrowserProfile) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(activeProfileId = profile.id) }
            val existing = _tabs.value.firstOrNull { it.profileId == profile.id }
            if (existing != null) {
                _activeTabId.value = existing.id
            } else {
                val tab = BrowserTab(profileId = profile.id, desktopMode = _settings.value.desktopSiteDefault)
                _tabs.value = _tabs.value + tab
                _activeTabId.value = tab.id
            }
            // A profile bound to a server follows that server when launched.
            profile.assignedServerId?.let { serverId ->
                _servers.value.firstOrNull { it.id == serverId }?.let { selectServer(it) }
            }
        }
    }

    fun newIncognitoTab() {
        val tab = BrowserTab(
            profileId = incognitoProfile.id,
            desktopMode = _settings.value.desktopSiteDefault
        )
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
    }

    fun isIncognitoTab(tab: BrowserTab): Boolean = tab.profileId == incognitoProfile.id

    /** Real end-to-end check: fetches https://api.ipify.org THROUGH the local bridge. */
    fun testBridgeConnection(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", App.localPort))
                    val conn = URL("https://api.ipify.org").openConnection(proxy) as HttpsURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.connect()
                    val code = conn.responseCode
                    val body = conn.inputStream.bufferedReader().readText().trim()
                    "OK — through ${activeServer?.name ?: "proxy"} your IP is $body (HTTP $code)"
                } catch (e: Exception) {
                    "FAILED — ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}. " +
                        "Check that the server is reachable."
                }
            }
            onResult(result)
        }
    }

    // ------------------------------------------------------------------ bookmarks / history

    fun addBookmark(url: String, title: String) {
        viewModelScope.launch {
            repository.addBookmark(Bookmark(url = url, title = title.ifBlank { url }))
            _events.emit(UiEvent.Snack("Bookmarked"))
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch {
            repository.removeBookmark(url)
            _events.emit(UiEvent.Snack("Bookmark removed"))
        }
    }

    fun isBookmarked(url: String): Boolean = _bookmarks.value.any { it.url == url }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearRecentUrls()
            _events.emit(UiEvent.Snack("History cleared"))
        }
    }

    // ------------------------------------------------------------------ profiles

    fun selectProfile(profile: BrowserProfile) {
        viewModelScope.launch {
            repository.updateSettings { it.copy(activeProfileId = profile.id) }
            // A profile switch opens a fresh tab bound to that profile (true isolation).
            val tab = BrowserTab(profileId = profile.id, desktopMode = _settings.value.desktopSiteDefault)
            _tabs.value = _tabs.value + tab
            _activeTabId.value = tab.id
            _events.emit(UiEvent.Snack("Profile '${profile.name}' active — new tab opened"))
        }
    }

    fun addOrUpdateProfile(profile: BrowserProfile) {
        viewModelScope.launch {
            val current = _profiles.value.toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile else current.add(profile)
            repository.saveProfiles(current)
            _events.emit(UiEvent.Snack("Profile '${profile.name}' saved"))
        }
    }

    fun deleteProfile(profile: BrowserProfile) {
        viewModelScope.launch {
            if (_profiles.value.size <= 1) {
                _events.emit(UiEvent.Snack("Cannot delete the last profile"))
                return@launch
            }
            WebViewProfileEngine.wipeProfileData(profile.id)
            val remaining = _profiles.value.filterNot { it.id == profile.id }
            repository.saveProfiles(remaining)
            if (profile.id == _settings.value.activeProfileId) {
                repository.updateSettings { it.copy(activeProfileId = remaining.first().id) }
            }
            // Close tabs bound to the deleted profile.
            val closed = _tabs.value.filter { it.profileId == profile.id }.map { it.id }
            _tabs.value = _tabs.value.filterNot { it.profileId == profile.id }
            _destroyedTabIds.value = _destroyedTabIds.value + closed
            if (_activeTabId.value in closed) {
                _activeTabId.value = _tabs.value.firstOrNull()?.id
            }
            if (_tabs.value.isEmpty()) newTab()
            _events.emit(UiEvent.Snack("Profile '${profile.name}' deleted and wiped"))
        }
    }

    fun wipeProfileData(profile: BrowserProfile) {
        viewModelScope.launch {
            WebViewProfileEngine.wipeProfileData(profile.id)
            _events.emit(UiEvent.Snack("Cookies & cache wiped for '${profile.name}'"))
        }
    }

    fun clearActiveProfileData() {
        viewModelScope.launch {
            val profile = activeProfile ?: return@launch
            WebViewProfileEngine.wipeProfileData(profile.id)
            _events.emit(UiEvent.Snack("Cleared cookies & cache for '${profile.name}'"))
        }
    }

    // ------------------------------------------------------------------ tabs

    fun newTab(url: String = "") {
        val profile = activeProfile ?: return
        val tab = BrowserTab(
            profileId = profile.id,
            url = url,
            desktopMode = _settings.value.desktopSiteDefault
        )
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) _activeTabId.value = tabId
    }

    fun closeTab(tabId: String) {
        val closedTab = _tabs.value.firstOrNull { it.id == tabId }
        val remaining = _tabs.value.filterNot { it.id == tabId }
        _destroyedTabIds.value = _destroyedTabIds.value + tabId
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        if (remaining.isEmpty()) newTab()
        // Closing the last incognito tab wipes its data immediately.
        if (closedTab?.profileId == incognitoProfile.id &&
            remaining.none { it.profileId == incognitoProfile.id }
        ) {
            viewModelScope.launch { WebViewProfileEngine.wipeProfileData(incognitoProfile.id) }
        }
    }

    fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _tabs.value = _tabs.value.map { if (it.id == tabId) transform(it) else it }
    }

    fun navigateActiveTab(rawInput: String) {
        val tab = activeTab ?: return
        try {
            val url = resolveInputToUrl(rawInput.trim(), _settings.value.searchEngine)
            updateTab(tab.id) { it.copy(url = url, loadError = null) }
            viewModelScope.launch { repository.addRecentUrl(url) }
        } catch (e: Exception) {
            // v1.0.5: a bad input must never kill the app (the omnibox crash
            // path) — show a message instead and keep browsing.
            snack("Could not open that link: ${e.javaClass.simpleName}")
        }
    }

    fun reloadActiveTab() {
        activeTab?.let {
            updateTab(it.id) { t ->
                t.copy(url = t.url, loadError = null, reloadNonce = t.reloadNonce + 1)
            }
        }
    }

    /**
     * v1.0.4: after any active-server change, reload the current page through
     * the new server. Returns true when a reload was triggered. Nothing to
     * reload when no page is open yet, and no double-reload while a load is
     * already in progress.
     */
    private fun reloadActiveTabIfLoaded(): Boolean {
        val tab = activeTab ?: return false
        if (tab.url.isBlank() || tab.isLoading) return false
        reloadActiveTab()
        return true
    }

    fun toggleDesktopMode(tabId: String) {
        updateTab(tabId) { it.copy(desktopMode = !it.desktopMode, loadError = null) }
    }

    fun clearTabError(tabId: String) {
        updateTab(tabId) { it.copy(loadError = null) }
    }

    fun onTabError(tabId: String, message: String) {
        updateTab(tabId) { it.copy(isLoading = false, loadError = message) }
    }

    fun resolveInputToUrl(input: String, engine: SearchEngine): String {
        if (input.isBlank()) return _settings.value.homePageUrl
        val looksLikeUrl = input.contains(".") && !input.contains(" ") &&
            (input.startsWith("http://") || input.startsWith("https://") ||
                input.matches(Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(/.*)?$")))
        return if (looksLikeUrl) {
            if (input.startsWith("http://") || input.startsWith("https://")) input
            else "https://$input"
        } else {
            engine.queryUrl.format(java.net.URLEncoder.encode(input, "UTF-8"))
        }
    }

    // ------------------------------------------------------------------ backup

    fun exportJson(onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(repository.exportJson())
        }
    }

    fun importJson(raw: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = repository.importJson(raw)
            result.fold(
                onSuccess = {
                    onResult(true, "Backup imported — servers, profiles and settings restored")
                },
                onFailure = { e ->
                    onResult(false, e.message ?: "Import failed")
                }
            )
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            repository.resetAll()
            _tabs.value = emptyList()
            _activeTabId.value = null
            _latency.value = emptyMap()
            _events.emit(UiEvent.Snack("All settings reset to defaults"))
        }
    }

    fun snack(message: String) {
        viewModelScope.launch { _events.emit(UiEvent.Snack(message)) }
    }

    fun clearCookiesOnExitIfNeeded() {
        val profile = activeProfile ?: return
        if (profile.clearCookiesOnExit) {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        // Incognito data never survives an app close.
        if (_tabs.value.any { it.profileId == incognitoProfile.id }) {
            WebViewProfileEngine.wipeProfileData(incognitoProfile.id)
        }
    }

    fun newServerTemplate(): ServerNode = ServerNode(
        id = UUID.randomUUID().toString(),
        name = "New Server",
        host = "",
        port = 10800,
        protocol = ProxyProtocol.SOCKS5,
        username = "",
        password = "",
        countryCode = "AUTO"
    )

    fun newProfileTemplate(): BrowserProfile = BrowserProfile(
        id = UUID.randomUUID().toString(),
        name = "Profile ${_profiles.value.size + 1}",
        assignedServerId = null
    )
}
