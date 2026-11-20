// App state — Dart equivalent of BrowserViewModel.kt + the app-level
// wiring (bridge start, proxy bind, legacy migration, latency, downloads).
//
// One ChangeNotifier holds everything: servers, profiles, settings, tabs,
// per-profile history, bookmarks, downloads, latency, bridge state.
// The UI reads it and calls its methods; nothing in the UI talks to the
// store, the bridge or the platform channels directly.
//
// Gap-sweep additions (new vs the Kotlin VM, all wired to UI):
//  - deleteProfile — real delete (confirm dialog in the UI), wipes the
//    profile's native WebView data and closes its tabs. v1.0.7 had NO
//    caller for this.
//  - wipeProfileData — per-profile cookies/storage wipe (v1.0.7's
//    wipeProfileData was only ever called for the ACTIVE profile).
//  - clearHistoryForProfile / deleteHistoryEntry — per-profile history
//    delete, the gap the user named. History is stored per profile now.
//  - clearAllHistory / clearAllCookiesAndCache — the global variants.
//  - closeAllTabs, renameBookmark, isIncognitoTab (badge) — all missing.
//  - ServerNode.languageOverride — wired: the server editor exposes it
//    and the anti-leak script overrides navigator.language/languages.
//  - Dead code dropped: selectProfile (launchProfile covers it).

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import 'bridge.dart';
import 'crash.dart';
import 'downloader.dart';
import 'latency.dart';
import 'models.dart';
import 'native.dart';
import 'store.dart';

class AppState extends ChangeNotifier {
  final Store _store;

  AppState({Store? store}) : _store = store ?? Store();

  // ----- persisted state -------------------------------------------------
  List<ServerNode> servers = [];
  List<BrowserProfile> profiles = [];
  AppSettings settings = const AppSettings();
  Map<String, List<String>> history = {};
  List<Bookmark> bookmarks = [];
  List<DownloadRecord> downloads = [];

  // ----- runtime state ---------------------------------------------------
  List<BrowserTab> tabs = [];
  String? activeTabId;
  bool isReady = false;
  bool isPinging = false;
  bool hasIncognitoTabs = false;
  int bridgePort = 0;
  bool proxyOverrideApplied = false;
  bool proxyBlocked = false;
  String bridgeError = '';
  String bridgeTestResult = '';
  String? activeCountry;
  Map<String, ServerLatencyResult> latencyResults = {};
  bool hasAnyServer = false;
  Map<String, int> profileConfigVersions = {};
  bool onboardingCompleted = false;

  final LocalProxyBridge _bridge = LocalProxyBridge();
  final StreamController<String> _events =
      StreamController<String>.broadcast();
  Stream<String> get events => _events.stream;
  StreamSubscription<BridgeError?>? _bridgeErrorSub;
  Future<void> _serverSaveChain = Future.value();

  /// Set by the browser screen to the active tab WebView's cookie getter,
  /// so downloads (including retries from the Downloads screen) carry the
  /// profile's cookies. Null when no WebView is mounted.
  Future<String?> Function(String url)? cookieProvider;

  // ----- derived ---------------------------------------------------------
  ServerNode? get activeServer {
    for (final s in servers) {
      if (s.id == settings.activeServerId) return s;
    }
    return servers.isNotEmpty ? servers.first : null;
  }

  BrowserProfile? get activeProfile {
    for (final p in profiles) {
      if (p.id == settings.activeProfileId) return p;
    }
    return profiles.isNotEmpty ? profiles.first : null;
  }

  BrowserTab? get activeTab {
    for (final t in tabs) {
      if (t.id == activeTabId) return t;
    }
    return tabs.isNotEmpty ? tabs.first : null;
  }

  bool get isConnected => activeServer != null && bridgePort != 0;

  bool isIncognitoTab(BrowserTab tab) => tab.profileId == 'incognito';

  List<String> historyFor(String profileId) => history[profileId] ?? const [];

  BrowserTab? _tabById(String id) {
    for (final t in tabs) {
      if (t.id == id) return t;
    }
    return null;
  }

  // ----- init ------------------------------------------------------------

