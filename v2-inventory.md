# FastBrowser v2.0.0 — Step 0 inventory + gap sweep

Traced 2026-11-20 from the v1.0.7 Kotlin app (commit `e1ec8b7`, 23 files, 6175 lines).
Every file was read in full. Grep-verified dead code is marked [GREP].

## 1. Feature / button inventory (what the old app HAS)

### Shell (MainActivity)
- Bottom nav: Profiles | Proxies | Settings
- Green "+ Add profile" FAB — Profiles tab only → ProfileEditDialog
- "Browsing blocked" fail-closed screen (proxy override unsupported + kill-switch on) + "Turn off kill-switch & continue" escape button
- "Starting proxy bridge…" spinner until proxy override applied
- Onboarding gate (`onboardingDone == false` → OnboardingScreen)
- onDestroy: per-profile "clear cookies on exit" + incognito wipe

### Home / Profiles (HomeScreen)
- Header: "F" logo, "FastBrowser", "Self-hosted • N servers • Unlimited", "● Connected / ● Offline" (online if any latency online OR stale)
- "Active server: 🇸🇪 name" line
- Profile cards: tap → edit dialog; green dot when running; name; bound server flag+code+name or "Uses active server"; UA preset label + "• WebRTC shield"; round ▶ play button → launch browser with that profile (bound server follows)
- Footer hint: "Tap ▶ to open a profile's browser. Tap the card to edit it. Each profile keeps its own cookies, storage and cache."

### Proxies (ProxiesScreen)
- Title "FastBrowser proxies", "Unlimited — your own servers. Nothing to buy, no data cap."
- Server cards: tap → ping that server; flag, name, ✓ when active, host:port, latency ("pinging…"/"offline"/"X ms"/"X ms (stale)"), protocol chip "SOCKS5 • Unlimited", "Set active" button (when not active), Edit icon, Delete icon (when >1 server, confirm dialog)
- "No password — test mode" / "No password set — tap Edit…" warnings
- "Add Server" button, "Ping All" button
- ServerEditDialog: Name, Host/IP, Port (1–65535 validation), Protocol dropdown (SOCKS5/HTTP), Username, Password (show/hide), Timezone override, "Test Connection & Latency" button (probes with a copy of the edited server), "Password is stored only on this phone" note, Save/Cancel

### Settings (SettingsScreen) — 9 sections
1. Self-hosted plan card ("Self-hosted • Unlimited • No subscription")
2. Servers & Routing: "Manage servers" row (→ ServerSwitcherSheet), "Strict Kill-Switch" toggle, "Auto-select fastest server" toggle, "Test All Servers Now" button, "Pinging…" text, latency card (per-server ms / stale / offline)
3. Privacy & Security: "WebRTC IP leak blocker" toggle, "Block third-party cookies" toggle, "Clear active profile data" row (confirm dialog), note that per-profile WebRTC/canvas/timezone/language live in the Profiles tab
4. Appearance: "AMOLED pure black" toggle, "Force dark mode on websites" toggle, "Address bar position" Top/Bottom buttons, "Text size: X%" slider 0.5–2.0
5. Speed & Performance: "Ad & tracker blocker" toggle, "Data Saver (block images)" toggle, "Hardware acceleration" toggle, "Desktop site by default" toggle
6. Search & Home: Default search engine dropdown (DuckDuckGo / Google / Brave / Startpage), Homepage URL field (http(s) validation + error text), "Show new-tab page" toggle
7. Backup & Portability: "Export Servers & Profiles (JSON)" → dialog with JSON, Copy + Share buttons, "contains your proxy passwords" warning; "Import Configuration (JSON)" → paste dialog, Import/Close; "Reset All Settings" → confirm dialog
8. About: version (BuildConfig), "Test build — proxy auth OFF…", "Local bridge: 127.0.0.1:port", "Proxy override: applied / not applied yet", "Active server: …", "Servers: …", "How switching works" explainer
9. Diagnostics: "Last bridge error: …" (+time), "Last crash: …" (first 5 stack lines + "Clear crash report" button), explainer, "Test Connection Through Bridge" button (fetches api.ipify.org through the bridge), result text

