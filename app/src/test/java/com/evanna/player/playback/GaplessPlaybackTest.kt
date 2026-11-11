package com.evanna.player.playback

import java.nio.ByteBuffer
import com.truesample.usbaudio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GaplessPlaybackTest {
    @Test fun switchingABoundaryTimerToMinutesClearsTheContinuousPcmBarrier() = runBlocking {
        for (mode in listOf(SleepMode.SONG, SleepMode.ALBUM)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val output = Output().apply { writeAction = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val owner = UsbSessionOwner(UsbPlaybackController { output }, Focus())
            try {
                owner.enqueue(source("first", listOf(256)).copy(artist = "Artist", album = "First album"))
                owner.enqueue(source("next", listOf(512)).copy(artist = "Artist", album = "Next album"))
                owner.open(7); assertTrue(owner.setSleepTimer(mode)); assertTrue(owner.play())
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertTrue(owner.setSleepTimer(SleepMode.MINUTES, 1))
                release.countDown()
                val ended = withTimeout(5000) { owner.snapshot.first { it.state in listOf(UsbPlaybackState.ENDED, UsbPlaybackState.ERROR) } }
                assertEquals(UsbPlaybackState.ENDED, ended.state)
                assertEquals("$mode left a stale gapless barrier", listOf(256, 512), output.samples)
                assertEquals(1, output.starts); assertEquals(1, output.drains)
                assertEquals(SleepMode.MINUTES, owner.sleepTimer.value.mode)
            } finally { release.countDown(); owner.close() }
        }
    }
    @Test fun repeatingAnOccurrenceWithChangedTagsMustEndTheOldGainStream()=runBlocking {
        val output=Output();val profile=com.evanna.player.user.DacProfile(com.evanna.player.usb.dacIdentity(1,2,"DAC"),levelingMode=com.evanna.player.loudness.LevelingMode.TRACK)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val owner=UsbSessionOwner(UsbPlaybackController {output},Focus())
        try {
            owner.enqueue(QueueSource(null,"Changing fixture",null,null,1000) {
                val created=calls.incrementAndGet();check(created<=2){"A changed-tag repeat must not enter the old stream"}
                object:PcmTrack by source("fixture",listOf(256)).create() {
                    override val loudnessTags=com.evanna.player.loudness.LoudnessTags(trackDb=if(created==1)-4f else -8f)
                }
            })
            owner.open(7){profile};owner.setRepeat(QueueRepeat.SONG);assertTrue(owner.play())
            val ended=withTimeout(5000){owner.snapshot.first {it.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR)}}
            assertEquals(UsbPlaybackState.ENDED,ended.state);assertEquals(listOf(256),output.samples)
            assertEquals(listOf(-4f),output.levelingOffsets);assertEquals(2,calls.get())
        }finally {owner.close()}
    }
    @Test fun sameLevelingOffsetsContinueAndADifferentOffsetStartsBeforeTheNextSong()=runBlocking {
        val output=Output();val profile=com.evanna.player.user.DacProfile(com.evanna.player.usb.dacIdentity(1,2,"DAC"),levelingMode=com.evanna.player.loudness.LevelingMode.TRACK)
        val resolved=mutableListOf<String?>()
        val controller=UsbPlaybackController(resolveLeveling={mode,id,_ ->
            synchronized(resolved){resolved+=id}
            com.evanna.player.loudness.LevelingDecision(mode,if(id=="c".repeat(64))-8f else -4f,"Fixture")
        }){output}
        val owner=UsbSessionOwner(controller,Focus())
        try {
            for((name,value) in listOf("a" to 256,"b" to 512,"c" to 768))owner.enqueue(source(name,listOf(value)).copy(libraryId=name.repeat(64)))
            owner.open(7){profile};assertTrue(owner.play())
            val firstEnd=withTimeout(5000){owner.snapshot.first {it.state==UsbPlaybackState.ENDED}}
            assertEquals(listOf(256,512),output.samples);assertEquals(listOf(-4f),output.levelingOffsets)
            assertTrue(firstEnd.stream!!.bitPerfect);assertFalse(firstEnd.conversionAllowed)
            assertTrue(owner.onPlaybackState(firstEnd))
            withTimeout(5000){owner.snapshot.first {it.state==UsbPlaybackState.ENDED && it.generation!=firstEnd.generation}}
            assertEquals(listOf(256,512,768),output.samples);assertEquals(listOf(-4f,-8f),output.levelingOffsets)
            assertEquals(2,output.starts);assertFalse(owner.snapshot.value.conversionAllowed)
        }finally {owner.close()}
    }
    @Test fun pausingAFinishedSongThenReplayingCountsANewListen()=runBlocking {
        val saved=mutableListOf<com.evanna.player.user.PlayedSong>()
        val owner=UsbSessionOwner(UsbPlaybackController {Output()},Focus(),saveHistory={saved+=it;true})
        try {
            owner.enqueue(source("a",List(96){256},durationUs=2000).copy(libraryId="a".repeat(64)))
            owner.open(7)
            repeat(2) {
                assertTrue(owner.play())
                owner.onPlaybackState(withTimeout(5000){owner.snapshot.first {it.state==UsbPlaybackState.ENDED}})
                assertTrue(owner.pause())
            }
            assertEquals(2,saved.size);assertEquals(2,saved.map {it.eventId}.toSet().size)
        }finally {owner.close()}
    }
    @Test fun historyCountsEveryConfirmedTinyGaplessSongAndRetriesFailedWrites()=runBlocking {
        val output=Output();var available=false
        val saved=mutableListOf<com.evanna.player.user.PlayedSong>()
        val owner=UsbSessionOwner(UsbPlaybackController {output},Focus(),saveHistory={if(!available)false else {saved+=it;true}})
        try {
            owner.enqueue(source("a",List(96){256},durationUs=2000).copy(libraryId="a".repeat(64)))
            owner.enqueue(source("b",List(96){512},durationUs=2000).copy(libraryId="b".repeat(64)))
            owner.open(7);assertTrue(owner.play())
            val ended=withTimeout(5000){owner.snapshot.first {it.state==UsbPlaybackState.ENDED}}
            owner.onPlaybackState(ended)
            assertTrue(owner.queue.value.historySaveFailed);assertTrue(saved.isEmpty())
            assertTrue(owner.close())
            available=true;owner.checkpoint()
            assertEquals(listOf("a".repeat(64),"b".repeat(64)),saved.map {it.trackId})
            assertFalse(owner.queue.value.historySaveFailed)
            owner.checkpoint();assertEquals(2,saved.size);assertEquals(1,output.starts)
        }finally {owner.close()}
    }
    @Test fun albumSleepBarrierStopsContinuousPcmAndNaturalQueueAdvance()=runBlocking {
        val output=Output();val focus=Focus();val owner=UsbSessionOwner(UsbPlaybackController {output},focus)
        try {
            owner.enqueue(source("a",listOf(256,512)).copy(album="Album",artist="Artist"))
            owner.enqueue(source("b",listOf(768)).copy(album="Album",artist="Artist"))
            owner.enqueue(source("c",listOf(1024)).copy(album="Next album",artist="Artist"))
            owner.open(7);assertTrue(owner.setSleepTimer(SleepMode.ALBUM));assertTrue(owner.play())
            val ended=withTimeout(5000){owner.snapshot.first {it.state==UsbPlaybackState.ENDED}}
            owner.onPlaybackState(ended)
            assertEquals(listOf(256,512,768),output.samples);assertEquals(1,output.starts)
            assertEquals(1,owner.queue.value.index);assertEquals(3,owner.queue.value.items.size)
            assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state)
            assertEquals(SleepMode.OFF,owner.sleepTimer.value.mode);assertFalse(focus.held)
        }finally {owner.close()}
    }
    private class Focus:PlaybackFocus {
        override var held=false
        override fun request():Boolean{held=true;return true}
        override fun abandon(){held=false}
    }
    private class Output:UsbPlaybackOutput {
        override val description="Sequence test output"
        var starts=0;var drains=0;var sent=0L
        var dacBits=24
        var completed:Long?=null
        var holdDrain=false
        var stopCompleted:Long?=null
        private var stopped=CountDownLatch(1)
        val samples=mutableListOf<Int>()
        val levelingOffsets=mutableListOf<Float>()
        var writeAction: () -> Unit = {}
        override fun setLevelingOffsetDb(db:Float):Boolean {levelingOffsets+=db;return true}
        override fun setEqualizer(bands:List<EqBand>,preampDb:Float)=Unit
        override fun plan(source:PcmFormat)=PlaybackPlan(source.sampleRate,dacBits,false,dacBits>=source.significantBits)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo{starts++;sent=0;stopped=CountDownLatch(1);return StreamInfo(source.sampleRate,source.significantBits,2,DacFormat(1,1,1,2,(dacBits+7)/8,dacBits,DacFormat.SyncType.SYNC,512,listOf(source.sampleRate),source.sampleRate,source.sampleRate),source.sampleRate,source.sampleRate,dacBits>=source.significantBits,false)}
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(sent,0,0,false,sent,completed ?: sent,false)
        override fun write(buffer:ByteBuffer,frames:Int):Int{writeAction();repeat(frames){samples+=buffer.getInt(it*8)};sent+=frames;return frames}
        override fun drain():Boolean{drains++;if(holdDrain)check(stopped.await(5,TimeUnit.SECONDS));return !holdDrain}
        override fun stop():Boolean{stopCompleted?.let{completed=it};stopped.countDown();return true}
        override fun close()=Unit
    }
    private fun source(name:String,values:List<Int>,processed:Boolean=false,durationUs:Long=1000)=QueueSource(null,name,null,null,durationUs){object:PcmTrack {
        override val id="track:$name"
        override val format=TrackPcmFormat(48000,24,2,if(processed)null else 24,if(processed)"MP3" else "FLAC",!processed,durationUs)
        override val gaplessEligible=true
        override fun open():PcmReader {val pcm=format;return object:PcmReader {
            override val format=pcm
            var offset=0
            override fun read(target:ByteBuffer,maxFrames:Int):Int{val n=minOf(maxFrames,values.size-offset);repeat(n){target.putInt(it*8,values[offset+it]);target.putInt(it*8+4,0)};offset+=n;return n}
            override fun requestCancel()=Unit
            override fun close()=Unit
        }}
        override fun close()=Unit
    }}
    @Test fun queuePcmUsesOneOutputStartAndFinalDrainAndPublishesLastCompletedOccurrence()=runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try {
            owner.enqueue(source("a",listOf(256,512,768)));owner.enqueue(source("b",listOf(1024,1280)))
            owner.open(7);assertTrue(owner.play())
            withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.ENDED}}
            owner.checkpoint()
            assertEquals(listOf(256,512,768,1024,1280),output.samples)
            assertEquals(1,output.starts);assertEquals(1,output.drains)
            assertEquals(1,owner.queue.value.index)
            assertEquals(41L,owner.positionUs)
        }finally{owner.close()}
    }
    @Test fun continuousRepeatCannotInheritStrictConvertedOnceAuthorization()=runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try {
            owner.enqueue(source("processed",listOf(256),true));owner.setRepeat(QueueRepeat.SONG)
            owner.open(7);assertTrue(owner.play(true))
            val ended=withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.ENDED}}
            assertFalse(owner.onPlaybackState(ended))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            assertEquals(1,output.starts);assertEquals(listOf(256),output.samples)
        }finally{owner.close()}
    }
    @Test fun strictBitReductionCannotCarryConvertedOncePermissionIntoLosslessSuccessors()=runBlocking {
        val output=Output().apply{dacBits=16};val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try {
            owner.enqueue(source("first",listOf(256,512)));owner.enqueue(source("second",listOf(768)))
            owner.enqueue(source("third",listOf(1024)))
            val first=owner.queue.value.current!!.id;val second=owner.queue.value.items[1].id
            owner.open(7);assertFalse(owner.play());assertEquals(0,output.starts)
            assertTrue(owner.play(true))
            val ended=withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.ENDED}}
            owner.checkpoint()
            assertFalse(ended.plan!!.resampled);assertFalse(ended.plan.bitPerfect)
            assertEquals(16,ended.stream!!.format.bitResolution);assertFalse(ended.stream.bitPerfect)
            assertEquals("Only the authorized first song may reach the reduced-bit stream",listOf(256,512),output.samples)
            assertEquals(first,owner.queue.value.current!!.id)
            assertFalse(owner.onPlaybackState(ended));assertEquals(second,owner.queue.value.current!!.id)
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            assertFalse(owner.snapshot.value.conversionAllowed);assertEquals(1,output.starts)
        }finally{owner.close()}
    }
    @Test fun compatibilityBitReductionStillContinuesThroughOneStream()=runBlocking {
        val output=Output().apply{dacBits=16};val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try {
            owner.enqueue(source("first",listOf(256)));owner.enqueue(source("second",listOf(512)))
            owner.open(7);owner.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY);assertTrue(owner.play())
            withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.ENDED}}
            owner.checkpoint()
            assertEquals(listOf(256,512),output.samples);assertEquals(1,output.starts);assertEquals(1,output.drains)
            assertEquals(1,owner.queue.value.index);assertFalse(owner.snapshot.value.conversionAllowed)
        }finally{owner.close()}
    }
    private suspend fun convertedQueueEdit(edit:suspend (UsbSessionOwner)->Boolean) {
        val output=Output().apply{completed=0;holdDrain=true};val focus=Focus()
        var saved:SavedQueue?=null
        val owner=UsbSessionOwner(UsbPlaybackController{output},focus,persistence=object:QueuePersistence {
            override fun load():SavedQueue?=null
            override fun save(state:SavedQueue){saved=state}
        })
        try {
            owner.enqueue(source("current",listOf(256,512),true));owner.enqueue(source("next",listOf(768),true))
            owner.open(7);assertFalse(owner.play())
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            assertTrue(owner.play(true))
            withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.DRAINING}}
            val current=owner.queue.value.current!!.id
            assertEquals(current,owner.snapshot.value.queueId);assertTrue(owner.snapshot.value.conversionAllowed)
            assertTrue("Edit must report success after restarting the converted song",edit(owner))
            withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.DRAINING}}
            assertEquals(current,owner.queue.value.current!!.id);assertEquals(current,owner.snapshot.value.queueId)
            assertTrue(owner.snapshot.value.conversionAllowed);assertNull(owner.snapshot.value.failure)
            assertTrue(focus.held);assertEquals(2,output.starts)
            assertEquals(owner.queue.value.items.map{it.id},saved!!.items.map{it.id})
            assertTrue(owner.pause());val starts=output.starts
            assertTrue(owner.enqueue(source("paused append",listOf(1024),true)))
            assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state);assertEquals(starts,output.starts)
            assertFalse(owner.snapshot.value.conversionAllowed)
        }finally{owner.close()}
    }
    @Test fun appendingDuringConvertedGaplessPlayKeepsCurrentAuthorization()=runBlocking {
        convertedQueueEdit { it.libraryQueue(true){source("added",listOf(1024),true)} }
    }
    @Test fun playNextDuringConvertedGaplessPlayKeepsCurrentAuthorization()=runBlocking {
        convertedQueueEdit { it.libraryQueue(true,next=true) {source("next",listOf(1024),true)} }
    }
    @Test fun movingDuringConvertedGaplessPlayKeepsCurrentAuthorization()=runBlocking {
        convertedQueueEdit { it.moveQueue(it.queue.value.current!!.id,1) }
    }
    @Test fun removingAnotherSongDuringConvertedGaplessPlayKeepsCurrentAuthorization()=runBlocking {
        convertedQueueEdit { it.removeQueue(it.queue.value.items[1].id) }
    }
    @Test fun removingCurrentConvertedSongCannotAuthorizeItsReplacement()=runBlocking {
        val output=Output().apply{completed=0;holdDrain=true};val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try {
            owner.enqueue(source("current",listOf(256),true));owner.enqueue(source("next",listOf(512),true))
            owner.open(7);assertTrue(owner.play(true))
            withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.DRAINING}}
            val next=owner.queue.value.items[1].id
            assertFalse(owner.removeQueue(owner.queue.value.current!!.id))
            assertEquals(next,owner.queue.value.current!!.id);assertEquals(1,output.starts)
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            assertFalse(owner.snapshot.value.conversionAllowed)
        }finally{owner.close()}
    }
    @Test fun editCannotTransferAuthorizationWhenStopCompletesTheNextSongBoundary()=runBlocking {
        val output=Output().apply{completed=0;holdDrain=true;stopCompleted=1}
        val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try {
            owner.enqueue(source("converted",listOf(256),true));owner.enqueue(source("lossless",listOf(512)))
            owner.open(7);assertTrue(owner.play(true))
            withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.DRAINING}}
            val next=owner.queue.value.items[1].id
            assertTrue(owner.snapshot.value.conversionAllowed)
            assertTrue(owner.enqueue(source("added",listOf(768),true)))
            assertEquals(next,owner.queue.value.current!!.id)
            assertFalse(owner.snapshot.value.conversionAllowed)
        }finally{owner.close()}
    }
    @Test fun pauseAndShutdownAdoptBoundaryCompletedDuringStop()=runBlocking {
        for(operation in listOf("pause","shutdown","reopen")) {
            val output=Output().apply{completed=0;holdDrain=true;stopCompleted=4}
            var saved:SavedQueue?=null
            val owner=UsbSessionOwner(UsbPlaybackController{output},Focus(),persistence=object:QueuePersistence {
                override fun load():SavedQueue?=null
                override fun save(state:SavedQueue){saved=state}
            })
            try {
                owner.enqueue(source("a",listOf(256,512,768)));owner.enqueue(source("b",listOf(1024,1280)))
                owner.open(7);owner.play()
                withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.DRAINING}}
                assertEquals(0,owner.queue.value.index)
                assertTrue(when(operation){"shutdown"->owner.shutdown();"reopen"->owner.open(8);else->owner.pause()})
                assertEquals(1,owner.queue.value.index)
                assertEquals(1,saved!!.index)
                assertEquals(20L,saved!!.positionUs)
            }finally{owner.close()}
        }
    }
}
