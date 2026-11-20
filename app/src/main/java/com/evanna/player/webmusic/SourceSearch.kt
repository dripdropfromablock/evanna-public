package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.sources.SourceReference
import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.IOException

internal data class SearchArtist(val provider: String, val id: String, val name: String, val pageUrl: String)

internal data class SearchRelease(
    val provider: String, val id: String, val title: String, val artist: String, val pageUrl: String
)

internal data class SearchTrack(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val pageUrl: String,
    val streamUrl: String? = null,
    val downloads: List<SearchDownloadFile> = emptyList(),
    val format: String? = null,
    val quality: String? = null,
    val disc: Int? = null,
    val number: Int? = null,
    val durationMs: Long? = null,
    val preview: Boolean = false,
    val release: SearchRelease? = null,
    val libraryId: String? = null,
    val sourceRef: SourceReference? = null,
)

internal data class SourceMatches(
    val artists: List<SearchArtist> = emptyList(),
    val releases: List<SearchRelease> = emptyList(),
    val tracks: List<SearchTrack> = emptyList()
)

internal interface MusicSearchProvider {
    val id: String
    val label: String
    fun search(query: String, cancel: CancelScope): SourceMatches
    fun artist(artist: SearchArtist, cancel: CancelScope): SourceMatches
    fun tracks(release: SearchRelease, cancel: CancelScope): List<SearchTrack>
}

internal fun providerGet(url: HttpUrl, cancel: CancelScope, maxBytes: Int): String {
    val access = HttpAccess(NetworkPolicy(url.toString(), requirePublic = true))
    val response = access.fetch(url.toString(), maxBytes, headers = mapOf("Accept" to "application/json"), scope = cancel)
    if (response.code != 200) throw IOException("The source returned HTTP ${response.code}.")
    return response.bytes.toString(Charsets.UTF_8)
}

internal fun JSONObject.providerText(key: String, limit: Int = 400): String? =
    (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= limit && it.none(Char::isISOControl) }
