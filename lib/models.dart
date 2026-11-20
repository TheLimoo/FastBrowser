// Data models — JSON field names are kept IDENTICAL to the v1.0.7 Kotlin app
// (kotlinx.serialization) so v1.0.7 backup JSON imports directly and the
// legacy migration can hand over its stored JSON unchanged.

import 'dart:convert';

String _uuid() {
  final now = DateTime.now().microsecondsSinceEpoch;
  final rnd = (now * 2654435761) % 0x7fffffff;
  return '${now.toRadixString(16)}-${rnd.toRadixString(16)}-${DateTime.now().millisecondsSinceEpoch.toRadixString(16)}';
}

enum ProxyProtocol { SOCKS5, HTTP }

enum ProxyTransport { DIRECT, WEBSOCKET }

class ServerNode {
  final String id;
  final String name;
  final String host;
  final int port;
  final ProxyProtocol protocol;
  final String username;
  final String password;
  final String countryCode;
  final String timezoneOverride;
  final String languageOverride;
  final ProxyTransport transport;
  final String wsPath;

  const ServerNode({
    required this.id,
    required this.name,
    required this.host,
    required this.port,
    this.protocol = ProxyProtocol.SOCKS5,
    this.username = '',
    this.password = '',
    this.countryCode = 'AUTO',
    this.timezoneOverride = '',
    this.languageOverride = '',
    this.transport = ProxyTransport.DIRECT,
    this.wsPath = '/ws',
  });

  ServerNode copyWith({
    String? id,
    String? name,
    String? host,
    int? port,
    ProxyProtocol? protocol,
    String? username,
    String? password,
    String? countryCode,
    String? timezoneOverride,
    String? languageOverride,
    ProxyTransport? transport,
    String? wsPath,
  }) =>
      ServerNode(
        id: id ?? this.id,
        name: name ?? this.name,
        host: host ?? this.host,
        port: port ?? this.port,
        protocol: protocol ?? this.protocol,
        username: username ?? this.username,
        password: password ?? this.password,
        countryCode: countryCode ?? this.countryCode,
        timezoneOverride: timezoneOverride ?? this.timezoneOverride,
        languageOverride: languageOverride ?? this.languageOverride,
        transport: transport ?? this.transport,
        wsPath: wsPath ?? this.wsPath,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'host': host,
        'port': port,
        'protocol': protocol.name,
        'username': username,
        'password': password,
        'countryCode': countryCode,
        'timezoneOverride': timezoneOverride,
        'languageOverride': languageOverride,
        'transport': transport.name,
        'wsPath': wsPath,
      };

  factory ServerNode.fromJson(Map<String, dynamic> j) => ServerNode(
        id: j['id'] as String? ?? _uuid(),
        name: j['name'] as String? ?? 'Server',
        host: j['host'] as String? ?? '',
        port: (j['port'] as num?)?.toInt() ?? 10800,
        protocol: ProxyProtocol.values.firstWhere(
          (e) => e.name == j['protocol'],
          orElse: () => ProxyProtocol.SOCKS5,
        ),
        username: j['username'] as String? ?? '',
        password: j['password'] as String? ?? '',
        countryCode: j['countryCode'] as String? ?? 'AUTO',
        timezoneOverride: j['timezoneOverride'] as String? ?? '',
        languageOverride: j['languageOverride'] as String? ?? '',
        transport: ProxyTransport.values.firstWhere(
          (e) => e.name == j['transport'],
          orElse: () => ProxyTransport.DIRECT,
        ),
        wsPath: j['wsPath'] as String? ?? '/ws',
      );
}

enum UserAgentPreset {
  ANDROID_CHROME(
    'Android Mobile (Chrome 131)',
    'Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
  ),
  DESKTOP_WINDOWS(
    'Desktop Windows (Chrome 131)',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  ),
  DESKTOP_MAC(
    'Desktop macOS (Chrome 131)',
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  ),
  IPHONE_SAFARI(
    'iPhone 16 Pro (Safari 18)',
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
  );

