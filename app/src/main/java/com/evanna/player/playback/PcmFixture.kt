package com.evanna.player.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Full-range analysis markers, never an automatic listening signal. */
internal class PcmFixture(override val sampleRate: Int, override val significantBits: Int, override val channels: Int) : PcmFormat {
    init {
        require(sampleRate > 0)
        require(significantBits in listOf(16, 24, 32))
        require(channels in 1..2)
    }

    fun block(firstFrame: Long, frames: Int): ByteBuffer {
        require(firstFrame >= 0)
        require(frames in 1..4096) { "Fixture blocks must contain 1..4096 frames" }
        val shift = 32 - significantBits
        val one = 1 shl shift
        val markers = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE and (-1 shl shift),
            one, -1 shl shift, 0, 0, one, 0, 0, one)
        return ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (frame in 0 until frames) {
                val marker = ((firstFrame % 5 + frame) % 5).toInt() * 2
                for (channel in 0 until channels) putInt(markers[marker + channel])
            }
            flip()
        }
    }
}
