package com.evanna.player.webmusic

import android.content.Context
import android.system.Os
import com.evanna.player.decoder.NetworkAudioTrack
import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpRangeSource
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.playback.QueueSource
import com.evanna.player.sources.SourceReference
import okhttp3.HttpUrl
import java.io.IOException
import java.util.UUID

internal object WebStreams {
    private const val CONNECTION_ID = "2aa5c2c0-59cd-4827-a7f4-9ac4922a2748"
    private val streams = LinkedHashMap<SourceReference, Entry>()
    internal class Entry(val ref: SourceReference, val source: QueueSource, val media: ResolvedNetworkMedia)

    fun owns(ref: SourceReference) = ref.connectionId == CONNECTION_ID

    @Synchronized
    operator fun get(ref: SourceReference): Entry? = streams[ref]

    @Synchronized
    fun remember(entry: Entry) {
        // Playback references are durable. Short-lived requests and their credentials are process-local.
        streams[entry.ref] = entry
        if (streams.size > 4) streams.remove(streams.keys.first())
    }

    fun prepare(context: Context, url: HttpUrl, name: String, agent: String, cookie: String?,
                cancel: CancelScope): Entry {
        val address = url.newBuilder().fragment(null).build().toString()
        val policy = NetworkPolicy(address, requirePublic = true)
        val headers = webRequestHeaders(agent, cookie)
        val ref = SourceReference(CONNECTION_ID, UUID.randomUUID().toString())
        val media = ResolvedNetworkMedia { app, scope ->
            val source = HttpRangeSource(app, address, headers, policy, scope, WEB_AUDIO_MAX_BYTES)
            try {
                val prefix = ByteArray(4)
                if (Os.pread(source.descriptor.fileDescriptor, prefix, 0, prefix.size, 0L) != prefix.size ||
                    webAudioCodec(prefix) == null) throw IOException("WEB_AUDIO_REQUIRED")
                source
            } catch (failure: Throwable) {
                runCatching { source.close() }
                throw failure
            }
        }
        val track = NetworkAudioTrack.prepare(context, ref.personalId, cancel, fastRangeSeek = true) { media }
        val info = track.info
        if (info.codec !in setOf("mp3", "flac") || info.durationUs == null || info.durationUs <= 0L)
            throw IOException("WEB_SEEKABLE_AUDIO_REQUIRED")
        cancel.checkActive()
        return Entry(ref, QueueSource(null, info.title ?: importedDownloadName(name, info.codec), info.artist,
            info.album, info.durationUs, musicTags = info.musicTags, sourceRef = ref,
            prepare = { scope -> scope.checkActive(); track }) { track }, media)
    }
}
