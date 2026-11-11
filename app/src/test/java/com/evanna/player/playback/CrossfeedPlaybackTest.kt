package com.evanna.player.playback

import com.evanna.player.equalizer.*
import com.evanna.player.user.DacProfile
import com.evanna.player.usb.dacIdentity
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CrossfeedPlaybackTest {
    private fun profile(level:Crossfeed=Crossfeed.STRONG)=DacProfile(dacIdentity(1,2,"DAC"),crossfeed=level)
    /** Plans like the native streamer: crossfeed processes stereo only. [dropsCrossfeed] models an output that loses the setting. */
    private class Output(val dropsCrossfeed:Boolean=false):UsbPlaybackOutput {
        override val description="Synthetic crossfeed output"
        var level=Crossfeed.OFF;var starts=0
        private fun processed(source:PcmFormat)=!dropsCrossfeed && level!=Crossfeed.OFF && source.channels==2
        override fun setEqualizer(bands:List<EqBand>,preampDb:Float)=Unit
        override fun setCrossfeed(level:Crossfeed){this.level=level}
        override fun plan(source:PcmFormat)=PlaybackPlan(48000,24,false,!processed(source),processed(source))
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo {
            starts++
            return StreamInfo(source.sampleRate,source.significantBits,source.channels,
                DacFormat(1,1,1,source.channels,3,24,DacFormat.SyncType.SYNC,1024,listOf(48000),48000,48000),48000,48000,
                !processed(source),false,processed(source))
        }
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(0,0,0,false)
        override fun write(buffer:ByteBuffer,frames:Int):Int {Thread.sleep(5);return frames}
        override fun stop()=true
        override fun close()=Unit
    }
    private fun track(channels:Int=2)=object:PcmTrack {
        override val id="crossfeed-fixture"
        override val format=TrackPcmFormat(48000,24,channels,24,"FLAC",true,1_000_000)
        override fun open():PcmReader {val pcm=format;return object:PcmReader {
            override val format=pcm
            override fun read(target:ByteBuffer,maxFrames:Int):Int {target.putLong(0,0L);return 1}
            override fun requestCancel()=Unit
            override fun close()=Unit
        }}
        override fun close()=Unit
    }

    @Test fun offByDefaultAndPresetNamesFollowTheirStrength() {
        assertEquals(Crossfeed.OFF,DacProfile(dacIdentity(1,2,"DAC")).crossfeed)
        assertEquals(Crossfeed.OFF,storedCrossfeed(null));assertEquals(Crossfeed.OFF,storedCrossfeed("LOUDEST"))
        assertEquals(Crossfeed.STRONG,storedCrossfeed("STRONG"))
        // The feed level is how much quieter the crossed sound is: Strong crosses the most.
        assertEquals(listOf(650 to 9.5f,700 to 6f,700 to 4.5f),listOf(Crossfeed.LIGHT,Crossfeed.MEDIUM,Crossfeed.STRONG).map {it.cutHz to it.feedDb})
        assertTrue(playbackSignalStatus(UsbPlaybackSnapshot(route=PlaybackRoute.ANDROID,crossfeed=Crossfeed.STRONG)).contains("Crossfeed: Off on Android audio"))
    }

    @Test fun strictAsksFirstAndAuthorizedOrCompatibilityPlayIsReportedAsProcessed()=runBlocking {
        val output=Output();val controller=UsbPlaybackController {output}
        try {
            controller.open(7,profile());assertFalse(controller.start(track()))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,controller.snapshot.value.failure);assertEquals(0,output.starts)
            assertEquals(Crossfeed.STRONG,output.level);assertTrue(controller.snapshot.value.crossfeedProcessed)
            assertTrue(controller.start(track(),true));assertFalse(controller.snapshot.value.eqProcessed)
            assertTrue(controller.snapshot.value.stream!!.processed);assertFalse(controller.snapshot.value.stream!!.bitPerfect)
            assertTrue(playbackSignalStatus(controller.snapshot.value).contains("crossfeed strong · 700 Hz · 4.5 dB"))
            assertTrue(controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY));assertTrue(controller.start(track()))
            assertTrue(controller.snapshot.value.stream!!.processed);assertFalse(controller.snapshot.value.conversionAllowed)
        }finally {controller.close()}
    }

    @Test fun monoSongsBypassAndStayBitPerfectInStrict()=runBlocking {
        val output=Output();val controller=UsbPlaybackController {output}
        try {
            controller.open(7,profile());assertTrue(controller.start(track(1)))
            assertTrue(controller.snapshot.value.stream!!.bitPerfect);assertFalse(controller.snapshot.value.crossfeedProcessed)
            assertTrue(playbackSignalStatus(controller.snapshot.value).contains("bypassed: stereo songs only"))
        }finally {controller.close()}
    }

    @Test fun anOutputThatDropsTheSettingFailsClosed()=runBlocking {
        val controller=UsbPlaybackController {Output(dropsCrossfeed=true)}
        try {
            controller.open(7,profile());assertFalse(controller.start(track(),true))
            assertEquals(UsbPlaybackFailure.STREAM_MISMATCH,controller.snapshot.value.failure)
        }finally {controller.close()}
    }

    @Test fun changesAreProfileBoundSavedAndPauseWithoutReplay()=runBlocking {
        var saves=0;var save=true
        val output=Output()
        val controller=UsbPlaybackController(initialUsbPolicy=UsbPlaybackPolicy.COMPATIBILITY,saveCrossfeed={saves++;save}){output}
        val profile=profile(Crossfeed.OFF)
        try {
            assertFalse(controller.setCrossfeed(Crossfeed.LIGHT,null))
            controller.open(7,profile);assertTrue(controller.start(track()));assertTrue(controller.snapshot.value.stream!!.bitPerfect)
            assertFalse(controller.setCrossfeed(Crossfeed.LIGHT,"old-profile"))
            assertEquals(0,saves);assertEquals(UsbPlaybackState.PLAYING,controller.snapshot.value.state)
            save=false;assertFalse(controller.setCrossfeed(Crossfeed.LIGHT,profile.identity.key))
            assertEquals(Crossfeed.OFF,controller.snapshot.value.crossfeed)
            save=true;assertTrue(controller.setCrossfeed(Crossfeed.LIGHT,profile.identity.key))
            assertEquals(UsbPlaybackState.PAUSED,controller.snapshot.value.state);assertEquals(1,output.starts)
            assertEquals(Crossfeed.LIGHT,controller.snapshot.value.crossfeed);assertEquals(2,saves)
            assertTrue(playbackSignalStatus(controller.snapshot.value).contains("crossfeed light · waiting for the next playback start"))
            assertTrue(controller.start(track()));assertEquals(Crossfeed.LIGHT,output.level)
            assertTrue(controller.snapshot.value.stream!!.processed)
        }finally {controller.close()}
    }
}
