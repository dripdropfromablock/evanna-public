package com.evanna.player.playback

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import com.truesample.usbaudio.*
import com.evanna.player.decoder.MusicTags

class QueueRestorationTest {
    @Test fun embeddedTagsSurviveEnqueueSaveAndUnavailableRestoreWithoutOpeningSources()=runBlocking {
        val tags=MusicTags("Album artist",3,12,2,3,2024,"Jazz","Composer",true)
        val store=Store();val owner=UsbSessionOwner(UsbPlaybackController {error("Output opened")},Focus(),persistence=store)
        assertTrue(owner.enqueue(QueueSource("a".repeat(64),"Song","Artist","Album",20_000_000,musicTags=tags) {Track()}))
        assertEquals(tags,owner.queue.value.current?.musicTags);owner.checkpoint()
        assertEquals(tags,store.saved!!.items.single().musicTags);owner.shutdown()
        val restored=UsbSessionOwner(UsbPlaybackController {error("Output opened")},Focus(),persistence=store)
        try {
            restored.initialize();assertEquals(tags,restored.queue.value.current?.musicTags)
            assertEquals(tags,restored.queueSource(restored.queue.value.current!!.id)?.musicTags)
            assertNull(restored.selectedTrack);assertNull(restored.snapshot.value.route)
        } finally {restored.shutdown()}
    }
    @Test fun persistedTagsRejectUnboundedOrInconsistentValues() {
        for(tags in listOf(MusicTags(albumArtist="a".repeat(513)),MusicTags(trackNumber=0),MusicTags(totalDiscs=1000),
            MusicTags(year=999),MusicTags(trackNumber=3,totalTracks=2),MusicTags(discNumber=2,totalDiscs=1)))
            assertTrue(tags.toString(),runCatching {first.copy(musicTags=tags)}.isFailure)
    }
    private val first = SavedQueueEntry("evanna:local:queue:first", "a".repeat(64), null, "First", "Artist", null, 20_000_000)
    private val second = first.copy(id="evanna:local:queue:second",title="Second")
    private class Store(var saved: SavedQueue? = null) : QueuePersistence {
        var fail=false;var writes=0
        override fun load()=saved
        override fun save(state:SavedQueue){if(fail)throw java.io.IOException();saved=state;writes++}
    }
    private class Focus:PlaybackFocus{
        override var held=false
        override fun request():Boolean{held=true;return true}
        override fun abandon(){held=false}
    }
    private val format=TrackPcmFormat(48000,16,2,16,"FLAC",true,20_000_000)
    private inner class Track:PcmTrack{
        override val id="evanna:local:test"
        override val format=this@QueueRestorationTest.format
        override val seekable=true
        var seeks=mutableListOf<Long>();var closes=0
        override fun open()=object:PcmReader{
            override val format=this@Track.format
            override fun seekTo(positionUs:Long){seeks+=positionUs}
            override fun read(target:ByteBuffer,maxFrames:Int)=0
            override fun requestCancel()=Unit
            override fun close()=Unit
        }
        override fun close(){closes++}
    }
    private class Output:UsbPlaybackOutput{
        override val description="Synthetic restore output"
        var starts=0
        override fun plan(source:PcmFormat)=PlaybackPlan(48000,16,false,true)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo{starts++;return StreamInfo(48000,16,2,DacFormat(1,1,1,2,2,16,DacFormat.SyncType.SYNC,512,listOf(48000),48000,48000),48000,48000,true,false)}
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(0,0,0,false)
        override fun write(buffer:ByteBuffer,frames:Int)=frames
        override fun drain()=true
        override fun stop()=true
        override fun close()=Unit
    }
    private fun source(saved:SavedQueueEntry,create:()->PcmTrack)=QueueSource(saved.libraryId,saved.title,saved.artist,saved.album,saved.durationUs,create=create)

