package com.evanna.player.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTagAuditTest {
    @Test fun filenameFallbackDoesNotCountAsAnEmbeddedTitle() {
        val track = track("one", title = "01 - Song.flac")
        assertTrue(TagAuditEntry(track, emptyMap()).isMissing(MusicTagField.TITLE))
        assertFalse(TagAuditEntry(track, mapOf(MusicTagField.TITLE to "Song")).isMissing(MusicTagField.TITLE))
        for (status in TagFileStatus.entries.filter { it != TagFileStatus.READABLE }) {
            assertFalse(TagAuditEntry(track.copy(status = status), emptyMap()).isMissing(MusicTagField.TITLE))
        }
        assertFalse(TagAuditEntry(track, null).isMissing(MusicTagField.TITLE))
    }

    @Test fun scopesKeepAlbumArtistsAndTrackArtistsDistinct() {
        val first = track("one", artist = "Guest", albumArtist = "Main")
        val second = track("two", artist = "Guest", albumArtist = "Another")
        val tracks = listOf(first, second)
        assertEquals(listOf(first, second), tracks.filter(TagScope(TagScopeKind.ARTIST, "Guest")::includes))
        assertEquals(listOf(first), tracks.filter(TagScope(TagScopeKind.ALBUM_ARTIST, "Main")::includes))
        assertEquals(listOf(first), tracks.filter(TagScope(TagScopeKind.ALBUM, "Album", "Main")::includes))
    }

    @Test fun albumScopeUsesTrackArtistWhenAlbumArtistIsMissing() {
        val first = track("one", artist = "Artist A", albumArtist = null).copy(album = "Greatest Hits")
        val sameAlbum = first.copy(id = "two", albumArtist = "")
        val otherAlbum = first.copy(id = "three", artist = "Artist B")
        val tracks = listOf(first, sameAlbum, otherAlbum)
        assertEquals(listOf(listOf(first, sameAlbum), listOf(otherAlbum)), tracks.groupBy(TagScopeKind.ALBUM::scopeOf).values.toList())
        assertEquals(listOf(first, sameAlbum), tracks.filter(TagScopeKind.ALBUM.scopeOf(first)::includes))
    }

    @Test fun defaultPreviewFillsOnlyMissingAndSkipsUnreadableFiles() {
        val old = TagAuditEntry(track("one"), mapOf(MusicTagField.GENRE to "Soul"))
        val blank = TagAuditEntry(track("two"), emptyMap())
        val bad = TagAuditEntry(track("three").copy(status = TagFileStatus.UNREADABLE), null)
        val requested = mapOf(MusicTagField.GENRE to TagChange.Set("R&B"))
        val entries = listOf(old, blank, bad)
        assertEquals(listOf("two"), previewTagEdits(entries, requested, fillMissing = true).map { it.source.id })
        assertEquals(listOf("one", "two"), previewTagEdits(entries, requested, fillMissing = false).map { it.source.id })
        assertTrue(previewTagEdits(entries, mapOf(MusicTagField.GENRE to TagChange.Clear), fillMissing = true).isEmpty())
        assertEquals(listOf("one"), previewTagEdits(entries, mapOf(MusicTagField.GENRE to TagChange.Clear), fillMissing = false)
            .map { it.source.id })
    }

    @Test fun totalsCannotLeaveTheTrackAndDiscNumbersInvalid() {
        val entry = TagAuditEntry(track("one"), mapOf(MusicTagField.TRACK_NUMBER to "4"))
        val edits = mapOf(MusicTagField.TRACK_TOTAL to TagChange.Set("3"))
        assertTrue(previewTagEdits(listOf(entry), edits, fillMissing = false).isEmpty())
        assertFalse(MusicTagField.TRACK_NUMBER.accepts("1000"))
        assertFalse(MusicTagField.YEAR.accepts("19"))
        assertTrue(MusicTagField.YEAR.accepts("2026"))
        assertTrue(MusicTagField.YEAR.accepts("2026-11"))
        assertTrue(MusicTagField.YEAR.accepts("2024-02-29"))
        assertFalse(MusicTagField.YEAR.accepts("2026-02-29"))
    }

    private fun track(id: String, title: String = "Track", artist: String = "Artist", albumArtist: String? = "Artist") =
        MusicTagSource(id, "content://music/$id", title, artist, "Album", albumArtist, "root", "Music", "$id.flac", TagFileStatus.READABLE)
}
