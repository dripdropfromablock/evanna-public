package com.evanna.player.playback

import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmFixtureTest {
    @Test fun integerMarkersPreservePrecisionAndChannelOrder() {
        val cases = listOf(
            16 to "000000800000ff7f000001000000ffff0000000000000000",
            24 to "0000008000ffff7f0001000000ffffff0000000000000000",
            32 to "00000080ffffff7f01000000ffffffff0000000000000000",
        )
        for ((bits, expected) in cases) {
            val fixture = PcmFixture(44100, bits, 2)
            val pcm = fixture.block(0, 3)
            assertTrue(pcm.isDirect)
            assertEquals(ByteOrder.LITTLE_ENDIAN, pcm.order())
            assertEquals(0, pcm.position())
            assertEquals(24, pcm.remaining())
            val bytes = ByteArray(pcm.remaining()).also { pcm.get(it) }
            assertEquals(expected, bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }

    @Test fun channelImpulsesAndChunkBoundariesAreDeterministic() {
        val fixture = PcmFixture(48000, 24, 2)
        val pcm = fixture.block(3, 3)
        assertEquals(listOf(256, 0, 0, 256, Int.MIN_VALUE, 0x7fffff00),
            List(6) { pcm.int })
        val single = fixture.block(4, 1)
        assertEquals(0, single.int)
        assertEquals(256, single.int)
    }

    @Test fun rejectsInvalidFormatsAndUnboundedBlocks() {
        for (bits in listOf(0, 8, 20, 33)) assertThrows(IllegalArgumentException::class.java) { PcmFixture(44100, bits, 2) }
        assertThrows(IllegalArgumentException::class.java) { PcmFixture(0, 16, 2) }
        assertThrows(IllegalArgumentException::class.java) { PcmFixture(44100, 16, 3) }
        val fixture = PcmFixture(44100, 16, 1)
        assertThrows(IllegalArgumentException::class.java) { fixture.block(-1, 1) }
        for (count in listOf(0, -1, 4097, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { fixture.block(0, count) }
        assertEquals(16384, fixture.block(0, 4096).capacity())
        assertEquals(4, fixture.block(Long.MAX_VALUE, 1).capacity())
    }
}
