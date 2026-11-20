// Password encryption — Dart equivalent of the v1.0.7 Tink AES256-GCM
// approach (CryptoManager): AES-256-GCM, one key held in secure storage
// (Android Keystore-backed via flutter_secure_storage), one associated-data
// value per server id. Same boundary rules as v1.0.7:
// - blank stays blank; legacy plaintext passes through on read
// - a decrypt failure must never wipe the server list (callers fall back
//   to blank); an encrypt failure keeps the plaintext rather than losing it
// - stored format: "enc:v2:" + base64(nonce[12] + mac[16] + ciphertext)
// - "enc:v1:" is the old Tink format: Dart cannot decrypt it. Values in
//   that format only arrive through the Kotlin legacy migration, which
//   decrypts them with the old CryptoManager before handing data to Dart.
//   If one ever reaches Dart unread, it reads as blank (no-auth servers
//   still work) rather than crashing the store.

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class CryptoManager {
  static const _keyStorageKey = 'fastbrowser_aes_key_v2';
  static const encryptedPrefix = 'enc:v2:';
  static const _legacyTinkPrefix = 'enc:v1:';

  final FlutterSecureStorage _secureStorage;
  final AesGcm _cipher = AesGcm.with256bits();
  SecretKey? _key;
  bool _keyTried = false;

  CryptoManager({FlutterSecureStorage? secureStorage})
      : _secureStorage = secureStorage ?? const FlutterSecureStorage();

  Future<SecretKey?> _loadKey() async {
    if (_key != null) return _key;
    if (_keyTried) return null;
    _keyTried = true;
    try {
      final stored = await _secureStorage.read(key: _keyStorageKey);
      if (stored != null && stored.isNotEmpty) {
        _key = SecretKey(base64Decode(stored));
        return _key;
      }
      final fresh = await _cipher.newSecretKey();
      final bytes = await fresh.extractBytes();
      await _secureStorage.write(
        key: _keyStorageKey,
        value: base64Encode(bytes),
      );
      _key = fresh;
      return _key;
    } catch (_) {
      return null;
    }
  }

  Future<String> encrypt(String plaintext, List<int> associatedData) async {
    if (plaintext.isEmpty) return '';
    final key = await _loadKey();
    if (key == null) return plaintext; // keep plaintext, never lose it
    try {
      final nonce = _randomBytes(12);
      final box = await _cipher.encrypt(
        utf8.encode(plaintext),
        secretKey: key,
        nonce: nonce,
        aad: associatedData,
      );
      final packed = Uint8List(12 + box.mac.bytes.length + box.cipherText.length);
      packed.setAll(0, nonce);
      packed.setAll(12, box.mac.bytes);
      packed.setAll(12 + box.mac.bytes.length, box.cipherText);
      return encryptedPrefix + base64Encode(packed);
    } catch (_) {
      return plaintext;
    }
  }

  Future<String> decrypt(String storedValue, List<int> associatedData) async {
    if (storedValue.isEmpty) return '';
    if (!storedValue.startsWith(encryptedPrefix)) {
      // Legacy plaintext passes through; the old Tink format cannot be
      // decrypted in Dart — blank, never a crash.
      if (storedValue.startsWith(_legacyTinkPrefix)) return '';
      return storedValue;
    }
    final key = await _loadKey();
    if (key == null) return '';
    try {
      final packed = base64Decode(storedValue.substring(encryptedPrefix.length));
      if (packed.length < 12 + 16) return '';
      final nonce = packed.sublist(0, 12);
      final mac = Mac(packed.sublist(12, 28));
      final cipherText = packed.sublist(28);
      final clear = await _cipher.decrypt(
        SecretBox(cipherText, nonce: nonce, mac: mac),
        secretKey: key,
        aad: associatedData,
      );
      return utf8.decode(clear);
    } catch (_) {
      return '';
    }
  }

  static List<int> _randomBytes(int n) {
    final rnd = Random.secure();
    return List<int>.generate(n, (_) => rnd.nextInt(256));
  }
}