  const UserAgentPreset(this.label, this.uaString);
  final String label;
  final String uaString;
}

class BrowserProfile {
  final String id;
  final String name;
  final String? assignedServerId;
  final UserAgentPreset userAgentPreset;
  final String customUserAgent;
  final bool blockWebRtcLeaks;
  final bool canvasNoiseEnabled;
  final bool clearCookiesOnExit;

  const BrowserProfile({
    required this.id,
    required this.name,
    this.assignedServerId,
    this.userAgentPreset = UserAgentPreset.ANDROID_CHROME,
    this.customUserAgent = '',
    this.blockWebRtcLeaks = true,
    this.canvasNoiseEnabled = false,
    this.clearCookiesOnExit = false,
  });

  BrowserProfile copyWith({
    String? id,
    String? name,
    String? assignedServerId,
    UserAgentPreset? userAgentPreset,
    String? customUserAgent,
    bool? blockWebRtcLeaks,
    bool? canvasNoiseEnabled,
    bool? clearCookiesOnExit,
  }) =>
      BrowserProfile(
        id: id ?? this.id,
        name: name ?? this.name,
        assignedServerId: assignedServerId ?? this.assignedServerId,
        userAgentPreset: userAgentPreset ?? this.userAgentPreset,
        customUserAgent: customUserAgent ?? this.customUserAgent,
        blockWebRtcLeaks: blockWebRtcLeaks ?? this.blockWebRtcLeaks,
        canvasNoiseEnabled: canvasNoiseEnabled ?? this.canvasNoiseEnabled,
        clearCookiesOnExit: clearCookiesOnExit ?? this.clearCookiesOnExit,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'assignedServerId': assignedServerId,
        'userAgentPreset': userAgentPreset.name,
        'customUserAgent': customUserAgent,
        'blockWebRtcLeaks': blockWebRtcLeaks,
        'canvasNoiseEnabled': canvasNoiseEnabled,
        'clearCookiesOnExit': clearCookiesOnExit,
      };

  factory BrowserProfile.fromJson(Map<String, dynamic> j) => BrowserProfile(
        id: j['id'] as String? ?? _uuid(),
        name: j['name'] as String? ?? 'Profile',
        assignedServerId: j['assignedServerId'] as String?,
        userAgentPreset: UserAgentPreset.values.firstWhere(
          (e) => e.name == j['userAgentPreset'],
          orElse: () => UserAgentPreset.ANDROID_CHROME,
        ),
        customUserAgent: j['customUserAgent'] as String? ?? '',
        blockWebRtcLeaks: j['blockWebRtcLeaks'] as bool? ?? true,
        canvasNoiseEnabled: j['canvasNoiseEnabled'] as bool? ?? false,
        clearCookiesOnExit: j['clearCookiesOnExit'] as bool? ?? false,
      );
}

enum SearchEngine {
  DUCKDUCKGO('DuckDuckGo', 'https://duckduckgo.com/?q=%s'),
  GOOGLE('Google', 'https://www.google.com/search?q=%s'),
  BRAVE('Brave Search', 'https://search.brave.com/search?q=%s'),
  STARTPAGE('Startpage', 'https://www.startpage.com/sp/search?query=%s');

  const SearchEngine(this.label, this.queryUrl);
  final String label;
  final String queryUrl;
}

class AppSettings {
  final String? activeServerId;
  final String activeProfileId;
  final bool killSwitchEnabled;
  final bool blockWebRtcGlobal;
  final bool adAndTrackerBlocker;
  final bool forceDarkWebPages;
  final bool bottomAddressBar;
  final bool amoledBlackTheme;
  final bool hardwareAcceleration;
  final bool blockImagesDataSaver;
  final SearchEngine searchEngine;
  final String homePageUrl;
  final bool autoSelectFastestServer;
  final bool blockThirdPartyCookies;
  final double textScale;
  final bool desktopSiteDefault;
  final bool showNewTabPage;
  final bool onboardingDone;