### Browser (BrowserScreen)
- OmniBar, ONE row: lock/search icon, ONE always-composed text field (clean domain unfocused, full URL selected on focus, clear-X when focused, Go IME action), Reload button (hidden while loading/focused), tab counter → tab switcher, ⋮ menu (compact popup, 280dp)
- ⋮ menu, 17 items: Server entry (name + latency/stale → ServerSwitcherSheet), New tab, New Incognito tab, History, Downloads (opens SYSTEM Downloads app), Bookmarks, Recent tabs (tab switcher), Share…, Copy link, Find in page, Translate… (Google Translate), Desktop site (✓ when on), Reload, Forward (disabled unless canGoForward), Quick IP check (browserleaks.com/ip), Home (homepage), Settings
- Loading progress bar (emerald, 2dp) while loading
- WebView per tab (AndroidView, profile-bound, proxy override, anti-leak script, ad-block interceptor, download listener → ProxyDownloader, page started/finished/progress/error callbacks)
- New Tab Page: "FastBrowser" title, big search field, "Pick a server…" text, server cards (tap → opens switcher sheet), latency, "Ping all servers" button, Quick links (IP check / Google / DuckDuckGo), Recent (top 5 recent URLs)
- ConnectionErrorPage: warning icon, "Blocked by Kill-Switch" / "Connection failed", Retry button, Switch Server button
- PasswordRequiredCard: "Password needed" + "Enter password" button → server editor (only when username set but password blank)
- Find-in-page bar: field, Previous/Next buttons, Close
- TabSwitcherOverlay: "Tabs (N)", 2-column grid cards (title, domain, per-card close X), "New Tab" button, Close button
- BookmarksSheet: "Bookmark this page" / "Remove this page from bookmarks" button, list (tap → open, per-item Delete), "No bookmarks yet."
- HistorySheet: "History" + "Clear all" button (clears GLOBAL recent URLs), list of recent URLs (tap → open)
- Back ordering: tab switcher → server sheet → omnibox editor → find bar → bookmarks → history → WebView back → exit browser

### Server switcher (ServerSwitcherSheet)
- "Switch Server" title, "Ping All" button, explainer (hot-swap <1ms, tab auto-reloads)
- Server cards: tap → select + dismiss; flag, name, ✓ active, host:port, protocol chip, latency, Edit icon, Delete icon (when >1, confirm)
- "Direct Connection (Bypass Proxy)" card — gated on kill-switch OFF (snack if on)
- "Add Server" button; "Auto-select fastest is ON" note when enabled
- Opens ServerEditDialog for add/edit; `editServer` param opens the editor immediately (blank-password guard)

### Onboarding (OnboardingScreen)
- "FastBrowser" headline, explainer (no-auth test build, kill-switch ON by default)
- Per-server cards: flag, name, protocol (+ "• WebSocket"), host:port • user, optional password field (show/hide), "Test Ping" button, result ("Reachable — X ms" / "Unreachable")
- "Launch Browser" button (ONE atomic save of both servers + onboardingDone)

### ViewModel functions (BrowserViewModel)
selectServer, selectDirectConnection, addOrUpdateServer, saveServersOnce, deleteServer, pingServer, pingAll, autoSelectFastestServer, updateSettings, setOnboardingDone, profileForTab, launchProfile, newIncognitoTab, isIncognitoTab, testBridgeConnection, addBookmark, removeBookmark, isBookmarked, clearHistory, selectProfile, addOrUpdateProfile, deleteProfile, wipeProfileData, clearActiveProfileData, newTab, selectTab, closeTab, updateTab, navigateActiveTab, reloadActiveTab, toggleDesktopMode, clearTabError, onTabError, resolveInputToUrl (URL-or-search), exportJson, importJson, resetAll, snack, clearCookiesOnExitIfNeeded, newServerTemplate, newProfileTemplate. `needsPasswordServer` guard. Ephemeral incognito profile (clearCookiesOnExit, wiped when last incognito tab closes / app closes).