  Future<void> init() async {
    final legacy = await NativeBridge.readLegacyStore();
    final data = await _store.load(legacyJson: legacy);
    servers = data.servers;
    profiles = data.profiles;
    settings = data.settings;
    history = data.history;
    bookmarks = data.bookmarks;
    downloads = data.downloads;
    hasAnyServer = servers.isNotEmpty;
    onboardingCompleted = settings.onboardingDone;

    _bridgeErrorSub = _bridge.lastErrorStream.listen((err) {
      if (err != null) {
        bridgeError = err.message;
        notifyListeners();
      }
    });
    final port = await _bridge.start();
    bridgePort = port;
    if (port == 0) {
      bridgeError = 'Failed to start local proxy bridge';
    } else {
      _syncBridgeUpstream();
      final result = await NativeBridge.bindProxy(
        port: port,
        killSwitch: settings.killSwitchEnabled,
      );
      if (result == 'applied') {
        proxyOverrideApplied = true;
      } else if (result == 'unsupported' && settings.killSwitchEnabled) {
        proxyBlocked = true;
        bridgeError =
            'Proxy override not available on this device, so FastBrowser blocks traffic to keep you safe.';
      }
    }
    newTab();
    isReady = true;
    notifyListeners();
    unawaited(refreshLatency());
  }

  @override
  void dispose() {
    unawaited(_bridgeErrorSub?.cancel());
    unawaited(_bridge.stop());
    _bridge.dispose();
    _events.close();
    super.dispose();
  }

  void _syncBridgeUpstream() {
    _bridge.updateUpstreamServer(activeServer, settings.killSwitchEnabled);
  }

  Future<void> _persist() => _store.save(StoreData(
        servers: servers,
        profiles: profiles,
        settings: settings,
        history: history,
        bookmarks: bookmarks,
        downloads: downloads,
      ));

  void _emit(String message) => _events.add(message);

  // ----- servers ---------------------------------------------------------

  Future<void> refreshLatency() async {
    if (servers.isEmpty || isPinging) return;
    isPinging = true;
    notifyListeners();
    try {
      final results = await LatencyMonitor.pingAll(servers);
      latencyResults = {
        ...latencyResults,
        ...results.map((k, v) => MapEntry(
              k,
              latencyResults.containsKey(k) && v.rttMs == null
                  ? latencyResults[k]!
                      .copyWith(stale: true, isOnline: true)
                  : v,
            )),
      };
      hasAnyServer = servers.isNotEmpty;
      notifyListeners();
    } finally {
      isPinging = false;
      notifyListeners();
    }
  }

  Future<void> saveServer(ServerNode server) async {
    final task = _serverSaveChain.then((_) async {
      final idx = servers.indexWhere((s) => s.id == server.id);
      if (idx >= 0) {
        servers = [...servers]..[idx] = server;
      } else {
        servers = [...servers, server];
      }
      await _persist();
      _syncBridgeUpstream();
      notifyListeners();
    });
    _serverSaveChain = task.catchError((_) {});
    await task;
  }

  Future<void> deleteServer(String id) async {
    if (servers.length <= 1) {
      _emit('You need at least one server');
      return;
    }
    final wasActive = settings.activeServerId == id;
    servers = servers.where((s) => s.id != id).toList();
    if (wasActive && servers.isNotEmpty) {
      settings = settings.copyWith(activeServerId: servers.first.id);
    }
    latencyResults = Map.of(latencyResults)..remove(id);
    hasAnyServer = servers.isNotEmpty;
    await _persist();
    _syncBridgeUpstream();
    notifyListeners();
    if (wasActive) {
      await bindProxyForActiveServer();
      unawaited(refreshLatency());
    }
  }

  Future<void> setActiveServer(ServerNode server) async {
    if (server.id == settings.activeServerId) {
      await bindProxyForActiveServer();
      return;
    }
    settings = settings.copyWith(activeServerId: server.id);
    await _persist();
    _syncBridgeUpstream();
    notifyListeners();
    await bindProxyForActiveServer();
    _emit('${server.name} selected');
    reloadActiveTabIfLoaded();
  }

  Future<void> bindProxyForActiveServer() async {
    if (bridgePort == 0) return;
    final result = await NativeBridge.bindProxy(
      port: bridgePort,
      killSwitch: settings.killSwitchEnabled,
    );
    proxyOverrideApplied = result == 'applied';
    proxyBlocked = result == 'unsupported' && settings.killSwitchEnabled;
    if (proxyBlocked) {
      bridgeError =
          'Proxy override not available on this device, so FastBrowser blocks traffic to keep you safe.';
    } else {
      bridgeError = '';
    }
    notifyListeners();
  }

