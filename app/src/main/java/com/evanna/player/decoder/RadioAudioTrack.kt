package com.evanna.player.decoder

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.StreamResponse
import com.evanna.player.playback.PcmReader
import com.evanna.player.playback.PcmTrack
import com.evanna.player.playback.TrackPcmFormat
import com.evanna.player.sources.radio.IcyAudioInput
import com.evanna.player.sources.radio.checkRadioAudio
import com.evanna.player.sources.radio.checkRadioResponse
import com.evanna.player.sources.radio.radioMime
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

internal class RadioAudioTrack private constructor(
    override val id: String,
    override val format: TrackPcmFormat,
    private val resolve: (CancelScope) -> ResolvedNetworkMedia,
) : PcmTrack {
    override fun open(): PcmReader = open(CancelScope())

    override fun open(cancel: CancelScope): PcmReader = openRadio(resolve(cancel), cancel).also { reader ->
        if (reader.format != format) {
            reader.close()
            throw IOException("SOURCE_FORMAT_CHANGED")
        }
    }

    override fun close() = Unit

    companion object {
        fun prepare(id: String, cancel: CancelScope, resolve: (CancelScope) -> ResolvedNetworkMedia): RadioAudioTrack {
            val probe = CancelScope()
            val unlink = cancel.watch { probe.cancel() }
            val format = try {
                openRadio(resolve(probe), probe).use { it.format }
            } finally {
                unlink()
            }
            cancel.checkActive()
            return RadioAudioTrack(id, format, resolve)
        }
    }
}

private fun openRadio(media: ResolvedNetworkMedia, cancel: CancelScope): RadioReader {
    val url = checkNotNull(media.url)
    val response = HttpAccess(checkNotNull(media.policy)).openStream(url, media.headers, cancel)
    var pipe: RadioPipe? = null
    var decoder: NativeDecoder? = null
    try {
        checkRadioResponse(response.code, response.headers)
        checkRadioAudio(url.toHttpUrl(), radioMime(response.headers))
        val interval = response.headers["icy-metaint"]?.let {
            it.trim().toIntOrNull()?.takeIf { value -> value in 1..1024 * 1024 }
                ?: throw IOException("RADIO_INVALID_ICY_INTERVAL")
        }
        val input = if (interval == null) response.input else IcyAudioInput(response.input, interval)
        val source = RadioPipe(input, response, cancel)
        pipe = source
        val signal = CancellationSignal()
        val unlink = cancel.watch { signal.cancel() }
        val opened = try { NativeDecoder.openStream(source.fd, signal) } finally { unlink() }
        decoder = opened
        cancel.checkActive()
        return RadioReader(source, opened, cancel)
    } catch (failure: Throwable) {
        cancel.cancel()
        runCatching { decoder?.close() }
        runCatching { if (pipe == null) response.close() else pipe.close() }
        throw failure
    }
}

private class RadioReader(
    private val pipe: RadioPipe,
    private val decoder: NativeDecoder,
    private val cancel: CancelScope,
) : PcmReader {
    override val format = decoder.info.let { info ->
        TrackPcmFormat(info.sampleRate, info.significantBits ?: 32, info.channels, info.significantBits,
            sourceCodec(info.codec, info.sampleRate), false, null)
    }
    private val unlink = cancel.watch { decoder.requestCancel() }

    override fun read(target: ByteBuffer, maxFrames: Int): Int {
        cancel.checkActive()
        return decoder.readPcm(target, maxFrames).also {
            if (it == 0) {
                pipe.checkEnd()
                throw IOException("RADIO_STREAM_ENDED")
            }
        }
    }

    override fun requestCancel() = cancel.cancel()

    override fun close() {
        cancel.cancel()
        unlink()
        try { decoder.close() } finally { pipe.close() }
    }
}

/** The kernel pipe bounds pending audio and backpressures the HTTP reader. */
private class RadioPipe(input: InputStream, response: StreamResponse, cancel: CancelScope) : Closeable {
    private val descriptors = ParcelFileDescriptor.createPipe()
    val fd get() = descriptors[0].fd
    @Volatile private var failed = false
    private val worker = try {
        thread(name = "EvannaRadio", isDaemon = true) {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    cancel.checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } catch (_: IOException) {
                failed = !cancel.cancelled
            } finally {
                runCatching { output.close() }
                runCatching { response.close() }
            }
        }
    } catch (failure: Throwable) {
        descriptors.forEach { runCatching { it.close() } }
        throw failure
    }

    fun checkEnd() {
        if (failed) throw IOException("RADIO_STREAM_INTERRUPTED")
    }

    override fun close() {
        descriptors[0].close()
        worker.join(1000)
    }
}
