package com.evanna.player.playback

import org.junit.Assert.*
import org.junit.Test

class SessionArtworkTest {
    @Test fun obsoleteLoadsCannotAttachToAReplacementSongAndMissingArtClearsImmediately() {
        val art=SessionArtwork()
        art.select("first");assertTrue(art.update("first",byteArrayOf(1,2)))
        assertArrayEquals(byteArrayOf(1,2),art.forItem("first"));assertNull(art.forItem("second"))
        art.select("second");assertNull(art.forItem("first"));assertNull(art.forItem("second"))
        assertFalse(art.update("first",byteArrayOf(3)));assertNull(art.forItem("second"))
        assertTrue(art.update("second",byteArrayOf(4)));assertTrue(art.update("second",null))
        assertNull(art.forItem("second"));art.select(null);assertFalse(art.update("second",byteArrayOf(5)))
    }
    @Test fun payloadIsBoundedAndCallerCannotMutateStoredBytes() {
        val art=SessionArtwork();art.select("song")
        val data=byteArrayOf(1,2);assertTrue(art.update("song",data));data[0]=9
        assertEquals(1,art.forItem("song")!![0].toInt())
        assertTrue(art.update("song",ByteArray(300*1024)))
        assertFalse(art.update("song",ByteArray(300*1024+1)));assertNull(art.forItem("song"))
        assertFalse(art.update("song",byteArrayOf()));assertNull(art.forItem("song"))
    }
}