  Future<void> autoSelectFastestServer() async {
    final results = await LatencyMonitor.pingAll(servers);
    latencyResults = {...latencyResults, ...results};
    final online = results.values.where((r) => r.isOnline).toList();
    if (online.isEmpty) {
      _emit('No servers responded. Try again or check your connection.');
      notifyListeners();
      return;
    }
    online.sort((a, b) => (a.rttMs ?? 1 << 30).compareTo(b.rttMs ?? 1 << 30));
    ServerNode best = servers.first;
    for (final s in servers) {
      if (s.id == online.first.serverId) {
        best = s;
        break;
      }
    }
    await setActiveServer(best);
    _emit('${best.name} is fastest (${online.first.rttMs} ms)');
  }

  // ----- profiles --------------------------------------------------------

  Future<void> saveProfile(BrowserProfile profile) async {
    final idx = profiles.indexWhere((p) => p.id == profile.id);
    if (idx >= 0) {
      profiles = [...profiles]..[idx] = profile;
    } else {
      profiles = [...profiles, profile];
    }
    // Shield settings (UA / WebRTC / canvas / timezone / language) are
    // baked into the native WebView at creation — bump the version so
    // the browser screen rebuilds that profile's tabs with new params.
    profileConfigVersions[profile.id] =
        (profileConfigVersions[profile.id] ?? 0) + 1;
    await _persist();
    notifyListeners();
  }

  /// Real profile delete (v1.0.7 had NO UI caller for this). Wipes the
  /// profile's native WebView data, closes its tabs, and moves the active
  /// profile if needed. Returns false when the delete was refused.
  Future<bool> deleteProfile(String profileId) async {
    if (profiles.length <= 1) {
      _emit('You need at least one profile');
      return false;
    }
    String name = '';
    for (final p in profiles) {
      if (p.id == profileId) {
        name = p.name;
        break;
      }
    }
    profiles = profiles.where((p) => p.id != profileId).toList();
    history = Map.of(history)..remove(profileId);
    await NativeBridge.deleteProfileData(profileId);
    final hadActiveTab = tabs.any((t) => t.profileId == profileId);
    tabs = tabs.where((t) => t.profileId != profileId).toList();
    if (settings.activeProfileId == profileId) {
      settings = settings.copyWith(activeProfileId: profiles.first.id);
    }
    activeTabId = tabs.isEmpty ? null : tabs.first.id;
    profileConfigVersions.remove(profileId);
    await _persist();
    if (hadActiveTab && tabs.isEmpty) {
      newTab();
    }
    notifyListeners();
    _emit('Profile "$name" deleted');
    return true;
  }

  /// Wipes one profile's cookies + storage + cache (per-profile).
  Future<void> wipeProfileData(BrowserProfile profile) async {
    await NativeBridge.deleteProfileData(profile.id);
    profileConfigVersions[profile.id] =
        (profileConfigVersions[profile.id] ?? 0) + 1;
    _emit('Cleared data for ${profile.name}');
    notifyListeners();
  }

  /// Wipes the ACTIVE profile's data (the v1.0.7 clearActiveProfileData).
  Future<void> clearActiveProfileData() async {
    final profile = activeProfile;
    if (profile != null) await wipeProfileData(profile);
  }

  /// Global wipe: every profile's cookies + storage + cache, plus the
  /// global cookie jar and WebStorage. Explicit, separate button.
  Future<void> clearAllCookiesAndCache() async {
    await NativeBridge.clearAllWebData([
      ...profiles.map((p) => p.id),
      'incognito',
    ]);
    _emit('Cleared cookies and cache for all profiles');
    notifyListeners();
  }

  Future<void> setActiveProfile(BrowserProfile profile) async {
    settings = settings.copyWith(activeProfileId: profile.id);
    await _persist();
    notifyListeners();
  }

  Future<void> launchProfile(BrowserProfile profile) async {
    await setActiveProfile(profile);
    if (profile.assignedServerId != null) {
      ServerNode? server;
      for (final s in servers) {
        if (s.id == profile.assignedServerId) {
          server = s;
          break;
        }
      }
      if (server != null) await setActiveServer(server);
    }
    BrowserTab? existing;
    for (final t in tabs) {
      if (t.profileId == profile.id && !isIncognitoTab(t)) {
        existing = t;
        break;
      }
    }
    if (existing == null) {
      newTab(profileId: profile.id);
    } else {
      activeTabId = existing.id;
      notifyListeners();
    }
  }

