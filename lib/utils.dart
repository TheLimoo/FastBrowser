// Small shared helpers (ports of the Kotlin top-level functions in
// BrowserScreen.kt).

import 'package:flutter/material.dart';

import 'models.dart';

String cleanDomain(String url) {
  if (url.isEmpty) return '';
  var out = url
      .replaceFirst('https://', '')
      .replaceFirst('http://', '')
      .replaceFirst('www.', '');
  out = out.split('/').first.split('?').first;
  return out.isEmpty ? url : out;
}

String flagFor(String countryCode) => switch (countryCode.toUpperCase()) {
      'SE' => '🇸🇪',
      'US' => '🇺🇸',
      'FR' => '🇫🇷',
      'DE' => '🇩🇪',
      'GB' => '🇬🇧',
      'NL' => '🇳🇱',
      _ => '🌐',
    };

Color latencyColor(int? rttMs) {
  if (rttMs == null) return Colors.red;
  if (rttMs < 80) return const Color(0xFF00E676);
  if (rttMs <= 180) return const Color(0xFFFFC107);
  return Colors.red;
}

/// URL-or-search resolution (port of BrowserViewModel.resolveInputToUrl).
/// Blank input → homepage. Looks-like-a-URL → https:// prefix if missing.
/// Anything else → the default search engine's query URL.
String resolveInputToUrl(String input, SearchEngine engine, String homePageUrl) {
  final trimmed = input.trim();
  if (trimmed.isEmpty) return homePageUrl;
  final looksLikeUrl = trimmed.contains('.') &&
      !trimmed.contains(' ') &&
      (trimmed.startsWith('http://') ||
          trimmed.startsWith('https://') ||
          RegExp(r'^[a-zA-Z0-9-]+(\.[a-zA-Z0-9-]+)+(/.*)?$')
              .hasMatch(trimmed));
  if (looksLikeUrl) {
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return trimmed;
    }
    return 'https://$trimmed';
  }
  return engine.queryUrl
      .replaceFirst('%s', Uri.encodeComponent(trimmed));
}

String formatTime(int millis) {
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  final h = d.hour.toString().padLeft(2, '0');
  final m = d.minute.toString().padLeft(2, '0');
  final s = d.second.toString().padLeft(2, '0');
  return '$h:$m:$s';
}

String formatDateTime(int millis) {
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  final y = d.year.toString();
  final mo = d.month.toString().padLeft(2, '0');
  final day = d.day.toString().padLeft(2, '0');
  return '$y-$mo-$day ${formatTime(millis)}';
}
