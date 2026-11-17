package com.fastbrowser.gologin.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProxyProtocol {
    SOCKS5,
    HTTP
}

@Serializable
data class ServerNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val username: String = "",
    val password: String = "",
    val countryCode: String = "AUTO",
    val timezoneOverride: String = "",
    val languageOverride: String = ""
)

@Serializable
enum class UserAgentPreset(val label: String, val uaString: String) {
    ANDROID_CHROME(
        "Android Mobile (Chrome 131)",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    ),
    DESKTOP_WINDOWS(
        "Desktop Windows (Chrome 131)",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    ),
    DESKTOP_MAC(
        "Desktop macOS (Chrome 131)",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    ),
    IPHONE_SAFARI(
        "iPhone 16 Pro (Safari 18)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"
    )
}

@Serializable
data class BrowserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val assignedServerId: String?,
    val userAgentPreset: UserAgentPreset = UserAgentPreset.ANDROID_CHROME,
    val customUserAgent: String = "",
    val blockWebRtcLeaks: Boolean = true,
    val canvasNoiseEnabled: Boolean = false,
    val clearCookiesOnExit: Boolean = false
)

@Serializable
enum class SearchEngine(val label: String, val queryUrl: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s")
}

@Serializable
data class AppSettings(
    val activeServerId: String? = null,
    val activeProfileId: String = "default_profile",
    val killSwitchEnabled: Boolean = true,
    val blockWebRtcGlobal: Boolean = true,
    val adAndTrackerBlocker: Boolean = true,
    val forceDarkWebPages: Boolean = false,
    val bottomAddressBar: Boolean = false,
    val amoledBlackTheme: Boolean = true,
    val hardwareAcceleration: Boolean = true,
    val blockImagesDataSaver: Boolean = false,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val homePageUrl: String = "https://browserleaks.com/ip",
    // Extended settings (all wired to the ViewModel/DataStore — no dead toggles)
    val autoSelectFastestServer: Boolean = false,
    val blockThirdPartyCookies: Boolean = false,
    val textScale: Float = 1.0f,
    val desktopSiteDefault: Boolean = false,
    val showNewTabPage: Boolean = true,
    val onboardingDone: Boolean = false
)

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val title: String = "New Tab",
    val url: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val desktopMode: Boolean = false,
    val loadError: String? = null
)

@Serializable
data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class BackupBundle(
    val servers: List<ServerNode>,
    val profiles: List<BrowserProfile>,
    val settings: AppSettings
)
