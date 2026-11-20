package com.evanna.player.webmusic

import java.util.Locale

internal class WebAdBlocker(
    blockedHosts: () -> Set<String>,
    @Volatile var enabled: Boolean,
) {
    private val hosts by lazy(blockedHosts)

    fun blocks(host: String?, mainFrame: Boolean): Boolean {
        if (!enabled || mainFrame || host == null) return false
        var suffix = host.lowercase(Locale.ROOT).trimEnd('.')
        // shortcut: match ad-server domains only, add URL or cosmetic rules if sites need them.
        while (suffix.isNotEmpty()) {
            if (suffix in hosts) return true
            suffix = suffix.substringAfter('.', missingDelimiterValue = "")
        }
        return false
    }
}
