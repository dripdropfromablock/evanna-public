package com.evanna.player.sources

import org.junit.Assert.*
import org.junit.Test

class SourceReferenceTest {
    private val connection = "4a311355-23c0-405b-a068-4b72cf965ec0"
    private val item = "bc62a391-83af-4491-a8b8-f710b1ccf8cb"
    @Test fun opaqueReferencesRoundTripWithoutLocationsOrCredentials() {
        val ref = SourceReference(connection, item)
        assertEquals(ref, SourceReference.parse(ref.encoded))
        assertTrue(ref.personalId.startsWith("remote:"))
        assertFalse(ref.encoded.contains("http"))
        assertNotEquals(ref, ref.copy(connectionId = item))
    }
    @Test fun referencesRejectUrlsCredentialsAndMalformedIds() {
        for (bad in listOf("https://server/song?token=secret", "../item", "", "A".repeat(10000), "user:password")) {
            assertTrue(runCatching { SourceReference(connection, bad) }.isFailure)
            assertNull(SourceReference.parse(bad))
        }
    }
}
