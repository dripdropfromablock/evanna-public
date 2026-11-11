package com.evanna.player.library

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun libraryStyleLrcBecomesTimedLines() {
        val lyrics = checkNotNull(parseLyrics("[00:18.69]Your butt is mine, gonna tell you right, ah\n[00:22.87]Just show your face\n[04:05.24]Who's bad?"))
        assertTrue(lyrics.timed)
        assertEquals(listOf(18_690L, 22_870L, 245_240L), lyrics.lines.map { it.timeMs })
        assertEquals("Who's bad?", lyrics.lines.last().text)
    }

    @Test fun headersOffsetRepeatsWordStampsAndBreaksAreHandled() {
        val text = "\uFEFF[ar:Evanna]\r\n[offset:+250]\r\n[00:03.5][00:01.000]Hello <00:01.20>again\r\n[00:02:00]\r\n[1:02.07]Late"
        val lyrics = checkNotNull(parseLyrics(text))
        assertEquals(listOf(750L, 1_750L, 3_250L, 61_820L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("Hello again", "", "Hello again", "Late"), lyrics.lines.map { it.text })
    }

    @Test fun wordStampsBecomeTimedWordsThatFollowTheOffsetAndRepeatedLines() {
        val lyrics = checkNotNull(parseLyrics("[offset:+250]\n[00:03.50][00:01.00]Hello <00:01.20>again\n[00:05.00]<00:05.00>Hel<00:05.40>lo  <00:06.00>you"))
        assertEquals(listOf(750L, 3_250L, 4_750L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf(LyricWord(750, "Hello "), LyricWord(950, "again")), lyrics.lines[0].words)
        assertEquals(listOf(LyricWord(3_250, "Hello "), LyricWord(3_450, "again")), lyrics.lines[1].words)
        assertEquals(listOf(LyricWord(4_750, "Hel"), LyricWord(5_150, "lo "), LyricWord(5_750, "you")), lyrics.lines[2].words)
        assertEquals("Hello you", lyrics.lines[2].text)
        assertEquals(lyrics.lines[2].text, lyrics.lines[2].words.joinToString("") { it.text })
    }

    @Test fun linesWithoutValidWordStampsHaveNoWords() {
        val lyrics = checkNotNull(parseLyrics("[00:02.00]Plain line\n[00:04.00]Back <00:03.00>in time\n[00:06.00]Out <00:07.00>of <00:06.50>order"))
        assertTrue(lyrics.lines.all { it.words.isEmpty() })
        assertEquals(listOf("Plain line", "Back in time", "Out of order"), lyrics.lines.map { it.text })
    }

    @Test fun negativeOffsetDelaysAndTimesNeverGoBelowZero() {
        assertEquals(listOf(1_500L), parseLyrics("[offset:-500]\n[00:01.00]A")!!.lines.map { it.timeMs })
        assertEquals(listOf(0L), parseLyrics("[offset:2000]\n[00:01.00]A")!!.lines.map { it.timeMs })
    }

    @Test fun plainTextKeepsSectionLinesAndOneBlankBetweenVerses() {
        val lyrics = checkNotNull(parseLyrics("\n\n[Chorus: Drake]\nFirst line\n\n\n\nSecond verse\n\n"))
        assertFalse(lyrics.timed)
        assertEquals(listOf("[Chorus: Drake]", "First line", "", "Second verse"), lyrics.lines.map { it.text })
        assertTrue(lyrics.lines.all { it.timeMs == null })
        assertEquals(-1, lyrics.lineAt(10_000))
    }

    @Test fun mostlyPlainTextStaysPlainAndEmptyTextIsNothing() {
        val mixed = checkNotNull(parseLyrics("[00:01.00]One\nTwo\nThree"))
        assertFalse(mixed.timed)
        assertEquals(listOf("One", "Two", "Three"), mixed.lines.map { it.text })
        assertNull(parseLyrics(""))
        assertNull(parseLyrics(" \n\r\n[ar:Only a header]\n"))
        assertNull(parseLyrics("[00:01.00]\n[00:02.00]"))
    }

    @Test fun currentLineFollowsThePosition() {
        val lyrics = checkNotNull(parseLyrics("[00:01.00]A\n[00:02.00]B\n[00:04.00]C"))
        assertEquals(-1, lyrics.lineAt(999))
        assertEquals(0, lyrics.lineAt(1_000))
        assertEquals(1, lyrics.lineAt(3_999))
        assertEquals(2, lyrics.lineAt(Long.MAX_VALUE))
    }

    @Test fun bestTagPrefersTimedThenMoreLines() {
        val timed = "[00:01.00]Timed"
        assertTrue(bestLyrics(listOf("Plain one\nPlain two\nPlain three", timed))!!.timed)
        assertEquals(3, bestLyrics(listOf("One", "One\nTwo\nThree", ""))!!.lines.size)
        assertNull(bestLyrics(emptyList()))
    }

    @Test fun stampsPerLineAreBounded() {
        val lyrics = checkNotNull(parseLyrics("[00:01.00]".repeat(1000) + "Hello"))
        assertEquals(64, lyrics.lines.size)
        assertTrue(lyrics.lines.all { it.timeMs == 1_000L })
    }

    @Test fun lineCountIsBounded() {
        val lyrics = checkNotNull(parseLyrics((0 until 5000).joinToString("\n") { "[00:%02d.00]Line $it".format(it % 60) }))
        assertEquals(2000, lyrics.lines.size)
    }
}