  // ----- tabs ------------------------------------------------------------

  /// New tab: the new-tab page when showNewTabPage is on, else the homepage.
  void newTab({String? profileId}) {
    final pid = profileId ?? settings.activeProfileId;
    final tab = BrowserTab(
      profileId: pid,
      url: settings.showNewTabPage ? '' : settings.homePageUrl,
      desktopMode: settings.desktopSiteDefault,
    );
    tabs = [...tabs, tab];
    activeTabId = tab.id;
    hasIncognitoTabs = tabs.any(isIncognitoTab);
    notifyListeners();
  }

  void newIncognitoTab() => newTab(profileId: 'incognito');

  void selectTab(String tabId) {
    if (_tabById(tabId) != null) {
      activeTabId = tabId;
      notifyListeners();
    }
  }

  void closeTab(String tabId) {
    final closed = _tabById(tabId);
    if (closed == null) return;
    tabs = tabs.where((t) => t.id != tabId).toList();
    if (activeTabId == tabId) {
      activeTabId = tabs.isNotEmpty ? tabs.last.id : null;
    }
    hasIncognitoTabs = tabs.any(isIncognitoTab);
    if (isIncognitoTab(closed) && !hasIncognitoTabs) {
      unawaited(NativeBridge.deleteProfileData('incognito'));
    }
    if (tabs.isEmpty) {
      newTab();
      return;
    }
    notifyListeners();
  }

  void closeAllTabs() {
    final hadIncognito = tabs.any(isIncognitoTab);
    tabs = [];
    activeTabId = null;
    hasIncognitoTabs = false;
    if (hadIncognito) {
      unawaited(NativeBridge.deleteProfileData('incognito'));
    }
    newTab();
  }

  void updateTab(BrowserTab tab) {
    tabs = [
      for (final t in tabs) t.id == tab.id ? tab : t,
    ];
    notifyListeners();
  }

  void navigateActiveTab(String url) {
    final tab = activeTab;
    if (tab == null) return;
    updateTab(tab.copyWith(
      url: url,
      isLoading: true,
      clearLoadError: true,
    ));
  }

  void reloadActiveTabIfLoaded() {
    final tab = activeTab;
    if (tab == null || tab.url.isEmpty) return;
    updateTab(tab.copyWith(reloadNonce: tab.reloadNonce + 1));
  }

  void reloadActiveTab() {
    final tab = activeTab;
    if (tab == null) return;
    if (tab.url.isEmpty) {
      updateTab(tab.copyWith(
        url: settings.homePageUrl,
        isLoading: true,
        clearLoadError: true,
      ));
    } else {
      updateTab(tab.copyWith(reloadNonce: tab.reloadNonce + 1));
    }
  }

  void stopLoadingActiveTab() {
    final tab = activeTab;
    if (tab == null) return;
    updateTab(tab.copyWith(isLoading: false));
  }

  void onTabPageStarted(String url, String tabId) {
    final tab = _tabById(tabId);
    if (tab == null) return;
    updateTab(tab.copyWith(
      url: url,
      isLoading: true,
      clearLoadError: true,
    ));
  }

  void onTabPageFinished(String url, String title, String tabId) {
    final tab = _tabById(tabId);
    if (tab == null) return;
    updateTab(tab.copyWith(
      url: url,
      title: title.isEmpty ? tab.title : title,
      isLoading: false,
    ));
    addRecentUrl(tab.profileId, url);
  }

  void onTabProgress(int progress, String tabId) {
    final tab = _tabById(tabId);
    if (tab == null) return;
    updateTab(tab.copyWith(progress: progress));
  }

  void onTabNavState(bool canGoBack, bool canGoForward, String tabId) {
    final tab = _tabById(tabId);
    if (tab == null) return;
    updateTab(tab.copyWith(canGoBack: canGoBack, canGoForward: canGoForward));
  }

  void onTabError(String message, String tabId) {
    final tab = _tabById(tabId);
    if (tab == null) return;
    updateTab(tab.copyWith(isLoading: false, loadError: message));
  }

  void clearTabError(String tabId) {
    final tab = _tabById(tabId);
    if (tab == null) return;
    updateTab(tab.copyWith(clearLoadError: true));
  }

