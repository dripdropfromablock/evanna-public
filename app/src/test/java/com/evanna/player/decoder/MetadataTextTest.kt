package com.evanna.player.decoder

import org.junit.Assert.*
import org.junit.Test

class MetadataTextTest {
    @Test fun emptyControlsInvalidUtf8AndLongTagsStayBoundedText() {
        assertNull(metadataText(byteArrayOf()))
        assertNull(metadataText("\n\r\u0000".toByteArray()))
        assertEquals("café song", metadataText("  café\nsong  ".toByteArray()))
        assertEquals(512, metadataText("x".repeat(5000).toByteArray())!!.length)
        assertTrue(metadataText(byteArrayOf(0xc3.toByte(), 0x28))!!.contains('\uFFFD'))
    }
}
