package com.evanna.player.playback

import java.nio.ByteBuffer
import com.truesample.usbaudio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ShuffleRepeatTest {
    private val format=TrackPcmFormat(48000,16,2,16,"FLAC",true,1_000_000)
    private class Focus:PlaybackFocus{
        override var held=false
        override fun request():Boolean{held=true;return true}
        override fun abandon(){held=false}
    }
    private class Store:QueuePersistence{
        var value:SavedQueue?=null
        var fail=false
        override fun load()=value
        override fun save(state:SavedQueue){check(!fail);value=state}
    }
    private class Output:UsbPlaybackOutput{
        override val description="Synthetic shuffle sink"
        var starts=0
        override fun plan(source:PcmFormat)=PlaybackPlan(48000,source.significantBits,false,true)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo{starts++;return StreamInfo(48000,source.significantBits,2,DacFormat(1,1,1,2,(source.significantBits+7)/8,source.significantBits,DacFormat.SyncType.SYNC,512,listOf(48000),48000,48000),48000,48000,true,false)}
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(0,0,0,false)
        override fun write(buffer:ByteBuffer,frames:Int)=frames
        override fun drain()=true
        override fun stop()=true
        override fun close()=Unit
    }
    private fun source(name:String,processed:Boolean=false):QueueSource {
        val pcm=if(processed)format.copy(codec="MP3",significantBits=32,sourceBits=null,strictEligible=false) else format
        return QueueSource("a".repeat(64),name,null,null,1_000_000){object:PcmTrack{
        override val id="evanna:local:$name"
        override val format=pcm
        override fun open()=object:PcmReader{
            override val format=pcm
            var read=false
            override fun read(target:ByteBuffer,maxFrames:Int):Int{if(read)return 0;read=true;target.putInt(0,0);target.putInt(4,0);return 1}
            override fun requestCancel()=Unit
            override fun close()=Unit
        }
        override fun close()=Unit
    }}
    }
    private suspend fun ended(owner:UsbSessionOwner)=withTimeout(5000){owner.snapshot.first{it.state==UsbPlaybackState.ENDED}}

    @Test fun shufflePreservesBaseOrderCurrentAndDuplicatesAndRestoresItsPermutation()=runBlocking{
        val store=Store();val output=Output();val owner=UsbSessionOwner(UsbPlaybackController{output},Focus(),persistence=store,shuffleOrder={it.reversed()})
        try{
            repeat(4){owner.enqueue(source(if(it==3)"same" else "$it"))}
            val base=owner.queue.value.items.map{it.id};val current=owner.queue.value.current!!.id
            assertTrue(owner.setShuffle(true))
            val order=owner.queue.value.playOrder
            assertEquals(listOf(base[0],base[3],base[2],base[1]),order)
            assertEquals(base,owner.queue.value.items.map{it.id});assertEquals(current,owner.queue.value.current!!.id);assertEquals(0,output.starts)
            owner.skipQueue(1);assertEquals(base[3],owner.queue.value.current!!.id)
            owner.skipQueue(-1);assertEquals(current,owner.queue.value.current!!.id)
            val restored=UsbSessionOwner(UsbPlaybackController{error("No output")},Focus(),persistence=store,restoreSource={source(it.title)})
            restored.initialize();assertTrue(restored.queue.value.shuffleEnabled);assertEquals(order,restored.queue.value.playOrder)
            owner.setShuffle(false);assertEquals(base,owner.queue.value.playOrder);assertEquals(current,owner.queue.value.current!!.id)
        }finally{owner.close()}
    }
    @Test fun repeatSongReplaysAtEofButManualNavigationMovesAndRepeatQueueWraps()=runBlocking{
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try{
            owner.enqueue(source("a"));owner.enqueue(source("b"));owner.setRepeat(QueueRepeat.SONG);owner.open(7);owner.play()
            val first=ended(owner);assertTrue(owner.onPlaybackState(first));assertEquals(0,owner.queue.value.index)
            val again=ended(owner);assertEquals(2,output.starts)
            owner.pause();assertFalse(owner.onPlaybackState(again))
            assertTrue(owner.skipQueue(1));assertEquals(1,owner.queue.value.index);assertFalse(owner.skipQueue(1))
            owner.setRepeat(QueueRepeat.QUEUE);assertTrue(owner.skipQueue(1));assertEquals(0,owner.queue.value.index)
            assertTrue(owner.skipQueue(-1));assertEquals(1,owner.queue.value.index)
            owner.play();assertTrue(owner.onPlaybackState(ended(owner)));assertEquals(0,owner.queue.value.index)
        }finally{owner.close()}
    }
    @Test fun modeChangesAfterEndAndLostFocusCannotAutoplayAndConversionDoesNotRepeat()=runBlocking{
        val output=Output();val focus=Focus();val owner=UsbSessionOwner(UsbPlaybackController{output},focus)
        try{
            owner.enqueue(source("a",true));owner.setRepeat(QueueRepeat.SONG);owner.open(7);assertTrue(owner.play(true))
            assertFalse(owner.onPlaybackState(ended(owner)));assertEquals(1,output.starts)
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            owner.replaceQueue(source("lossless"));owner.setRepeat(QueueRepeat.OFF);owner.play();val end=ended(owner)
            owner.setRepeat(QueueRepeat.QUEUE);owner.setShuffle(true)
            assertFalse(owner.onPlaybackState(end));assertEquals(2,output.starts)
            owner.play();val last=ended(owner);focus.abandon();assertFalse(owner.onPlaybackState(last));assertEquals(3,output.starts)
        }finally{owner.close()}
    }
    @Test fun editsKeepPermutationAndStopKeepsSettingsWithSaveFailureReported()=runBlocking{
        val store=Store();val owner=UsbSessionOwner(UsbPlaybackController{Output()},Focus(),persistence=store,shuffleOrder={it.reversed()})
        try{
            repeat(3){owner.enqueue(source("$it"))};owner.setShuffle(true);owner.setRepeat(QueueRepeat.QUEUE)
            val first=owner.queue.value.current!!.id;val order=owner.queue.value.playOrder
            owner.moveQueue(first,2);assertEquals(order,owner.queue.value.playOrder);assertEquals(first,owner.queue.value.current!!.id)
            owner.removeQueue(first);assertEquals(order[1],owner.queue.value.current!!.id)
            owner.enqueue(source("new"));assertEquals(owner.queue.value.items.map{it.id}.toSet(),owner.queue.value.playOrder.toSet())
            store.fail=true;owner.setRepeat(QueueRepeat.SONG);assertTrue(owner.queue.value.saveFailed)
            store.fail=false;owner.checkpoint();assertEquals(QueueRepeat.SONG,store.value!!.repeat)
            owner.close();assertTrue(store.value!!.items.isEmpty());assertTrue(store.value!!.shuffleEnabled);assertEquals(QueueRepeat.SONG,store.value!!.repeat)
        }finally{store.fail=false;owner.close()}
    }

    @Test fun upcomingMovesFollowPlaybackOrderAndStayAfterTheCurrentSong() = runBlocking {
        val store = Store()
        val owner = UsbSessionOwner(UsbPlaybackController { Output() }, Focus(), persistence = store, shuffleOrder = { it.reversed() })
        try {
            repeat(4) { owner.enqueue(source("$it")) }
            val (first, current, third, fourth) = owner.queue.value.items.map { it.id }
            assertTrue(owner.selectQueue(current))
            assertFalse(owner.moveQueue(third, current))
            assertTrue(owner.moveQueue(fourth, third))
            val base = listOf(first, current, fourth, third)
            assertEquals(base, owner.queue.value.items.map { it.id })

            assertTrue(owner.setShuffle(true))
            assertEquals(listOf(current, third, fourth, first), owner.queue.value.playOrder)
            assertFalse(owner.moveQueue(third, current))
            assertFalse(owner.moveQueue(first, third))
            assertTrue(owner.moveQueue(fourth, third))
            val shuffled = listOf(current, fourth, third, first)
            assertEquals(shuffled, store.value!!.playOrder)
            assertEquals(base, owner.queue.value.items.map { it.id })
            assertTrue(owner.skipQueue(1))
            assertEquals(fourth, owner.queue.value.current?.id)
            assertFalse(owner.moveQueue(third, fourth))
            assertEquals(shuffled, owner.queue.value.playOrder)
        } finally { owner.close() }
    }
}
