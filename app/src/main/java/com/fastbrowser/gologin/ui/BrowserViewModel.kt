package com.fastbrowser.gologin.ui

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastbrowser.gologin.App
import com.fastbrowser.gologin.browser.WebViewProfileEngine
import com.fastbrowser.gologin.data.SettingsRepository
import com.fastbrowser.gologin.model.AppSettings
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.model.BrowserTab
import com.fastbrowser.gologin.model.ProxyProtocol
import com.fastbrowser.gologin.model.SearchEngine
import com.fastbrowser.gologin.model.ServerNode
import com.fastbrowser.gologin.network.LatencyMonitor
import com.fastbrowser.gologin.network.ServerLatencyResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

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
            combine(
                repository.serversFlow,
                repository.profilesFlow,
                repository.settingsFlow,
                repository.recentUrlsFlow
            ) { servers, profiles, settings, recent ->
                _servers.value = servers
                _profiles.value = profiles
                _settings.value = settings
                _recentUrls.value = recent
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

    /** One-tap switch: atomic bridge hot-swap (<1ms) + persisted active server + fresh ping. */
    fun selectServer(server: ServerNode) {
        viewModelScope.launch {
            App.bridge.updateUpstreamServer(server, _settings.value.killSwitchEnabled)
            repository.updateSettings { it.copy(activeServerId = server.id) }
            _events.emit(
                UiEvent.Snack(
                    message = "Switched to ${server.name} • ${server.host}",
                    actionLabel = "Reload",
                    onAction = { reloadActiveTab() }
                )
            )
            pingServer(server)
        }
    }

    /** Direct (no proxy) mode — only reachable when the kill-switch is OFF. */
    fun selectDirectConnection() {
        viewModelScope.launch {
            App.bridge.updateUpstreamServer(null, killSwitch = false)
            repository.updateSettings { it.copy(activeServerId = null) }
            _events.emit(UiEvent.Snack("Direct connection — traffic bypasses the proxy"))
        }
    }

    fun addOrUpdateServer(server: ServerNode) {
        viewModelScope.launch {
            val current = _servers.value.toMutableList()
            val idx = current.indexOfFirst { it.id == server.id }
            if (idx >= 0) current[idx] = server else current.add(server)
            repository.saveServers(current)
            // If we edited the active server, re-push it to the bridge immediately.
            if (server.id == _settings.value.activeServerId) {
                App.bridge.updateUpstreamServer(server, _settings.value.killSwitchEnabled)
            }
            _events.emit(UiEvent.Snack("Server '${server.name}' saved"))
            pingServer(server)
        }
    }

    fun deleteServer(server: ServerNode) {
        viewModelScope.launch {
            val remaining = _servers.value.filterNot { it.id == server.id }
            repository.saveServers(remaining)
            if (server.id == _settings.value.activeServerId) {
                val fallback = remaining.firstOrNull()
                App.bridge.updateUpstreamServer(fallback, _settings.value.killSwitchEnabled)
                repository.updateSettings { it.copy(activeServerId = fallback?.id) }
            }
            _events.emit(UiEvent.Snack("Server '${server.name}' deleted"))
        }
    }

    fun pingServer(server: ServerNode) {
        viewModelScope.launch {
            _pinging.value = _pinging.value + server.id
            val result = LatencyMonitor.pingServer(server)
            _latency.value = _latency.value + (server.id to result)
            _pinging.value = _pinging.value - server.id
        }
    }

    fun pingAll() {
        viewModelScope.launch {
            val servers = _servers.value
            if (servers.isEmpty()) return@launch
            _pinging.value = servers.map { it.id }.toSet()
            val results = LatencyMonitor.pingAll(servers)
            _latency.value = results
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
            _latency.value = results
            _pinging.value = emptySet()
            val fastest = results.values
                .filter { it.isOnline && it.rttMs != null }
                .minByOrNull { it.rttMs!! }
                ?.let { r -> servers.firstOrNull { it.id == r.serverId } }
            if (fastest != null && fastest.id != _settings.value.activeServerId) {
                App.bridge.updateUpstreamServer(fastest, _settings.value.killSwitchEnabled)
                repository.updateSettings { it.copy(activeServerId = fastest.id) }
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
        val remaining = _tabs.value.filterNot { it.id == tabId }
        _destroyedTabIds.value = _destroyedTabIds.value + tabId
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        if (remaining.isEmpty()) newTab()
    }

    fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _tabs.value = _tabs.value.map { if (it.id == tabId) transform(it) else it }
    }

    fun navigateActiveTab(rawInput: String) {
        val tab = activeTab ?: return
        val url = resolveInputToUrl(rawInput.trim(), _settings.value.searchEngine)
        updateTab(tab.id) { it.copy(url = url, loadError = null) }
        viewModelScope.launch { repository.addRecentUrl(url) }
    }

    fun reloadActiveTab() {
        activeTab?.let { updateTab(it.id) { t -> t.copy(url = t.url, loadError = null) } }
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
