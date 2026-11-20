package com.evanna.player.sources.radio

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.sources.RemoteEntry
import com.evanna.player.sources.SourceClient
import com.evanna.player.sources.SourceConnection
import com.evanna.player.sources.SourceItem
import com.evanna.player.sources.SourceKind
import com.evanna.player.sources.SourcePage
import okhttp3.Headers
import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal class RadioClient : SourceClient {
    override fun browse(connection: SourceConnection, parent: SourceItem?, offset: Int, query: String?,
        cancel: CancelScope): SourcePage {
        require(parent == null && offset in 0..1000 && (query?.length ?: 0) <= 256)
        val policy = radioPolicy(connection.config)
        val url = policy.origin
        val stations = HttpAccess(policy).openStream(url.toString(), emptyMap(), cancel).use { response ->
            checkRadioResponse(response.code, response.headers)
            val mime = radioMime(response.headers)
            val playlist = radioPlaylistFormat(url, mime)
            if (playlist == null) {
                checkRadioAudio(url, mime)
                listOf(RadioStation(url.toString(), connection.name))
            } else {
                parseRadioPlaylist(readPlaylist(response.input, cancel), playlist, policy)
            }
        }
        val entries = stations.mapIndexed { index, station ->
            RemoteEntry(station.url, station.title ?: "Station ${index + 1}")
        }.filter { query.isNullOrBlank() || it.title.contains(query, ignoreCase = true) }
        val end = offset + 100
        return SourcePage(entries.drop(offset).take(100), end.takeIf { it < entries.size })
    }

    override fun resolve(connection: SourceConnection, item: SourceItem, cancel: CancelScope): ResolvedNetworkMedia {
        require(!item.folder)
        cancel.checkActive()
        val policy = radioPolicy(connection.config)
        val url = radioUrl(item.details.getString("_key")).also(policy::validate)
        require(radioPlaylistFormat(url) == null) { "RADIO_NESTED_PLAYLIST" }
        return ResolvedNetworkMedia(url.toString(), mapOf("Icy-MetaData" to "1"), policy)
    }

    companion object {
        fun validate(input: JSONObject, cancel: CancelScope): JSONObject {
            val config = JSONObject().put("url", radioUrl(input.getString("url")).toString())
                .put("allowLanHttp", input.optBoolean("allowLanHttp", false))
            RadioClient().browse(SourceConnection("", SourceKind.RADIO, "Radio", config, true), null, 0, null, cancel)
            return config
        }
    }
}

private fun radioPolicy(config: JSONObject) =
    NetworkPolicy(radioUrl(config.getString("url")).toString(), config.optBoolean("allowLanHttp", false))

internal fun radioMime(headers: Headers) = headers["Content-Type"].orEmpty().substringBefore(';').trim().lowercase()

internal fun checkRadioResponse(status: Int, headers: Headers) {
    if (status != 200) throw IOException("RADIO_UNAVAILABLE")
    if (headers["Content-Encoding"].orEmpty().lowercase() !in setOf("", "identity"))
        throw IOException("RADIO_CONTENT_ENCODING_UNSUPPORTED")
}

internal fun checkRadioAudio(url: HttpUrl, mime: String) {
    if (radioPlaylistFormat(url, mime) != null) throw IOException("RADIO_NESTED_PLAYLIST")
    if (mime !in setOf("", "audio/mpeg", "audio/x-mpeg", "audio/mp3", "audio/x-mp3", "audio/aac", "audio/aacp",
        "audio/aac-adts", "audio/x-aac", "audio/x-aacp", "audio/ogg", "audio/opus", "application/ogg",
        "application/octet-stream")) throw IOException("RADIO_AUDIO_UNSUPPORTED")
}

private fun readPlaylist(input: InputStream, cancel: CancelScope): ByteArray {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    val buffer = ByteArray(8192)
    val bytes = ByteArrayOutputStream()
    while (true) {
        cancel.checkActive()
        if (System.nanoTime() >= deadline) throw IOException("RADIO_PLAYLIST_TIMEOUT")
        val read = input.read(buffer)
        if (read < 0) return bytes.toByteArray()
        if (bytes.size() + read > MAX_RADIO_PLAYLIST_BYTES) throw IOException("RADIO_PLAYLIST_TOO_LARGE")
        bytes.write(buffer, 0, read)
    }
}
