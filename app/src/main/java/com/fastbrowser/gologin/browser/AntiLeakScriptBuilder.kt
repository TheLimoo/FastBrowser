package com.fastbrowser.gologin.browser

object AntiLeakScriptBuilder {

    fun build(
        profileId: String,
        uaPresetName: String,
        canvasNoiseEnabled: Boolean = true,
        blockWebRtc: Boolean = true,
        // v1.0.7 adaptation: the old inline script also injected a timezone
        // override (the server's timezoneOverride) — the pack's builder had
        // no timezone parameter, so it is kept here as an optional one
        // instead of silently dropping the override.
        timezone: String = ""
    ): String {
        val (platform, maxTouchPoints) = when (uaPresetName) {
            "DESKTOP_WINDOWS" -> "Win32" to 0
            "DESKTOP_MAC" -> "MacIntel" to 0
            "IPHONE_SAFARI" -> "iPhone" to 5
            else -> "Linux armv8l" to 5 // ANDROID_CHROME
        }
        val seed = profileId.hashCode()

        return buildString {
            appendLine("(function() {")
            appendLine("  'use strict';")
            appendLine("  const defineProp = (obj, prop, value) => {")
            appendLine("    try { Object.defineProperty(obj, prop, { get: () => value, configurable: true }); } catch (_) {}")
            appendLine("  };")
            appendLine("  defineProp(Navigator.prototype, 'webdriver', false);")
            appendLine("  defineProp(Navigator.prototype, 'platform', '$platform');")
            appendLine("  defineProp(Navigator.prototype, 'maxTouchPoints', $maxTouchPoints);")

            if (blockWebRtc) {
                appendLine("  const noopRtc = function() { throw new DOMException('WebRTC disabled', 'NotAllowedError'); };")
                appendLine("  try { window.RTCPeerConnection = noopRtc; window.webkitRTCPeerConnection = noopRtc; } catch (_) {}")
                // Kept from the old inline script: hide media devices too,
                // so enumerateDevices cannot leak real hardware.
                appendLine("  try { if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) { navigator.mediaDevices.enumerateDevices = () => Promise.resolve([]); } } catch (_) {}")
            }

            if (timezone.isNotBlank()) {
                appendLine("  try {")
                appendLine("    const origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;")
                appendLine("    Intl.DateTimeFormat.prototype.resolvedOptions = function() {")
                appendLine("      const res = origResolved.apply(this, arguments);")
                appendLine("      res.timeZone = '$timezone';")
                appendLine("      return res;")
                appendLine("    };")
                appendLine("  } catch (_) {}")
            }

            if (canvasNoiseEnabled) {
                appendLine("  const seed = $seed;")
                appendLine("  const noise = ((seed & 0xff) % 3) - 1;")
                appendLine("  const origToDataURL = HTMLCanvasElement.prototype.toDataURL;")
                appendLine("  HTMLCanvasElement.prototype.toDataURL = function(...args) {")
                appendLine("    try {")
                appendLine("      const ctx = this.getContext('2d');")
                appendLine("      if (ctx && this.width > 0 && this.height > 0) {")
                appendLine("        const img = ctx.getImageData(0, 0, 1, 1);")
                appendLine("        img.data[0] = (img.data[0] + noise + 256) % 256;")
                appendLine("        ctx.putImageData(img, 0, 0);")
                appendLine("      }")
                appendLine("    } catch (_) {}")
                appendLine("    return origToDataURL.apply(this, args);")
                appendLine("  };")
            }
            appendLine("})();")
        }
    }
}
