package com.evanna.player.library

import org.junit.Assert.*
import org.junit.Test

class LibrarySortTest {
    @Test fun persistedSortNamesAreScopedAndCannotInjectQueryText() {
        assertEquals(LibrarySort.TITLE,storedLibrarySort(LibrarySortScope.SONGS,"YEAR"))
        assertEquals(LibrarySort.YEAR,storedLibrarySort(LibrarySortScope.ALBUMS,"YEAR"))
        assertEquals(LibrarySort.TITLE,storedLibrarySort(LibrarySortScope.ALBUMS,"DURATION"))
        assertEquals(LibrarySort.DURATION,storedLibrarySort(LibrarySortScope.SEARCH,"DURATION"))
        for(value in listOf(null,"","title","TITLE; DROP"))assertEquals(LibrarySort.TITLE,storedLibrarySort(LibrarySortScope.FOLDERS,value))
        assertNull(libraryContextRequest("SONGS",null,null,"","a".repeat(64),"PLAY_FROM","YEAR"))
        assertEquals(LibrarySort.ARTIST,libraryContextRequest("SONGS",null,null,"","a".repeat(64),"PLAY_FROM","ARTIST")?.filter?.sort)
    }
}