  const AppSettings({
    this.activeServerId,
    this.activeProfileId = 'default_profile',
    this.killSwitchEnabled = true,
    this.blockWebRtcGlobal = true,
    this.adAndTrackerBlocker = true,
    this.forceDarkWebPages = false,
    this.bottomAddressBar = false,
    this.amoledBlackTheme = false,
    this.hardwareAcceleration = true,
    this.blockImagesDataSaver = false,
    this.searchEngine = SearchEngine.GOOGLE,
    this.homePageUrl = 'https://browserleaks.com/ip',
    this.autoSelectFastestServer = false,
    this.blockThirdPartyCookies = false,
    this.textScale = 1.0,
    this.desktopSiteDefault = false,
    this.showNewTabPage = true,
    this.onboardingDone = false,
  });

  AppSettings copyWith({
    String? activeServerId,
    String? activeProfileId,
    bool? killSwitchEnabled,
    bool? blockWebRtcGlobal,
    bool? adAndTrackerBlocker,
    bool? forceDarkWebPages,
    bool? bottomAddressBar,
    bool? amoledBlackTheme,
    bool? hardwareAcceleration,
    bool? blockImagesDataSaver,
    SearchEngine? searchEngine,
    String? homePageUrl,
    bool? autoSelectFastestServer,
    bool? blockThirdPartyCookies,
    double? textScale,
    bool? desktopSiteDefault,
    bool? showNewTabPage,
    bool? onboardingDone,
  }) =>
      AppSettings(
        activeServerId: activeServerId ?? this.activeServerId,
        activeProfileId: activeProfileId ?? this.activeProfileId,
        killSwitchEnabled: killSwitchEnabled ?? this.killSwitchEnabled,
        blockWebRtcGlobal: blockWebRtcGlobal ?? this.blockWebRtcGlobal,
        adAndTrackerBlocker: adAndTrackerBlocker ?? this.adAndTrackerBlocker,
        forceDarkWebPages: forceDarkWebPages ?? this.forceDarkWebPages,
        bottomAddressBar: bottomAddressBar ?? this.bottomAddressBar,
        amoledBlackTheme: amoledBlackTheme ?? this.amoledBlackTheme,
        hardwareAcceleration:
            hardwareAcceleration ?? this.hardwareAcceleration,
        blockImagesDataSaver:
            blockImagesDataSaver ?? this.blockImagesDataSaver,
        searchEngine: searchEngine ?? this.searchEngine,
        homePageUrl: homePageUrl ?? this.homePageUrl,
        autoSelectFastestServer:
            autoSelectFastestServer ?? this.autoSelectFastestServer,
        blockThirdPartyCookies:
            blockThirdPartyCookies ?? this.blockThirdPartyCookies,
        textScale: textScale ?? this.textScale,
        desktopSiteDefault: desktopSiteDefault ?? this.desktopSiteDefault,
        showNewTabPage: showNewTabPage ?? this.showNewTabPage,
        onboardingDone: onboardingDone ?? this.onboardingDone,
      );

  Map<String, dynamic> toJson() => {
        'activeServerId': activeServerId,
        'activeProfileId': activeProfileId,
        'killSwitchEnabled': killSwitchEnabled,
        'blockWebRtcGlobal': blockWebRtcGlobal,
        'adAndTrackerBlocker': adAndTrackerBlocker,
        'forceDarkWebPages': forceDarkWebPages,
        'bottomAddressBar': bottomAddressBar,
        'amoledBlackTheme': amoledBlackTheme,
        'hardwareAcceleration': hardwareAcceleration,
        'blockImagesDataSaver': blockImagesDataSaver,
        'searchEngine': searchEngine.name,
        'homePageUrl': homePageUrl,
        'autoSelectFastestServer': autoSelectFastestServer,
        'blockThirdPartyCookies': blockThirdPartyCookies,
        'textScale': textScale,
        'desktopSiteDefault': desktopSiteDefault,
        'showNewTabPage': showNewTabPage,
        'onboardingDone': onboardingDone,
      };

