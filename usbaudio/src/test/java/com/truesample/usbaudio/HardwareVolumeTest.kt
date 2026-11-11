package com.truesample.usbaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HardwareVolumeTest {
    @Test fun invalidVolumeNeverTurnsIntoAZeroDbRequest() {
        for (db in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -128.001f, 128f))
            assertThrows(IllegalArgumentException::class.java) { hardwareVolume256(db) }
    }

    @Test fun quantizationNeverRaisesTheRequestedGain() {
        assertEquals(-7680, hardwareVolume256(-30f))
        assertEquals(-7681, hardwareVolume256(-30.001f))
        assertEquals(-32768, hardwareVolume256(-128f))
        assertEquals(32767, hardwareVolume256(32767f / 256f))
    }
}
