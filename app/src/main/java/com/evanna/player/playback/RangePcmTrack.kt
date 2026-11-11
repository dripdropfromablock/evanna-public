package com.evanna.player.playback

import java.io.IOException
import java.nio.ByteBuffer

/** One CUE track: exact source frames [start, end) of a whole-file track; a null end plays to the file's end. */
internal class RangePcmTrack(private val file: PcmTrack, private val start: Long, private val end: Long?) : PcmTrack by file {
    private val rate = file.format.sampleRate
    init { require(start >= 0 && (end == null || end > start) && file.format is TrackPcmFormat) }
    override val format: PcmFormat = (file.format as TrackPcmFormat).let { whole ->
        val frames = end?.minus(start) ?: whole.durationUs?.let { ((it * rate + 500_000) / 1_000_000 - start).coerceAtLeast(0) }
        whole.copy(durationUs = frames?.let { it * 1_000_000 / rate })
    }
    // Whole-file gain tags do not describe one track of it.
    override val loudnessTags = com.evanna.player.loudness.LoudnessTags()

    override fun open(): PcmReader {
        val reader = file.open()
        try {
            if (start > 0) reader.seekTo(exactUs(start))
            return object : PcmReader {
                private var position = start
                override val format = this@RangePcmTrack.format
                override fun read(target: ByteBuffer, maxFrames: Int): Int {
                    val left = end?.minus(position) ?: Long.MAX_VALUE
                    if (left <= 0) return 0
                    val count = reader.read(target, minOf(maxFrames.toLong(), left).toInt())
                    position += count
                    return count
                }
                override fun seekTo(positionUs: Long) {
                    require(positionUs >= 0)
                    val frame = Math.addExact(start, (positionUs * rate + 999_999) / 1_000_000)
                    if (end != null && frame > end) throw IOException("RANGE_SEEK_PAST_END")
                    reader.seekTo(exactUs(frame))
                    position = frame
                }
                override fun requestCancel() = reader.requestCancel()
                override fun close() = reader.close()
            }
        } catch (failure: Throwable) { reader.close(); throw failure }
    }

    /** The smallest time whose rounded-up frame (the decoder's rule) is exactly [frame]. */
    private fun exactUs(frame: Long) = if (frame == 0L) 0L else (frame - 1) * 1_000_000 / rate + 1
}
