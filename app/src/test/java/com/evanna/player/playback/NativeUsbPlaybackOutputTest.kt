package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeUsbPlaybackOutputTest {
    @Test fun levelingKeepsTheUserBaseWhileApplyingVerifiedCappedHardwareGain() {
        val dac=Dac();val output=NativeUsbPlaybackOutput(identity,dac)
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        try {
            output.startVolumeDb=-30f;output.volumeLimitDb=-10f
            assertTrue(output.setLevelingOffsetDb(8f))
            assertNotNull(output.plan(music));assertTrue(output.prepareVolume());assertTrue(output.start(music).bitPerfect)
            assertEquals(-30f,output.usbVolume!!.currentDb);assertEquals(-22f,output.appliedGainDb)
            assertTrue(output.adjustVolume(1));assertEquals(-29f,output.usbVolume!!.currentDb);assertEquals(-21f,output.appliedGainDb)
            assertTrue(output.setVolume(-12f));assertEquals(-12f,output.usbVolume!!.currentDb);assertEquals(-10f,output.appliedGainDb)
            output.volumeLimitDb=-15f;assertTrue(output.setVolume(-12f))
            assertEquals(-15f,output.usbVolume!!.currentDb);assertEquals(-15f,output.appliedGainDb)
            assertFalse(output.setLevelingOffsetDb(-4f))
            output.stop();assertNull(output.appliedGainDb)
            assertTrue(output.setLevelingOffsetDb(-4.25f));assertNotNull(output.plan(music));assertTrue(output.prepareVolume());output.start(music)
            assertEquals(-15f,output.usbVolume!!.currentDb);assertEquals(-20f,output.appliedGainDb)
            assertEquals(listOf(-22f,-21f,-10f,-15f,-20f),dac.gains)
        }finally {output.stop();output.close()}
    }
    @Test fun levelingRejectsInvalidValuesAndAnalysisCalibrationAndFailsOnReadbackLoss() {
        val dac=Dac();val output=NativeUsbPlaybackOutput(identity,dac)
        try {
            assertFalse(output.setLevelingOffsetDb(Float.NaN));assertFalse(output.setLevelingOffsetDb(31f))
            assertTrue(output.setCalibration(calibration()));assertFalse(output.setLevelingOffsetDb(1f))
            assertFalse(output.setCalibration(null))
            assertTrue(output.setLevelingOffsetDb(3f))
            assertFalse(output.setCalibration(calibration()))
            val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
            assertNotNull(output.plan(music));assertTrue(output.prepareVolume());output.start(music)
            dac.volumeSet=false
            assertFalse(output.setVolume(-40f));assertFalse(output.readiness().volumeVerified);assertNull(output.appliedGainDb)
        }finally {output.stop();output.close()}
    }
    @Test fun cappedMusicRefusesADacWhoseOnlyModeExceedsTheCeiling() {
        val onlyHigh=object:UsbDacConnection by Dac() {
            override val formats=listOf(format.copy(sampleRates=listOf(96000),minRate=96000,maxRate=96000))
            override val outputModes=listOf(mode.copy(sampleRate=96000))
            override fun plan(source:PcmFormat,mode:OutputMode?)=PlaybackPlan(96000,24,source.sampleRate!=96000,source.sampleRate==96000)
        }
        val output=NativeUsbPlaybackOutput(identity,onlyHigh)
        try {
            for(rate in listOf(44100,192000))assertNull(output.plan(TrackPcmFormat(rate,24,2,24,"FLAC",true,1_000_000),48000))
        }finally {output.close()}
    }
    @Test fun profileRateCeilingsPreferSupportedExactDivisors() {
        val rates=listOf(44100,48000,88200,96000,176400,192000)
        val dac=object:UsbDacConnection by Dac() {
            override val formats=listOf(format.copy(sampleRates=rates,minRate=rates.first(),maxRate=rates.last()))
            override val outputModes=rates.map {mode.copy(sampleRate=it)}
        }
        val output=NativeUsbPlaybackOutput(identity,dac)
        try {
            for((sourceRate,cap,expected) in listOf(Triple(176400,96000,88200),Triple(192000,96000,96000),Triple(352800,192000,176400),Triple(44100,96000,44100)))
                assertEquals(expected,output.plan(TrackPcmFormat(sourceRate,24,2,24,"FLAC",true,1_000_000),cap)?.outputRate)
        }finally {output.close()}
    }
    @Test fun drainRequiresReadinessBeforeAndAfterNativeProof() {
        val dac = Dac(); val output = NativeUsbPlaybackOutput(identity, dac)
        try {
            assertFalse(output.drain())
            assertTrue(output.setCalibration(calibration()))
            assertNotNull(output.plan(source)); assertTrue(output.prepareVolume()); output.start(source)
            assertTrue(output.drain())
            dac.clock = false
            assertFalse(output.drain())
            assertEquals(1, dac.calls.count { it == "drain" })
            dac.clock = true
            dac.drainAction = { dac.feedback = false; true }
            assertFalse(output.drain())
        } finally { output.stop(); output.close() }
    }
    @Test fun stopDoesNotWaitForTheDrainControlMonitor() {
        val dac = Dac(); val output = NativeUsbPlaybackOutput(identity, dac)
        val entered = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(output.setCalibration(calibration()))
            assertNotNull(output.plan(source)); assertTrue(output.prepareVolume()); output.start(source)
            dac.drainAction = { entered.countDown(); check(dac.release.await(5, TimeUnit.SECONDS)); true }
            val drained = executor.submit<Boolean> { output.drain() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(output.stop())
            assertFalse(drained.get(2, TimeUnit.SECONDS))
        } finally { dac.release.countDown(); executor.shutdownNow(); output.stop(); output.close() }
    }
    private val source = PcmFixture(44100, 24, 2)
    private val identity = UsbDacIdentity("session-one", 7, 0x31b2, 0x3214, "a".repeat(64))
    private val format = DacFormat(1, 1, 3, 2, 4, 24, DacFormat.SyncType.SYNC, 384, listOf(44100, 48000), 44100, 48000)
    private val mode = OutputMode(0, 44100, 24, 4, 2)
    private fun calibration() = UsbVolumeCalibration(identity, 44100, 24, 2, mode, -50f)

    private inner class Dac : UsbDacConnection {
        override val description = "Synthetic native boundary"
        override val formats = listOf(format)
        override val outputModes = listOf(mode, mode.copy(sampleRate = 48000))
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val wrote = CountDownLatch(1)
        val release = CountDownLatch(1)
        var clock = true
        var feedback = true
        var volume = true
        var volumeSet = true
        var range: VolumeRange? = VolumeRange(-60f, 0f, 1f, -20f)
        var rangeReads=0
        override val volumeRange get() = range.also{rangeReads++}
        val gains = mutableListOf<Float>()
        var volumeAction:(()->Unit)?=null
        var writeAction:(()->Int)?=null
        var quiet = true
        var drainAction: () -> Boolean = { true }
        var failClose = false
        var startedMode: OutputMode? = null
        var actual: StreamInfo? = null
        var startGate: CountDownLatch? = null
        val enteredStart = CountDownLatch(1)
        override fun plan(source: PcmFormat, mode: OutputMode?): PlaybackPlan {
            calls += "plan"
            val rate = mode?.sampleRate ?: source.sampleRate
            return PlaybackPlan(rate, 24, rate != source.sampleRate, rate == source.sampleRate)
        }
        override fun start(source: PcmFormat, mode: OutputMode): StreamInfo {
            calls += "start"; startedMode = mode
            enteredStart.countDown()
            startGate?.let { check(it.await(5, TimeUnit.SECONDS)) }
            return actual ?: StreamInfo(source.sampleRate, source.significantBits, source.channels,
                format, mode.sampleRate, 0, mode.sampleRate == source.sampleRate, mode.sampleRate != source.sampleRate)
        }
        override fun clockConfigured() = clock
        override fun feedbackReady() = feedback
        override fun volumeVerified() = volume
        override fun setVolumeDb(db: Float): Boolean { gains += db; calls += "volume"; volumeAction?.invoke();if(volumeSet)range=range?.copy(currentDb=db); return volumeSet }
        override fun stats() = StreamStats(0, 0, 0, false)
        override fun drain(): Boolean { calls += "drain"; return drainAction() }
        override fun write(buffer: ByteBuffer, frames: Int): Int {
            calls += "write"
            assertEquals(frames * 2 * 4, buffer.capacity())
            assertEquals(Int.MIN_VALUE, buffer.getInt(0))
            wrote.countDown()
            writeAction?.let{return it()}
            check(release.await(5, TimeUnit.SECONDS))
            return -1
        }
        override fun stop(): Boolean { calls += "stop"; release.countDown(); return quiet }
        override fun close() { calls += "close"; check(!failClose) }
    }

    @Test fun songRateCapPicksAnExactDivisorAndLeavesLowerRatesAlone() {
        val dac = Dac(); val output = NativeUsbPlaybackOutput(identity, dac)
        fun music(rate: Int) = TrackPcmFormat(rate, 24, 2, 24, "ALAC", true, 1_000_000)
        try {
            for ((rate, expected) in listOf(88200 to 44100, 176400 to 44100, 96000 to 48000, 192000 to 48000, 64000 to 48000)) {
                val plan = output.plan(music(rate), FOLLOW_SONG_RATE_CAP)!!
                assertEquals("$rate Hz", expected, plan.outputRate)
                assertTrue(plan.resampled); assertTrue(output.prepareVolume())
            }
            val unchanged = output.plan(music(44100), FOLLOW_SONG_RATE_CAP)!!
            assertEquals(44100, unchanged.outputRate); assertFalse(unchanged.resampled)
            assertEquals("Analysis fixtures never take the music cap", 96000,
                output.plan(PcmFixture(96000, 24, 2), FOLLOW_SONG_RATE_CAP)!!.outputRate)
        } finally { output.close() }
    }

    @Test fun absentCalibrationRejectsStartBeforeHardwareStreaming() = runBlocking {
        val dac = Dac()
        val output = NativeUsbPlaybackOutput(identity, dac)
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7))
            assertFalse(controller.start(source))
            assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED, controller.snapshot.value.failure)
            assertFalse(dac.calls.contains("start"))
            assertFalse(dac.calls.contains("volume"))
            assertFalse(dac.calls.contains("write"))
        } finally { controller.close() }
    }

    @Test fun musicStartsAtTheStartingHardwareGainWithoutFabricatingCalibration() {
        val dac = Dac(); val output = NativeUsbPlaybackOutput(identity, dac)
        val music = TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        try {
            assertNotNull(output.plan(music)); assertTrue(output.prepareVolume())
            output.start(music)
            assertEquals(listOf(USB_START_VOLUME_DEFAULT_DB),dac.gains)
            assertTrue(output.readiness().volumeVerified)
            assertTrue(dac.calls.indexOf("start") < dac.calls.indexOf("volume"))
            assertFalse(dac.calls.contains("write"))
            output.stop(); output.plan(source)
            assertFalse("Full-scale analysis still needs measured evidence",output.prepareVolume())
        } finally { output.stop(); output.close() }
    }

    @Test fun musicWithUnavailableHardwareVolumeNeverReachesPcm() = runBlocking {
        val dac = Dac().apply { range=null }; val output = NativeUsbPlaybackOutput(identity,dac)
        val controller = UsbPlaybackController { output }
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        val track = object:PcmTrack {
            override val id="evanna:local:volume-test"
            override val format=music
            override fun open()=object:PcmReader {
                override val format=music
                override fun read(target:ByteBuffer,maxFrames:Int):Int=error("PCM must not be read")
                override fun requestCancel()=Unit
                override fun close()=Unit
            }
            override fun close()=Unit
        }
        try {
            controller.open(7); assertFalse(controller.start(track))
            assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED,controller.snapshot.value.failure)
            assertTrue(dac.calls.contains("start")); assertTrue(dac.gains.isEmpty())
            assertFalse(dac.calls.contains("write"))
        } finally { controller.close() }
    }

    @Test fun musicVolumeIsBoundedRetainedOnlyWithinConnectionAndNeverRaisedByRangeChanges() {
        val dac=Dac();val output=NativeUsbPlaybackOutput(identity,dac).apply{startVolumeDb=-60f}
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        try {
            output.plan(music);assertTrue(output.prepareVolume());output.start(music)
            assertEquals(-60f,output.usbVolume!!.currentDb)
            assertTrue(output.adjustVolume(1));assertEquals(-59f,output.usbVolume!!.currentDb)
            output.stop();output.plan(music);assertTrue(output.prepareVolume());output.start(music)
            assertEquals(-59f,dac.gains.last())
            repeat(65){assertTrue(output.adjustVolume(1))}
            assertEquals(0f,output.usbVolume!!.currentDb);assertTrue(dac.gains.all{it<=0})
            repeat(60){assertTrue(output.adjustVolume(-1))}
            output.stop();dac.range=VolumeRange(-20f,0f,1f,-10f)
            output.plan(music);assertTrue(output.prepareVolume());val count=dac.gains.size;output.start(music)
            assertEquals(count,dac.gains.size);assertFalse(output.readiness().volumeVerified)
        } finally { output.stop();output.close() }
        // A new connection forgets the old gain and starts at the default starting volume.
        val fresh=NativeUsbPlaybackOutput(identity.copy(sessionId="fresh"),Dac())
        try {fresh.plan(music);assertTrue(fresh.prepareVolume());fresh.start(music);assertEquals(USB_START_VOLUME_DEFAULT_DB,fresh.usbVolume!!.currentDb)}
        finally{fresh.stop();fresh.close()}
    }

    @Test fun configuredCeilingCoversStartupLiveCommandsAndRestartWithoutRaisingGain() {
        val dac=Dac();val output=NativeUsbPlaybackOutput(identity,dac).apply {volumeLimitDb=-20f}
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        try {
            output.plan(music);assertTrue(output.prepareVolume());output.start(music)
            assertEquals(-20f,output.usbVolume!!.currentDb);assertEquals(-20f,output.usbVolume!!.maxDb)
            assertTrue(output.setVolume(10f));assertEquals(-20f,dac.gains.last())
            assertTrue(output.adjustVolume(1));assertEquals(-20f,dac.gains.last())
            assertTrue(output.setVolume(-40f));val writes=dac.gains.size
            output.volumeLimitDb=-10f;assertTrue(output.setVolume(-40f))
            assertEquals(writes,dac.gains.size);assertEquals(-10f,output.usbVolume!!.maxDb)
            output.volumeLimitDb=-50f;assertTrue(output.setVolume(-40f));assertEquals(-50f,dac.gains.last())
            output.stop();output.plan(music);assertTrue(output.prepareVolume());output.start(music)
            assertEquals(-50f,dac.gains.last());assertEquals(-50f,output.usbVolume!!.maxDb)
            output.volumeLimitDb=-60f;assertTrue(output.setVolume(0f));assertEquals(-60f,dac.gains.last())
            assertEquals(output.usbVolume!!.minDb,output.usbVolume!!.maxDb)
            output.volumeLimitDb=-80f;assertFalse(output.setVolume(0f))
            assertFalse(output.readiness().volumeVerified)
        } finally {output.stop();output.close()}
        val analysis=NativeUsbPlaybackOutput(identity,Dac()).apply {volumeLimitDb=-60f}
        try {assertTrue(analysis.setCalibration(calibration()));analysis.plan(source);assertFalse(analysis.prepareVolume())}
        finally {analysis.stop();analysis.close()}
    }
    @Test fun startVolumeAndSliderTargetsRoundDownWithinTheVerifiedRange() {
        // The DAC's own current value must sit on its grid (-60 + 1.5 k), or the range is refused.
        val dac=Dac().apply{range=VolumeRange(-60f,0f,1.5f,-21f)};val output=NativeUsbPlaybackOutput(identity,dac)
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        try {
            assertFalse("No stream is open",output.setVolume(-30f))
            output.plan(music);assertTrue(output.prepareVolume());output.start(music)
            assertEquals("-5 dB rounds down on a 1.5 dB grid",-6f,output.usbVolume!!.currentDb)
            assertTrue(output.setVolume(-10.2f));assertEquals(-10.5f,output.usbVolume!!.currentDb);assertEquals(-10.5f,dac.gains.last())
            assertTrue(output.setVolume(12f));assertEquals(0f,output.usbVolume!!.currentDb)
            assertTrue(output.setVolume(-200f));assertEquals(-60f,output.usbVolume!!.currentDb)
            assertFalse(output.setVolume(Float.NaN))
            val count=dac.gains.size
            assertTrue("The same value needs no control write",output.setVolume(-60f));assertEquals(count,dac.gains.size)
            dac.volumeSet=false
            assertFalse(output.setVolume(-30f));assertFalse(output.readiness().volumeVerified)
            assertTrue(dac.gains.all{it<=0})
        } finally { output.stop();output.close() }
        val quiet=NativeUsbPlaybackOutput(identity.copy(sessionId="quiet"),Dac()).apply{startVolumeDb=-30f}
        try {quiet.plan(music);assertTrue(quiet.prepareVolume());quiet.start(music);assertEquals(-30f,quiet.usbVolume!!.currentDb)}
        finally{quiet.stop();quiet.close()}
    }

    @Test fun invalidMusicRangesDoNotWriteGainAndReadbackFailureBlocksWrites() {
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        for(range in listOf(null,VolumeRange(0f,1f,1f,0f),VolumeRange(-60f,0f,0f,-20f),VolumeRange(-60f,0f,1f,-20.5f))) {
            val dac=Dac().apply{this.range=range};val output=NativeUsbPlaybackOutput(identity,dac)
            try {
                output.plan(music);assertTrue(output.prepareVolume());output.start(music)
                assertFalse(output.readiness().volumeVerified);assertTrue(dac.gains.isEmpty())
                assertEquals(-1,output.write(source.block(0,1),1));assertFalse(dac.calls.contains("write"))
            }finally{output.stop();output.close()}
        }
        val dac=Dac().apply{volume=false};val output=NativeUsbPlaybackOutput(identity,dac)
        try {
            output.plan(music);assertTrue(output.prepareVolume());output.start(music)
            assertFalse(output.readiness().volumeVerified);assertNull(output.usbVolume)
        }finally{output.stop();output.close()}
    }

    @Test fun liveGainUsesNativeSetReadbackWithoutExtraRangeTransfers() {
        val dac=Dac();val output=NativeUsbPlaybackOutput(identity,dac)
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        try {
            output.plan(music);assertTrue(output.prepareVolume());output.start(music)
            val reads=dac.rangeReads
            assertTrue(output.adjustVolume(1))
            assertEquals("Native setVolume already validates ranges and reads every changed control back",reads,dac.rangeReads)
        }finally{output.stop();output.close()}
    }

    @Test fun controllerCeilingAndStepApplyLiveAndSurviveReopenWithoutRaisingGain()=runBlocking {
        val dac=Dac().apply {writeAction={Thread.sleep(2);1}}
        val output=NativeUsbPlaybackOutput(identity,dac);val saved=mutableListOf<Float>()
        val controller=UsbPlaybackController(initialVolumeLimitDb=-20f,initialUsbKeyStepDb=3f,saveVolumeLimitDb={saved+=it;true}) {output}
        val track=continuousMusic()
        try {
            controller.open(7);assertTrue(controller.start(track));val generation=controller.snapshot.value.generation
            assertEquals(-20f,controller.snapshot.value.usbVolume!!.currentDb)
            assertTrue(controller.setUsbVolume(-29f,generation));assertTrue(controller.adjustUsbVolume(1,generation))
            assertEquals(-26f,controller.snapshot.value.usbVolume!!.currentDb)
            assertTrue(controller.setVolumeLimit(-30f));assertEquals(-30f,controller.snapshot.value.usbVolume!!.currentDb)
            val writes=dac.gains.size
            assertTrue(controller.setVolumeLimit(-10f));assertEquals(writes,dac.gains.size)
            assertEquals(-30f,controller.snapshot.value.usbVolume!!.currentDb);assertEquals(-10f,controller.snapshot.value.usbVolume!!.maxDb)
            assertTrue(controller.setUsbVolume(0f,generation));assertEquals(-10f,controller.snapshot.value.usbVolume!!.currentDb)
            assertTrue(controller.setUsbKeyStep(2f));assertTrue(controller.adjustUsbVolume(-1,generation))
            assertEquals(-12f,controller.snapshot.value.usbVolume!!.currentDb)
            assertTrue(controller.pause());assertTrue(controller.start(track));assertEquals(-12f,controller.snapshot.value.usbVolume!!.currentDb)
            assertFalse(controller.setVolumeLimit(-80f));assertEquals(-80f,controller.snapshot.value.volumeLimitDb)
            assertEquals(UsbPlaybackState.ERROR,controller.snapshot.value.state)
            val before=dac.gains.size
            assertFalse(controller.start(track));assertEquals(before,dac.gains.size)
            assertEquals(listOf(-30f,-10f,-80f),saved)
            controller.close();assertEquals(-80f,controller.snapshot.value.volumeLimitDb);assertEquals(2f,controller.snapshot.value.usbKeyStepDb)
        } finally {controller.close()}
    }
    @Test fun refusedSettingsKeepOldConfigAndSavedCapStillStopsOnReadbackFailure()=runBlocking {
        val dac=Dac().apply {writeAction={Thread.sleep(2);1}};val output=NativeUsbPlaybackOutput(identity,dac)
        var save=false;var calls=0
        val controller=UsbPlaybackController(saveVolumeLimitDb={calls++;save},saveUsbKeyStepDb={calls++;save}) {output}
        try {
            controller.open(7);assertTrue(controller.start(continuousMusic()));val writes=dac.gains.size
            for(value in listOf(Float.NaN,1f,-81f,-5.5f))assertFalse(controller.setVolumeLimit(value))
            for(value in listOf(0f,1.5f,Float.NaN))assertFalse(controller.setUsbKeyStep(value))
            assertEquals(0,calls);assertFalse(controller.setVolumeLimit(-30f));assertFalse(controller.setUsbKeyStep(3f))
            assertEquals(writes,dac.gains.size);assertEquals(0f,controller.snapshot.value.volumeLimitDb);assertEquals(1f,controller.snapshot.value.usbKeyStepDb)
            save=true;dac.volumeSet=false
            assertFalse(controller.setVolumeLimit(-30f));assertEquals(-30f,controller.snapshot.value.volumeLimitDb)
            assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED,controller.snapshot.value.failure)
            assertEquals(UsbPlaybackState.ERROR,controller.snapshot.value.state)
        } finally {controller.close()}
    }
    @Test fun controllerVolumeRejectsStaleRequestsAndStopsOnGainFailure()=runBlocking {
        val dac=Dac().apply{writeAction={Thread.sleep(2);1}};val output=NativeUsbPlaybackOutput(identity,dac);val controller=UsbPlaybackController{output}
        val music=TrackPcmFormat(44100,24,2,24,"FLAC",true,1_000_000)
        val track=object:PcmTrack {
            override val id="evanna:local:volume-command"
            override val format=music
            override fun open()=object:PcmReader {
                override val format=music
                override fun read(target:ByteBuffer,maxFrames:Int):Int {target.putInt(0,Int.MIN_VALUE);target.putInt(4,0);return 1}
                override fun requestCancel()=Unit
                override fun close()=Unit
            }
            override fun close()=Unit
        }
        try {
            controller.open(7);assertTrue(controller.start(track));assertTrue(dac.wrote.await(5,TimeUnit.SECONDS))
            val generation=controller.snapshot.value.generation
            assertFalse(controller.adjustUsbVolume(1,generation+1));assertFalse(controller.adjustUsbVolume(0,generation))
            assertEquals("A new connection starts at the default starting volume",listOf(USB_START_VOLUME_DEFAULT_DB),dac.gains)
            assertTrue(controller.adjustUsbVolume(1,generation));assertEquals(-4f,controller.snapshot.value.usbVolume!!.currentDb)
            // Slider targets: stale or non-numeric requests are refused; others round down to the 1 dB grid.
            assertFalse(controller.setUsbVolume(-30f,generation+1));assertFalse(controller.setUsbVolume(Float.NaN,generation))
            assertTrue(controller.setUsbVolume(-30.4f,generation));assertEquals(-31f,controller.snapshot.value.usbVolume!!.currentDb)
            assertTrue(controller.setUsbVolume(20f,generation));assertEquals(0f,controller.snapshot.value.usbVolume!!.currentDb)
            assertEquals(listOf(USB_START_VOLUME_DEFAULT_DB,-4f,-31f,0f),dac.gains)
            dac.volumeSet=false
            // At 0 dB a step up changes nothing, so step down to reach the failing control write.
            assertFalse(controller.adjustUsbVolume(-1,generation))
            assertEquals(UsbPlaybackState.ERROR,controller.snapshot.value.state)
            assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED,controller.snapshot.value.failure)
            assertNull(controller.snapshot.value.usbVolume);assertFalse(output.readiness().volumeVerified)
            assertTrue(dac.calls.contains("stop"))
        }finally{controller.close()}
    }

    @Test fun cancellingSavedLimitApplicationCannotLeaveAnOverLimitStream()=runBlocking {
        val dac=Dac().apply {writeAction={Thread.sleep(2);1}};val output=NativeUsbPlaybackOutput(identity,dac)
        val controller=UsbPlaybackController {output};val entered=CountDownLatch(1);val releaseGain=CountDownLatch(1)
        try {
            controller.open(7);assertTrue(controller.start(continuousMusic()))
            dac.volumeAction={entered.countDown();check(releaseGain.await(5,TimeUnit.SECONDS))}
            val setting=launch(Dispatchers.Default) {controller.setVolumeLimit(-30f)}
            assertTrue(entered.await(5,TimeUnit.SECONDS));setting.cancel();releaseGain.countDown();setting.join()
            assertEquals(-30f,controller.snapshot.value.volumeLimitDb);assertEquals(-30f,dac.gains.last())
            assertEquals(-30f,controller.snapshot.value.usbVolume!!.maxDb)
            assertEquals(UsbPlaybackState.PLAYING,controller.snapshot.value.state)
        } finally {releaseGain.countDown();controller.close()}
    }

    private fun continuousMusic(nextRead:CountDownLatch?=null):PcmTrack {
        val pcm=TrackPcmFormat(44100,24,2,24,"FLAC",true,null)
        return object:PcmTrack {
            override val id="evanna:local:gain-race"
            override val format=pcm
            override fun open()=object:PcmReader {
                override val format=pcm
                var read=false
                override fun read(target:ByteBuffer,maxFrames:Int):Int {if(read)nextRead?.let{check(it.await(5,TimeUnit.SECONDS))};read=true;target.putInt(0,Int.MIN_VALUE);target.putInt(4,0);return 1}
                override fun requestCancel(){nextRead?.countDown()}
                override fun close()=Unit
            }
            override fun close()=Unit
        }
    }

    @Test fun liveGainVerificationDoesNotRaceWriterReadiness()=kotlinx.coroutines.runBlocking {
        val writes=java.util.concurrent.atomic.AtomicInteger()
        val dac=Dac().apply{writeAction={Thread.sleep(2);writes.incrementAndGet();1}}
        val output=NativeUsbPlaybackOutput(identity,dac);val controller=UsbPlaybackController{output}
        val entered=CountDownLatch(1);val releaseGain=CountDownLatch(1)
        try {
            controller.open(7);assertTrue(controller.start(continuousMusic()));assertTrue(dac.wrote.await(5,TimeUnit.SECONDS))
            dac.volumeAction={entered.countDown();check(releaseGain.await(5,TimeUnit.SECONDS))}
            val adjusting=async(Dispatchers.Default){controller.adjustUsbVolume(1,controller.snapshot.value.generation)}
            assertTrue(entered.await(5,TimeUnit.SECONDS));val before=writes.get()
            kotlinx.coroutines.delay(30);releaseGain.countDown();assertTrue(adjusting.await())
            val resumed=kotlinx.coroutines.withTimeoutOrNull(1000){while(writes.get()<=before+3)kotlinx.coroutines.delay(5);true}
            assertEquals("A successful gain change must leave the writer running",true,resumed)
            assertEquals(UsbPlaybackState.PLAYING,controller.snapshot.value.state)
            assertNull(controller.snapshot.value.failure)
        }finally{releaseGain.countDown();controller.close()}
    }

    @Test fun cancellingAnInFlightGainChangeStopsWriterAndAbandonsFocus()=kotlinx.coroutines.runBlocking {
        val dac=Dac().apply{writeAction={1}}
        val output=NativeUsbPlaybackOutput(identity,dac);val controller=UsbPlaybackController{output}
        val focus=object:PlaybackFocus {
            override var held=false
            override fun request():Boolean{held=true;return true}
            override fun abandon(){held=false}
        }
        val owner=UsbSessionOwner(controller,focus)
        val entered=CountDownLatch(1);val releaseGain=CountDownLatch(1)
        val nextRead=CountDownLatch(1)
        try {
            owner.selectTrack{continuousMusic(nextRead)};owner.open(7);assertTrue(owner.play());assertTrue(dac.wrote.await(5,TimeUnit.SECONDS))
            dac.volumeAction={entered.countDown();check(releaseGain.await(5,TimeUnit.SECONDS))}
            val changing=launch(Dispatchers.Default){owner.adjustUsbVolume(1,owner.snapshot.value.generation)}
            assertTrue(entered.await(5,TimeUnit.SECONDS));changing.cancel();releaseGain.countDown();changing.join()
            assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state)
            assertFalse(focus.held);assertNull(owner.snapshot.value.usbVolume)
        }finally{releaseGain.countDown();nextRead.countDown();owner.close()}
    }

    @Test fun waitingVolumeCommandCannotBlockPauseOfABlockedWriter()=runBlocking {
        val dac=Dac();val output=NativeUsbPlaybackOutput(identity,dac);val controller=UsbPlaybackController{output}
        val owner=UsbSessionOwner(controller,object:PlaybackFocus {
            override var held=false
            override fun request():Boolean{held=true;return true}
            override fun abandon(){held=false}
        })
        var adjusting:Deferred<Boolean>?=null
        try {
            owner.selectTrack{continuousMusic()};owner.open(7);assertTrue(owner.play());assertTrue(dac.wrote.await(5,TimeUnit.SECONDS))
            adjusting=async(Dispatchers.Default){owner.adjustUsbVolume(1,owner.snapshot.value.generation)}
            delay(30)
            val paused=withTimeoutOrNull(500){owner.pause()}
            assertEquals("A pending gain command must not prevent Stop from releasing a blocked write",true,paused)
            assertFalse(adjusting.await());assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state)
        }finally{adjusting?.cancel();dac.release.countDown();owner.close()}
    }

    @Test fun calibratedSessionPinsModeAndPreservesUnknownReadback() = runBlocking {
        val dac = Dac()
        val output = NativeUsbPlaybackOutput(identity, dac)
        assertTrue(output.setCalibration(calibration()))
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7))
            assertTrue(controller.start(source))
            assertTrue(dac.wrote.await(5, TimeUnit.SECONDS))
            assertEquals(mode, dac.startedMode)
            assertEquals(0, controller.snapshot.value.stream!!.deviceRate)
            assertTrue(dac.calls.indexOf("eq") < dac.calls.indexOf("plan"))
            assertTrue(dac.calls.indexOf("start") < dac.calls.indexOf("volume"))
            assertTrue(dac.calls.indexOf("volume") < dac.calls.indexOf("write"))
        } finally { controller.close() }
        assertFalse(output.readiness().volumeVerified)
        assertTrue(dac.calls.indexOf("stop") < dac.calls.indexOf("close"))
    }

    @Test fun invalidOrStaleCalibrationCannotAuthorizePcm() {
        val profiles = listOf(
            calibration().copy(identity = identity.copy(sessionId = "old-session")),
            calibration().copy(identity = identity.copy(deviceId = 8)),
            calibration().copy(identity = identity.copy(descriptorSha256 = "b".repeat(64))),
            calibration().copy(mode = mode.copy(formatIndex = 9)),
            calibration().copy(mode = mode.copy(channels = 1)),
            calibration().copy(volumeDb = Float.NaN), calibration().copy(volumeDb = 0.5f),
            calibration().copy(volumeDb = -128.1f), calibration().copy(volumeDb = -50.001f),
        )
        for (profile in profiles) {
            val dac = Dac()
            val output = NativeUsbPlaybackOutput(identity, dac)
            assertFalse(output.setCalibration(profile))
            output.plan(source)
            assertFalse(output.prepareVolume())
            assertEquals(-1, output.write(source.block(0, 1), 1))
            assertTrue(dac.calls.none { it in listOf("start", "volume", "write") })
            output.close()
        }
    }

    @Test fun calibrationCannotBeReusedForAnotherSourceFormat() {
        val output = NativeUsbPlaybackOutput(identity, Dac())
        assertTrue(output.setCalibration(calibration()))
        output.plan(PcmFixture(48000, 24, 2))
        assertFalse(output.prepareVolume())
        output.close()
    }

    @Test fun nativeReadinessFailuresNeverSubmitPcm() = runBlocking {
        for (failure in listOf("clock", "feedback", "setter", "readback")) {
            val dac = Dac().apply {
                clock = failure != "clock"; feedback = failure != "feedback"
                volumeSet = failure != "setter"; volume = failure != "readback"
            }
            val output = NativeUsbPlaybackOutput(identity, dac)
            assertTrue(output.setCalibration(calibration()))
            val controller = UsbPlaybackController { output }
            try {
                assertTrue(controller.open(7))
                assertFalse(controller.start(source))
                val expected = when (failure) {
                    "clock" -> UsbPlaybackFailure.CLOCK_UNVERIFIED
                    "feedback" -> UsbPlaybackFailure.FEEDBACK_UNVERIFIED
                    else -> UsbPlaybackFailure.VOLUME_UNVERIFIED
                }
                assertEquals(expected, controller.snapshot.value.failure)
                assertFalse(dac.calls.contains("write"))
                if (failure in listOf("clock", "feedback")) assertFalse(dac.calls.contains("volume"))
                assertTrue(dac.calls.contains("stop"))
            } finally { controller.close() }
        }
    }

    @Test fun unexpectedActualStreamIsRejectedBeforeGainChanges() = runBlocking {
        val dac = Dac().apply { actual = StreamInfo(44100, 24, 2, format.copy(altSetting = 2), 44100, 0, true, false) }
        val output = NativeUsbPlaybackOutput(identity, dac)
        assertTrue(output.setCalibration(calibration()))
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7))
            assertFalse(controller.start(source))
            assertFalse(dac.calls.contains("volume"))
            assertFalse(dac.calls.contains("write"))
            assertTrue(dac.calls.contains("stop"))
        } finally { controller.close() }
    }

    @Test fun lostProofBlocksDirectWritesAndCleanupFailuresKeepOwnership() {
        val dac = Dac()
        val output = NativeUsbPlaybackOutput(identity, dac)
        assertTrue(output.setCalibration(calibration()))
        output.plan(source); assertTrue(output.prepareVolume()); output.start(source)
        assertTrue(output.readiness().volumeVerified)
        dac.volume = false
        assertEquals(-1, output.write(source.block(0, 1), 1))
        assertFalse(dac.calls.contains("write"))
        dac.quiet = false
        assertFalse(output.stop())
        dac.quiet = true
        assertTrue(output.stop())
        output.plan(source)
        assertFalse(output.prepareVolume())
        dac.failClose = true
        assertThrows(IllegalStateException::class.java) { output.close() }
        dac.failClose = false
        output.close(); output.close()
        assertEquals(2, dac.calls.count { it == "close" })
    }

    @Test fun conversionStillRequiresAuthorizationForEachControllerStart() = runBlocking {
        val dac = Dac()
        val output = NativeUsbPlaybackOutput(identity, dac)
        assertTrue(output.setCalibration(calibration().copy(mode = mode.copy(sampleRate = 48000))))
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7))
            assertFalse(controller.start(source))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, controller.snapshot.value.failure)
            assertFalse(dac.calls.contains("start"))
            assertTrue(controller.start(source, allowConversion = true))
            assertTrue(dac.wrote.await(5, TimeUnit.SECONDS))
            assertEquals(48000, dac.startedMode!!.sampleRate)
            assertTrue(controller.pause())
            assertFalse(controller.start(source))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, controller.snapshot.value.failure)
            assertEquals(1, dac.calls.count { it == "start" })
        } finally { controller.close() }
    }

    @Test fun revokingPreparedCalibrationPreventsNativeStart() {
        val dac = Dac()
        val output = NativeUsbPlaybackOutput(identity, dac)
        assertTrue(output.setCalibration(calibration()))
        output.plan(source)
        assertTrue(output.prepareVolume())
        assertFalse(output.setCalibration(null))
        assertThrows(IllegalStateException::class.java) { output.start(source) }
        assertFalse(dac.calls.contains("start"))
        output.close()
    }

    @Test fun calibrationCannotChangeWhileNativeStartupIsInFlight() {
        val gate = CountDownLatch(1)
        val dac = Dac().apply { startGate = gate }
        val output = NativeUsbPlaybackOutput(identity, dac)
        assertTrue(output.setCalibration(calibration()))
        output.plan(source); assertTrue(output.prepareVolume())
        val threads = Executors.newFixedThreadPool(2)
        try {
            val started = threads.submit<StreamInfo> { output.start(source) }
            assertTrue(dac.enteredStart.await(5, TimeUnit.SECONDS))
            val changing = CountDownLatch(1)
            val changed = threads.submit<Boolean> { changing.countDown(); output.setCalibration(null) }
            assertTrue(changing.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { changed.get(50, TimeUnit.MILLISECONDS) }
            gate.countDown()
            started.get(5, TimeUnit.SECONDS)
            val failure = assertThrows(ExecutionException::class.java) { changed.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalStateException)
        } finally {
            gate.countDown(); output.stop(); output.close(); threads.shutdownNow()
        }
    }
}
