package com.evanna.player.webmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCatalogTest {
    @Test fun catalogSearchEscapesUserTextBeforeBuildingTheRequest() {
        val url = requireNotNull(musicBrainzRecordingSearch("Björk & \"live\""))
        assertEquals("musicbrainz.org", url.host)
        assertEquals("/ws/2/recording/", url.encodedPath)
        assertEquals("""(recording:"Björk" OR artist:"Björk") AND (recording:"\"live\"" OR artist:"\"live\"")""",
            url.queryParameter("query"))
        assertEquals("json", url.queryParameter("fmt"))
    }

    @Test fun catalogSearchCombinesRecordingAndArtistWords() {
        val url = requireNotNull(musicBrainzRecordingSearch("Hallelujah Leonard Cohen"))
        assertEquals("""(recording:"Hallelujah" OR artist:"Hallelujah") AND (recording:"Leonard" OR artist:"Leonard") AND (recording:"Cohen" OR artist:"Cohen")""",
            url.queryParameter("query"))
    }

    @Test fun ccmixterAndAppleUseTheirPublishedQueryParameters() {
        val recording = CatalogRecording("identity", "A & B", "C: D", null, null, null)
        val site = CatalogSite.CCMIXTER.searchUrl(recording)
        assertEquals("dig.ccmixter.org", site.host)
        assertEquals("/search", site.encodedPath)
        assertEquals("A & B C: D", site.queryParameter("searchp"))
        assertEquals(setOf("searchp"), site.queryParameterNames)

        val api = appleRecordingSearch("Björk & live", "IN")
        assertEquals("itunes.apple.com", api.host)
        assertEquals("song", api.queryParameter("entity"))
        assertEquals("IN", api.queryParameter("country"))
        assertEquals("Björk & live", api.queryParameter("term"))
    }

    @Test fun archiveSearchKeepsTheAudioFilterAndEncodesTheRecordingName() {
        val recording = CatalogRecording("identity", "A & B", "C: D", null, null, null)
        val url = CatalogSite.ARCHIVE.searchUrl(recording)
        assertEquals("archive.org", url.host)
        assertEquals("/search.php", url.encodedPath)
        assertEquals("\"A & B\" AND \"C: D\" AND mediatype:audio", url.queryParameter("query"))
        assertEquals(setOf("query"), url.queryParameterNames)
        assertFalse(url.encodedQuery.orEmpty().contains("A & B"))
    }

    @Test fun catalogKeepsDifferentVersionsAndOmitsAbsentDetails() {
        val first = CatalogRecording("a", "Song", "Artist", "live", "2001", 185_000)
        val second = CatalogRecording("b", "Song", "Artist", null, null, null)
        assertTrue(first.details.contains("live"))
        assertTrue(first.details.contains("3:05"))
        assertEquals("Artist", second.details)
        assertEquals(listOf(first, second), listOf(first, second, first).distinctBy { it.id })
    }
}
