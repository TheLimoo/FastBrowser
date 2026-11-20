import 'dart:convert';

import 'package:fastbrowser/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final stored = <String, String>{};
  setUp(() {
    stored.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
      (call) async {
        final args = call.arguments as Map?;
        switch (call.method) {
          case 'read':
            return stored[(args?['key'] as String?) ?? ''];
          case 'write':
            stored[(args?['key'] as String?) ?? ''] =
                args?['value'] as String? ?? '';
            return null;
          case 'delete':
            stored.remove((args?['key'] as String?) ?? '');
            return null;
          case 'deleteAll':
            stored.clear();
            return null;
          case 'readAll':
            return Map<String, String>.from(stored);
          case 'containsKey':
            return stored.containsKey((args?['key'] as String?) ?? '');
          default:
            return null;
        }
      },
    );
  });

  test('encrypt then decrypt round-trips with the same AAD', () async {
    final crypto = CryptoManager();
    final aad = utf8.encode('server-stockholm-cf');
    final stored = await crypto.encrypt('hunter2', aad);
    expect(stored.startsWith(CryptoManager.encryptedPrefix), isTrue);
    expect(stored, isNot('hunter2'));
    expect(await crypto.decrypt(stored, aad), 'hunter2');
  });

  test('blank stays blank', () async {
    final crypto = CryptoManager();
    final aad = utf8.encode('server-ohio');
    expect(await crypto.encrypt('', aad), '');
    expect(await crypto.decrypt('', aad), '');
  });

  test('legacy plaintext passes through on read', () async {
    final crypto = CryptoManager();
    expect(await crypto.decrypt('old-plain-password', utf8.encode('x')), 'old-plain-password');
  });

  test('old Tink enc:v1: values read as blank, never crash', () async {
    final crypto = CryptoManager();
    expect(await crypto.decrypt('enc:v1:AAAA', utf8.encode('x')), '');
  });

  test('wrong AAD does not decrypt', () async {
    final crypto = CryptoManager();
    final stored =
        await crypto.encrypt('secret', utf8.encode('server-a'));
    expect(await crypto.decrypt(stored, utf8.encode('server-b')), '');
  });

  test('corrupt ciphertext reads as blank', () async {
    final crypto = CryptoManager();
    expect(
      await crypto.decrypt('enc:v2:not-valid-base64!!!', utf8.encode('x')),
      '',
    );
  });
}
