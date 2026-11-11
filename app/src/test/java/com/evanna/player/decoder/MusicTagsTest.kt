package com.evanna.player.decoder

import org.junit.Assert.*
import org.junit.Test

class MusicTagsTest {
    @Test fun numberPairsRequireCompleteBoundedNumbers() {
        assertEquals(1 to null, parseNumberPair("1"))
        assertEquals(1 to null, parseNumberPair("01"))
        assertEquals(1 to 17, parseNumberPair(" 1 / 17 "))
        assertEquals(null to 17, parseNumberPair("0/17"))
        assertEquals(17 to null, parseNumberPair("17/2"))
        assertEquals(999 to 999, parseNumberPair("999/999"))
        for (text in listOf(null, "", "abc", "1foo", "-1", "1000", "99999999999999", "1/2/3"))
            assertEquals(text, null to null, parseNumberPair(text))
    }
    @Test fun yearsAndFlagsDoNotGuessAtJunk() {
        for (text in listOf("2018", "2018-08-03", " 2018/08 ")) assertEquals(2018, parseYear(text))
        for (text in listOf(null, "18", "20180", "abcd2018", "0999", "3000")) assertNull(text, parseYear(text))
        for (text in listOf("1", "TRUE", " Yes ")) assertTrue(parseFlag(text))
        for (text in listOf(null, "0", "false", "anything")) assertFalse(parseFlag(text))
    }
    @Test fun fullMetadataUsesValidTotalsAndYearFallbacks() {
        val tags = MutableList<String?>(16) { null }.apply {
            this[3] = "Album artist"; this[4] = "2/17"; this[5] = "20"
            this[7] = "2"; this[8] = "bad"; this[9] = "3"
            this[10] = "invalid"; this[11] = "2019"; this[12] = "2020"
            this[13] = "Genre"; this[14] = "Composer"; this[15] = "1"
        }
        assertEquals(MusicTags("Album artist", 2, 17, 2, 3, 2019, "Genre", "Composer", true), parseMusicTags(tags))
        tags[4] = "17/2"; tags[5] = "1"; tags[6] = "25"
        assertEquals(25, parseMusicTags(tags).totalTracks)
        assertEquals(MusicTags(), parseMusicTags(List(16) { null }))
    }
    @Test(expected = IllegalArgumentException::class) fun tagSlotMismatchIsRejected() {
        parseMusicTags(listOf("title", "artist", "album"))
    }
    @Test fun structuredTagsAreValidatedBeforeDisplayAndNativeTruncation() {
        val bytes = Array(16) { byteArrayOf() }
        bytes[4] = ("0".repeat(511) + "1foo").toByteArray()
        assertNull(musicTagsFromBytes(bytes).trackNumber)
        bytes[4] = ("1" + " ".repeat(4095)).toByteArray()
        assertNull(musicTagsFromBytes(bytes).trackNumber)
        bytes[4] = "01/17".toByteArray()
        bytes[3] = (" A\n" + "é".repeat(3000)).toByteArray()
        val tags = musicTagsFromBytes(bytes)
        assertEquals(1, tags.trackNumber); assertEquals(17, tags.totalTracks)
        assertEquals(512, tags.albumArtist?.length)
        assertFalse(tags.albumArtist!!.contains('\n'))
    }
}