  void toggleDesktopMode() {
    final tab = activeTab;
    if (tab == null) return;
    updateTab(tab.copyWith(
      desktopMode: !tab.desktopMode,
      reloadNonce: tab.reloadNonce + 1,
    ));
  }

  // ----- history (per profile) -------------------------------------------

  void addRecentUrl(String profileId, String url) {
    if (url.isEmpty) return;
    final list = List<String>.from(history[profileId] ?? const []);
    list.remove(url);
    list.insert(0, url);
    history = {...history, profileId: list.take(50).toList()};
    unawaited(_persist());
  }

  Future<void> clearHistoryForProfile(String profileId) async {
    history = {...history, profileId: []};
    await _persist();
    notifyListeners();
  }

  Future<void> deleteHistoryEntry(String profileId, String url) async {
    final list = List<String>.from(history[profileId] ?? const [])
      ..remove(url);
    history = {...history, profileId: list};
    await _persist();
    notifyListeners();
  }

  Future<void> clearAllHistory() async {
    history = {for (final p in profiles) p.id: <String>[]};
    await _persist();
    notifyListeners();
  }

  // ----- bookmarks ---------------------------------------------------------

  Future<void> addBookmark(Bookmark bookmark) async {
    if (bookmarks.any((b) => b.url == bookmark.url)) {
      _emit('Already bookmarked');
      return;
    }
    bookmarks = [...bookmarks, bookmark];
    await _persist();
    notifyListeners();
    _emit('Bookmarked');
  }

  Future<void> renameBookmark(Bookmark bookmark, String newTitle) async {
    bookmarks = [
      for (final b in bookmarks)
        b.url == bookmark.url ? b.copyWith(title: newTitle) : b,
    ];
    await _persist();
    notifyListeners();
  }

  Future<void> removeBookmark(String url) async {
    bookmarks = bookmarks.where((b) => b.url != url).toList();
    await _persist();
    notifyListeners();
  }

  bool isBookmarked(String url) => bookmarks.any((b) => b.url == url);

  // ----- downloads ---------------------------------------------------------

  Future<void> startDownload({
    required String url,
    required String userAgent,
    required String contentDisposition,
    required String mimeType,
    required String profileId,
    Future<String?> Function(String url)? getCookie,
    String? fileNameOverride,
  }) async {
    if (bridgePort == 0) {
      _emit('Download failed: the proxy bridge is not running');
      return;
    }
    // Prefer the native side's URLUtil.guessFileName result (more exact
    // for content-disposition edge cases); fall back to the Dart guesser.
    final fileName =
        (fileNameOverride != null && fileNameOverride.isNotEmpty)
            ? fileNameOverride
            : guessFileName(url, contentDisposition, mimeType);
    final record = DownloadRecord(
      url: url,
      fileName: fileName,
      mimeType: mimeType,
      status: DownloadStatus.downloading,
      profileId: profileId,
    );
    downloads = [record, ...downloads];
    notifyListeners();
    try {
      final tempDir = await getTemporaryDirectory();
      final result = await downloadThroughProxy(
        url: url,
        userAgent: userAgent,
        fileName: fileName,
        mimeType: mimeType,
        localProxyPort: bridgePort,
        getCookie: getCookie ?? cookieProvider ?? (url) async => null,
        tempDir: tempDir,
        saveToDownloads: NativeBridge.saveToDownloads,
        onProgress: (received, total) {
          downloads = [
            for (final d in downloads)
              d.id == record.id ? d.copyWith(sizeBytes: received) : d,
          ];
          notifyListeners();
        },
      );
      downloads = [
        for (final d in downloads)
          d.id == record.id
              ? d.copyWith(
                  fileName: result.savedName,
                  sizeBytes: result.sizeBytes,
                  status: DownloadStatus.done,
                )
              : d,
      ];
      await _persist();
      notifyListeners();
      _emit('Downloaded ${result.savedName}');
    } catch (e) {
      downloads = [
        for (final d in downloads)
          d.id == record.id
              ? d.copyWith(
                  status: DownloadStatus.failed,
                  error: e.toString().replaceFirst('Exception: ', ''),
                )
              : d,
      ];
      await _persist();
      notifyListeners();
      _emit('Download failed: ${e.toString().replaceFirst('Exception: ', '')}');
    }
  }

