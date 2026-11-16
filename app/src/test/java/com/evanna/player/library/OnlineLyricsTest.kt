package com.evanna.player.library

import org.junit.Assert.*
import org.junit.Test

class OnlineLyricsTest {
    // The shape LRCLIB sends (serde_yaml): lists at their key's indent, quotes only where needed.
    private val wordSynced = """
        version: '1.0'
        metadata:
          title: Blinding Lights
          artist: The Weeknd
          duration_ms: 200000
          instrumental: false
        lines:
        - text: Yeah
          start_ms: 13120
          end_ms: 14551
          words:
          - text: Yeah
            start_ms: 13159
        - text: I've been tryna call
          start_ms: 27170
          end_ms: 28268
          words:
          - text: 'I''ve '
            start_ms: 27209
          - text: 'been '
            start_ms: 27598
          - text: 'tryna '
            start_ms: 27889
          - text: call
            start_ms: 28200
        plain: |
          Yeah

          I've been tryna call
    """.trimIndent()

    @Test fun miniYamlReadsALyricsfile() {
        val root = MiniYaml.parse(wordSynced) as Map<*, *>
        assertEquals("1.0", root["version"])
        assertEquals("The Weeknd", (root["metadata"] as Map<*, *>)["artist"])
        val lines = root["lines"] as List<*>
        assertEquals(2, lines.size)
        val second = lines[1] as Map<*, *>
        assertEquals("I've been tryna call", second["text"])
        assertEquals(listOf("I've ", "been ", "tryna ", "call"), (second["words"] as List<*>).map { (it as Map<*, *>)["text"] })
        assertEquals("Yeah\n\nI've been tryna call\n", root["plain"])
    }

    @Test fun miniYamlHandlesQuotesFoldingCommentsAndEmptyCollections() {
        val root = MiniYaml.parse("""
            # a comment
            ---
            a: "Caf\u00e9 \"bar\"" # trailing comment
            b: 'one
              two'
            c: plain text
              goes on
            d: []
            e: ~
            f: >-
              folded
              lines
            g:
              - x
              -   y: 1
                  z: 'it''s'
        """.trimIndent()) as Map<*, *>
        assertEquals("Café \"bar\"", root["a"])
        assertEquals("one two", root["b"])
        assertEquals("plain text goes on", root["c"])
        assertEquals(emptyList<Any?>(), root["d"])
        assertTrue(root.containsKey("e") && root["e"] == null)
        assertEquals("folded lines", root["f"])
        assertEquals(listOf("x", mapOf("y" to "1", "z" to "it's")), root["g"])
    }

    @Test fun miniYamlGivesUpOnWhatItDoesNotRead() {
        assertNull(MiniYaml.parse("lines: [{text: a}]"))
        assertNull(MiniYaml.parse("a: &anchor 1\nb: *anchor"))
        assertNull(MiniYaml.parse("a: 'never closed"))
    }

    @Test fun lyricsfileBecomesEnhancedLrcThatPlaysWordByWord() {
        val lrc = checkNotNull(lyricsfileLrc(wordSynced))
        // The 12.6 s before the second line is a break: an empty line at the first one's end brings the waiting dots.
        assertEquals("[00:13.12]<00:13.16>Yeah<00:14.55>\n[00:14.55]\n" +
            "[00:27.17]<00:27.21>I've <00:27.60>been <00:27.89>tryna <00:28.20>call<00:28.27>\n" +
            "[00:28.27]\n", lrc)
        val lyrics = checkNotNull(parseLyrics(lrc))
        assertEquals(listOf("Yeah", "", "I've been tryna call", ""), lyrics.lines.map { it.text })
        assertEquals(listOf(LyricWord(27_210, "I've "), LyricWord(27_600, "been "), LyricWord(27_890, "tryna "), LyricWord(28_200, "call")),
            lyrics.lines[2].words)
    }

