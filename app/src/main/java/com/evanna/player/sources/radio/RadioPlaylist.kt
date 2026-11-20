package com.evanna.player.sources.radio

import com.evanna.player.network.NetworkPolicy
import com.evanna.player.user.PLAYLIST_LIMIT
import com.evanna.player.user.parseM3u
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.net.URLDecoder

internal const val MAX_RADIO_PLAYLIST_BYTES = 1024 * 1024
internal enum class RadioPlaylistFormat { M3U, PLS }
internal data class RadioStation(val url: String, val title: String?)

internal fun radioPlaylistFormat(url: HttpUrl, mime: String = ""): RadioPlaylistFormat? = when {
    mime in setOf("audio/x-scpls", "application/pls+xml") ||
        url.pathSegments.last().endsWith(".pls", ignoreCase = true) -> RadioPlaylistFormat.PLS
    mime in setOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl") ||
        url.pathSegments.last().substringAfterLast('.', "").lowercase() in setOf("m3u", "m3u8") -> RadioPlaylistFormat.M3U
    else -> null
}

internal fun radioUrl(value: String): HttpUrl {
    require(value.length in 1..4096 && '\\' !in value && value.none(Char::isISOControl)) { "INVALID_RADIO_ADDRESS" }
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.rawAuthority?.isNotEmpty() == true && '@' !in uri.rawAuthority &&
        uri.rawFragment == null && uri.scheme?.lowercase() in setOf("http", "https")) { "INVALID_RADIO_ADDRESS" }
    for (segment in uri.rawPath.orEmpty().split('/')) {
        val decoded = URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        require(decoded != "." && decoded != ".." && '%' !in decoded &&
            decoded.none { it == '/' || it == '\\' || it.isISOControl() }) { "INVALID_RADIO_PATH" }
    }
    return value.toHttpUrl()
}

internal fun parseRadioPlaylist(bytes: ByteArray, format: RadioPlaylistFormat, policy: NetworkPolicy): List<RadioStation> {
    require(bytes.size in 1..MAX_RADIO_PLAYLIST_BYTES) { "INVALID_RADIO_PLAYLIST_SIZE" }
    val rows = when (format) {
        RadioPlaylistFormat.M3U -> {
            require(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence().none {
                it.trimStart().startsWith("#EXT-X-", ignoreCase = true)
            }) { "HLS_UNSUPPORTED" }
            parseM3u(bytes).entries.map { row ->
                row.path to listOfNotNull(row.artist, row.title).joinToString(" - ")
            }
        }
        RadioPlaylistFormat.PLS -> parsePls(bytes)
    }
    require(rows.isNotEmpty()) { "RADIO_PLAYLIST_EMPTY" }
    return rows.map { (value, label) ->
        val url = radioUrl(value).also(policy::validate)
        require(radioPlaylistFormat(url) == null) { "RADIO_NESTED_PLAYLIST" }
        RadioStation(url.toString(), label?.filterNot(Char::isISOControl)?.trim()?.take(512)?.ifBlank { null })
    }.distinctBy { it.url }
}

private fun parsePls(bytes: ByteArray): List<Pair<String, String?>> {
    val lines = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence()
        .map { raw -> require(raw.length <= 4096) { "RADIO_PLAYLIST_LINE_TOO_LONG" }; raw.trim() }
        .filter { it.isNotEmpty() && !it.startsWith(';') && !it.startsWith('#') }.iterator()
    require(lines.hasNext() && lines.next().equals("[playlist]", true)) { "INVALID_PLS_HEADER" }
    val files = sortedMapOf<Int, String>()
    val titles = mutableMapOf<Int, String>()
    var count: Int? = null
    var fields = 0
    while (lines.hasNext()) {
        require(++fields <= PLAYLIST_LIMIT * 3 + 2) { "RADIO_PLAYLIST_TOO_MANY_FIELDS" }
        val line = lines.next()
        require('=' in line) { "INVALID_PLS_FIELD" }
        val key = line.substringBefore('=').trim().lowercase()
        val value = line.substringAfter('=').trim()
        when {
            key == "numberofentries" -> {
                require(count == null) { "DUPLICATE_PLS_FIELD" }
                count = value.toIntOrNull()?.takeIf { it in 1..PLAYLIST_LIMIT }
                    ?: throw IllegalArgumentException("INVALID_PLS_COUNT")
            }
            key.startsWith("file") || key.startsWith("title") -> {
                val file = key.startsWith("file")
                val index = key.removePrefix(if (file) "file" else "title").toIntOrNull()
                require(index != null && index in 1..PLAYLIST_LIMIT) { "INVALID_PLS_INDEX" }
                val target = if (file) files else titles
                require(target.put(index, value) == null) { "DUPLICATE_PLS_FIELD" }
            }
        }
    }
    require(count == null || count == files.size) { "INVALID_PLS_COUNT" }
    return files.map { (index, url) -> url to titles[index] }
}
