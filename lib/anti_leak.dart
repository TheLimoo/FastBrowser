// Anti-leak injection script — Dart port of AntiLeakScriptBuilder.kt.
// The script text is built in Dart and handed to the native WebView host,
// which injects it at document start (AndroidX
// WebViewCompat.addDocumentStartJavaScript — Flutter has no API for this).
//
// webdriver=false, platform/maxTouchPoints per UA preset, WebRTC blocked
// (constructor throws) when the shield is on, enumerateDevices emptied,
// timezone override (Intl resolvedOptions), deterministic canvas noise
// seeded by the profile id hash (same seed math as the Kotlin version:
// (seed & 0xff) % 3 - 1), and — NEW in v2.0.0, wiring the previously dead
// ServerNode.languageOverride — navigator.language / languages override.

String buildAntiLeakScript({
  required String profileId,
  required String uaPresetName,
  bool canvasNoiseEnabled = true,
  bool blockWebRtc = true,
  String timezone = '',
  String language = '',
}) {
  final (platform, maxTouchPoints) = switch (uaPresetName) {
    'DESKTOP_WINDOWS' => ('Win32', 0),
    'DESKTOP_MAC' => ('MacIntel', 0),
    'IPHONE_SAFARI' => ('iPhone', 5),
    _ => ('Linux armv8l', 5), // ANDROID_CHROME
  };
  // Java String.hashCode() semantics (the Kotlin seed): h = 31*h + c.
  var seed = 0;
  for (final c in profileId.codeUnits) {
    seed = (31 * seed + c).toSigned(32);
  }
  final sb = StringBuffer()
    ..writeln('(function() {')
    ..writeln("  'use strict';")
    ..writeln('  const defineProp = (obj, prop, value) => {')
    ..writeln(
      "    try { Object.defineProperty(obj, prop, { get: () => value, configurable: true }); } catch (_) {}",
    )
    ..writeln('  };')
    ..writeln("  defineProp(Navigator.prototype, 'webdriver', false);")
    ..writeln("  defineProp(Navigator.prototype, 'platform', '$platform');")
    ..writeln(
      "  defineProp(Navigator.prototype, 'maxTouchPoints', $maxTouchPoints);");
  if (language.isNotEmpty) {
    final safe = language.replaceAll("'", '');
    sb
      ..writeln("  defineProp(Navigator.prototype, 'language', '$safe');")
      ..writeln(
        "  defineProp(Navigator.prototype, 'languages', ['$safe']);");
  }
  if (blockWebRtc) {
    sb
      ..writeln(
        "  const noopRtc = function() { throw new DOMException('WebRTC disabled', 'NotAllowedError'); };")
      ..writeln(
        '  try { window.RTCPeerConnection = noopRtc; window.webkitRTCPeerConnection = noopRtc; } catch (_) {}')
      ..writeln(
        '  try { if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) { navigator.mediaDevices.enumerateDevices = () => Promise.resolve([]); } } catch (_) {}');
  }
  if (timezone.isNotEmpty) {
    sb
      ..writeln('  try {')
      ..writeln(
        '    const origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;')
      ..writeln(
        '    Intl.DateTimeFormat.prototype.resolvedOptions = function() {')
      ..writeln('      const res = origResolved.apply(this, arguments);')
      ..writeln("      res.timeZone = '$timezone';")
      ..writeln('      return res;')
      ..writeln('    };')
      ..writeln('  } catch (_) {}');
  }
  if (canvasNoiseEnabled) {
    sb
      ..writeln('  const seed = $seed;')
      ..writeln('  const noise = ((seed & 0xff) % 3) - 1;')
      ..writeln(
        '  const origToDataURL = HTMLCanvasElement.prototype.toDataURL;')
      ..writeln(
        '  HTMLCanvasElement.prototype.toDataURL = function(...args) {')
      ..writeln('    try {')
      ..writeln("      const ctx = this.getContext('2d');")
      ..writeln('      if (ctx && this.width > 0 && this.height > 0) {')
      ..writeln('        const img = ctx.getImageData(0, 0, 1, 1);')
      ..writeln('        img.data[0] = (img.data[0] + noise + 256) % 256;')
      ..writeln('        ctx.putImageData(img, 0, 0);')
      ..writeln('      }')
      ..writeln('    } catch (_) {}')
      ..writeln('    return origToDataURL.apply(this, args);')
      ..writeln('  };');
  }
  sb.writeln('})();');
  return sb.toString();
}
