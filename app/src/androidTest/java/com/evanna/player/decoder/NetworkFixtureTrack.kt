package com.evanna.player.decoder

import android.content.Context
import com.evanna.player.playback.PcmReader
import com.evanna.player.playback.PcmTrack
import com.evanna.player.playback.TrackPcmFormat
import java.net.URL
import java.nio.ByteBuffer

/** Test-only source shared by synthetic and physical USB checks. Each playback gets a fresh proxy. */
internal class NetworkFixtureTrack(private val context: Context, private val url: URL,
    private val length: Long, info: DecoderInfo) : PcmTrack {
    override val id = "evanna:network:generated-flac"
    override val format = TrackPcmFormat(info.sampleRate, checkNotNull(info.significantBits), info.channels,
        info.significantBits, "FLAC", true, info.durationUs)
    override val seekable = true
    @Volatile var lastSource: HttpRangeProxy? = null; private set
    override fun open(): PcmReader {
        val source = HttpRangeProxy(context, url, length)
        val decoder = try {
            NativeDecoder.open(source.descriptor.fd).also { decoder ->
                try {
                    check(decoder.info.codec == "flac" && decoder.info.lossless && !decoder.info.quantized &&
                        decoder.info.sampleRate == format.sampleRate && decoder.info.significantBits == format.significantBits &&
                        decoder.info.channels == format.channels && decoder.info.durationUs == format.durationUs) { "Network format changed" }
                } catch (failure: Throwable) { decoder.close(); throw failure }
            }
        } catch (failure: Throwable) { source.close(); throw failure }
        lastSource = source
        val pcm = format
        return object : PcmReader {
            override val format = pcm
            override fun read(target: ByteBuffer, maxFrames: Int) = decoder.readPcm(target, maxFrames)
            override fun seekTo(positionUs: Long) = decoder.seekTo(positionUs)
            override fun requestCancel() { source.cancel(); decoder.requestCancel() }
            override fun close() { source.cancel(); try { decoder.close() } finally { source.close() } }
        }
    }
    override fun close() = Unit
}
