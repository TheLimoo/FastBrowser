package com.fastbrowser.gologin.browser

object AdBlockFilter {
    private val blockedSuffixes = hashSetOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "facebook.net",
        "connect.facebook.net",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.io",
        "appsflyer.com",
        "adjust.com",
        "branch.io",
        "moatads.com",
        "amazon-adsystem.com"
    )

    fun isBlockedDomain(host: String): Boolean {
        if (blockedSuffixes.contains(host)) return true
        var dotIdx = host.indexOf('.')
        while (dotIdx != -1) {
            val suffix = host.substring(dotIdx + 1)
            if (blockedSuffixes.contains(suffix)) return true
            dotIdx = host.indexOf('.', dotIdx + 1)
        }
        return false
    }
}