    @Test fun lyricsfileBreaksGetAnEmptyLineAndWordsNeverRunBackwards() {
        val lrc = checkNotNull(lyricsfileLrc("""
            lines:
            - text: Late word
              start_ms: 1000
              end_ms: 2000
              words:
              - text: 'Late '
                start_ms: 900
              - text: word
                start_ms: 800
            - text: After the break
              start_ms: 10000
        """.trimIndent()))
        assertEquals("[00:01.00]<00:01.00>Late <00:01.00>word<00:02.00>\n[00:02.00]\n[00:10.00]After the break\n", lrc)
    }

    @Test fun lyricsfileWithoutTimedLinesIsNull() {
        assertNull(lyricsfileLrc("version: '1.0'\nlines: []\nplain: |\n  Just words\n"))
        assertNull(lyricsfileLrc("not: [yaml we read"))
    }

    private fun record(duration: Double?, words: Boolean = false, synced: Boolean = true, title: String = "Song", instrumental: Boolean = false) =
        LrcLibRecord(title, duration, instrumental, words, if (synced) "[00:01.00]a" else null, "a", if (words) wordSynced else null)

    @Test fun theRichestRecordOfTheSameLengthWins() {
        val query = LyricsQuery("Song", "Artist", "Album", 200_400)
        val exact = record(200.0)
        val words = record(201.0, words = true)
        assertSame(words, bestLrcLibRecord(query, exact, listOf(record(263.0, words = true), words)))
        assertSame(exact, bestLrcLibRecord(query, exact, listOf(record(200.0))))
        assertNull(bestLrcLibRecord(query, null, listOf(record(205.0, words = true))))
    }

    @Test fun withoutALengthTheTitleMustMatch() {
        val query = LyricsQuery("Song", null, null, null)
        val other = record(200.0, words = true, title = "Other song")
        val same = record(180.0, title = "song")
        assertSame(same, bestLrcLibRecord(query, null, listOf(other, same)))
    }

    @Test fun anInstrumentalRecordIsReportedWhenNothingHasWords() {
        val query = LyricsQuery("Song", "Artist", null, 200_000)
        val instrumental = record(200.0, synced = false, instrumental = true)
        assertSame(instrumental, bestLrcLibRecord(query, instrumental, emptyList()))
        assertEquals(0, lrcLibTier(instrumental))
    }

    @Test fun savedTextNamesTheSongAndKeepsTheBestTiming() {
        val query = LyricsQuery("Blinding Lights", "The Weeknd", "After [Hours]", 200_000)
        val text = checkNotNull(lrcLibText(record(200.0, words = true), query))
        assertTrue(text.startsWith("[ti:Blinding Lights]\n[ar:The Weeknd]\n[al:After [Hours]\n[by:LRCLIB (lrclib.net)]\n[00:13.12]<00:13.16>Yeah"))
        assertTrue(checkNotNull(parseLyrics(text)).lines[2].words.isNotEmpty())
        assertEquals("[ti:Song]\n[by:LRCLIB (lrclib.net)]\n[00:01.00]a\n", lrcLibText(record(200.0), LyricsQuery("Song", null, null, null)))
    }

    @Test fun timesRoundToHundredthsInAsciiDigits() {
        assertEquals("00:00.00", lrcTime(-5))
        assertEquals("00:13.16", lrcTime(13_159))
        assertEquals("01:02.07", lrcTime(62_070))
        assertEquals("100:00.00", lrcTime(6_000_000))
    }

    @Test fun theLrcBesideASongSharesItsName() {
        assertEquals("Poppin.lrc", sidecarName("Poppin.flac"))
        assertEquals("Live.at.Wembley.lrc", sidecarName("Live.at.Wembley.m4a"))
        assertEquals("README.lrc", sidecarName("README"))
        assertEquals(".hidden.lrc", sidecarName(".hidden"))
    }

    @Test fun lrcFilesInUtf16AreRead() {
        val text = "[00:01.00]Héllo"
        assertEquals(text, lyricsText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, lyricsText(text.toByteArray(Charsets.UTF_8)))
    }
}
