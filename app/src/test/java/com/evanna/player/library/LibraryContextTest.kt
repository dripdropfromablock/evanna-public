package com.evanna.player.library

import org.junit.Assert.*
import org.junit.Test

class LibraryContextTest {
    @Test fun genreAndComposerContextsAreExactBoundedAndCanExplicitlyPlayOrShuffle() {
        for(kind in listOf("GENRE","COMPOSER"))for(value in listOf(null,"Named tag")) {
            for(action in listOf("PLAY_FIRST","SHUFFLE"))assertNotNull(libraryContextRequest(kind,value,null,"",null,action))
            assertNotNull(libraryContextRequest(kind,value,null,"",id,"PLAY_FROM"))
            assertNull(libraryContextRequest(kind,"x".repeat(513),null,"",id,"PLAY_FROM"))
            assertNull(libraryContextRequest(kind,value,"Album artist","",null,"PLAY_FIRST"))
        }
    }
    @Test fun artistPlayAndShuffleRetainExactArtistAndChronologicalSongOrder() {
        for(artist in listOf(null,"Artist"))for(action in listOf("PLAY_FIRST","SHUFFLE")) {
            val request=libraryContextRequest("ARTIST",artist,null,"song",null,action,"ADDED")
            assertNotNull(request)
            assertEquals(LibraryFilter("song",LibraryGroupKind.ARTIST,artist),request?.filter)
        }
        assertNull(libraryContextRequest("ARTIST","Artist",null,"",id,"PLAY_FIRST"))
        assertNull(libraryContextRequest("ARTIST","Artist","Other","",null,"SHUFFLE"))
    }
    @Test fun listSortIsValidatedAndPreservedWithoutChangingAlbumTrackOrder() {
        val id="a".repeat(64)
        assertEquals(LibrarySort.ADDED,libraryContextRequest("SONGS",null,null,"",id,"PLAY_FROM","ADDED")?.filter?.sort)
        assertEquals(LibrarySort.TITLE,libraryContextRequest("ALBUM","Album",null,"",id,"PLAY_FROM","ADDED")?.filter?.sort)
        assertNull(libraryContextRequest("SONGS",null,null,"",id,"PLAY_FROM","title;DROP"))
    }
    private val id = "a".repeat(64)
    @Test fun validatesExactContextsAndActionsWithoutInventingArguments() {
        val album = checkNotNull(libraryContextRequest("ALBUM", "Greatest Hits", "Artist", "", id, "PLAY_FROM"))
        assertEquals(LibraryFilter("", LibraryGroupKind.ALBUM, "Greatest Hits", "Artist"), album.filter)
        assertEquals(id, album.startId)
        assertNotNull(libraryContextRequest("ALBUM", null, null, "", null, "SHUFFLE"))
        assertNotNull(libraryContextRequest("ARTIST", null, null, "title", id, "PLAY_FROM"))
        assertNotNull(libraryContextRequest("FOLDER", "content://local/tree/music", null, "", id, "PLAY_FROM"))
        assertEquals(LibraryFilter("literal%_"), libraryContextRequest("SONGS", null, null, "literal%_", id, "PLAY_FROM")?.filter)
        for (bad in listOf("", "A".repeat(64), "a".repeat(63), "a".repeat(65)))
            assertNull(libraryContextRequest("SONGS", null, null, "", bad, "PLAY_FROM"))
    }
    @Test fun rejectsMissingOversizedOrContradictoryValues() {
        assertNull(libraryContextRequest(null, null, null, "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("PLAYLIST", null, null, "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("SONGS", "unexpected", null, "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("ARTIST", "Artist", "wrong", "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("FOLDER", null, null, "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("FOLDER", "x".repeat(8193), null, "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("ALBUM", "a".repeat(513), null, "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("ALBUM", "Album", "a".repeat(513), "", id, "PLAY_FROM"))
        assertNull(libraryContextRequest("SONGS", null, null, "x".repeat(201), id, "PLAY_FROM"))
        assertNull(libraryContextRequest("SONGS", null, null, null, id, "PLAY_FROM"))
        assertNull(libraryContextRequest("SONGS", null, null, "", null, "PLAY_FROM"))
        assertNull(libraryContextRequest("ALBUM", "Album", null, "", id, "PLAY_FIRST"))
        assertNull(libraryContextRequest("SONGS", null, null, "", null, "SHUFFLE"))
        assertNull(libraryContextRequest("ALBUM", "Album", null, "", null, "UNKNOWN"))
    }
}
