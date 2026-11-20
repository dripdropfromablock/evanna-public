package com.evanna.player.casting

import org.junit.Assert.*
import org.junit.Test

class RendererEvidenceTest {
    private val uri = "http://192.168.1.2:4567/track"
    private fun reading(phase: String, at: Long?, url: String = uri) = RendererReading(phase, url, at, 100_000L)

    @Test fun completionRequiresMatchingUriAndEndPositionAcrossPlayingAndStopped() {
        assertFalse(rendererCompleted(uri, null, reading("STOPPED", 100_000)))
        assertFalse(rendererCompleted(uri, reading("PLAYING", 60_000), reading("STOPPED", 100_000)))
        assertFalse(rendererCompleted(uri, reading("PLAYING", 99_000), reading("STOPPED", 0)))
        assertFalse(rendererCompleted(uri, reading("PLAYING", 99_000), reading("STOPPED", 100_000, uri + "2")))
        assertFalse(rendererCompleted(uri, reading("PAUSED_PLAYBACK", 99_000), reading("STOPPED", 100_000)))
        assertTrue(rendererCompleted(uri, reading("PLAYING", 99_000), reading("STOPPED", 100_000)))
    }

    @Test fun rangesAreFiniteAndDoNotAcceptMultipleRanges() {
        assertEquals(0L..999L, relayRange(null, 1000L))
        assertEquals(990L..999L, relayRange("bytes=-10", 1000L))
        assertEquals(990L..999L, relayRange("bytes=990-", 1000L))
        assertEquals(990L..999L, relayRange("bytes=990-9000", 1000L))
        for (bad in listOf("bytes=", "bytes=1000-", "bytes=-0", "bytes=10-0", "bytes=0-1,5-9"))
            assertNull(relayRange(bad, 1000L))
    }
}
