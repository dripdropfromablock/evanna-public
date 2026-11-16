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

    @Test fun lyricsPageContextSkipsBlanksAndStopsAtSongEdges() {
        val lines = listOf("  before  ", "", "first", "second", " ", " after ")
        assertEquals("before" to "after", contextAround(lines, setOf(2, 3)))
        assertEquals(null to "first", contextAround(lines, setOf(0)))
        assertEquals("second" to null, contextAround(lines, setOf(5)))
        assertEquals(null to null, contextAround(lines, emptySet()))
    }

    @Test fun linerNotesNeedsSongsAndLyricLooksStaySeparate() {
        val lyric = ShareCard.LyricCard(listOf("Words"), "Song", "Artist")
        val album = ShareCard.AlbumCard("Album", "Artist")
        assertEquals(7, ShareLook.available(lyric).size)
        assertTrue(ShareLook.available(lyric).all { it.forLyrics })
        assertEquals(8, ShareLook.available(album).size)
        assertFalse(ShareLook.ALBUM_LINER in ShareLook.available(album))
        assertEquals(9, ShareLook.available(album.copy(tracks = listOf(ShareTrack("Song", "3:41")))).size)
    }

    @Test fun oldSavedLooksMigrateAndInvalidOrUnavailableLooksFallBack() {
        val lyric = ShareCard.LyricCard(listOf("Words"), "Song", "Artist")
        val album = ShareCard.AlbumCard("Album", "Artist")
        for ((old, lyrics, albums) in listOf(
            Triple("GLASS", ShareLook.LYRIC_CARD, ShareLook.ALBUM_CARD),
            Triple("BOLD", ShareLook.LYRIC_BIG, ShareLook.ALBUM_FLOATING),
            Triple("COVER", ShareLook.LYRIC_COVER, ShareLook.ALBUM_FULL))) {
            assertEquals(lyrics, ShareLook.stored(lyric, old))
            assertEquals(albums, ShareLook.stored(album, old))
        }
        assertEquals(ShareLook.LYRIC_CARD, ShareLook.stored(lyric, "ALBUM_POSTER"))
        assertEquals(ShareLook.ALBUM_CARD, ShareLook.stored(album, "ALBUM_LINER"))
        assertEquals(ShareLook.LYRIC_CARD, ShareLook.stored(lyric, "unknown"))
        assertEquals(ShareLook.ALBUM_CARD, ShareLook.stored(album, null))
        assertNotEquals(shareStyleKey(lyric), shareStyleKey(album))
    }

    @Test fun lastUsedLookComesFirstWithoutLosingOtherChoices() {
        val card = ShareCard.LyricCard(listOf("Words"), "Song", "Artist")
        val choices = ShareLook.ordered(card, "LYRIC_QUOTE")
        assertEquals(ShareLook.LYRIC_QUOTE, choices.first())
        assertEquals(ShareLook.available(card).toSet(), choices.toSet())
        assertEquals(7, choices.size)
        assertEquals(ShareLook.LYRIC_COVER, ShareLook.ordered(card, "COVER").first())
    }

    @Test fun colourCardWordsStayReadableAcrossArtworkColours() {
        val colours = (0..255 step 5).map { 0xFF000000.toInt() or (it shl 16) or (it shl 8) or it } +
            listOf(0xFFF0C27A.toInt(), 0xFF45AF55.toInt(), 0xFF5577BB.toInt(), 0xFFCC4488.toInt())
        for (colour in colours) assertTrue("Unreadable colour card: ${colour.toUInt().toString(16)}",
            contrastRatio(colourInk(colour), colour) >= 4.5)
    }
}
