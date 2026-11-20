package com.evanna.player.sources.radio

import com.evanna.player.network.NetworkPolicy
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RadioPlaylistTest {
    private val policy = NetworkPolicy("https://stations.example/list.m3u")

    @Test fun parsesStationLabelsAndPlsOrder() {
        val m3u = "\uFEFF#EXTM3U\n#EXTINF:-1,Sunday - Jazz\nhttps://audio.example/jazz?channel=1"
        val station = parseRadioPlaylist(m3u.toByteArray(), RadioPlaylistFormat.M3U, policy).single()
        assertEquals("Sunday - Jazz", station.title)
        assertEquals("https://audio.example/jazz?channel=1", station.url)
        val pls = "[playlist]\nFile2=https://audio.example/pop\nTitle2=Pop\nFile1=https://audio.example/jazz\nTitle1=Jazz\nNumberOfEntries=2\nVersion=2"
        assertEquals(listOf("Jazz", "Pop"),
            parseRadioPlaylist(pls.toByteArray(), RadioPlaylistFormat.PLS, policy).map { it.title })
    }

    @Test fun rejectsNestedUnsafeAndOversizedPlaylists() {
        val entries = listOf(
            "../stream", "https://user:secret@audio.example/live", "https://audio.example/%2e%2e/live",
            "https://audio.example/%252e%252e/live", "https://audio.example/other.PLS",
            "http://audio.example/live", "#EXT-X-TARGETDURATION:10\nhttps://audio.example/segment"
        )
        for (entry in entries) {
            assertThrows(entry, IllegalArgumentException::class.java) {
                parseRadioPlaylist("#EXTM3U\n$entry".toByteArray(), RadioPlaylistFormat.M3U, policy)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRadioPlaylist(ByteArray(1024 * 1024 + 1), RadioPlaylistFormat.M3U, policy)
        }
    }

    @Test fun stripsIcyMetadataEvenAcrossShortBodyReads() {
        val wire = byteArrayOf(1, 2, 3, 1) + ByteArray(16) { 99 } + byteArrayOf(4, 5, 6, 0, 7)
        val oneByteAtATime = object : FilterInputStream(ByteArrayInputStream(wire)) {
            override fun read(target: ByteArray, offset: Int, length: Int) = super.read(target, offset, minOf(1, length))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7), IcyAudioInput(oneByteAtATime, 3).readBytes())
        assertThrows(IOException::class.java) {
            IcyAudioInput(ByteArrayInputStream(byteArrayOf(1, 1, 99)), 1).readBytes()
        }
    }
}
