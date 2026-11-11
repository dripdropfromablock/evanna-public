package com.evanna.player.decoder

import android.os.CancellationSignal
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import com.evanna.player.loudness.LoudnessMeasurement
import com.evanna.player.loudness.LOUDNESS_MAX_DURATION_US

internal data class DecoderInfo(
    val codec: String, val sampleRate: Int, val significantBits: Int?,
    val channels: Int, val durationUs: Long?, val seekable: Boolean,
    val lossless: Boolean, val quantized: Boolean,
    val title: String? = null, val artist: String? = null, val album: String? = null,
    val gaplessEligible:Boolean=false,
    val musicTags: MusicTags = MusicTags(),
    val sortTags:SortTags=SortTags(),
    val loudnessTags:com.evanna.player.loudness.LoudnessTags=com.evanna.player.loudness.LoudnessTags(),
)
internal data class DecoderStats(val positionFrames: Long, val quantizedSamples: Long, val clippedSamples: Long)

/** Fixed-format decoder. Reads/seek/close serialize; cancellation can interrupt a read or seek. */
internal class NativeDecoder private constructor(private var handle: Long, val info: DecoderInfo) : Closeable {
    private val lifetime = ReentrantReadWriteLock()
    private val reader = Any()

    /** Writes left-aligned interleaved S32 LE at the buffer base; zero means drained EOF. */
    fun readPcm(target: ByteBuffer, maxFrames: Int): Int = synchronized(reader) {
        require(maxFrames in 1..4096)
        require(target.isDirect && !target.isReadOnly && target.order() == ByteOrder.LITTLE_ENDIAN)
        require(target.position() == 0 && target.limit() == target.capacity())
        require(target.capacity().toLong() >= maxFrames.toLong() * info.channels * 4)
        lifetime.read { check(handle != 0L) { "Decoder is closed" }; nativeRead(handle, target, maxFrames) }
    }

    fun requestCancel() = lifetime.read { if (handle != 0L) nativeCancel(handle) }

    /** Copies bounded embedded image bytes; does not consume or seek audio. */
    fun artwork(): ByteArray? = synchronized(reader) {
        lifetime.read { check(handle != 0L) { "Decoder is closed" }; nativeArtwork(handle) }
    }

    /** Embedded lyric texts as stored (at most four, each up to 256 KiB); does not consume or seek audio. */
    fun lyrics(): List<String> = synchronized(reader) {
        lifetime.read { check(handle != 0L) { "Decoder is closed" }; nativeLyrics(handle).map { it.toString(Charsets.UTF_8) } }
    }

    /** Exact decoded-timeline boundary, rounded up to the next source frame; work remains bounded. */
    fun seekTo(positionUs: Long) = synchronized(reader) {
        require(positionUs >= 0)
        lifetime.read { check(handle != 0L) { "Decoder is closed" }; nativeSeek(handle, positionUs) }
    }

    fun stats(): DecoderStats = synchronized(reader) {
        lifetime.read {
            check(handle != 0L) { "Decoder is closed" }
            val values = nativeStats(handle)
            DecoderStats(values[0], values[1], values[2])
        }
    }

    /** Consumes a fresh decoder to EOF without output; requestCancel interrupts bounded decode blocks. */
    fun measureLoudness():LoudnessMeasurement = synchronized(reader) {
        lifetime.read {
            check(handle!=0L){"Decoder is closed"}
            val values=nativeMeasureLoudness(handle)
            require(values.size==3 && values[2].isFinite() && values[2]>=1 && values[2]%1.0==0.0)
            val maximumFrames=info.sampleRate.toLong()*604800
            require(values[2]<=maximumFrames.toDouble())
            val duration=values[2].toLong()*1_000_000/info.sampleRate
            require(duration in 1..LOUDNESS_MAX_DURATION_US)
            LoudnessMeasurement(values[0].takeUnless {it==Double.NEGATIVE_INFINITY},values[1],duration)
        }
    }

    override fun close() {
        requestCancel()
        lifetime.write {
            if (handle != 0L) { nativeClose(handle); handle = 0L }
        }
    }

    companion object {
        init { System.loadLibrary("evanna_decoder") }

        /** Borrows fd only during this call; native owns a duplicate when it returns. */
        @Throws(IOException::class)
        fun open(fd: Int): NativeDecoder {
            val values = nativeOpen(fd)
            try {
                return NativeDecoder(values[0], readInfo(values, playback = true))
            } catch (failure: Throwable) {
                nativeClose(values[0])
                throw failure
            }
        }

        /** Reads bounded container metadata and tags without preparing PCM output. */
        @Throws(IOException::class)
        fun inspect(fd: Int, signal: CancellationSignal): DecoderInfo {
            val values = nativeInspect(fd, signal)
            return try { readInfo(values, playback = false) }
            finally { nativeClose(values[0]) }
        }

        private fun readInfo(values: LongArray, playback: Boolean): DecoderInfo {
            val rawTags = nativeTags(values[0])
            require(rawTags.size == 20) { "DECODER_TAGS_MISMATCH" }
            val loudness = if (playback) com.evanna.player.loudness.loudnessTagsFromText(
                nativeGainTags(values[0]).map { it.toString(Charsets.UTF_8) }) else com.evanna.player.loudness.LoudnessTags()
            return DecoderInfo(nativeCodec(values[0]), values[1].toInt(), values[2].toInt().takeIf { it > 0 },
                values[3].toInt(), values[4].takeIf { it >= 0 }, true, values[5] != 0L, values[6] != 0L,
                metadataText(rawTags[0]), metadataText(rawTags[1]), metadataText(rawTags[2]), playback && values[7] != 0L,
                musicTagsFromBytes(rawTags.copyOfRange(0,16)), sortTagsFromBytes(rawTags.copyOfRange(16,20)), loudness)
        }

        @JvmStatic private external fun nativeOpen(fd: Int): LongArray
        @JvmStatic private external fun nativeInspect(fd: Int, signal: CancellationSignal): LongArray
        @JvmStatic private external fun nativeCodec(handle: Long): String
        @JvmStatic private external fun nativeTags(handle: Long): Array<ByteArray>
        @JvmStatic private external fun nativeGainTags(handle:Long):Array<ByteArray>
        @JvmStatic private external fun nativeArtwork(handle: Long): ByteArray?
        @JvmStatic private external fun nativeLyrics(handle: Long): Array<ByteArray>
        @JvmStatic private external fun nativeRead(handle: Long, target: ByteBuffer, maxFrames: Int): Int
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeSeek(handle: Long, positionUs: Long)
        @JvmStatic private external fun nativeStats(handle: Long): LongArray
        @JvmStatic private external fun nativeMeasureLoudness(handle:Long):DoubleArray
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}

internal fun metadataText(bytes: ByteArray): String? = bytes.toString(Charsets.UTF_8)
    .map { if (it.isISOControl()) ' ' else it }.joinToString("")
    .replace(Regex("\\s+"), " ").trim().take(512).ifEmpty { null }
