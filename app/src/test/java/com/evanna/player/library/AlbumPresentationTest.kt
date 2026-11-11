package com.evanna.player.library

import com.evanna.player.decoder.MusicTags
import org.junit.Assert.*
import org.junit.Test

class AlbumPresentationTest {
    @Test fun albumKeysDistinguishMissingEmptyAndDelimiterContainingTags() {
        val identities = listOf(AlbumIdentity(null, null), AlbumIdentity("", null), AlbumIdentity("Unknown album", null),
            AlbumIdentity("Greatest Hits", "A"), AlbumIdentity("Greatest Hits", "B"),
            AlbumIdentity("a:b", "c"), AlbumIdentity("a", "b:c"), AlbumIdentity("-", ""))
        assertEquals(identities.size, identities.map { it.key }.toSet().size)
        assertEquals(AlbumIdentity("Title", "Artist").key, AlbumIdentity("Title", "Artist").key)
    }
    @Test fun discHeadingsRespectAccumulatedPagesAndUnknownNumbers() {
        fun song(disc: Int?) = LibrarySong("id", "content://test", "Title", null, "Album", 10, "FLAC", 44100, true,
            MusicTags(discNumber = disc))
        val songs = List(100) { song(1) } + song(2) + song(2) + song(null)
        val summary = AlbumSummary("Artist", 2019, true)
        assertEquals("Disc 1", albumDiscHeading(songs, 0, summary))
        assertNull(albumDiscHeading(songs, 99, summary))
        assertEquals("Disc 2", albumDiscHeading(songs, 100, summary))
        assertNull(albumDiscHeading(songs, 101, summary))
        assertEquals("Disc unknown", albumDiscHeading(songs, 102, summary))
        assertNull(albumDiscHeading(songs, 0, summary.copy(multipleDiscs = false)))
        assertNull(albumDiscHeading(songs, 0, null))
    }
}
