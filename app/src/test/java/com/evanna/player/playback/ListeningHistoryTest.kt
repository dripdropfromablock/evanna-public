package com.evanna.player.playback

import org.junit.Assert.*
import org.junit.Test

class ListeningHistoryTest {
    @Test fun lateSegmentProgressWithinTheSameStreamCannotRecountItsPredecessor() {
        val meter=ListeningHistory()
        meter.progress("first","a",1,1,1000,1000,0)
        meter.progress("second","b",1,1,1000,1000,10)
        meter.progress("first","a",1,2,1000,1000,0)
        assertEquals(listOf("a","b"),meter.pending().map {it.trackId})
    }
    @Test fun lateProgressFromAnOldStreamCannotRecountAPreviousPlay() {
        val meter=ListeningHistory()
        meter.progress("first","q",1,5000,1000,10_000_000)
        meter.progress("second","q",2,5000,1000,10_000_000)
        meter.progress("first","q",1,6000,1000,10_000_000)
        assertEquals(2,meter.pending().size)
    }
    @Test fun confirmedListeningSurvivesPauseAndSeekButDoesNotCountPositionJumpsOrCounterRegression() {
        val meter=ListeningHistory {123L}
        meter.progress("play","queue",1,4000,1000,10_000_000)
        assertTrue(meter.pending().isEmpty())
        meter.progress("play","queue",1,3999,1000,10_000_000)
        meter.progress("play","queue",1,4000,1000,10_000_000)
        assertTrue(meter.pending().isEmpty())
        // A resumed/seeked stream starts its own completed counter at zero, not at the source seek position.
        meter.progress("play","queue",2,1000,1000,10_000_000)
        assertEquals(1,meter.pending().size);assertEquals(123L,meter.pending().single().playedAt)
        meter.progress("play","queue",2,9000,1000,10_000_000)
        assertEquals(1,meter.pending().size)
        meter.progress("replay","queue",3,5000,1000,10_000_000)
        assertEquals(2,meter.pending().size)
    }
    @Test fun unknownAndLongSongsNeedFourMinutesAndShortKnownSongsNeedHalf() {
        val meter=ListeningHistory()
        meter.progress("unknown","q",1,239999,1000,null);assertTrue(meter.pending().isEmpty())
        meter.progress("unknown","q",1,240000,1000,null);assertEquals(1,meter.pending().size)
        meter.progress("long","q",2,240000,1000,900_000_000);assertEquals(2,meter.pending().size)
        meter.progress("short","q",3,1,1000,2000);assertEquals(3,meter.pending().size)
        meter.progress("file",null,4,240000,1000,null);assertEquals(3,meter.pending().size)
    }
    @Test fun retainedWindowIsBoundedAndAcknowledgementNeverDropsNewerEvents() {
        val meter=ListeningHistory()
        repeat(5001){meter.progress("play$it","q",it.toLong(),1,1000,1000)}
        val before=meter.pending();assertEquals(5000,before.size)
        assertEquals(5000,before.map {it.eventId}.toSet().size)
        meter.progress("new","q",9999,1,1000,1000)
        meter.acknowledge(before.last().sequence)
        assertEquals(1,meter.pending().size)
        meter.acknowledge(meter.pending().last().sequence);assertTrue(meter.pending().isEmpty())
    }
}
