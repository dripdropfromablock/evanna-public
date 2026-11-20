package com.evanna.player.playback

import com.evanna.player.network.CancelScope
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourceOpeningCancellationTest {
    @Test fun pauseCancelsQueueSourcePreparationBeforeOwnerLock()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val controller=UsbPlaybackController {Output()}
        val owner=UsbSessionOwner(controller,object:PlaybackFocus {
            override val held=false;override fun request()=true;override fun abandon()=Unit
        })
        fun blocked(cancel:CancelScope):PcmTrack {
            val unregister=cancel.watch {release.countDown()}
            try {entered.countDown();check(release.await(10,TimeUnit.SECONDS));cancel.checkActive();error("Cancelled prepare returned")}
            finally {unregister()}
        }
        val source=QueueSource(null,"Remote",null,null,null,prepare={blocked(it)}) {blocked(CancelScope())}
        assertTrue(controller.open(7))
        val selecting=async(Dispatchers.IO){owner.replaceQueue(source)}
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertTrue(withTimeout(650){owner.pause()})
            assertFalse(selecting.await())
        }finally {release.countDown();selecting.join();owner.shutdown()}
    }
    @Test fun pauseCancelsBlockedSourceOpeningBeforeWaitingForCommandLock()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val controller=UsbPlaybackController {Output()}
        val track=object:PcmTrack {
            override val id="test:network";override val format=TrackPcmFormat(48000,16,2,16,"FLAC",true,1000000)
            override fun open():PcmReader=open(CancelScope())
            override fun open(cancel:CancelScope):PcmReader {
                val unregister=cancel.watch {release.countDown()}
                try {entered.countDown();check(release.await(10,TimeUnit.SECONDS));cancel.checkActive();error("Cancelled open returned")}
                finally {unregister()}
            }
            override fun close()=Unit
        }
        assertTrue(controller.open(7))
        val starting=async(Dispatchers.IO){controller.start(track)}
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertTrue(withTimeout(650){controller.pause()})
            assertFalse(starting.await())
            assertEquals(UsbPlaybackState.PAUSED,controller.snapshot.value.state)
        }finally {release.countDown();starting.join();controller.close()}
    }
    @Test fun pauseCancelsTheInitialSeekBeforeStartingOutput()=runBlocking {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val controller=UsbPlaybackController {Output()}
        val seekFormat=TrackPcmFormat(48000,16,2,16,"FLAC",true,1000000)
        val track=object:PcmTrack {
            override val id="seek-fixture"
            override val seekable=true
            override val format=seekFormat
            override fun open():PcmReader=object:PcmReader {
                override val format=seekFormat
                override fun seekTo(positionUs:Long){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                override fun read(target:ByteBuffer,maxFrames:Int)=error("Output must stay paused")
                override fun requestCancel(){release.countDown()}
                override fun close()=Unit
            }
            override fun close()=Unit
        }
        assertTrue(controller.open(7))
        val starting=async(Dispatchers.IO){controller.start(track,positionUs=10000)}
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            assertTrue(withTimeout(650){controller.pause()})
            assertFalse(starting.await())
            assertEquals(UsbPlaybackState.PAUSED,controller.snapshot.value.state)
            assertNull(controller.snapshot.value.failure)
        }finally {release.countDown();starting.join();controller.close()}
    }
    private class Output:UsbPlaybackOutput {
        override val description="cancel probe"
        override fun plan(source:PcmFormat)=PlaybackPlan(48000,16,false,true)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo=error("No PCM may start")
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(0,0,0,false)
        override fun write(buffer:ByteBuffer,frames:Int)=error("No PCM may be written")
        override fun stop()=true
        override fun close()=Unit
    }
}
