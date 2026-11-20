// Persistence — Dart equivalent of SettingsRepository.kt.
//
// One JSON file (`store.json` in the app documents dir), written atomically
// (temp file + rename) behind a write mutex. Same boundary rules as the
// Kotlin repo: server passwords are ENCRYPTED in the file (AES-GCM via
// CryptoManager, AAD = server id) and plaintext in memory; legacy
// plaintext passes through; export stays plaintext by design (a backup
// must import on a fresh install whose key is different).
//
// v2.0.0 change: history is PER-PROFILE (`history: {profileId: [urls]}`).
// The old app kept one global recent-URLs list shared by all profiles —
// that is the gap the user named (no per-profile history delete). The
// legacy migration assigns the old global list to the default profile.

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'crypto.dart';
import 'models.dart';
import 'package:path_provider/path_provider.dart';

class StoreData {
  final List<ServerNode> servers;
  final List<BrowserProfile> profiles;
  final AppSettings settings;
  final Map<String, List<String>> history;
  final List<Bookmark> bookmarks;
  final List<DownloadRecord> downloads;

  const StoreData({
    required this.servers,
    required this.profiles,
    required this.settings,
    required this.history,
    required this.bookmarks,
    required this.downloads,
  });
}

class Store {
  static const String stockholmCfId = 'server-stockholm-cf';
  static const String ohioId = 'server-ohio';
  static const String stockholmDirectId = 'server-stockholm';
  static const String defaultProfileId = 'default_profile';

  static List<ServerNode> defaultServers() => [
        ServerNode(
          id: stockholmCfId,
          name: 'Stockholm (Cloudflare)',
          host: 'cf-gost.limoo.indevs.in',
          port: 2083,
          protocol: ProxyProtocol.SOCKS5,
          countryCode: 'SE',
          timezoneOverride: 'Europe/Stockholm',
          transport: ProxyTransport.WEBSOCKET,
          wsPath: '/ws',
        ),
        ServerNode(
          id: ohioId,
          name: 'Ohio',
          host: '18.190.239.234',
          port: 10800,
          protocol: ProxyProtocol.SOCKS5,
          countryCode: 'US',
          timezoneOverride: 'America/New_York',
        ),
      ];

  static List<BrowserProfile> defaultProfiles() => [
        const BrowserProfile(id: defaultProfileId, name: 'Default Profile'),
      ];

  static AppSettings defaultSettings() => const AppSettings(
        activeServerId: stockholmCfId,
        activeProfileId: defaultProfileId,
      );

  final CryptoManager _crypto;
  File? _file;
  Future<void> _writeChain = Future.value();
  StoreData? _cached;

  Store({CryptoManager? crypto}) : _crypto = crypto ?? CryptoManager();

  Future<File> _storeFile() async {
    _file ??= File(
      '${(await getApplicationDocumentsDirectory()).path}/store.json',
    );
    return _file!;
  }

  /// Loads the store, applying migrations. If [legacyJson] is supplied (the
  /// Kotlin legacy migration's output), it is imported first and then
  /// persisted in the new format.
  Future<StoreData> load({Map<String, dynamic>? legacyJson}) async {
    if (_cached != null) return _cached!;
    Map<String, dynamic>? raw;
    try {
      final file = await _storeFile();
      if (await file.exists()) {
        raw = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      }
    } catch (_) {
      raw = null;
    }
    raw ??= legacyJson ?? {};

    var servers = _parseServers(raw['servers']);
    var profiles = _parseProfiles(raw['profiles']);
    var settings = _parseSettings(raw['settings']);
    var history = _parseHistory(raw['history'], raw['recentUrls']);
    var bookmarks = _parseBookmarks(raw['bookmarks']);
    var downloads = _parseDownloads(raw['downloads']);

    // Decrypt stored passwords (AAD = server id). Blank stays blank;
    // legacy plaintext passes through; a failure reads as blank.
    servers = [
      for (final s in servers)
        s.copyWith(
          password: await _crypto.decrypt(
            s.password,
            utf8.encode(s.id),
          ),
        ),
    ];

    var changed = false;
    // Migration 1: drop the direct Stockholm server (DPI-dead from Iran).
    if (servers.any((s) => s.id == stockholmDirectId)) {
      servers = servers.where((s) => s.id != stockholmDirectId).toList();
      if (servers.isEmpty) servers = defaultServers();
      if (settings.activeServerId == stockholmDirectId) {
        settings = settings.copyWith(
          activeServerId: servers
                  .firstWhere(
                    (s) => s.id == stockholmCfId,
                    orElse: () => servers.first,
                  )
                  .id,
        );
      }
      changed = true;
    }
    // Migration 2: add the Cloudflare server if missing.
    if (servers.every((s) => s.id != stockholmCfId)) {
      servers = [...servers, defaultServers().first];
      changed = true;
    }
    // Migration 3: strip auth from the known no-auth servers.
    var stripped = false;
    servers = servers.map((s) {
      if ((s.id == ohioId || s.id == stockholmCfId) &&
          (s.username.isNotEmpty || s.password.isNotEmpty)) {
        stripped = true;
        return s.copyWith(username: '', password: '');
      }
      return s;
    }).toList();
    if (stripped) changed = true;
    // Migration 4: layered theme is the default (turn off stored AMOLED).
    if (settings.amoledBlackTheme) {
      settings = settings.copyWith(amoledBlackTheme: false);
      changed = true;
    }
    if (servers.isEmpty) {
      servers = defaultServers();
      changed = true;
    }
    if (profiles.isEmpty) {
      profiles = defaultProfiles();
      changed = true;
    }

    final data = StoreData(
      servers: servers,
      profiles: profiles,
      settings: settings,
      history: history,
      bookmarks: bookmarks,
      downloads: downloads,
    );
    _cached = data;
    if (changed || legacyJson != null) await _write(data);
    return data;
  }

