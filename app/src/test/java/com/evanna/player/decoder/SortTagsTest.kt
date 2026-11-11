package com.evanna.player.decoder

import org.junit.Assert.*
import org.junit.Test

class SortTagsTest {
    @Test fun sortNamesAreBoundedPrivateTextAndNeverReplaceVisibleMetadata() {
        val tags=sortTagsFromBytes(arrayOf("  Artist, The\n".toByteArray(),"\u0000".toByteArray(),"a".repeat(5000).toByteArray(),byteArrayOf()))
        assertEquals("Artist, The",tags.title);assertNull(tags.artist)
        assertEquals(512,tags.album?.length);assertNull(tags.albumArtist)
        assertEquals(SortTags(),sortTagsFromBytes(Array(4){byteArrayOf()}))
        assertTrue(runCatching {sortTagsFromBytes(Array(3){byteArrayOf()})}.isFailure)
    }
}
