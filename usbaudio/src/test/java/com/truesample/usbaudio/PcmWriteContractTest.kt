package com.truesample.usbaudio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWriteContractTest {
    private fun direct() = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun acceptsZeroAndFullBufferWithoutChangingPosition() {
        val pcm = direct()
        validatePcmBuffer(pcm, 0)
        validatePcmBuffer(pcm, 2)
        assertEquals(0, pcm.position())
        val slice = pcm.apply { position(4); limit(12) }.slice().order(ByteOrder.LITTLE_ENDIAN)
        validatePcmBuffer(slice, 1)
        assertEquals(8, slice.capacity())
    }

    @Test fun rejectsWrongRepresentationAndAmbiguousBufferRange() {
        val invalid = listOf(ByteBuffer.allocate(16), direct().order(ByteOrder.BIG_ENDIAN),
            direct().apply { position(4) }, direct().apply { limit(8) })
        for (pcm in invalid) assertThrows(IllegalArgumentException::class.java) { validatePcmBuffer(pcm, 1) }
        assertThrows(IllegalArgumentException::class.java) { validatePcmBuffer(direct(), -1) }
    }
}
