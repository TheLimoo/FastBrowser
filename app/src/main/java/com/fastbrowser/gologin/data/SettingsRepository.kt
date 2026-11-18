package com.fastbrowser.gologin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fastbrowser.gologin.model.AppSettings
import com.fastbrowser.gologin.model.BackupBundle
import com.fastbrowser.gologin.model.Bookmark
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.model.ProxyProtocol
import com.fastbrowser.gologin.model.ProxyTransport
import com.fastbrowser.gologin.model.ServerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fastbrowser_settings")

/**
 * DataStore persistence for servers, profiles and settings.
 * Passwords live ONLY here (on-device DataStore) — never in the repo or in code.
 * Pre-seeds Stockholm + Ohio (direct) and Stockholm-via-Cloudflare (WebSocket)
 * with blank username/password on first launch (TEST BUILD v1.0.2+: the
 * servers run NO-AUTH, so blank user+pass is correct).
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val serversKey = stringPreferencesKey("servers_json")
    private val profilesKey = stringPreferencesKey("profiles_json")
    private val settingsKey = stringPreferencesKey("settings_json")
    private val recentUrlsKey = stringPreferencesKey("recent_urls_json")
    private val bookmarksKey = stringPreferencesKey("bookmarks_json")

    companion object {
        const val STOCKHOLM_ID = "server-stockholm"
        const val OHIO_ID = "server-ohio"
        const val STOCKHOLM_CF_ID = "server-stockholm-cf"
        const val DEFAULT_PROFILE_ID = "default_profile"

        fun defaultServers(): List<ServerNode> = listOf(
            ServerNode(
                id = STOCKHOLM_ID,
                name = "Stockholm",
                host = "16.192.116.137",
                port = 10800,
                protocol = ProxyProtocol.SOCKS5,
                // TEST BUILD (v1.0.2): the servers currently run NO-AUTH, so both
                // fields are blank. The bridge treats blank user+pass as no-auth.
                username = "",
                password = "",
                countryCode = "SE",
                timezoneOverride = "Europe/Stockholm"
            ),
            ServerNode(
                id = OHIO_ID,
                name = "Ohio",
                host = "18.190.239.234",
                port = 10800,
                protocol = ProxyProtocol.SOCKS5,
                username = "",
                password = "",
                countryCode = "US",
                timezoneOverride = "America/New_York"
            ),
            // v1.0.3: Sweden through Cloudflare. Direct TCP to Sweden:10800/10801
            // is DPI-dropped from Iran (the proxy handshake never gets through),
            // but the same Gost behind Cloudflare on WSS :2083 works — it looks
            // like ordinary HTTPS. Server side verified end-to-end from Ohio.
            ServerNode(
                id = STOCKHOLM_CF_ID,
                name = "Stockholm (Cloudflare)",
                host = "cf-gost.limoo.indevs.in",
                port = 2083,
                protocol = ProxyProtocol.SOCKS5,
                username = "",
                password = "",
                countryCode = "SE",
                timezoneOverride = "Europe/Stockholm",
                transport = ProxyTransport.WEBSOCKET,
                wsPath = "/ws"
            )
        )

        fun defaultProfiles(): List<BrowserProfile> = listOf(
            BrowserProfile(
                id = DEFAULT_PROFILE_ID,
                name = "Default Profile",
                assignedServerId = null
            )
        )

        fun defaultSettings(): AppSettings = AppSettings(
            activeServerId = STOCKHOLM_ID,
            activeProfileId = DEFAULT_PROFILE_ID
        )
    }

    val serversFlow: Flow<List<ServerNode>> = context.dataStore.data.map { prefs ->
        val raw = prefs[serversKey]
        if (raw.isNullOrBlank()) defaultServers()
        else runCatching { json.decodeFromString<List<ServerNode>>(raw) }.getOrElse { defaultServers() }
    }

    val profilesFlow: Flow<List<BrowserProfile>> = context.dataStore.data.map { prefs ->
        val raw = prefs[profilesKey]
        if (raw.isNullOrBlank()) defaultProfiles()
        else runCatching { json.decodeFromString<List<BrowserProfile>>(raw) }.getOrElse { defaultProfiles() }
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val raw = prefs[settingsKey]
        if (raw.isNullOrBlank()) defaultSettings()
        else runCatching { json.decodeFromString<AppSettings>(raw) }.getOrElse { defaultSettings() }
    }

    val recentUrlsFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[recentUrlsKey]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }
    }

    val bookmarksFlow: Flow<List<Bookmark>> = context.dataStore.data.map { prefs ->
        val raw = prefs[bookmarksKey]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<Bookmark>>(raw) }.getOrElse { emptyList() }
    }

    suspend fun seedIfNeeded() {
        context.dataStore.edit { prefs ->
            if (prefs[serversKey].isNullOrBlank()) {
                prefs[serversKey] = json.encodeToString(defaultServers())
            }
            if (prefs[profilesKey].isNullOrBlank()) {
                prefs[profilesKey] = json.encodeToString(defaultProfiles())
            }
            if (prefs[settingsKey].isNullOrBlank()) {
                prefs[settingsKey] = json.encodeToString(defaultSettings())
            }
        }
    }

    suspend fun saveServers(servers: List<ServerNode>) {
        context.dataStore.edit { it[serversKey] = json.encodeToString(servers) }
    }

    /**
     * TEST BUILD (v1.0.2) one-time migration: existing installs from v1.0.0/v1.0.1
     * stored the old "fastbrowser" username and a password on the two known
     * servers. The servers now run NO-AUTH, so strip both fields from the known
     * server ids. Custom servers the user added themselves are left untouched.
     * Returns true when anything was changed.
     */
    suspend fun migrateKnownServersToNoAuth(): Boolean {
        val current = serversFlow.first()
        var changed = false
        val migrated = current.map { server ->
            if ((server.id == STOCKHOLM_ID || server.id == OHIO_ID) &&
                (server.username.isNotBlank() || server.password.isNotBlank())
            ) {
                changed = true
                server.copy(username = "", password = "")
            } else server
        }
        if (changed) saveServers(migrated)
        return changed
    }

    /**
     * v1.0.3 one-time migration: existing installs have only the two direct
     * servers stored, so append "Stockholm (Cloudflare)" (the WebSocket
     * route) if it is missing. Runs on every launch but only writes once.
     * Returns true when anything was changed.
     */
    suspend fun migrateAddCloudflareServer(): Boolean {
        val current = serversFlow.first()
        if (current.any { it.id == STOCKHOLM_CF_ID }) return false
        val cfServer = defaultServers().first { it.id == STOCKHOLM_CF_ID }
        saveServers(current + cfServer)
        return true
    }

    /**
     * v1.0.5 one-time migration: the layered dark theme is the new default.
     * Existing installs stored amoledBlackTheme=true (the old default),
     * which would keep the flat pure-black background the user asked to
     * replace. Turn it off once so the new theme is what they see — the
     * toggle in Settings → Appearance ("AMOLED pure black") still restores
     * true black in one tap. Runs on every launch but only writes once.
     * Returns true when anything was changed.
     */
    suspend fun migrateThemeToLayered(): Boolean {
        val current = settingsFlow.first()
        if (!current.amoledBlackTheme) return false
        saveSettings(current.copy(amoledBlackTheme = false))
        return true
    }

    suspend fun saveProfiles(profiles: List<BrowserProfile>) {
        context.dataStore.edit { it[profilesKey] = json.encodeToString(profiles) }
    }

    suspend fun saveSettings(settings: AppSettings) {
        context.dataStore.edit { it[settingsKey] = json.encodeToString(settings) }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = settingsFlow.first()
        saveSettings(transform(current))
    }

    suspend fun addRecentUrl(url: String) {
        if (url.isBlank()) return
        val current = recentUrlsFlow.first().toMutableList()
        current.remove(url)
        current.add(0, url)
        val trimmed = current.take(10)
        context.dataStore.edit { it[recentUrlsKey] = json.encodeToString(trimmed) }
    }

    suspend fun clearRecentUrls() {
        context.dataStore.edit { it[recentUrlsKey] = json.encodeToString(emptyList<String>()) }
    }

    suspend fun addBookmark(bookmark: Bookmark) {
        val current = bookmarksFlow.first().toMutableList()
        if (current.any { it.url == bookmark.url }) return
        current.add(0, bookmark)
        context.dataStore.edit { it[bookmarksKey] = json.encodeToString(current.take(500)) }
    }

    suspend fun removeBookmark(url: String) {
        val current = bookmarksFlow.first().filterNot { it.url == url }
        context.dataStore.edit { it[bookmarksKey] = json.encodeToString(current) }
    }

    /** Export everything (servers + profiles + settings) as JSON. Contains passwords — UI warns not to share. */
    suspend fun exportJson(): String {
        val servers = serversFlow.first()
        val profiles = profilesFlow.first()
        val settings = settingsFlow.first()
        return json.encodeToString(BackupBundle(servers, profiles, settings))
    }

    /** Import a JSON backup. Returns an error message on invalid input instead of crashing. */
    suspend fun importJson(raw: String): Result<Unit> {
        return try {
            val bundle = json.decodeFromString<BackupBundle>(raw.trim())
            require(bundle.servers.isNotEmpty()) { "Backup contains no servers." }
            require(bundle.profiles.isNotEmpty()) { "Backup contains no profiles." }
            bundle.servers.forEach { server ->
                require(server.host.isNotBlank()) { "A server is missing its host." }
                require(server.port in 1..65535) { "Server '${server.name}' has an invalid port." }
            }
            saveServers(bundle.servers)
            saveProfiles(bundle.profiles)
            saveSettings(bundle.settings)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Import failed: ${e.message ?: "invalid JSON"}"))
        }
    }

    /** Full reset: back to the pre-seeded Stockholm/Ohio servers and default settings. */
    suspend fun resetAll() {
        context.dataStore.edit { prefs ->
            prefs[serversKey] = json.encodeToString(defaultServers())
            prefs[profilesKey] = json.encodeToString(defaultProfiles())
            prefs[settingsKey] = json.encodeToString(defaultSettings())
            prefs[recentUrlsKey] = json.encodeToString(emptyList<String>())
        }
    }
}