### Engine
- LocalProxyBridge: HTTP CONNECT + plain HTTP proxy; SOCKS5 (RFC 1928/1929, remote DNS ATYP 0x03, user/pass auth); HTTP CONNECT upstream; WebSocket upstream (TLS + WS handshake, Sec-WebSocket-Accept check, masked binary frames, fragmentation, ping→pong, close echo); kill-switch 503 (no server) / 502 (upstream fail, surfaced in Diagnostics); direct mode (kill-switch off only); semaphore 64; timeouts 30s read / 10s connect; relayBothDirections (first-direction-ends teardown — the v1.0.1 pump fix); lastError + proxyOverrideApplied flows
- LatencyMonitor: pingTcp / pingWebSocket (TLS + WS handshake, expect 101), 8s timeout, 1 retry after 400ms, pingAll in parallel; stale flag (keep last-known latency in amber)
- WebViewProfileEngine: bindWebViewToLocalBridge (ProxyController, fail-closed); configureWebView (MULTI_PROFILE setProfile, bg #0A0C10, HW accel layer type, third-party cookies, JS/DOM/DB, cache, viewport, zoom, media gesture, images, textZoom 50–200, UA resolve, algorithmic darkening, document-start anti-leak script, shouldInterceptRequest ad-block on non-main-frame only, page callbacks, WebChromeClient progress, download listener → ProxyDownloader); applyRuntimeSettings; resolveUserAgent; wipeProfileData (ProfileStore.deleteProfile + global removeAllCookies + deleteAllData); profileCookieManager (per-profile CookieManager via ProfileStore)
- AntiLeakScriptBuilder: webdriver=false, platform/maxTouchPoints per UA preset, WebRTC constructor throws, enumerateDevices → [], timezone override (Intl resolvedOptions), canvas noise seeded by profile id hash
- AdBlockFilter: 24 hardcoded blocked suffixes, suffix matching
- ProxyDownloader: HTTP proxy 127.0.0.1:port, 15s connect / 30s read, profile cookies, MediaStore Downloads (Q+) / public dir
- CryptoManager: Tink AES256-GCM, AndroidKeysetManager, master key in Android Keystore, prefix "enc:v1:", legacy plaintext passthrough, blank stays blank
- CrashReporter: uncaught handler, last_crash.txt (25 stack lines), chained previous handler, on-device only
- Models: ServerNode (id, name, host, port, protocol, username, password, countryCode, timezoneOverride, languageOverride, transport, wsPath), BrowserProfile (id, name, assignedServerId, userAgentPreset, customUserAgent, blockWebRtcLeaks, canvasNoiseEnabled, clearCookiesOnExit), SearchEngine ×4, AppSettings (16 fields), BrowserTab (incl. reloadNonce), Bookmark, BackupBundle

### Pre-seeded data
- Servers: Stockholm (Cloudflare) cf-gost.limoo.indevs.in:2083 WS /ws, timezone Europe/Stockholm (DEFAULT active); Ohio 18.190.239.234:10800, timezone America/New_York. Both no-auth, SOCKS5. Direct Stockholm removed in v1.0.6.
- Profile: "Default Profile". Settings defaults: kill-switch ON, WebRTC block ON, ad-block ON, search Google, homepage browserleaks.com/ip, showNewTabPage ON.

## 2. Gap sweep — dead code, unwired functions, missing capabilities

### A. Code exists, NO UI caller (dead) — must be wired or dropped
| # | Item | Evidence | v2.0.0 action |
|---|------|----------|---------------|
| A1 | `deleteProfile` | [GREP] only the definition exists; HomeScreen cards tap → edit only, no delete button anywhere | ADD working Delete button on profile cards + in profile editor (confirm dialog, wipes data, closes its tabs, falls back active profile) |
| A2 | `wipeProfileData(profile)` (VM) | [GREP] no UI caller; only `clearActiveProfileData` (Settings, active profile only) reaches it | ADD "Wipe cookies & data" button per profile (card overflow + editor) |
| A3 | `selectProfile` | [GREP] no callers; `launchProfile` is what HomeScreen uses | DROP — launchProfile covers it (opens browser + binds server) |
| A4 | `isIncognitoTab` | [GREP] no callers; incognito tabs exist but UI shows NO incognito badge | IMPLEMENT + ADD incognito badge in omnibox + tab switcher cards |

### B. Settings/fields nothing reads — must be wired or dropped
| # | Item | Evidence | v2.0.0 action |
|---|------|----------|---------------|
| B1 | `ServerNode.languageOverride` | [GREP] appears ONLY in Models.kt; no UI field, no engine use | WIRE IT: add Language override field to the server editor + `navigator.language`/`languages` override in the anti-leak script (same pattern as the timezone override) |
| B2 | `timezoneOverride` | Wired (anti-leak script) | Keep |
| B3 | All other AppSettings fields | Verified wired (kill-switch, WebRTC, 3rd-party cookies, AMOLED, dark pages, address bar position, text scale, ad-block, data saver, HW accel, desktop default, search engine, homepage, show NTP, auto-select, onboardingDone) | Keep all |

### C. Buttons that exist but do nothing / half-work
| # | Item | Evidence | v2.0.0 action |
|---|------|----------|---------------|
| C1 | Menu "Downloads" | Only opens the SYSTEM Downloads app; no in-app download list, no delete | ADD in-app Downloads screen (records from the proxy downloader: name, size, status, open, delete) |
| C2 | Menu "Reload" while loading | Reload button is hidden while loading; no Stop button (old code notes Chrome shows stop instead) | ADD Stop button while a page is loading (webview stopLoading) |
| C3 | NTP server cards | Tap opens the switcher sheet instead of selecting the server directly | KEEP the sheet (it is the same list) — but make the card tap select the server directly AND show the sheet only via the ⋮ menu, to match what the card promises ("Tap a card to switch") — actually the old text says "Tap a card to switch" but it only opens the sheet: FIX by selecting on tap |
| C4 | History "Clear all" | Clears the GLOBAL list; history is not per-profile at all | See D1/D2 |

### D. Missing capabilities a production browser needs — ADD in v2.0.0
| # | Gap | v2.0.0 action |
|---|-----|---------------|
| D1 | NO per-profile history (history is one global `recentUrls` list shared by all profiles) | Per-profile history store; History sheet shows the ACTIVE profile's history; NTP "Recent" is per-profile |
| D2 | NO per-profile history delete (the gap the user named) | "Clear history for this profile" button + delete button on EACH history entry |
| D3 | NO individual history-entry delete (only global "Clear all") | Per-entry delete button (covers D2's second half) |
| D4 | NO profile delete button (A1) | See A1 |
| D5 | NO per-profile wipe-data button (A2) | See A2 |
| D6 | NO in-app downloads list / delete (C1) | See C1 |
| D7 | NO global "clear all cookies & cache" (only active-profile clear) | ADD Settings → "Clear all cookies & cache (all profiles)" |
| D8 | NO global "clear all history (all profiles)" | ADD Settings → Advanced → "Clear history for all profiles" |
| D9 | NO "close all tabs" | ADD "Close all tabs" button in the tab switcher |
| D10 | NO download progress (only a "Downloading X" snack, then silent) | Downloads screen shows status (downloading / done / failed); progress where the stream reports it |
| D11 | NO bookmark rename (add/remove only) | ADD rename in the bookmarks sheet (edit title) |
| D12 | NO incognito indicator (A4) | ADD badge |
| D13 | NO "stop loading" (C2) | ADD |
| D14 | `wipeProfileData` also wipes GLOBAL cookies/storage, not just the profile's (v1.0.7 known issue: "WebView-version-dependent behaviour") | FIX in v2: per-profile wipe must target ONLY that profile's store (native ProfileStore.deleteProfile + profile CookieManager); global wipe is a separate explicit button (D7) |

### E. Deliberately NOT added (with reason)
- Custom ad-block list UI: the 24-domain list is hardcoded and works; a list editor is scope creep, no user asked for it. List stays hardcoded (same 24 domains).
- Import/export of bookmarks/history: export covers servers/profiles/settings (as before); bookmarks/history are device-local by design.
- Light theme: the v1.0.5 layered dark theme is the app's look; AMOLED toggle stays; no new light-theme work.

## 3. Per-profile isolation — what the trace shows, and what Flutter can/cannot do

What the native app actually isolates per profile (WebViewProfileEngine):
- `WebViewCompat.setProfile(webView, profile.id)` (AndroidX WebKit MULTI_PROFILE): separate cookies, localStorage, cache, IndexedDB, WebStorage per profile.
- Per-profile CookieManager via `ProfileStore.getProfile(id).cookieManager` — downloads send THAT profile's cookies.
- `ProfileStore.deleteProfile(id)` for wipe.
- Everything else is per-WebView configuration, not storage: UA (preset/custom/desktop), WebRTC shield, canvas noise, timezone override, third-party-cookie policy, text zoom, darkening, images on/off.

What Flutter's `webview_flutter` CANNOT do (no API):
- Per-profile WebViews / separate cookie stores (single global CookieManager per app).
- Proxy override per WebView (ProxyController is AndroidX-only, global).
- Document-start JavaScript injection (AndroidX-only).
- Download interception with custom handling (no onDownloadStart equivalent; `webview_flutter` has no download listener).
- `findAllAsync` / `findNext` (no find-in-page API).
- `WebSettings.textZoom`, algorithmic darkening, layer type (no API).

Consequence — stated plainly: a pure-`webview_flutter` rewrite would SILENTLY REGRESS per-profile isolation (all profiles would share one cookie jar) and lose downloads, find-in-page, and the anti-leak script. That is not acceptable: the old app's core promise is "each profile keeps its own cookies, storage and cache".

v2.0.0 approach (thin Kotlin, only where Flutter has no API):
- Custom Flutter PlatformView in Kotlin that hosts a real `android.webkit.WebView` and mirrors WebViewProfileEngine: MULTI_PROFILE setProfile, ProxyController override (global, once at startup), document-start JS (script string built in Dart, passed over the channel), ad-block request interception (domain list passed from Dart), download listener → event to Dart (Dart downloads through the bridge with cookies fetched from the profile's CookieManager over the channel), find-in-page, back/forward, reload/stop, text zoom, darkening, layer type, third-party cookies, wipe (ProfileStore.deleteProfile + profile CookieManager only).
- Dart owns everything else: all UI, state, the bridge, WS upstream, latency, crypto, downloads, anti-leak script text, ad-block list, crash capture.
- Data migration (also Kotlin, justified: only native code can read the old Android DataStore protobuf + Tink keyset): a small legacy reader hands the old JSON (passwords already decrypted by the old CryptoManager) to Dart once; Dart re-encrypts into its own store. Passwords are blank (no-auth) so this is near-trivial, but profiles/settings/custom servers must survive the upgrade.

## 4. What v2.0.0 must keep verbatim (from the trace)
- Same package `com.fastbrowser.gologin`, same app name, same debug keystore → installs over v1.0.7 in place.
- Version 2.0.0 / versionCode 9.
- Pre-seeded servers (CF Stockholm default active, Ohio), no-auth, SOCKS5, WS /ws on 2083.
- Bridge semantics: 127.0.0.1, kill-switch fail-closed, atomic hot-swap, semaphore 64, 30s/10s timeouts, first-direction-ends relay, 502/503 errors surfaced.
- Latency semantics: 8s + 1 retry, WS handshake ping, stale-in-amber, never "offline" from one timeout.
- Crypto semantics: AES-GCM, key in secure storage, AAD = server id, legacy plaintext passes through, blank stays blank, decrypt failure never wipes the list, export stays plaintext by design.
- Layered dark theme (bg #0A0C10, surfaces #15171F/#1F222C, borders #2E3342, emerald #00E676, active tint #122019), AMOLED toggle.
- Back ordering: overlays → omnibox editor → find bar → bookmarks → history → WebView back → exit.
- Omnibox: ONE always-composed field, full URL selected on focus.
- All 17 ⋮ menu items, all Settings sections, onboarding flow, NTP, tab switcher, bookmarks/history sheets, server switcher, both dialogs, diagnostics (bridge error + last crash + bridge test), import/export/reset.
