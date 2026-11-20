// WebView host — Dart side of the custom Flutter PlatformView.
//
// Why a custom platform view instead of `webview_flutter`: Flutter's
// WebView plugin has NO API for the things the old app relies on —
// per-profile WebViews (AndroidX MULTI_PROFILE, separate cookie jars),
// the ProxyController proxy override, document-start JavaScript
// injection, download interception, find-in-page, text zoom or
// algorithmic darkening. A pure-plugin rewrite would silently share one
// cookie jar across all profiles. So the WebView itself stays a real
// `android.webkit.WebView`, hosted by thin Kotlin code
// (android/app/.../FastBrowserWebView.kt, a direct mirror of
// WebViewProfileEngine.kt) and driven from Dart over a MethodChannel +
// EventChannel. Dart owns the script text, the blocklist and all state;
// Kotlin only hosts the view.
//
// One platform view per tab, kept alive in an Offstage Stack so tab
// switches do not destroy the WebView. Changing a profile's shield
// settings rebuilds its tabs' views (new creation params) — the old app
// had the same one-shot configuration.

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../adblock.dart';
import '../anti_leak.dart';
import '../models.dart';

/// Configuration handed to the native side at view creation.
class WebViewConfig {
  final BrowserProfile profile;
  final AppSettings settings;
  final String timezoneOverride;
  final String languageOverride;
  final bool desktopMode;
  final String initialUrl;

  const WebViewConfig({
    required this.profile,
    required this.settings,
    required this.timezoneOverride,
    this.languageOverride = '',
    required this.desktopMode,
    required this.initialUrl,
  });

  String userAgent() {
    if (desktopMode) return UserAgentPreset.DESKTOP_WINDOWS.uaString;
    return profile.customUserAgent.isEmpty
        ? profile.userAgentPreset.uaString
        : profile.customUserAgent;
  }

  Map<String, dynamic> toCreationParams() => {
        'profileId': profile.id,
        'userAgent': userAgent(),
        'blockWebRtc':
            settings.blockWebRtcGlobal || profile.blockWebRtcLeaks,
        'canvasNoise': profile.canvasNoiseEnabled,
        'timezone': timezoneOverride,
        'language': languageOverride,
        'desktopMode': desktopMode,
        'textScale': settings.textScale,
        'blockImages': settings.blockImagesDataSaver,
        'hardwareAcceleration': settings.hardwareAcceleration,
        'forceDark': settings.forceDarkWebPages,
        'blockThirdPartyCookies': settings.blockThirdPartyCookies,
        'adBlockEnabled': settings.adAndTrackerBlocker,
        'adBlockDomains': AdBlockFilter.blockedSuffixes.toList(),
        'antiLeakScript': buildAntiLeakScript(
          profileId: profile.id,
          uaPresetName: desktopMode
              ? UserAgentPreset.DESKTOP_WINDOWS.name
              : profile.userAgentPreset.name,
          canvasNoiseEnabled: profile.canvasNoiseEnabled,
          blockWebRtc:
              settings.blockWebRtcGlobal || profile.blockWebRtcLeaks,
          timezone: timezoneOverride,
          language: languageOverride,
        ),
        'initialUrl': initialUrl,
      };

  Map<String, dynamic> toApplySettings() => {
        'userAgent': userAgent(),
        'textScale': settings.textScale,
        'blockImages': settings.blockImagesDataSaver,
        'hardwareAcceleration': settings.hardwareAcceleration,
        'forceDark': settings.forceDarkWebPages,
        'blockThirdPartyCookies': settings.blockThirdPartyCookies,
      };
}

class WebViewEvent {
  final String type;
  final Map<String, dynamic> data;
  const WebViewEvent(this.type, this.data);
}

/// Handle to one native WebView, created by [FastBrowserWebView].
class WebViewHandle {
  final int viewId;
  late final MethodChannel _channel;
  final StreamController<WebViewEvent> _events =
      StreamController<WebViewEvent>.broadcast();
  StreamSubscription<dynamic>? _eventSub;

  WebViewHandle(this.viewId) {
    _channel = MethodChannel('fastbrowser/webview_$viewId');
    final eventChannel =
        EventChannel('fastbrowser/webview_$viewId/events');
    _eventSub = eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        final map = Map<String, dynamic>.from(event);
        final type = map['type'] as String? ?? '';
        _events.add(WebViewEvent(type, map));
      }
    });
  }

  Stream<WebViewEvent> get events => _events.stream;

  Future<void> loadUrl(String url) =>
      _channel.invokeMethod('loadUrl', {'url': url});

  Future<void> reload() => _channel.invokeMethod('reload');

  Future<void> stopLoading() => _channel.invokeMethod('stopLoading');

  Future<void> goBack() => _channel.invokeMethod('goBack');

  Future<void> goForward() => _channel.invokeMethod('goForward');

  Future<void> findAll(String query) =>
      _channel.invokeMethod('findAll', {'query': query});

  Future<void> findNext(bool forward) =>
      _channel.invokeMethod('findNext', {'forward': forward});

  Future<void> clearMatches() => _channel.invokeMethod('clearMatches');

  Future<void> applySettings(Map<String, dynamic> settings) =>
      _channel.invokeMethod('applySettings', settings);

  Future<String?> getCookie(String url) async {
    final result =
        await _channel.invokeMethod<String>('getCookie', {'url': url});
    return result;
  }

  Future<void> dispose() async {
    await _eventSub?.cancel();
    await _events.close();
    try {
      await _channel.invokeMethod('destroy');
    } catch (_) {}
  }
}

/// The platform view widget. [onCreated] receives the handle once the
/// native WebView exists; [onEvent] receives navigation/progress/error/
/// download events. The widget is kept mounted (Offstage) by the browser
/// screen so the WebView survives tab switches.
class FastBrowserWebView extends StatefulWidget {
  final WebViewConfig config;
  final int configVersion;
  final void Function(WebViewHandle handle) onCreated;
  final void Function(WebViewEvent event) onEvent;

  const FastBrowserWebView({
    super.key,
    required this.config,
    required this.configVersion,
    required this.onCreated,
    required this.onEvent,
  });

  @override
  State<FastBrowserWebView> createState() => _FastBrowserWebViewState();
}

class _FastBrowserWebViewState extends State<FastBrowserWebView> {
  WebViewHandle? _handle;

  @override
  void dispose() {
    unawaited(_handle?.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const Center(child: Text('FastBrowser needs Android'));
    }
    return AndroidView(
      key: ValueKey('webview_${widget.config.profile.id}_v${widget.configVersion}'),
      viewType: 'fastbrowser/webview',
      creationParams: widget.config.toCreationParams(),
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: (viewId) {
        final handle = WebViewHandle(viewId);
        _handle = handle;
        handle.events.listen(widget.onEvent);
        widget.onCreated(handle);
      },
    );
  }
}

/// Encodes a creation-params map for tests / debugging.
String creationParamsJson(WebViewConfig config) =>
    jsonEncode(config.toCreationParams());
