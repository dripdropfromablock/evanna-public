package com.evanna.player.library

import com.evanna.player.decoder.MusicTags
import com.evanna.player.ShareTrack
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

    @Test fun albumShareListsFirstSixTracksAndFullAlbumDetails() {
        val songs = (1..8).map { number ->
            LibrarySong("$number", "content://test/$number", "Song $number", "Artist", "Album", 180_000_000,
                "FLAC", 44100, true)
        }
        val card = albumShareCard("Album", AlbumSummary("Artist", 2026, false), LibraryPage(emptyList(), songs, 8))
        assertEquals("2026 · 8 songs · 24 min", card.meta)
        assertEquals((1..6).map { ShareTrack("Song $it", "3:00") }, card.tracks)
        assertEquals("+2 more · 24 min", card.more)
    }

    @Test fun albumShareDoesNotInventDurationForPartialOrUnknownTracks() {
        val known = LibrarySong("id", "content://test", "Song", null, "Album", 60_000_000,
            "FLAC", 44100, true)
        val partial = albumShareCard("Album", null, LibraryPage(emptyList(), listOf(known), 10))
        assertEquals("10 songs", partial.meta)
        assertEquals("Unknown artist", partial.artist)
        assertEquals("+9 more", partial.more)
        val unknown = albumShareCard("Album", null,
            LibraryPage(emptyList(), listOf(known.copy(durationUs = null)), 1))
        assertEquals("1 song", unknown.meta)
        assertEquals(listOf(ShareTrack("Song", "--:--")), unknown.tracks)
        assertNull(unknown.more)
    }
}