  List<ServerNode> _parseServers(dynamic raw) {
    if (raw is! List) return defaultServers();
    try {
      final list = raw
          .map((e) => ServerNode.fromJson(e as Map<String, dynamic>))
          .toList();
      return list.isEmpty ? defaultServers() : list;
    } catch (_) {
      return defaultServers();
    }
  }

  List<BrowserProfile> _parseProfiles(dynamic raw) {
    if (raw is! List) return defaultProfiles();
    try {
      final list = raw
          .map((e) => BrowserProfile.fromJson(e as Map<String, dynamic>))
          .toList();
      return list.isEmpty ? defaultProfiles() : list;
    } catch (_) {
      return defaultProfiles();
    }
  }

  AppSettings _parseSettings(dynamic raw) {
    if (raw is! Map<String, dynamic>) return defaultSettings();
    try {
      return AppSettings.fromJson(raw);
    } catch (_) {
      return defaultSettings();
    }
  }

  Map<String, List<String>> _parseHistory(
    dynamic perProfile,
    dynamic legacyGlobal,
  ) {
    final out = <String, List<String>>{};
    if (perProfile is Map<String, dynamic>) {
      for (final e in perProfile.entries) {
        if (e.value is List) {
          out[e.key] =
              (e.value as List).map((u) => u.toString()).take(50).toList();
        }
      }
    }
    // Old global list migrates to the default profile (v1.0.7 data).
    if (legacyGlobal is List && legacyGlobal.isNotEmpty) {
      final existing = out[defaultProfileId] ?? <String>[];
      final merged = <String>[...existing];
      for (final u in legacyGlobal.map((e) => e.toString())) {
        if (!merged.contains(u)) merged.add(u);
      }
      out[defaultProfileId] = merged.take(50).toList();
    }
    return out;
  }

  List<Bookmark> _parseBookmarks(dynamic raw) {
    if (raw is! List) return [];
    try {
      return raw
          .map((e) => Bookmark.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return [];
    }
  }

  List<DownloadRecord> _parseDownloads(dynamic raw) {
    if (raw is! List) return [];
    try {
      return raw
          .map((e) => DownloadRecord.fromJson(e as Map<String, dynamic>))
          .where((d) => d.status != DownloadStatus.downloading)
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> save(StoreData data) async {
    _cached = data;
    await _write(data);
  }

  Future<void> _write(StoreData data) async {
    final task = _writeChain.then((_) => _writeNow(data));
    _writeChain = task.catchError((_) {});
    await task;
  }

  Future<void> _writeNow(StoreData data) async {
    final encryptedServers = <Map<String, dynamic>>[];
    for (final s in data.servers) {
      final encrypted = await _crypto.encrypt(s.password, utf8.encode(s.id));
      encryptedServers.add(s.copyWith(password: encrypted).toJson());
    }
    final doc = {
      'servers': encryptedServers,
      'profiles': data.profiles.map((p) => p.toJson()).toList(),
      'settings': data.settings.toJson(),
      'history': data.history,
      'bookmarks': data.bookmarks.map((b) => b.toJson()).toList(),
      'downloads': data.downloads.map((d) => d.toJson()).toList(),
    };
    final file = await _storeFile();
    final tmp = File('${file.path}.tmp');
    await tmp.writeAsString(jsonEncode(doc));
    await tmp.rename(file.path);
  }

  /// Export (servers + profiles + settings) — plaintext by design, same
  /// shape as the v1.0.7 BackupBundle.
  String exportJson(StoreData data) => BackupBundle(
        servers: data.servers,
        profiles: data.profiles,
        settings: data.settings,
      ).toJsonString();

  /// Import a backup. Throws ArgumentError with a human message on bad input.
  Future<StoreData> importJson(String raw, StoreData current) async {
    final bundle = BackupBundle.fromJsonString(raw);
    if (bundle.servers.isEmpty) {
      throw ArgumentError('Backup contains no servers.');
    }
    if (bundle.profiles.isEmpty) {
      throw ArgumentError('Backup contains no profiles.');
    }
    for (final s in bundle.servers) {
      if (s.host.isEmpty) {
        throw ArgumentError('A server is missing its host.');
      }
      if (s.port < 1 || s.port > 65535) {
        throw ArgumentError("Server '${s.name}' has an invalid port.");
      }
    }
    final next = StoreData(
      servers: bundle.servers,
      profiles: bundle.profiles,
      settings: bundle.settings,
      history: current.history,
      bookmarks: current.bookmarks,
      downloads: current.downloads,
    );
    await save(next);
    return next;
  }

  Future<StoreData> resetAll() async {
    final next = StoreData(
      servers: defaultServers(),
      profiles: defaultProfiles(),
      settings: defaultSettings(),
      history: {},
      bookmarks: [],
      downloads: [],
    );
    await save(next);
    return next;
  }
}
