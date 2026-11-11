package com.evanna.player.playback

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RangePcmTrackTest {
    /** Mono frame n holds the value n; seeking follows the decoder's rounded-up microsecond rule. */
    private class Ramp(val frames: Long, val rate: Int = 44100) : PcmTrack {
        override val id = "ramp"
        override val format = TrackPcmFormat(rate, 32, 1, 32, "FLAC", true, frames * 1_000_000 / rate)
        override val seekable = true
        override val gaplessEligible = true
        var opens = 0
        override fun open(): PcmReader { opens++; return object : PcmReader {
            var position = 0L
            override val format = this@Ramp.format
            override fun read(target: ByteBuffer, maxFrames: Int): Int {
                val count = minOf(maxFrames.toLong(), frames - position).toInt()
                repeat(count) { target.putInt(it * 4, (position + it).toInt()) }
                position += count; return count
            }
            override fun seekTo(positionUs: Long) { position = (positionUs * rate + 999_999) / 1_000_000; if (position > frames) throw IOException() }
            override fun requestCancel() = Unit
            override fun close() = Unit
        } }
        override fun close() = Unit
    }
    private fun PcmReader.drain(chunk: Int = 1000): List<Int> {
        val buffer = ByteBuffer.allocateDirect(chunk * 4).order(ByteOrder.LITTLE_ENDIAN)
        return buildList { while (true) { val count = read(buffer, chunk); if (count == 0) break; repeat(count) { add(buffer.getInt(it * 4)) } } }
    }

    @Test fun adjacentRangesCoverEveryFrameExactlyOnce() {
        for (rate in listOf(44100, 48000, 96000)) {
            val file = Ramp(10_000, rate)
            val bounds = listOf(0L, 2_646L, 5_313L, null)
            val played = bounds.zipWithNext().flatMap { (start, end) -> RangePcmTrack(file, start!!, end).open().use { it.drain(997) } }
            assertEquals((0 until 10_000).toList(), played)
        }
    }

    @Test fun durationAndSeekingStayInsideTheRange() {
        val track = RangePcmTrack(Ramp(10_000), 2_646, 5_313)
        assertEquals(2_667L * 1_000_000 / 44100, track.format.durationUs)
        assertEquals(RangePcmTrack(Ramp(10_000), 5_313, null).format.durationUs, (10_000L - 5_313) * 1_000_000 / 44100)
        assertNull(track.loudnessTags.trackDb)
        track.open().use { reader ->
            reader.seekTo(1_000_000L * 100 / 44100 + 1) // frame 101 of the range
            assertEquals(2_646 + 101, reader.drain().first())
            reader.seekTo(0); assertEquals(2_646, reader.drain(1).first())
            reader.seekTo((2_667L - 1) * 1_000_000 / 44100 + 1); assertTrue(reader.drain().isEmpty()) // exactly the end
            assertTrue(runCatching { reader.seekTo(1_000_000) }.isFailure)
        }
    }

    @Test fun invalidRangesAreRefused() {
        assertTrue(runCatching { RangePcmTrack(Ramp(10), -1, null) }.isFailure)
        assertTrue(runCatching { RangePcmTrack(Ramp(10), 5, 5) }.isFailure)
    }
}