  Future<void> deleteDownload(String id) async {
    DownloadRecord? record;
    for (final d in downloads) {
      if (d.id == id) {
        record = d;
        break;
      }
    }
    if (record == null) return;
    if (record.status == DownloadStatus.done) {
      await NativeBridge.deleteDownload(record.fileName);
    }
    downloads = downloads.where((d) => d.id != id).toList();
    await _persist();
    notifyListeners();
  }

  // ----- settings ----------------------------------------------------------

  Future<void> updateSettings(AppSettings next) async {
    final oldKillSwitch = settings.killSwitchEnabled;
    settings = next;
    onboardingCompleted = next.onboardingDone;
    await _persist();
    _syncBridgeUpstream();
    notifyListeners();
    if (next.killSwitchEnabled != oldKillSwitch) {
      await bindProxyForActiveServer();
    }
  }

  Future<void> completeOnboarding() async {
    await updateSettings(settings.copyWith(onboardingDone: true));
  }

  Future<void> testBridgeConnection() async {
    if (bridgePort == 0) {
      bridgeTestResult = 'FAILED — the local bridge is not running.';
      notifyListeners();
      return;
    }
    final client = HttpClient();
    client.findProxy = (uri) => 'PROXY 127.0.0.1:$bridgePort';
    client.connectionTimeout = const Duration(seconds: 8);
    try {
      final request = await client.getUrl(Uri.parse('https://api.ipify.org'));
      request.headers.set(HttpHeaders.userAgentHeader, 'FastBrowser/2.0.0');
      final response =
          await request.close().timeout(const Duration(seconds: 10));
      final body = await response.transform(const SystemEncoding().decoder).join();
      if (response.statusCode == 200) {
        activeCountry = 'Connected';
        bridgeTestResult =
            'OK — through ${activeServer?.name ?? 'server'} your IP is ${body.trim()} (HTTP 200).';
      } else {
        bridgeTestResult =
            'FAILED — the server answered HTTP ${response.statusCode} instead of 200.';
      }
    } catch (e) {
      bridgeTestResult =
          'FAILED — ${e.toString().replaceFirst('Exception: ', '')}';
    } finally {
      client.close(force: true);
    }
    notifyListeners();
  }

  Future<void> clearBridgeError() async {
    bridgeError = '';
    notifyListeners();
  }

  Future<void> clearCrash() async {
    await CrashReporter.clear();
    notifyListeners();
  }

  /// Best-effort exit cleanup: wipe incognito data + any profile with
  /// clearCookiesOnExit. Called from the shell's AppLifecycleState
  /// handler; the Kotlin MainActivity also deletes the incognito profile
  /// in onDestroy as a second net.
  Future<void> onAppDetached() async {
    await NativeBridge.deleteProfileData('incognito');
    for (final p in profiles) {
      if (p.clearCookiesOnExit) {
        await NativeBridge.deleteProfileData(p.id);
      }
    }
  }

  // ----- import / export / reset -------------------------------------------

  String exportBackup() => _store.exportJson(StoreData(
        servers: servers,
        profiles: profiles,
        settings: settings,
        history: history,
        bookmarks: bookmarks,
        downloads: downloads,
      ));

  Future<void> importBackup(String raw) async {
    final current = StoreData(
      servers: servers,
      profiles: profiles,
      settings: settings,
      history: history,
      bookmarks: bookmarks,
      downloads: downloads,
    );
    try {
      final next = await _store.importJson(raw, current);
      servers = next.servers;
      profiles = next.profiles;
      settings = next.settings;
      latencyResults = {};
      hasAnyServer = servers.isNotEmpty;
      notifyListeners();
      _syncBridgeUpstream();
      await bindProxyForActiveServer();
      unawaited(refreshLatency());
      _emit('Backup imported');
    } catch (e) {
      _emit('Import failed: ${e.toString().replaceFirst('Exception: ', '')}');
    }
  }

  Future<void> resetAll() async {
    final next = await _store.resetAll();
    servers = next.servers;
    profiles = next.profiles;
    settings = next.settings;
    history = next.history;
    bookmarks = next.bookmarks;
    downloads = next.downloads;
    latencyResults = {};
    hasAnyServer = servers.isNotEmpty;
    notifyListeners();
    _syncBridgeUpstream();
    await bindProxyForActiveServer();
    unawaited(refreshLatency());
  }

  // ----- misc --------------------------------------------------------------

  List<BrowserTab> tabsForProfile(String profileId) =>
      tabs.where((t) => t.profileId == profileId).toList();
}
