import 'dart:convert';
import 'dart:io';

import 'package:fastbrowser/crypto.dart';
import 'package:fastbrowser/models.dart';
import 'package:fastbrowser/store.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  final secure = <String, String>{};

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp('fb_store_test');
    secure.clear();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel('plugins.flutter.io/path_provider'),
      (call) async {
        if (call.method == 'getApplicationDocumentsDirectory') {
          return tempDir.path;
        }
        return null;
      },
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
      (call) async {
        final args = call.arguments as Map?;
        switch (call.method) {
          case 'read':
            return secure[(args?['key'] as String?) ?? ''];
          case 'write':
            secure[(args?['key'] as String?) ?? ''] =
                args?['value'] as String? ?? '';
            return null;
          default:
            return null;
        }
      },
    );
  });

  tearDown(() async {
    await tempDir.delete(recursive: true);
  });

  test('load with no file gives the pre-seeded servers', () async {
    final store = Store();
    final data = await store.load();
    expect(data.servers.map((s) => s.id), contains(Store.stockholmCfId));
    expect(data.servers.map((s) => s.id), contains(Store.ohioId));
    expect(data.profiles.length, 1);
    expect(data.settings.activeServerId, Store.stockholmCfId);
  });

  test('save then load round-trips, passwords encrypted at rest', () async {
    final store = Store();
    final data = await store.load();
    // Passwords live on a CUSTOM server: the known no-auth servers
    // (Stockholm CF / Ohio) get their credentials stripped on load by
    // design (the v1.0.2 no-auth migration), so a password stored on one
    // of those would come back blank.
    const custom = ServerNode(
      id: 'server-custom',
      name: 'Custom',
      host: '1.2.3.4',
      port: 10800,
    );
    final withPassword = StoreData(
      servers: [...data.servers, custom.copyWith(password: 's3cret')],
      profiles: data.profiles,
      settings: data.settings,
      history: data.history,
      bookmarks: data.bookmarks,
      downloads: data.downloads,
    );
    await store.save(withPassword);

    // The file on disk must not contain the plaintext password.
    final raw = await File('${tempDir.path}/store.json').readAsString();
    expect(raw, isNot(contains('s3cret')));
    expect(raw, contains(CryptoManager.encryptedPrefix));

    // A fresh Store decrypts it back.
    final reloaded = await Store().load();
    final back =
        reloaded.servers.firstWhere((s) => s.id == 'server-custom');
    expect(back.password, 's3cret');
  });

  test('legacy migration: global history goes to the default profile, '
      'direct Stockholm is dropped, CF server is added', () async {
    final store = Store();
    final legacy = {
      'servers': [
        {
          'id': 'server-stockholm',
          'name': 'Stockholm (direct)',
          'host': '16.192.116.137',
          'port': 10800,
          'protocol': 'SOCKS5',
          'transport': 'DIRECT',
        },
      ],
      'profiles': [
        {'id': 'default_profile', 'name': 'Default Profile'},
      ],
      'settings': {
        'activeServerId': 'server-stockholm',
        'activeProfileId': 'default_profile',
      },
      'recentUrls': ['https://example.com', 'https://example.org'],
      'bookmarks': [],
    };
    final data = await store.load(legacyJson: legacy);
    expect(
      data.servers.map((s) => s.id),
      isNot(contains('server-stockholm')),
    );
    expect(data.servers.map((s) => s.id), contains(Store.stockholmCfId));
    expect(data.history['default_profile'], hasLength(2));
    expect(data.settings.activeServerId, Store.stockholmCfId);
  });

  test('export then import round-trips servers and profiles', () async {
    final store = Store();
    final data = await store.load();
    final exported = store.exportJson(data);
    final decoded = jsonDecode(exported) as Map<String, dynamic>;
    expect((decoded['servers'] as List).length, 2);

    final imported = await store.importJson(exported, data);
    expect(imported.servers.length, 2);
    expect(imported.profiles.length, 1);
  });

  test('import rejects a backup with no servers', () async {
    final store = Store();
    final data = await store.load();
    expect(
      () => store.importJson('{"servers": [], "profiles": [], "settings": {}}', data),
      throwsA(isA<ArgumentError>()),
    );
  });

  test('resetAll restores the pre-seeded state', () async {
    final store = Store();
    final data = await store.load();
    await store.save(StoreData(
      servers: [
        const ServerNode(id: 'x', name: 'X', host: '1.2.3.4', port: 1),
      ],
      profiles: data.profiles,
      settings: data.settings,
      history: {'default_profile': ['https://example.com']},
      bookmarks: data.bookmarks,
      downloads: data.downloads,
    ));
    final reset = await store.resetAll();
    expect(reset.servers.map((s) => s.id), contains(Store.stockholmCfId));
    expect(reset.history, isEmpty);
  });
}
