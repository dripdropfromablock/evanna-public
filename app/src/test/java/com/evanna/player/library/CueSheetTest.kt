package com.evanna.player.library

import org.junit.Assert.*
import org.junit.Test

class CueSheetTest {
    private fun cue(text: String) = parseCueSheet(text.toByteArray(Charsets.UTF_8))
    private fun rejects(text: String) = assertTrue(runCatching { cue(text) }.isFailure)

    @Test fun singleFileTracksUseIndexOneAndKeepPregapsInThePreviousTrack() {
        val sheet = cue("""
            REM GENRE Test
            PERFORMER "Album Artist"
            TITLE "Album"
            FILE "Album.flac" WAVE
              TRACK 01 AUDIO
                TITLE "One"
                ISRC ABCDE1234567
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Two"
                PERFORMER "Guest"
                INDEX 00 00:59:70
                INDEX 01 01:00:00
              TRACK 03 AUDIO
                TITLE Unquoted title
                INDEX 01 02:00:37
        """.trimIndent())
        assertEquals("Album", sheet.title);assertEquals("Album Artist", sheet.performer)
        val file = sheet.files.single()
        assertEquals("Album.flac", file.name)
        assertEquals(listOf(CueTrack(1, "One", null, 0), CueTrack(2, "Two", "Guest", 4500), CueTrack(3, "Unquoted title", null, 9037)), file.tracks)
        val ranges = cueRanges(file, 44100, 200_000_000)!!
        assertEquals(listOf(0L to 2_646_000L, 2_646_000L to 5_313_756L, 5_313_756L to null), ranges.map { it.startFrame to it.endFrame })
        assertEquals(listOf(0L, 2_880_000L), cueRanges(file, 48000, null)!!.take(2).map { it.startFrame })
        assertNull("A track starting after the file's end is never guessed", cueRanges(file, 44100, 120_000_000))
    }

    @Test fun multiFileSheetsMapEachTrackToTheFileActiveAtIndexOne() {
        val sheet = cue("FILE \"a.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 00 03:00:00\n" +
            "FILE \"b.wav\" WAVE\nINDEX 01 00:00:00\nTRACK 03 MODE1/2352\nINDEX 01 00:10:00\nTRACK 04 AUDIO\nINDEX 01 01:00:00")
        assertEquals(listOf("a.wav" to listOf(1), "b.wav" to listOf(2, 4)), sheet.files.map { it.name to it.tracks.map { track -> track.number } })
        assertEquals(4, sheet.totalTracks)
        val saved = com.evanna.player.playback.SavedQueueEntry("evanna:local:queue:cue-four", "a".repeat(64), null,
            "Four", null, null, 1000, com.evanna.player.decoder.MusicTags(trackNumber = 4, totalTracks = sheet.totalTracks))
        assertEquals(4, saved.musicTags.totalTracks)
    }

    @Test fun totalTracksUsesTheHighestAudioNumberRatherThanTheCountOrADataTrackNumber() {
        val sheet = cue("FILE \"album.wav\" WAVE\nTRACK 07 AUDIO\nINDEX 01 00:00:00\n" +
            "TRACK 09 AUDIO\nINDEX 01 00:01:00\nTRACK 99 MODE1/2352\nINDEX 01 00:02:00")
        assertEquals(9, sheet.totalTracks)
        assertNull(cue("FILE \"data.bin\" BINARY\nTRACK 01 MODE1/2352\nINDEX 01 00:00:00").totalTracks)
    }

    @Test fun utf8WithOrWithoutBomAndWindows1252AreRead() {
        val text = "TITLE \"Caf\u00e9\"\nFILE \"x.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00"
        assertEquals("Caf\u00e9", parseCueSheet(text.toByteArray(Charsets.UTF_8)).title)
        assertEquals("Caf\u00e9", parseCueSheet(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)).title)
        assertEquals("Caf\u00e9", parseCueSheet(text.toByteArray(charset("windows-1252"))).title)
    }

    @Test fun malformedOrUnboundedSheetsAreRejected() {
        val ok = "FILE \"x.flac\" WAVE\nTRACK 01 AUDIO\n"
        rejects(ok + "INDEX 01 00:00:75")
        rejects(ok + "INDEX 01 00:60:00")
        rejects(ok + "INDEX 01 0:00")
        rejects(ok + "TITLE no index")
        rejects(ok + "INDEX 01 00:00:00\nINDEX 01 00:01:00")
        rejects(ok + "INDEX 01 00:02:00\nTRACK 02 AUDIO\nINDEX 01 00:01:00")
        rejects(ok + "INDEX 01 00:00:00\nTRACK 01 AUDIO\nINDEX 01 00:01:00")
        rejects("TRACK 01 AUDIO\nINDEX 01 00:00:00")
        rejects("FILE \"x.flac\" WAVE\nTRACK 100 AUDIO\nINDEX 01 00:00:00")
        rejects(ok + "INDEX 01 00:00:00\nREM " + "x".repeat(4100))
        assertTrue(runCatching { parseCueSheet(ByteArray(CUE_SHEET_MAX_BYTES + 1) { ' '.code.toByte() }) }.isFailure)
        assertEquals(1, cue(ok + "INDEX 01 00:00:00").files.single().tracks.size)
    }
}
