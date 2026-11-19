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
 *
 * v1.0.7: server passwords are stored ENCRYPTED (Tink AES256-GCM via
 * CryptoManager, key in the Android Keystore, one associated-data value per
 * server id). The boundary is here: saveServers encrypts, serversFlow
 * decrypts, so everything in memory stays plaintext while the stored JSON
 * is ciphertext. Legacy plaintext entries (including blank no-auth
 * passwords) still read fine — CryptoManager passes them through.
 * Export deliberately stays plaintext (a backup must import on a fresh
 * install whose Keystore keyset is different); the UI already warns not
 * to share exports.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val crypto = CryptoManager(context)

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
            // v1.0.6: the list is now Ohio + Stockholm (Cloudflare) only.
            // The direct Stockholm server (16.192.116.137:10800) is GONE
            // from the app — it does not work from Iran (raw SOCKS5 is
            // DPI-dropped; port 10801 failed too) and the user ordered it
            // removed. The Sweden Gost listener on :10800 stays up on the
            // server itself; this is app-side only. The Cloudflare route
            // is first: it is the default active server.
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
            // v1.0.6: the Cloudflare route is the default — the direct
            // Stockholm server was removed (DPI-blocked from Iran), so a
            // fresh install or a reset must not land on a dead server.
            activeServerId = STOCKHOLM_CF_ID,
            activeProfileId = DEFAULT_PROFILE_ID
        )
    }

    val serversFlow: Flow<List<ServerNode>> = context.dataStore.data.map { prefs ->
        val raw = prefs[serversKey]
        val stored = if (raw.isNullOrBlank()) defaultServers()
        else runCatching { json.decodeFromString<List<ServerNode>>(raw) }.getOrElse { defaultServers() }
        // v1.0.7: stored passwords are ciphertext — decrypt on read.
        stored.map { node -> node.copy(password = decryptPassword(node)) }
    }

    /**
     * v1.0.7: decrypt one stored password (associated data = the server id).
     * Blank stays blank; legacy plaintext passes through (CryptoManager).
     * A decrypt failure (e.g. the Keystore keyset was lost) must never kill
     * the whole servers flow — fall back to blank; the servers are no-auth,
     * and a stuck flow would leave the app with no servers at all.
     */
    private fun decryptPassword(node: ServerNode): String {
        if (node.password.isEmpty()) return ""
        return runCatching { crypto.decrypt(node.password, node.id.toByteArray()) }.getOrElse { "" }
    }

    /**
     * v1.0.7: encrypt one password for storage. Blank stays blank. If
     * encryption itself fails, keep the plaintext rather than losing the
     * user's password — the failure mode is "not encrypted", not "lost".
     */
    private fun encryptPassword(node: ServerNode): String {
        if (node.password.isEmpty()) return ""
        return runCatching { crypto.encrypt(node.password, node.id.toByteArray()) }
            .getOrElse { node.password }
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
        // v1.0.7: encrypt passwords before they reach DataStore.
        val encrypted = servers.map { it.copy(password = encryptPassword(it)) }
        context.dataStore.edit { it[serversKey] = json.encodeToString(encrypted) }
    }

    /**
     * TEST BUILD (v1.0.2) one-time migration: existing installs from v1.0.0/v1.0.1
     * stored the old "fastbrowser" username and a password on the Ohio server.
     * The servers now run NO-AUTH, so strip both fields. (The direct
     * Stockholm server is deleted outright by migrateRemoveStockholmDirect
     * below, so it needs no credential strip here.) Custom servers the user
     * added themselves are left untouched. Returns true when anything changed.
     */
    suspend fun migrateKnownServersToNoAuth(): Boolean {
        val current = serversFlow.first()
        var changed = false
        val migrated = current.map { server ->
            if (server.id == OHIO_ID &&
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
     * v1.0.6 one-time migration: delete the direct Stockholm server
     * (STOCKHOLM_ID, 16.192.116.137:10800) from existing installs — it does
     * not work from Iran and the user ordered it removed. If it was the
     * active server, the active server becomes Stockholm (Cloudflare); if
     * that is missing too, the first remaining server. Runs on every launch
     * but only writes once.
     */
    suspend fun migrateRemoveStockholmDirect(): Boolean {
        val current = serversFlow.first()
        if (current.none { it.id == STOCKHOLM_ID }) return false
        val remaining = current.filterNot { it.id == STOCKHOLM_ID }
        if (remaining.isEmpty()) {
            saveServers(defaultServers())
            saveSettings(settingsFlow.first().copy(activeServerId = STOCKHOLM_CF_ID))
            return true
        }
        saveServers(remaining)
        val settings = settingsFlow.first()
        if (settings.activeServerId == STOCKHOLM_ID) {
            val fallback = remaining.firstOrNull { it.id == STOCKHOLM_CF_ID } ?: remaining.first()
            saveSettings(settings.copy(activeServerId = fallback.id))
        }
        return true
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

    /** Full reset: back to the pre-seeded Stockholm (Cloudflare) + Ohio
     *  servers and default settings. v1.0.6: bookmarks are reset too — the
     *  old code left them behind, so a "reset" silently kept them. */
    suspend fun resetAll() {
        context.dataStore.edit { prefs ->
            prefs[serversKey] = json.encodeToString(defaultServers())
            prefs[profilesKey] = json.encodeToString(defaultProfiles())
            prefs[settingsKey] = json.encodeToString(defaultSettings())
            prefs[recentUrlsKey] = json.encodeToString(emptyList<String>())
            prefs[bookmarksKey] = json.encodeToString(emptyList<Bookmark>())
        }
    }
}