    @Test fun contextAtCapRestoresExactOrderAndSelectionPausedEvenAfterSaveFailure()=runBlocking {
        val store=Store();val output=Output();val owner=UsbSessionOwner(UsbPlaybackController {output},Focus(),persistence=store,shuffleOrder={it.reversed()})
        var prepares=0
        try {
            val sources=List(1000) {n->QueueSource(n.toString(16).padStart(64,'0'),"Track $n",null,null,format.durationUs) {prepares++;Track()} }
            store.fail=true
            assertTrue(owner.libraryContext {ContextQueue(sources,800,true)})
            assertTrue(owner.queue.value.saveFailed);assertEquals(1,prepares);assertEquals(0,output.starts)
            store.fail=false;owner.setRepeat(QueueRepeat.QUEUE);owner.checkpoint()
            val saved=checkNotNull(store.saved);assertFalse(owner.queue.value.saveFailed)
            val restored=UsbSessionOwner(UsbPlaybackController {output},Focus(),persistence=store,restoreSource={source(it) {prepares++;Track()}})
            try {
                restored.initialize()
                assertEquals(saved.items.map {it.id},restored.queue.value.items.map {it.id})
                assertEquals(800,restored.queue.value.index);assertEquals(saved.playOrder,restored.queue.value.playOrder)
                assertEquals(QueueRepeat.QUEUE,restored.queue.value.repeat);assertTrue(restored.queue.value.shuffleEnabled)
                assertEquals(1,prepares);assertEquals(0,output.starts);assertNull(restored.snapshot.value.route)
            } finally {restored.shutdown()}
        } finally {store.fail=false;owner.close()}
    }
    @Test fun restoresDuplicatesCurrentAndPositionWithoutOpeningOrFocus()=runBlocking{
        val store=Store(SavedQueue(listOf(first,second),1,7_000_000));val focus=Focus();var prepares=0;var outputs=0
        val owner=UsbSessionOwner(UsbPlaybackController{outputs++;Output()},focus,persistence=store,restoreSource={source(it){prepares++;Track()}})
        try{
            owner.initialize()
            assertEquals(listOf(first.id,second.id),owner.queue.value.items.map{it.id})
            assertEquals(1,owner.queue.value.index);assertEquals(7_000_000L,owner.positionUs)
            assertEquals(0,prepares);assertEquals(0,outputs);assertFalse(focus.held);assertNull(owner.snapshot.value.route)
            assertTrue(owner.moveQueue(first.id,1));assertEquals(second.id,store.saved!!.items[store.saved!!.index].id)
            assertEquals(7_000_000L,store.saved!!.positionUs)
            assertTrue(owner.close());assertTrue(store.saved!!.items.isEmpty())
        }finally{owner.close()}
    }
    @Test fun missingCurrentStaysInQueueAndCanRetryAtSavedPosition()=runBlocking{
        val store=Store(SavedQueue(listOf(first,second),0,4_000_000));var available=false;val track=Track();val output=Output()
        val owner=UsbSessionOwner(UsbPlaybackController{output},Focus(),persistence=store,restoreSource={source(it){check(available);track}})
        try{
            owner.initialize();owner.open(7);assertFalse(owner.play())
            assertEquals(2,owner.queue.value.items.size);assertEquals(4_000_000L,owner.positionUs)
            available=true;assertTrue(owner.play());assertEquals(listOf(4_000_000L),track.seeks)
            assertEquals(1,output.starts)
        }finally{owner.close()}
    }
    @Test fun shutdownKeepsSavedQueueWhileStopClearsItAndSaveFailureIsVisible()=runBlocking{
        val store=Store(SavedQueue(listOf(first,second),1,3_000_000));val output=Output()
        val owner=UsbSessionOwner(UsbPlaybackController{output},Focus(),persistence=store,restoreSource={source(it){Track()}})
        try{
            owner.initialize();assertTrue(owner.shutdown())
            assertEquals(2,store.saved!!.items.size);assertEquals(3_000_000L,store.saved!!.positionUs)
            store.fail=true;assertTrue(owner.moveQueue(first.id,1));assertTrue(owner.queue.value.saveFailed)
            store.fail=false;owner.checkpoint();assertFalse(owner.queue.value.saveFailed)
            assertEquals(second.id,store.saved!!.items[store.saved!!.index].id)
            assertTrue(owner.close());assertTrue(store.saved!!.items.isEmpty())
        }finally{store.fail=false;owner.close()}
    }
    @Test fun corruptLoadDoesNotGetOverwrittenByIdleOrOutputCommands()=runBlocking{
        var saves=0
        val store=object:QueuePersistence{override fun load():SavedQueue?=throw java.io.IOException();override fun save(state:SavedQueue){saves++}}
        val owner=UsbSessionOwner(UsbPlaybackController{Output()},Focus(),persistence=store)
        try{
            owner.initialize();owner.checkpoint();owner.open(7)
            assertTrue(owner.queue.value.restoreFailed);assertEquals(0,saves)
            owner.close();assertEquals(1,saves)
        }finally{owner.close()}
    }
    @Test fun malformedSavedQueueIsRejectedWithoutInventingAnIndexOrTruncatingIt(){
        assertTrue(runCatching{SavedQueue(listOf(first,first),0,0)}.isFailure)
        assertTrue(runCatching{SavedQueue(listOf(first),5,0)}.isFailure)
        assertTrue(runCatching{SavedQueue(listOf(first),0,-1)}.isFailure)
        assertTrue(runCatching{first.copy(fileUri="https://example.com/audio")}.isFailure)
        assertTrue(runCatching{first.copy(title="x".repeat(513))}.isFailure)
    }
    @Test fun shutdownOfCompletedTrackSavesZeroForReplay()=runBlocking{
        val store=Store();val output=Output();val track=Track()
        val owner=UsbSessionOwner(UsbPlaybackController{output},Focus(),persistence=store)
        try{
            owner.enqueue(source(first){track});owner.open(7);assertTrue(owner.play())
            withTimeout(5000){while(owner.snapshot.value.state!=UsbPlaybackState.ENDED)yield()}
            assertTrue(owner.shutdown());assertEquals(0L,store.saved!!.positionUs);assertEquals(1,store.saved!!.items.size)
        }finally{owner.close()}
    }
    @Test fun outputLossShutdownKeepsSourceOrderAndFrozenAnchorForNewOwner()=runBlocking{
        val store=Store(SavedQueue(listOf(first,second),1,6_000_000))
        val owner=UsbSessionOwner(UsbPlaybackController{Output()},Focus(),persistence=store,restoreSource={source(it){Track()}})
        try{
            owner.initialize();owner.open(7);assertTrue(owner.shutdown())
            assertNull(owner.snapshot.value.route);assertEquals(6_000_000L,store.saved!!.positionUs)
            val restored=UsbSessionOwner(UsbPlaybackController{error("No automatic reopen")},Focus(),persistence=store,restoreSource={source(it){Track()}})
            restored.initialize();assertEquals(listOf(first.id,second.id),restored.queue.value.items.map{it.id});assertEquals(1,restored.queue.value.index)
            assertEquals(6_000_000L,restored.positionUs);assertNull(restored.selectedTrack)
        }finally{owner.close()}
    }
    @Test fun pauseAfterCompletedTrackCannotOverwriteSavedReplayPositionWithEof()=runBlocking{
        val store=Store();val owner=UsbSessionOwner(UsbPlaybackController{Output()},Focus(),persistence=store)
        try{
            owner.enqueue(source(first){Track()});owner.open(7);owner.seekTo(5_000_000);owner.play()
            withTimeout(5000){while(owner.snapshot.value.state!=UsbPlaybackState.ENDED)yield()}
            owner.onPlaybackState(owner.snapshot.value);assertEquals(0L,store.saved!!.positionUs)
            owner.pause();assertEquals(0L,store.saved!!.positionUs)
            owner.shutdown();assertEquals(0L,store.saved!!.positionUs)
        }finally{owner.close()}
    }
    @Test fun preparedSourceWithoutOutputKeepsPausedAnchorThroughPauseAndShutdown()=runBlocking{
        for(pauseFirst in listOf(false,true)){
            val store=Store();val owner=UsbSessionOwner(UsbPlaybackController{error("No output expected")},Focus(),persistence=store)
            try{
                owner.enqueue(source(first){Track()});assertTrue(owner.seekTo(2_000_000))
                assertEquals(2_000_000L,owner.positionUs)
                if(pauseFirst){assertTrue(owner.pause());assertEquals(2_000_000L,owner.positionUs)}
                assertTrue(owner.shutdown());assertEquals(2_000_000L,store.saved!!.positionUs)
            }finally{owner.close()}
        }
    }
    @Test fun choosingOutputAfterPreparedPausedSeekStillPlaysFromThatAnchor()=runBlocking{
        val track=Track();val owner=UsbSessionOwner(UsbPlaybackController{Output()},Focus())
        try{
            owner.enqueue(source(first){track});owner.seekTo(2_000_000);owner.pause();owner.open(7)
            assertTrue(owner.play());assertEquals(listOf(2_000_000L),track.seeks)
        }finally{owner.close()}
    }
}
