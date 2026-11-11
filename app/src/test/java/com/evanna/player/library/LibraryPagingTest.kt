package com.evanna.player.library

import org.junit.Assert.*
import org.junit.Test

class LibraryPagingTest {
    @Test fun emptyOrUnmeasuredLayoutsDoNotRequestPages() {
        assertFalse(libraryEndIsNear(null,0));assertFalse(libraryEndIsNear(null,100))
        assertFalse(libraryEndIsNear(0,0));assertFalse(libraryEndIsNear(-1,100))
        assertFalse(libraryEndIsNear(20,100));assertTrue(libraryEndIsNear(88,100))
        assertTrue(libraryEndIsNear(99,100));assertTrue(libraryEndIsNear(0,5))
    }
    @Test fun nextPageUsesTheJumpWindowBaseAndStopsAtTheActualEnd() {
        assertEquals(100,nextLibraryPage(0,100,10000))
        assertEquals(1100,nextLibraryPage(1000,100,10000))
        assertEquals(9900,nextLibraryPage(9800,100,10000))
        assertNull(nextLibraryPage(9900,100,10000))
        assertNull(nextLibraryPage(0,0,10000))
        assertNull(nextLibraryPage(Int.MAX_VALUE,100,Int.MAX_VALUE))
        assertNull(nextLibraryPage(-1,100,10000))
        assertNull(nextLibraryPage(0,-1,10000))
    }
    @Test fun railPositionsStayBoundedAndReachEveryLetter() {
        assertEquals('#',alphabetLetter(-10f,100f));assertEquals('Z',alphabetLetter(110f,100f))
        assertNull(alphabetLetter(1f,0f));assertNull(alphabetLetter(Float.NaN,100f))
        assertEquals(27,(0..10000).mapNotNull {alphabetLetter(it/100f,100f)}.toSet().size)
    }
    @Test fun barStartsBesideTheRowsAndKeepsRoomForItsLetters() {
        assertNull(alphabetBarTop(-1,1000,200))
        assertEquals(300,alphabetBarTop(300,1000,200));assertEquals(0,alphabetBarTop(0,1000,200))
        assertEquals(800,alphabetBarTop(950,1000,200))
        assertEquals(0,alphabetBarTop(300,150,200))
    }
}
