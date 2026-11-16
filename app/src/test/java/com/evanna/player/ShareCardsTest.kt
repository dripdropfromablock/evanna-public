package com.evanna.player

import org.junit.Assert.*
import org.junit.Test

class ShareCardsTest {
    @Test fun ticksStopAtFourLinesAndUntickFreesRoom() {
        var picked = setOf(2)
        for (line in listOf(5, 3, 9)) picked = checkNotNull(toggledPick(picked, line))
        assertEquals(setOf(2, 3, 5, 9), picked)
        assertNull(toggledPick(picked, 7))
        picked = checkNotNull(toggledPick(picked, 3))
        assertEquals(setOf(2, 5, 9, 7), checkNotNull(toggledPick(picked, 7)))
    }

    @Test fun pickedWordsComeInSongOrderWithoutBlankLines() {
        val lines = listOf("Lights on in the harbor,", "", "  the tide keeps the time,  ", "and I count every boat home.")
        assertEquals(listOf("Lights on in the harbor,", "the tide keeps the time,"), pickedWords(lines, setOf(2, 1, 0)))
        assertEquals(listOf("and I count every boat home."), pickedWords(lines, setOf(3, 12)))
        assertTrue(pickedWords(lines, emptySet()).isEmpty())
    }
}
