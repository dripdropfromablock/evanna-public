package com.evanna.player.playback

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    private val items=listOf(QueueItem("a","A","Artist","Album",1),QueueItem("b","B","Artist","Album",1),QueueItem("c","C","Other","Other album",1))
    @Test fun albumTimerStopsBeforeAnotherAlbumAndDoesNotLoopUnderRepeat() {
        val queue=PlaybackQueue(items,0)
        val timer=SleepTimerState(SleepMode.ALBUM,targetId="a",albumKey=sleepAlbumKey(items[0]))
        assertFalse(timer.blocks(queue,"a"));assertTrue(timer.blocks(queue,"b"))
        assertTrue(timer.blocks(queue.copy(repeat=QueueRepeat.SONG),"a"))
        assertTrue(timer.blocks(PlaybackQueue(items.take(2),1,repeat=QueueRepeat.QUEUE),"b"))
        assertTrue(SleepTimerState(SleepMode.SONG,targetId="a").blocks(queue,"a"))
        assertFalse(SleepTimerState(SleepMode.SONG,targetId="a").blocks(queue,"b"))
        assertNull(GaplessQueue(queue,emptyMap(),setOf("a")).next("a"))
    }
    @Test fun deadlineRoundsUpAndUnknownAlbumsStopAtTheCurrentSong() {
        val timer=SleepTimerState(SleepMode.MINUTES,deadlineMs=10001)
        assertEquals(2L,timer.remaining(9000));assertEquals(0L,timer.remaining(10001))
        assertEquals(0L,timer.remaining(20000))
        val unknown=items[0].copy(album=null)
        assertNull(sleepAlbumKey(unknown))
        assertTrue(SleepTimerState(SleepMode.ALBUM,targetId="a").blocks(PlaybackQueue(listOf(unknown,unknown.copy(id="b")),0),"a"))
    }
}
