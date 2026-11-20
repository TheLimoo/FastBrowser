// Ad/tracker domain blocklist — Dart port of AdBlockFilter.kt.
// Same 24 suffixes. Applied by the native WebView host in
// shouldInterceptRequest (non-main-frame requests only); the list is
// passed from Dart so there is one source of truth.

class AdBlockFilter {
  static const blockedSuffixes = <String>{
    'doubleclick.net',
    'googlesyndication.com',
    'googleadservices.com',
    'google-analytics.com',
    'googletagmanager.com',
    'facebook.net',
    'connect.facebook.net',
    'adnxs.com',
    'rubiconproject.com',
    'pubmatic.com',
    'openx.net',
    'criteo.com',
    'taboola.com',
    'outbrain.com',
    'scorecardresearch.com',
    'hotjar.com',
    'mixpanel.com',
    'segment.io',
    'appsflyer.com',
    'adjust.com',
    'branch.io',
    'moatads.com',
    'amazon-adsystem.com',
  };

  static bool isBlockedDomain(String host) {
    if (blockedSuffixes.contains(host)) return true;
    var dotIdx = host.indexOf('.');
    while (dotIdx != -1) {
      final suffix = host.substring(dotIdx + 1);
      if (blockedSuffixes.contains(suffix)) return true;
      dotIdx = host.indexOf('.', dotIdx + 1);
    }
    return false;
  }
}
