package com.evanna.player.sources

import com.evanna.player.user.*
import org.junit.Assert.*
import org.junit.Test

class PersonalSourceIdentityTest {
    private val ref=SourceReference("a0000000-0000-0000-0000-000000000001","b0000000-0000-0000-0000-000000000001")
    @Test fun remotePersonalIdentityIsStrictAndSeparateFromLocal() {
        assertTrue(validPersonalId(ref.personalId));assertFalse(validLibraryId(ref.personalId))
        assertFalse(validPersonalId("remote:https://server/song?token=secret"))
        assertEquals(ref.personalId,PlayedSong("event",ref.personalId,1).trackId)
    }
    @Test fun remotePlaylistReferenceSurvivesWithoutLocalFileIdentity() {
        val entry=PlaylistEntry(null,"Remote",sourceRef=ref)
        assertEquals(ref.personalId,entry.personalId)
        assertThrows(IllegalArgumentException::class.java){PlaylistEntry("a".repeat(64),"Mixed",sourceRef=ref)}
    }
}