  factory AppSettings.fromJson(Map<String, dynamic> j) => AppSettings(
        activeServerId: j['activeServerId'] as String?,
        activeProfileId: j['activeProfileId'] as String? ?? 'default_profile',
        killSwitchEnabled: j['killSwitchEnabled'] as bool? ?? true,
        blockWebRtcGlobal: j['blockWebRtcGlobal'] as bool? ?? true,
        adAndTrackerBlocker: j['adAndTrackerBlocker'] as bool? ?? true,
        forceDarkWebPages: j['forceDarkWebPages'] as bool? ?? false,
        bottomAddressBar: j['bottomAddressBar'] as bool? ?? false,
        amoledBlackTheme: j['amoledBlackTheme'] as bool? ?? false,
        hardwareAcceleration: j['hardwareAcceleration'] as bool? ?? true,
        blockImagesDataSaver: j['blockImagesDataSaver'] as bool? ?? false,
        searchEngine: SearchEngine.values.firstWhere(
          (e) => e.name == j['searchEngine'],
          orElse: () => SearchEngine.GOOGLE,
        ),
        homePageUrl:
            j['homePageUrl'] as String? ?? 'https://browserleaks.com/ip',
        autoSelectFastestServer:
            j['autoSelectFastestServer'] as bool? ?? false,
        blockThirdPartyCookies: j['blockThirdPartyCookies'] as bool? ?? false,
        textScale: (j['textScale'] as num?)?.toDouble() ?? 1.0,
        desktopSiteDefault: j['desktopSiteDefault'] as bool? ?? false,
        showNewTabPage: j['showNewTabPage'] as bool? ?? true,
        onboardingDone: j['onboardingDone'] as bool? ?? false,
      );
}

/// A browser tab — runtime state only, never persisted.
class BrowserTab {
  final String id;
  final String profileId;
  final String title;
  final String url;
  final int progress;
  final bool isLoading;
  final bool canGoBack;
  final bool canGoForward;
  final bool desktopMode;
  final String? loadError;
  final int reloadNonce;

  BrowserTab({
    String? id,
    required this.profileId,
    this.title = 'New Tab',
    this.url = '',
    this.progress = 0,
    this.isLoading = false,
    this.canGoBack = false,
    this.canGoForward = false,
    this.desktopMode = false,
    this.loadError,
    this.reloadNonce = 0,
  }) : id = id ?? _uuid();

  BrowserTab copyWith({
    String? title,
    String? url,
    int? progress,
    bool? isLoading,
    bool? canGoBack,
    bool? canGoForward,
    bool? desktopMode,
    String? loadError,
    bool clearLoadError = false,
    int? reloadNonce,
  }) =>
      BrowserTab(
        id: id,
        profileId: profileId,
        title: title ?? this.title,
        url: url ?? this.url,
        progress: progress ?? this.progress,
        isLoading: isLoading ?? this.isLoading,
        canGoBack: canGoBack ?? this.canGoBack,
        canGoForward: canGoForward ?? this.canGoForward,
        desktopMode: desktopMode ?? this.desktopMode,
        loadError: clearLoadError ? null : (loadError ?? this.loadError),
        reloadNonce: reloadNonce ?? this.reloadNonce,
      );
}

class Bookmark {
  final String id;
  final String url;
  final String title;
  final int createdAtMillis;

  Bookmark({
    String? id,
    required this.url,
    required this.title,
    int? createdAtMillis,
  })  : id = id ?? _uuid(),
        createdAtMillis =
            createdAtMillis ?? DateTime.now().millisecondsSinceEpoch;

  Bookmark copyWith({String? url, String? title}) => Bookmark(
        id: id,
        url: url ?? this.url,
        title: title ?? this.title,
        createdAtMillis: createdAtMillis,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'url': url,
        'title': title,
        'createdAtMillis': createdAtMillis,
      };

