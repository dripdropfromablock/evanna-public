package com.evanna.player.metadata

import com.evanna.player.library.LibraryAudio
import com.evanna.player.metadata.MusicTagField.*
import org.junit.Assert.*
import org.junit.Test

class OnlineTagReviewTest {
    private val source = MusicTagSource("one", "content:/one", "Opening", "The Artist", "The Album", "The Artist",
        "root", "Music", "Opening.flac", TagFileStatus.READABLE, 180_000_000)
    private val localTags = mapOf(TITLE to "Opening", ARTIST to "The Artist", ALBUM to "The Album", TRACK_NUMBER to "1")
    private val origin = TagOrigin(OnlineTagProvider.MUSICBRAINZ, "7dcbf5dd-80cc-413e-9594-9134d6e4cf97")
    private val match = OnlineTagMatch(origin, mapOf(ALBUM to "The Album", ALBUM_ARTIST to "The Artist", YEAR to "2012-09-03"),
        listOf(OnlineTagTrack(mapOf(TITLE to "Opening", ARTIST to "The Artist", TRACK_NUMBER to "1", DISC_NUMBER to "1"), 180_210)))

    @Test fun matchesATrackAndPreviewsOnlyChosenFields() {
        val suggestions = onlineTagSuggestions(listOf(TagAuditEntry(source, localTags)), match)
        val previews = previewOnlineTagEdits(suggestions, setOf(ALBUM_ARTIST, TITLE), fillMissing = true)
        assertEquals(listOf(TagValueChange(ALBUM_ARTIST, null, "The Artist")), previews.single().changes)
    }

    @Test fun editionAlbumPositionAndDurationDifferencesDoNotGuessAMatch() {
        val entry = TagAuditEntry(source, localTags)
        assertTrue(onlineTagSuggestions(listOf(entry), match.copy(tags = match.tags + (ALBUM to "The Album (Deluxe)"))).isEmpty())
        assertTrue(onlineTagSuggestions(listOf(entry), match.copy(tracks = listOf(match.tracks.single().copy(durationMs = 222_000)))).isEmpty())
        val wrongPosition = match.tracks.single().copy(tags = match.tracks.single().tags + (TRACK_NUMBER to "2"))
        assertTrue(onlineTagSuggestions(listOf(entry), match.copy(tracks = listOf(wrongPosition))).isEmpty())
        assertTrue(onlineTagSuggestions(listOf(entry), match.copy(tracks = match.tracks + match.tracks)).isEmpty())
    }

    @Test fun releaseSuggestionsOnlyReachTheMatchingAlbumInAnArtistBatch() {
        val first = TagAuditEntry(source, localTags)
        val other = TagAuditEntry(source.copy(id = "two", album = "Live"), localTags + (ALBUM to "Live"))
        val nonMatching = TagAuditEntry(source.copy(id = "three", title = "Closing"), localTags + (TITLE to "Closing"))
        assertEquals(listOf("one"), onlineTagSuggestions(listOf(first, other, nonMatching), match).map { it.first.source.id })
    }

    @Test fun wikidataRequiresReleaseAndPerformerAgreementAndComparisonSourcesCannotBeImported() {
        val entry = TagAuditEntry(source, localTags)
        val wikidata = OnlineTagMatch(TagOrigin(OnlineTagProvider.WIKIDATA, "Q123"),
            mapOf(ALBUM to "The Album", YEAR to "2012", GENRE to "Folk"), emptyList(),
            artistNames = listOf("The Artist"))
        assertEquals("Folk", onlineTagSuggestions(listOf(entry), wikidata).single().second[GENRE])
        assertTrue(onlineTagSuggestions(listOf(entry), wikidata.copy(artistNames = listOf("Different Artist"))).isEmpty())
        for (provider in listOf(OnlineTagProvider.DISCOGS, OnlineTagProvider.APPLE)) {
            assertTrue(onlineTagSuggestions(listOf(entry), match.copy(origin = TagOrigin(provider, "123"))).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                TagSidecar().edited(listOf(TagValueChange(GENRE, null, "Folk")), TagOrigin(provider, "123"))
            }
        }
    }

    @Test fun sidecarsOverrideClearAndInheritWhileKeepingEmbeddedValuesInTheAudit() {
        val changes = listOf(TagValueChange(ARTIST, "The Artist", null), TagValueChange(ALBUM_ARTIST, null, "New Artist"))
        val sidecar = TagSidecar().edited(changes, origin)
        val resolved = sidecar.applyTo(localTags)
        assertEquals("Opening", resolved[TITLE])
        assertNull(resolved[ARTIST])
        assertEquals("New Artist", resolved[ALBUM_ARTIST])
        assertEquals(mapOf(ALBUM_ARTIST to origin), sidecar.origins)
        assertNull(sidecar.edited(listOf(TagValueChange(ALBUM_ARTIST, "New Artist", "Manual")), null).origins[ALBUM_ARTIST])
        val audit = TagAuditEntry(source, resolved, localTags)
        assertTrue(audit.isMissing(ARTIST))
        assertFalse(audit.isMissing(ARTIST, embeddedOnly = true))
        assertTrue(audit.isMissing(ALBUM_ARTIST, embeddedOnly = true))
        val indexed = sidecar.applyTo(LibraryAudio("Opening", "The Artist", "The Album", 180_000_000, "FLAC", 44100, 24, 2))
        assertEquals("Opening", indexed.title)
        assertNull(indexed.artist)
        assertEquals("New Artist", indexed.musicTags.albumArtist)
    }

    @Test fun artistFactsMatchAListedAliasAcrossAlbums() {
        val artist = OnlineTagMatch(TagOrigin(OnlineTagProvider.WIKIDATA, "Q123"), mapOf(GENRE to "Folk"),
            emptyList(), artistNames = listOf("Stage Name", "The Artist"), artistEntity = true)
        val matching = TagAuditEntry(source, localTags + (ALBUM to "Another Album"))
        val unrelated = TagAuditEntry(source.copy(id = "two"), localTags + (ARTIST to "A Different Artist"))
        assertEquals(listOf("one"), onlineTagSuggestions(listOf(matching, unrelated), artist).map { it.first.source.id })
    }

    @Test fun discogsLinksOnlyResolveExplicitReleaseIds() {
        assertEquals("123456", discogsReleaseId("https://www.discogs.com/release/123456-Album"))
        assertEquals("123456", discogsReleaseId("[r123456]"))
        assertNull(discogsReleaseId("https://www.discogs.com/master/123456-Album"))
        assertNull(discogsReleaseId("https://example.org/release/123456"))
    }
}
