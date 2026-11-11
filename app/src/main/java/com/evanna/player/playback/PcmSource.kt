package com.evanna.player.playback

import java.io.Closeable
import java.nio.ByteBuffer

/** significantBits describes the native PCM input; sourceBits describes original integer precision. */
internal interface PcmFormat {
    val sampleRate: Int
    val significantBits: Int
    val channels: Int
    val sourceBits: Int? get() = significantBits
    val codec: String get() = "ANALYSIS_FIXTURE"
    val strictEligible: Boolean get() = true
    val durationUs: Long? get() = null
}

internal data class TrackPcmFormat(
    override val sampleRate: Int, override val significantBits: Int, override val channels: Int,
    override val sourceBits: Int?, override val codec: String, override val strictEligible: Boolean,
    override val durationUs: Long?,
) : PcmFormat {
    init {
        require(sampleRate in 8000..384000 && significantBits in listOf(16, 24, 32) && channels in 1..2)
        require(sourceBits == null || sourceBits in listOf(16, 24, 32))
        require(!strictEligible || sourceBits == significantBits)
        require(codec.matches(Regex("[A-Z][A-Z0-9_]{0,80}")))
        require(durationUs == null || durationUs >= 0)
    }
}

internal interface PcmReader : Closeable {
    val format: PcmFormat
    /** Fill at the buffer base without changing position/limit. Zero means EOF. */
    fun read(target: ByteBuffer, maxFrames: Int): Int
    fun seekTo(positionUs: Long) { check(positionUs == 0L) { "Source is not seekable" } }
    fun requestCancel()
}

/** The source location stays private to its factory, never in playback snapshots or MediaItems. */
internal interface PcmTrack : Closeable {
    val loudnessTags:com.evanna.player.loudness.LoudnessTags get()=com.evanna.player.loudness.LoudnessTags()
    val id: String
    val format: PcmFormat
    val seekable: Boolean get() = false
    /** Decoder boundaries verified for this codec/container; unknown trimming uses ordinary EOF. */
    val gaplessEligible: Boolean get() = false
    fun open(): PcmReader
    /** Release prepared resources while retaining a saved direct-file read grant for process restoration. */
    fun closeForRestore() = close()
}
