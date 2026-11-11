package com.evanna.player.user

import org.junit.Assert.*
import org.junit.Test

class PlaylistTest {
    private val song=PlaylistEntry("a".repeat(64),"Song","Artist","Album",123_000_000,"song.flac")
    @Test fun m3uRoundTripPreservesOrderDuplicatesAndUnknownDurations() {
        val bytes=writeM3u(listOf(song to "Album/song.flac",song.copy(durationUs=null) to "song.flac"))
        val parsed=parseM3u(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+bytes)
        assertEquals(2,parsed.entries.size)
        assertEquals(M3uEntry("Album/song.flac","Song","Artist",123_000_000),parsed.entries[0])
        assertNull(parsed.entries[1].durationUs)
        val legacy=parseM3u("#EXTM3U\r\n#EXTINF:1.5,Artist - Café\r\nsong.flac\r\n".toByteArray(charset("windows-1252")))
        assertEquals("Café",legacy.entries.single().title);assertEquals(1_500_000L,legacy.entries.single().durationUs)
        assertTrue(legacy.warnings.isNotEmpty())
    }
    @Test fun parserAndWriterBoundInputAndCannotInjectNewEntries() {
        for(bytes in listOf(ByteArray(1024*1024+1),"x".repeat(4097).toByteArray(),List(1001){"song.flac"}.joinToString("\n").toByteArray()))
            assertTrue(runCatching {parseM3u(bytes)}.isFailure)
        assertTrue(runCatching {writeM3u(listOf(song to "song.flac\nother.flac"))}.isFailure)
        val parsed=parseM3u(writeM3u(listOf(song.copy(title="Title\n#EXTINF:2,Fake") to "song.flac")))
        assertEquals(1,parsed.entries.size)
        assertNull(parseM3u("#EXTINF:NaN,Title\nsong.flac".toByteArray()).entries.single().durationUs)
    }
    @Test fun matchingNeverGuessesAmbiguousOrRemoteSongsAndCanRelinkRenamedFiles() {
        assertEquals(song,matchPlaylistEntry(song.copy(trackId=null),listOf(song)))
        assertNull(matchPlaylistEntry(song.copy(trackId=null),listOf(song,song.copy(trackId="b".repeat(64)))))
        assertEquals(song,matchPlaylistEntry(song.copy(trackId=null,fileName="old-name.flac"),listOf(song)))
        assertNull(resolveM3uEntry(M3uEntry("https://example/song.flac","Song","Artist",123_000_000),listOf(song)).trackId)
        assertEquals(song.trackId,resolveM3uEntry(M3uEntry("song.flac",null,null,123_000_000),listOf(song)).trackId)
        assertNull(matchPlaylistEntry(song.copy(trackId=null,durationUs=1),listOf(song)))
    }
    @Test fun modelsValidateNamesReferencesAndDurations() {
        assertEquals("List",validPlaylistName(" List "));assertNull(validPlaylistName(" "))
        assertNull(validPlaylistName("x".repeat(201)))
        assertTrue(runCatching {song.copy(trackId="file:///private")}.isFailure)
        assertTrue(runCatching {song.copy(title="x".repeat(513))}.isFailure)
        assertTrue(runCatching {song.copy(durationUs=-1)}.isFailure)
    }
    @Test fun relativeReferencesCannotEscapeTheVolumeOrResolveNetworkPaths() {
        assertEquals("primary:Music/Album/song.flac",relativeDocument("primary:Music","Album/song.flac"))
        assertEquals("primary:Music/song.flac",relativeDocument("primary:Music/Lists","../song.flac"))
        assertNull(relativeDocument("primary:Music","../../song.flac"))
        assertNull(relativeDocument("primary:Music","https://example/song.flac"))
        assertNull(relativeDocument("primary:Music","/song.flac"))
        assertNull(resolveM3uEntry(M3uEntry("../song.flac",null,null,null),listOf(song)).trackId)
        assertEquals("primary:Music/song.flac",playlistDocument("primary:Lists","/storage/emulated/0/Music/song.flac","/storage/emulated/0"))
        assertNull(playlistDocument("primary:Lists","/unknown/Music/song.flac","/storage/emulated/0"))
        val commentName=parseM3u(writeM3u(listOf(song to "#song.flac")))
        assertEquals("./#song.flac",commentName.entries.single().path)
    }
}