  factory Bookmark.fromJson(Map<String, dynamic> j) => Bookmark(
        id: j['id'] as String? ?? _uuid(),
        url: j['url'] as String? ?? '',
        title: j['title'] as String? ?? '',
        createdAtMillis: (j['createdAtMillis'] as num?)?.toInt() ??
            DateTime.now().millisecondsSinceEpoch,
      );
}

enum DownloadStatus { downloading, done, failed }

class DownloadRecord {
  final String id;
  final String url;
  final String fileName;
  final String mimeType;
  final DownloadStatus status;
  final String? savedPath;
  final int sizeBytes;
  final int createdAtMillis;
  final String profileId;
  final String? error;

  DownloadRecord({
    String? id,
    required this.url,
    required this.fileName,
    required this.mimeType,
    this.status = DownloadStatus.downloading,
    this.savedPath,
    this.sizeBytes = 0,
    int? createdAtMillis,
    required this.profileId,
    this.error,
  })  : id = id ?? _uuid(),
        createdAtMillis =
            createdAtMillis ?? DateTime.now().millisecondsSinceEpoch;

  DownloadRecord copyWith({
    String? fileName,
    DownloadStatus? status,
    String? savedPath,
    int? sizeBytes,
    String? error,
  }) =>
      DownloadRecord(
        id: id,
        url: url,
        fileName: fileName ?? this.fileName,
        mimeType: mimeType,
        status: status ?? this.status,
        savedPath: savedPath ?? this.savedPath,
        sizeBytes: sizeBytes ?? this.sizeBytes,
        createdAtMillis: createdAtMillis,
        profileId: profileId,
        error: error ?? this.error,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'url': url,
        'fileName': fileName,
        'mimeType': mimeType,
        'status': status.name,
        'savedPath': savedPath,
        'sizeBytes': sizeBytes,
        'createdAtMillis': createdAtMillis,
        'profileId': profileId,
        'error': error,
      };

  factory DownloadRecord.fromJson(Map<String, dynamic> j) => DownloadRecord(
        id: j['id'] as String? ?? _uuid(),
        url: j['url'] as String? ?? '',
        fileName: j['fileName'] as String? ?? 'download',
        mimeType: j['mimeType'] as String? ?? 'application/octet-stream',
        status: DownloadStatus.values.firstWhere(
          (e) => e.name == j['status'],
          orElse: () => DownloadStatus.done,
        ),
        savedPath: j['savedPath'] as String?,
        sizeBytes: (j['sizeBytes'] as num?)?.toInt() ?? 0,
        createdAtMillis: (j['createdAtMillis'] as num?)?.toInt(),
        profileId: j['profileId'] as String? ?? 'default_profile',
        error: j['error'] as String?,
      );
}

/// Backup bundle — same shape as the v1.0.7 Kotlin BackupBundle.
class BackupBundle {
  final List<ServerNode> servers;
  final List<BrowserProfile> profiles;
  final AppSettings settings;

  const BackupBundle({
    required this.servers,
    required this.profiles,
    required this.settings,
  });

  String toJsonString() => jsonEncode({
        'servers': servers.map((s) => s.toJson()).toList(),
        'profiles': profiles.map((p) => p.toJson()).toList(),
        'settings': settings.toJson(),
      });

  factory BackupBundle.fromJsonString(String raw) {
    final j = jsonDecode(raw.trim()) as Map<String, dynamic>;
    return BackupBundle(
      servers: (j['servers'] as List)
          .map((e) => ServerNode.fromJson(e as Map<String, dynamic>))
          .toList(),
      profiles: (j['profiles'] as List)
          .map((e) => BrowserProfile.fromJson(e as Map<String, dynamic>))
          .toList(),
      settings: AppSettings.fromJson(j['settings'] as Map<String, dynamic>),
    );
  }
}
