package com.evanna.player.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

class GaplessReaderTest {
    @Test fun resumedSuccessorRefusesChangedGainTagsEvenWhenThePcmFormatMatches() {
        val first=track("first",listOf(256));var gain=-4f
        fun successor()=object:PcmTrack by track("second",listOf(512)) {
            override val loudnessTags=com.evanna.player.loudness.LoudnessTags(trackDb=gain)
        }
        val items=listOf(QueueItem("one","one",null,null,1000),QueueItem("two","two",null,null,1000))
        val queue=GaplessQueue(PlaybackQueue(items,0),mapOf("two" to {successor()}))
        val reader=GaplessReader("one",first,first.open(),{queue},{true},{true})
        try {
            val pcm=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            while(reader.read(pcm,1)>0)Unit
            val resumed=reader.completed(2).track
            assertEquals(-4f,resumed.loudnessTags.trackDb)
            gain=-8f
            val opened=runCatching {resumed.open()}
            opened.getOrNull()?.close()
            assertTrue("A changed gain tag must require source reselection",opened.isFailure)
        }finally {reader.close()}
    }
    @Test fun aRejectedGainBoundaryNeverReadsTheSuccessorsSamples() {
        val first=track("first",listOf(256));var opened=false
        val second=object:PcmTrack by track("second",listOf(512)) {override fun open():PcmReader {opened=true;error("Rejected gain must not open")}}
        val items=listOf(QueueItem("one","one",null,null,1000),QueueItem("two","two",null,null,1000))
        val queue=GaplessQueue(PlaybackQueue(items,0),mapOf("two" to {second}));var checks=0
        val reader=GaplessReader("one",first,first.open(),{queue},{true},{true},
            successorAllowed={id,track->checks++;assertEquals("two",id);assertEquals("second",track.id);false})
        try {
            val pcm=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1,reader.read(pcm,1));assertEquals(256,pcm.getInt(0));assertEquals(0,reader.read(pcm,1));assertFalse(opened);assertEquals(1,checks)
        }finally {reader.close()}
    }
    private val format=TrackPcmFormat(48000,24,2,24,"FLAC",true,1000)
    private fun track(id:String,values:List<Int>,pcm:TrackPcmFormat=format)=object:PcmTrack {
        override val id=id
        override val format=pcm
        override val gaplessEligible=true
        override fun open()=object:PcmReader {
            override val format=pcm
            var offset=0
            override fun read(target:ByteBuffer,maxFrames:Int):Int {
                val count=minOf(maxFrames,values.size-offset)
                repeat(count){i->target.putInt(i*8,values[offset+i]);target.putInt(i*8+4,-values[offset+i])}
                offset+=count;return count
            }
            override fun requestCancel()=Unit
            override fun close()=Unit
        }
        override fun close()=Unit
    }
    @Test fun splitPcmIsContiguousAndCompletedCursorLagsSubmittedCursor() {
        val first=track("first",listOf(256,512,768));val next=track("next",listOf(1024,1280))
        val items=listOf(QueueItem("one","one",null,null,1000),QueueItem("two","two",null,null,1000))
        val state=GaplessQueue(PlaybackQueue(items,0),mapOf("two" to {next}))
        val reader=GaplessReader("one",first,first.open(),{state},{true},{true})
        try {
            val buffer=ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)
            val actual=mutableListOf<Int>()
            while(true){val n=reader.read(buffer,4);if(n==0)break;repeat(n){actual+=buffer.getInt(it*8)}}
            assertEquals(listOf(256,512,768,1024,1280),actual)
            assertEquals("one",reader.completed(2).queueId)
            assertEquals("two",reader.completed(3).queueId)
            assertEquals(3L,reader.completed(4).startFrame)
        }finally{reader.close()}
    }
    @Test fun changedFormatAndLostFocusDoNotConsumeNextTrack() {
        val first=track("first",listOf(1))
        val items=listOf(QueueItem("one","one",null,null,null),QueueItem("two","two",null,null,null))
        val state=GaplessQueue(PlaybackQueue(items,0),mapOf("two" to {track("next",listOf(2),format.copy(sampleRate=44100))}))
        val reader=GaplessReader("one",first,first.open(),{state},{true},{true})
        try {
            val buffer=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1,reader.read(buffer,1));assertEquals(0,reader.read(buffer,1))
            assertEquals("one",reader.completed(1).queueId)
        }finally{reader.close()}
        var opened=false
        val stopped=GaplessReader("one",first,first.open(),{GaplessQueue(PlaybackQueue(items,0),mapOf("two" to {opened=true;first}))},{false},{true})
        try{val b=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);assertEquals(1,stopped.read(b,1));assertEquals(0,stopped.read(b,1));assertFalse(opened)}finally{stopped.close()}
    }
    @Test fun changedNextDiscardsPreparedAudioAndRepeatUsesTheSubmittedOccurrence() {
        val first=track("first",listOf(1))
        val items=(1..3).map{QueueItem("$it","$it",null,null,null)}
        val factories=mapOf("1" to {track("first",listOf(1))},"2" to {track("second",listOf(2))},"3" to {track("third",listOf(3))})
        val state=AtomicReference(GaplessQueue(PlaybackQueue(items,0),factories))
        val reader=GaplessReader("1",first,first.open(),{state.get()},{true},{true})
        try {
            val b=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1,reader.read(b,1));assertEquals(1,b.getInt(0))
            state.set(GaplessQueue(PlaybackQueue(items,0,shuffleEnabled=true,repeat=QueueRepeat.QUEUE,playOrder=listOf("1","3","2")),factories))
            val played=mutableListOf<Int>()
            repeat(7){assertEquals(1,reader.read(b,1));played+=b.getInt(0)}
            assertEquals(listOf(3,2,1,3,2,1,3),played)
            assertEquals("1",reader.completed(0).queueId)
            assertEquals("3",reader.completed(1).queueId)
            assertEquals("2",reader.completed(2).queueId)
        }finally{reader.close()}
    }
    @Test fun cancellationJoinsLatePreparationAndClosesItsAcquiredTrack()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var closed=0
        val first=track("first",listOf(1))
        val items=listOf(QueueItem("1","1",null,null,null),QueueItem("2","2",null,null,null))
        val state=GaplessQueue(PlaybackQueue(items,0),mapOf("2" to {
            entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            object:PcmTrack by track("late",listOf(2)){
                override fun close(){closed++}
                override fun closeForRestore()=close()
            }
        }))
        val reader=GaplessReader("1",first,first.open(),{state},{true},{true})
        try {
            val b=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            reader.read(b,1);assertTrue(entered.await(5,TimeUnit.SECONDS))
            reader.requestCancel();release.countDown();reader.close();reader.close()
            assertEquals(1,closed)
            assertTrue(runCatching{reader.read(b,1)}.isFailure)
        }finally{release.countDown();reader.close()}
    }
    @Test fun cleanupFailureRetainsReaderUntilRetry() {
        var closes=0
        val initial=track("first",listOf(1))
        val failing=object:PcmReader by initial.open() {
            override fun close(){closes++;check(closes>1)}
        }
        val reader=GaplessReader("one",initial,failing,{GaplessQueue(PlaybackQueue(),emptyMap())},{true},{true})
        assertTrue(runCatching{reader.close()}.isFailure)
        reader.close();reader.close()
        assertEquals(2,closes)
    }
    @Test fun successorFailureDoesNotBecomeSuccessfulEof() {
        val initial=track("first",listOf(1))
        val items=listOf(QueueItem("1","1",null,null,null),QueueItem("2","2",null,null,null))
        val state=GaplessQueue(PlaybackQueue(items,0),mapOf("2" to {error("Source unavailable")}))
        val reader=GaplessReader("1",initial,initial.open(),{state},{true},{true})
        try {
            val b=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1,reader.read(b,1))
            assertTrue("Failed preparation must stop instead of autoplay retry",runCatching{reader.read(b,1)}.isFailure)
        }finally{reader.close()}
    }
    @Test fun aThousandTinyTracksStayOrderedAcrossBoundedCompletionBackpressure() {
        val items=(1..1000).map{QueueItem("$it","$it",null,null,null)}
        val factories=items.associate{item->item.id to {track(item.id,listOf(item.id.toInt()))}}
        val state=GaplessQueue(PlaybackQueue(items,0),factories)
        val initial=factories.getValue("1")()
        var submitted=0L
        val reader=GaplessReader("1",initial,initial.open(),{state},{true},{true},{submitted})
        try {
            val buffer=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            for(value in 1..1000) {
                assertEquals(1,reader.read(buffer,1));assertEquals(value,buffer.getInt(0));submitted++
            }
            assertEquals(0,reader.read(buffer,1))
            assertEquals("1000",reader.completed(submitted).queueId)
            assertEquals(999L,reader.completed(submitted).startFrame)
        }finally{reader.close()}
    }
}
