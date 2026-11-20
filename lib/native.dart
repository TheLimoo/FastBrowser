// Native channel wrappers — the only Dart→Kotlin calls outside the
// per-WebView channels (see webview_host.dart).
//
// `fastbrowser/proxy`: bind the Android WebView proxy override to the Dart
// bridge port (AndroidX ProxyController — Flutter has no API).
// `fastbrowser/native`: one-off native jobs — legacy store migration,
// MediaStore download insert/open/delete, profile-data deletion, global
// web-data clear.

import 'dart:convert';

import 'package:flutter/services.dart';

class NativeBridge {
  static const _proxyChannel = MethodChannel('fastbrowser/proxy');
  static const _nativeChannel = MethodChannel('fastbrowser/native');

  /// Binds the WebView proxy override to 127.0.0.1:[port].
  /// Returns 'applied', 'unsupported' (PROXY_OVERRIDE not available — the
  /// fail-closed case when the kill-switch is on) or 'direct' (kill-switch
  /// off: the caller explicitly allows direct traffic).
  static Future<String> bindProxy({
    required int port,
    required bool killSwitch,
  }) async {
    try {
      final result = await _proxyChannel.invokeMethod<String>('bindProxy', {
        'port': port,
        'killSwitch': killSwitch,
      });
      return result ?? 'unsupported';
    } catch (_) {
      return 'unsupported';
    }
  }

  /// Reads the v1.0.7 Kotlin app's stored data (Android DataStore +
  /// Tink-decrypted passwords) for the one-time upgrade migration.
  /// Returns the legacy JSON document or null when nothing was stored.
  static Future<Map<String, dynamic>?> readLegacyStore() async {
    try {
      final raw =
          await _nativeChannel.invokeMethod<String>('readLegacyStore');
      if (raw == null || raw.isEmpty) return null;
      return jsonDecode(raw) as Map<String, dynamic>;
    } catch (_) {
      return null;
    }
  }

  /// Inserts a downloaded temp file into public Downloads via MediaStore.
  /// Returns the saved display name.
  static Future<String> saveToDownloads({
    required String tempPath,
    required String fileName,
    required String mimeType,
  }) async {
    final result = await _nativeChannel.invokeMethod<String>(
      'saveToDownloads',
      {'tempPath': tempPath, 'fileName': fileName, 'mimeType': mimeType},
    );
    return result ?? fileName;
  }

  /// Opens a downloaded file from public Downloads (ACTION_VIEW intent).
  /// Returns true if an app handled it.
  static Future<bool> openDownload(String fileName) async {
    try {
      final result = await _nativeChannel.invokeMethod<bool>(
        'openDownload',
        {'fileName': fileName},
      );
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Deletes a downloaded file from public Downloads by display name.
  static Future<bool> deleteDownload(String fileName) async {
    try {
      final result = await _nativeChannel.invokeMethod<bool>(
        'deleteDownload',
        {'fileName': fileName},
      );
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Deletes one profile's native WebView data (ProfileStore.deleteProfile
  /// + that profile's cookie store). The v1.0.7 wipe also cleared GLOBAL
  /// cookies/storage — in v2 that is a separate explicit action
  /// ([clearAllWebData]), so a per-profile wipe no longer nukes other
  /// profiles' cookies.
  static Future<void> deleteProfileData(String profileId) async {
    try {
      await _nativeChannel.invokeMethod('deleteProfileData', {
        'profileId': profileId,
      });
    } catch (_) {}
  }

  /// Global clear: all cookies + WebStorage + WebView caches, all profiles.
  static Future<void> clearAllWebData(List<String> profileIds) async {
    try {
      await _nativeChannel.invokeMethod('clearAllWebData', {
        'profileIds': profileIds,
      });
    } catch (_) {}
  }
}
