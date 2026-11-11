package com.evanna.player.playback

import com.evanna.player.equalizer.*
import com.evanna.player.user.DacProfile
import com.evanna.player.usb.dacIdentity
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class EqualizerPlaybackTest {
    private val config=EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,1000f,6f,1f)))
    private fun profile(config:EqualizerConfig=this.config)=DacProfile(dacIdentity(1,2,"DAC"),equalizer=EqualizerPreset(1,"Fixture",config,1),eqEnabled=true)
    private class Output(val rate:Int=48000):UsbPlaybackOutput {
        override val description="Synthetic EQ output"
        var bands=emptyList<EqBand>();var preamp=0f;var starts=0;var wrongProcessing=false
        var blockWrites=true;var failEq=false
        var drainAction:()->Unit={}
        val writeEntered=CountDownLatch(1)
        private var stopped=CountDownLatch(1)
        private val processed get()=preamp!=0f || bands.any {kotlin.math.abs(it.gainDb)>=0.01 && it.frequencyHz<rate*0.49}
        override fun setEqualizer(bands:List<EqBand>,preampDb:Float){check(!failEq);this.bands=bands.toList();preamp=preampDb}
        override fun plan(source:PcmFormat)=PlaybackPlan(rate,24,rate!=source.sampleRate,rate==source.sampleRate && source.significantBits<=24 && !processed,processed)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo {
            starts++;stopped=CountDownLatch(1)
            return StreamInfo(source.sampleRate,source.significantBits,2,DacFormat(1,1,1,2,3,24,DacFormat.SyncType.SYNC,1024,listOf(rate),rate,rate),rate,rate,
                rate==source.sampleRate && source.significantBits<=24 && !processed,rate!=source.sampleRate,processed && !wrongProcessing)
        }
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(0,0,0,false)
        override fun write(buffer:ByteBuffer,frames:Int):Int {writeEntered.countDown();if(!blockWrites){Thread.sleep(5);return frames};check(stopped.await(5,TimeUnit.SECONDS));return -1}
        override fun stop():Boolean{stopped.countDown();return true}
        override fun drain():Boolean{drainAction();return true}
        override fun close()=Unit
    }
    private fun track(rate:Int=48000,end:Boolean=false)=object:PcmTrack {
        override val id="eq-fixture"
        override val format=TrackPcmFormat(rate,24,2,24,"FLAC",true,1_000_000)
        override fun open():PcmReader {val pcm=format;return object:PcmReader {
            override val format=pcm
            var reads=0
            override fun read(target:ByteBuffer,maxFrames:Int):Int {if(end && reads++>0)return 0;target.putLong(0,0L);return 1}
            override fun requestCancel()=Unit
            override fun close()=Unit
        }}
        override fun close()=Unit
    }
    @Test fun settingsRejectStaleEditorsBeforeSavingAndNeverStartPlayback()=runBlocking {
        val output=Output();val controller=UsbPlaybackController {output};val profile=profile()
        var saves=0
        try {
            controller.open(7,profile)
            val preset=profile.equalizer!!
            suspend fun change(key:String?,revision:Long,save:Boolean=true)=controller.setEqualizer(key,preset.id,revision,preset.config,false) {saves++;preset.takeIf {save}}
            assertFalse(change("old-profile",1));assertFalse(change(profile.identity.key,2));assertEquals(0,saves)
            assertFalse(change(profile.identity.key,1,false));assertTrue(controller.snapshot.value.eqEnabled)
            assertTrue(change(profile.identity.key,1));assertFalse(controller.snapshot.value.eqEnabled);assertEquals(0,output.starts)
            controller.close();assertTrue(change(profile.identity.key,1));assertEquals(UsbPlaybackState.CLOSED,controller.snapshot.value.state)
        }finally {controller.close()}
    }
    @Test fun liveEditsKeepTheStreamAndSafeHeadroomButFailuresStopAudio()=runBlocking {
        val output=Output().apply {blockWrites=false};val controller=UsbPlaybackController {output};val profile=profile()
        val changed=profile.equalizer!!.copy(config=EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,1000f,9f,1f))),revision=2)
        try {
            controller.open(7,profile);assertTrue(controller.start(track(),true))
            assertTrue(controller.setEqualizer(profile.identity.key,1,1,changed.config,true){changed})
            assertEquals(1,output.starts);assertEquals(UsbPlaybackState.PLAYING,controller.snapshot.value.state)
            assertTrue(output.preamp<=-9f);assertEquals(changed,controller.snapshot.value.equalizer)
            assertFalse(controller.setEqualizer(profile.identity.key,1,2,config,true){null})
            assertEquals(changed,controller.snapshot.value.equalizer);assertEquals(UsbPlaybackState.PLAYING,controller.snapshot.value.state)
            output.failEq=true
            assertFalse(controller.setEqualizer(profile.identity.key,1,2,config,true){changed.copy(config=config,revision=3)})
            assertEquals(UsbPlaybackState.ERROR,controller.snapshot.value.state)
        }finally {controller.close()}
    }
    @Test fun onOffChangesPauseForReplanningWithoutStartingOrGrantingConversion()=runBlocking {
        val output=Output().apply {blockWrites=false};val controller=UsbPlaybackController {output};val profile=profile()
        val preset=profile.equalizer!!
        try {
            controller.open(7,profile);assertTrue(controller.start(track(),true))
            assertTrue(controller.setEqualizer(profile.identity.key,1,1,preset.config,false){preset})
            assertEquals(UsbPlaybackState.PAUSED,controller.snapshot.value.state);assertEquals(1,output.starts)
            assertFalse(controller.snapshot.value.conversionAllowed);assertTrue(controller.start(track()))
            assertFalse(controller.snapshot.value.stream!!.processed);assertTrue(controller.snapshot.value.stream!!.bitPerfect)
            assertTrue(controller.setEqualizer(profile.identity.key,1,1,preset.config,true){preset})
            assertEquals(UsbPlaybackState.PAUSED,controller.snapshot.value.state);assertEquals(2,output.starts)
            assertFalse(controller.start(track()));assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,controller.snapshot.value.failure)
        }finally {controller.close()}
    }
    @Test fun waitingLiveEditDoesNotBlockPauseOrRestartAfterIt()=runBlocking {
        val output=Output();val profile=profile()
        val focus=object:PlaybackFocus {override var held=false;override fun request():Boolean{held=true;return true};override fun abandon(){held=false}}
        val owner=UsbSessionOwner(UsbPlaybackController {output},focus)
        try {
            owner.open(7){profile};owner.selectTrack {track()};assertTrue(owner.play(true))
            assertTrue(output.writeEntered.await(5,TimeUnit.SECONDS))
            val edit=async(Dispatchers.Default){owner.setEqualizer(profile.identity.key,1,1,config,true){profile.equalizer!!}}
            delay(30);assertFalse(edit.isCompleted)
            assertTrue(withTimeout(3000){owner.pause()});assertTrue(withTimeout(3000){edit.await()})
            assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state);assertEquals(1,output.starts);assertFalse(focus.held)
        }finally {owner.close()}
    }
    @Test fun lateActiveEditKeepsNaturalEndFocusAndQueueAdvance()=runBlocking {
        val output=Output().apply {blockWrites=false};val profile=profile()
        val focus=object:PlaybackFocus {override var held=false;override fun request():Boolean{held=true;return true};override fun abandon(){held=false}}
        val owner=UsbSessionOwner(UsbPlaybackController(initialUsbPolicy=UsbPlaybackPolicy.COMPATIBILITY){output},focus)
        try {
            owner.enqueue(QueueSource(null,"First",null,null,1_000_000){track(end=true)})
            owner.enqueue(QueueSource(null,"Next",null,null,1_000_000){track(end=true)})
            owner.open(7){profile};assertTrue(owner.play())
            val end=withTimeout(5000){owner.snapshot.first {it.state==UsbPlaybackState.ENDED}}
            val changed=profile.equalizer!!.copy(revision=2)
            assertTrue(owner.setEqualizer(profile.identity.key,1,1,changed.config,true){changed})
            assertEquals(end.generation,owner.snapshot.value.generation);assertEquals(UsbPlaybackState.ENDED,owner.snapshot.value.state)
            assertTrue(focus.held);assertTrue(owner.onPlaybackState(owner.snapshot.value))
            assertEquals("Next",owner.queue.value.current?.title);assertEquals(2,output.starts)
        }finally {owner.close()}
    }
    @Test fun strictRequiresPermissionForEqEvenWhenRateAndPrecisionMatch()=runBlocking {
        val output=Output();val controller=UsbPlaybackController {output}
        try {
            controller.open(7,profile());assertFalse(controller.start(track()))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,controller.snapshot.value.failure);assertEquals(0,output.starts)
            assertTrue(controller.snapshot.value.plan!!.processed);assertFalse(controller.snapshot.value.plan!!.bitPerfect)
            assertTrue(controller.start(track(),true));assertTrue(controller.snapshot.value.stream!!.processed)
            assertTrue(controller.snapshot.value.eqProcessed);assertTrue(controller.snapshot.value.eqPreampDb<=-6f)
            assertEquals(UsbPlaybackPolicy.STRICT,controller.snapshot.value.usbPolicy)
        }finally {controller.close()}
    }
    @Test fun compatibilityAllowsEqAndFlatOrInactiveBandsRemainAnExactBypass()=runBlocking {
        val output=Output();val controller=UsbPlaybackController {output}
        try {
            controller.open(7,profile());controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY)
            assertTrue(controller.start(track()));assertFalse(controller.snapshot.value.stream!!.bitPerfect)
        }finally {controller.close()}
        for((rate,config) in listOf(48000 to EqualizerConfig(),8000 to EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,20000f,6f,1f))))) {
            val bypass=UsbPlaybackController {Output(rate)}
            try {bypass.open(7,profile(config));assertTrue(bypass.start(track(rate)));assertTrue(bypass.snapshot.value.stream!!.bitPerfect);assertFalse(bypass.snapshot.value.eqProcessed)}
            finally {bypass.close()}
        }
    }
    @Test fun rateCapNeverGrantsEqPermissionAndUnsafeBoostsRefuseBeforeAudioStarts()=runBlocking {
        val output=Output();val controller=UsbPlaybackController {output}
        try {
            controller.open(7,profile().copy(maxRate=48000));assertFalse(controller.start(track(96000)))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,controller.snapshot.value.failure);assertEquals(0,output.starts)
            val unsafe=EqualizerConfig(List(3){EqBand(EqBand.Type.PEAK,1000f,10f,1f)})
            controller.open(7,profile(unsafe));assertFalse(controller.start(track(),true))
            assertEquals(UsbPlaybackFailure.EQ_HEADROOM_REQUIRED,controller.snapshot.value.failure);assertEquals(0,output.starts)
        }finally {controller.close()}
    }
    @Test fun observedProcessingMustMatchTheNativePlan()=runBlocking {
        val output=Output().apply {wrongProcessing=true};val controller=UsbPlaybackController {output}
        try {
            controller.open(7,profile());assertFalse(controller.start(track(),true))
            assertEquals(UsbPlaybackFailure.STREAM_MISMATCH,controller.snapshot.value.failure)
        }finally {controller.close()}
    }
}
