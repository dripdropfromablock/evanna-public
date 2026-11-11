package com.evanna.player.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceCodecTest {
    @Test fun dsdIsNamedByItsMultipleAndOtherCodecsKeepTheirNames() {
        assertEquals("DSD64", sourceCodec("dsd_lsbf_planar", 352_800))
        assertEquals("DSD128", sourceCodec("dsd_msbf", 705_600))
        assertEquals("DSD", sourceCodec("dsd_msbf", 384_000)) // 48 kHz-family DSD has no 44.1 kHz multiple name
        assertEquals("FLAC", sourceCodec("flac", 44_100))
    }
}
