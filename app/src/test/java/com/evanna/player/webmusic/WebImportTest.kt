package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class WebImportTest {
    @Test fun streamingAndDownloadsRecognizeOnlyStandaloneAudioSignatures() {
        assertEquals("flac", webAudioCodec("fLaC".encodeToByteArray()))
        assertEquals("mp3", webAudioCodec(byteArrayOf(0xff.toByte(), 0xe3.toByte(), 0, 0)))
        assertEquals("mp3", webAudioCodec("ID3\u0004".encodeToByteArray()))
        assertNull(webAudioCodec("<htm".encodeToByteArray()))
        assertNull(webAudioCodec("ID3".encodeToByteArray()))
    }
    @Test fun downloadedExcerptWarnsEvenWhenItHasNoEmbeddedTags() {
        val expected = RecordingEvidence("Rhapsody in Blue", "Paul Whiteman", 541_000)
        assertEquals(RecordingCheck.DURATION_MISMATCH,
            checkRecording(expected, RecordingEvidence(null, null, 257_000)))
    }

    @Test fun durationWithinTwoSecondsAndEquivalentTagsAreConsistent() {
        val expected = RecordingEvidence("Björk - Live", "An Artist", 90_000)
        val actual = RecordingEvidence("BJO\u0308RK (Live)", "an artist", 92_000)
        assertEquals(RecordingCheck.CONSISTENT, checkRecording(expected, actual))
        assertEquals(RecordingCheck.DURATION_MISMATCH, checkRecording(expected, actual.copy(durationMillis = 87_999)))
    }

    @Test fun contradictoryTagsWarnWithOrWithoutMatchingDuration() {
        val expected = RecordingEvidence("The Song", "The Band", 180_000)
        assertEquals(RecordingCheck.TAG_MISMATCH,
            checkRecording(expected, expected.copy(title = "The Song (Live)")))
        assertEquals(RecordingCheck.TAG_MISMATCH,
            checkRecording(expected, RecordingEvidence(null, "Another Band", null)))
    }

    @Test fun missingEvidenceStaysUnknown() {
        val expected = RecordingEvidence("The Song", "The Band", null)
        assertEquals(RecordingCheck.UNKNOWN, checkRecording(expected, RecordingEvidence("  - ", null, 0)))
        assertEquals(RecordingCheck.UNKNOWN, checkRecording(null, expected))
    }

    @Test fun outputNameUsesDecodedFormatAndDiscardsPathAndUnsafeCharacters() {
        assertEquals("My Song.flac", importedDownloadName("../My Song.exe", "flac"))
        assertEquals("Audio.mp3", importedDownloadName("?/.hidden", "mp3"))
        assertEquals("Track (2).mp3", importedDownloadName("Track.mp3", "mp3", setOf("track.mp3")))
    }

    @Test fun revokedWriteGrantDoesNotPublishTheAudio() {
        var discarded = false
        assertThrows(SecurityException::class.java) {
            publishVerifiedAudio({ ByteArrayInputStream(byteArrayOf(1)) }, { throw SecurityException() },
                { throw AssertionError("Read back a file that could not be written") }, CancelScope(),
                publish = { throw AssertionError("Published without write access") },
                discard = { discarded = true })
        }
        assertTrue(discarded)
    }

    @Test fun stagingIsVerifiedBeforePublicationAndInterruptedCopyIsDiscarded() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        var published = false
        var discarded = false
        fun save(readBack: () -> ByteArrayInputStream, scope: CancelScope) =
            publishVerifiedAudio({ ByteArrayInputStream(bytes) }, { output }, readBack, scope,
                publish = { published = true }, discard = { discarded = true })
        assertThrows(IOException::class.java) {
            save({ ByteArrayInputStream(byteArrayOf(1, 2)) }, CancelScope())
        }
        assertFalse(published)
        assertTrue(discarded)
        discarded = false
        assertThrows(IOException::class.java) {
            save({ ByteArrayInputStream(bytes) }, CancelScope().apply { cancel() })
        }
        assertFalse(published)
        assertTrue(discarded)
        discarded = false
        output.reset()
        save({ ByteArrayInputStream(output.toByteArray()) }, CancelScope())
        assertTrue(published)
        assertFalse(discarded)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun webStreamingPolicyRefusesLocalAndSharedAddressSpace() {
        for (host in listOf("127.0.0.1", "10.0.0.2", "192.168.1.1", "169.254.169.254", "100.64.0.2", "[fc00::1]", "[::ffff:192.168.1.1]")) {
            assertThrows(host, IOException::class.java) { NetworkPolicy("https://$host/", requirePublic = true) }
        }
        NetworkPolicy("https://1.1.1.1/", requirePublic = true)
    }
}
