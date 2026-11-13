package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import com.evanna.player.user.DacProfile
import com.evanna.player.usb.dacIdentity

class UsbPlaybackModesTest {
    @Test fun levelingModeIsProfileBoundAndPausesWithoutReplayingOrLosingTheSelection()=runBlocking {
        val profile=DacProfile(dacIdentity(1,2,"DAC"));var saves=0
        val output=Output();val controller=UsbPlaybackController(saveLevelingMode={saves++;true}){output}
        try {
            assertFalse(controller.setLevelingMode(com.evanna.player.loudness.LevelingMode.TRACK,null))
            controller.open(7,profile)
            val selected=track(TrackPcmFormat(48000,24,2,24,"FLAC",true,1_000_000))
            assertTrue(controller.start(selected));assertTrue(output.wrote.await(5,TimeUnit.SECONDS))
            assertFalse(controller.setLevelingMode(com.evanna.player.loudness.LevelingMode.TRACK,"old-profile"))
            assertEquals(UsbPlaybackState.PLAYING,controller.snapshot.value.state);assertEquals(0,saves)
            assertTrue(controller.setLevelingMode(com.evanna.player.loudness.LevelingMode.TRACK,profile.identity.key))
            assertEquals(UsbPlaybackState.PAUSED,controller.snapshot.value.state);assertEquals(selected.id,controller.snapshot.value.trackId)
            assertEquals(1,output.starts);assertEquals(1,saves);assertEquals(com.evanna.player.loudness.LevelingMode.TRACK,controller.snapshot.value.levelingMode)
        }finally {controller.close()}
    }
    @Test fun failedLevelingSaveKeepsModeAndClosedOutputDoesNotBecomePaused()=runBlocking {
        val profile=DacProfile(dacIdentity(1,2,"DAC"));var save=false
        val controller=UsbPlaybackController(saveLevelingMode={save}){Output()}
        try {
            controller.open(7,profile);controller.close()
            assertFalse(controller.setLevelingMode(com.evanna.player.loudness.LevelingMode.ALBUM,profile.identity.key))
            assertEquals(com.evanna.player.loudness.LevelingMode.OFF,controller.snapshot.value.levelingMode)
            save=true;assertTrue(controller.setLevelingMode(com.evanna.player.loudness.LevelingMode.ALBUM,profile.identity.key))
            assertEquals(UsbPlaybackState.CLOSED,controller.snapshot.value.state)
        }finally {controller.close()}
    }
    @Test fun armingABoundaryTimerKeepsOnlyTheCurrentConvertedPlayPermission()=runBlocking {
        val output=Output(48000,16)
        val focus=object:PlaybackFocus {
            override var held=false
            override fun request():Boolean {held=true;return true}
            override fun abandon(){held=false}
        }
        val owner=UsbSessionOwner(UsbPlaybackController {output},focus)
        try {
            owner.enqueue(QueueSource(null,"Reduced",null,null,10_000_000){object:PcmTrack by
                track(TrackPcmFormat(48000,24,2,24,"FLAC",true,10_000_000)) {override val gaplessEligible=true}})
            owner.open(7);assertTrue(owner.play(true));assertTrue(output.wrote.await(5,TimeUnit.SECONDS))
            assertTrue(owner.setSleepTimer(SleepMode.SONG))
            assertEquals(2,output.starts);assertTrue(owner.snapshot.value.conversionAllowed)
            assertEquals(UsbPlaybackPolicy.STRICT,owner.snapshot.value.usbPolicy)
            assertEquals(SleepMode.SONG,owner.sleepTimer.value.mode)
            assertTrue(owner.setSleepTimer(SleepMode.OFF))
        }finally {owner.close()}
    }
    @Test fun sleepExpiryUsesTheOwnerLaneAndPreservesQueueAndPosition()=runBlocking {
        var clock=1000L
        val output=Output()
        val focus=object:PlaybackFocus {
            override var held=false
            override fun request():Boolean {held=true;return true}
            override fun abandon(){held=false}
        }
        val owner=UsbSessionOwner(UsbPlaybackController {output},focus,nowMs={clock})
        try {
            owner.enqueue(QueueSource(null,"Song",null,null,10_000_000){track(TrackPcmFormat(48000,24,2,24,"FLAC",true,10_000_000))})
            owner.open(7);assertTrue(owner.play());assertTrue(output.wrote.await(5,TimeUnit.SECONDS))
            val before=owner.queue.value.items
            assertFalse(owner.setSleepTimer(SleepMode.MINUTES,181))
            assertTrue(owner.setSleepTimer(SleepMode.MINUTES,1))
            clock=60999;owner.tickSleepTimer();assertEquals(SleepMode.MINUTES,owner.sleepTimer.value.mode)
            clock=61000
            val tick=launch(start=CoroutineStart.LAZY) {owner.tickSleepTimer()}
            var stateAtExpiry:UsbPlaybackState?=null
            val observer=launch(Dispatchers.Unconfined) {
                owner.sleepTimer.first {it.mode==SleepMode.OFF}
                stateAtExpiry=owner.snapshot.value.state
                tick.cancel()
            }
            withTimeout(5000) {tick.join();observer.join()}
            assertEquals(UsbPlaybackState.PAUSED,stateAtExpiry)
            assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state)
            assertEquals(SleepMode.OFF,owner.sleepTimer.value.mode);assertFalse(focus.held)
            assertEquals(before,owner.queue.value.items);assertTrue(owner.positionUs>=0)
            assertTrue(owner.setSleepTimer(SleepMode.MINUTES,1));assertTrue(owner.setSleepTimer(SleepMode.OFF))
            clock+=100000;owner.tickSleepTimer();assertEquals(SleepMode.OFF,owner.sleepTimer.value.mode)
        }finally {owner.close()}
    }
    @Test fun queuedSettingsForAnOldDacCannotChangeTheNewProfile()=runBlocking {
        val first=DacProfile(dacIdentity(1,2,"First"),48000,-20f,-10f,1f)
        val second=DacProfile(dacIdentity(3,4,"Second"),96000,-30f,-25f,2f)
        var saves=0
        val controller=UsbPlaybackController(saveStartVolumeDb={saves++;true},saveVolumeLimitDb={saves++;true},
            saveUsbKeyStepDb={saves++;true},saveMaxUsbRate={saves++;true}) {Output()}
        try {
            assertTrue(controller.open(7,first));assertTrue(controller.open(8,second))
            val before=controller.snapshot.value
            assertFalse(controller.setStartVolume(-5f,first.identity.key))
            assertFalse(controller.setVolumeLimit(0f,first.identity.key))
            assertFalse(controller.setUsbKeyStep(3f,first.identity.key))
            assertFalse(controller.setMaxUsbRate(null,first.identity.key))
            assertFalse(controller.setMaxUsbRate(48000))
            assertEquals(before,controller.snapshot.value);assertEquals(0,saves)
        }finally {controller.close()}
    }
    @Test fun mismatchedDeviceIdentityRefusesTheProfileAndRetainsFailedCleanupForRetry()=runBlocking {
        val output=Output().apply {profileIdentityKey="another-dac";failClose=true}
        val profile=DacProfile(dacIdentity(0x31b2,0x3214,"FIIO JD1"),48000)
        val controller=UsbPlaybackController {output}
        try {
            assertFalse(controller.open(7,profile));assertEquals(0,output.starts)
            assertEquals(UsbPlaybackFailure.CLOSE_FAILED,controller.snapshot.value.failure)
            assertEquals(1,output.closes)
            output.failClose=false;assertTrue(controller.close());assertEquals(2,output.closes)
        }finally {output.failClose=false;controller.close()}
    }
    @Test fun profileSettingsAndRateCeilingSurviveCloseWithoutLeakingConversionPermission()=runBlocking {
        val profile=DacProfile(dacIdentity(0x31b2,0x3214,"FIIO JD1"),96000,-30f,-20f,2f)
        val saved=mutableListOf<Int?>();val output=Output(96000,24)
        val controller=UsbPlaybackController(saveMaxUsbRate={saved+=it;true}) {output}
        try {
            assertTrue(controller.open(7,profile))
            assertEquals(profile.identity.key,controller.snapshot.value.dacProfileKey)
            assertEquals("FIIO JD1",controller.snapshot.value.dacProfileName)
            assertEquals(-30f,controller.snapshot.value.startVolumeDb);assertEquals(-20f,controller.snapshot.value.volumeLimitDb)
            assertEquals(2f,controller.snapshot.value.usbKeyStepDb)
            assertTrue(controller.start(track(TrackPcmFormat(192000,24,2,24,"FLAC",true,10_000_000))))
            assertTrue(output.wrote.await(5,TimeUnit.SECONDS));assertEquals(96000,controller.snapshot.value.stream?.outputRate)
            assertFalse(controller.snapshot.value.conversionAllowed)
            assertTrue(controller.pause());assertTrue(controller.setMaxUsbRate(192000,profile.identity.key))
            assertFalse(controller.setMaxUsbRate(44100));assertEquals(listOf(192000),saved)
            assertTrue(controller.close());assertEquals(192000,controller.snapshot.value.maxUsbRate)
            assertEquals(profile.identity.key,controller.snapshot.value.dacProfileKey)
        }finally {controller.close()}
    }
    @Test fun compatibilityCannotExceedACeilingAndStrictDoesNotAuthorizeUnrelatedLowerRateConversion()=runBlocking {
        val over=Output(96000,24);val controller=UsbPlaybackController(initialUsbPolicy=UsbPlaybackPolicy.COMPATIBILITY) {over}
        try {
            controller.open(7);controller.setMaxUsbRate(48000)
            assertFalse(controller.start(track(TrackPcmFormat(192000,24,2,24,"FLAC",true,10_000_000))))
            assertEquals(UsbPlaybackFailure.UNSUPPORTED_FORMAT,controller.snapshot.value.failure);assertEquals(0,over.starts)
        }finally {controller.close()}
        val below=Output(32000,24);val strict=UsbPlaybackController {below}
        try {
            strict.open(7);strict.setMaxUsbRate(48000)
            assertFalse(strict.start(track(TrackPcmFormat(44100,24,2,24,"FLAC",true,10_000_000))))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,strict.snapshot.value.failure)
        }finally {strict.close()}
    }
    @Test fun failedProfileReadPausesTheOldWriterAndKeepsTheQueue()=runBlocking {
        val output=Output();val focus=object:PlaybackFocus {
            override var held=false
            override fun request():Boolean {held=true;return true}
            override fun abandon(){held=false}
        }
        val owner=UsbSessionOwner(UsbPlaybackController(initialUsbPolicy=UsbPlaybackPolicy.COMPATIBILITY) {output},focus)
        try {
            owner.enqueue(QueueSource(null,"Song",null,null,processed.durationUs) {track()})
            owner.open(7);assertTrue(owner.play());assertTrue(output.wrote.await(5,TimeUnit.SECONDS))
            val before=owner.queue.value.items
            assertTrue(runCatching {owner.open(8) {throw java.io.IOException("profile")}}.isFailure)
            assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state);assertFalse(focus.held)
            assertEquals(before,owner.queue.value.items)
        }finally {owner.close()}
    }
    private val processed = TrackPcmFormat(48000, 32, 2, null, "MP3", false, 10_000_000)
    private class Output(private val rate: Int = 48000, private val bits: Int = 32) : UsbPlaybackOutput {
        override val description = "Synthetic USB policy output"
        var starts = 0
        override var profileIdentityKey:String?=null
        var closes=0
        var failClose=false
        var writes = 0
        var quiet = true
        var calibrated = true
        var stopped = CountDownLatch(1)
        var wrote = CountDownLatch(1)
        override var startVolumeDb = Float.NaN
        override fun plan(source: PcmFormat) = PlaybackPlan(rate, bits, rate != source.sampleRate,
            rate == source.sampleRate && bits >= source.significantBits)
        override fun prepareVolume() = calibrated
        override fun start(source: PcmFormat): StreamInfo {
            starts++; stopped = CountDownLatch(1); wrote = CountDownLatch(1)
            return StreamInfo(source.sampleRate, source.significantBits, source.channels,
                DacFormat(1, 1, 1, 2, (bits + 7) / 8, bits, DacFormat.SyncType.SYNC, 1024, listOf(rate), rate, rate),
                rate, rate, rate == source.sampleRate && bits >= source.significantBits, rate != source.sampleRate)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(0, 0, 0, false)
        override fun write(buffer: ByteBuffer, frames: Int): Int {
            writes++; wrote.countDown(); check(stopped.await(5, TimeUnit.SECONDS)); return -1
        }
        override fun stop(): Boolean { stopped.countDown(); return quiet }
        override fun close() {closes++;check(!failClose)}
    }
    private fun track(source: PcmFormat = processed) = object : PcmTrack {
        override val id = "policy-source"
        override val format = source
        override fun open() = object : PcmReader {
            override val format = source
            override fun read(target: ByteBuffer, maxFrames: Int): Int { repeat(source.channels) { target.putInt(it * 4, 0) }; return 1 }
            override fun requestCancel() = Unit
            override fun close() = Unit
        }
        override fun close() = Unit
    }
    @Test fun missingOrInvalidPolicyIsStrict() {
        for (value in listOf(null, "", "compatibility", "UNKNOWN", "true")) assertEquals(UsbPlaybackPolicy.STRICT, UsbPlaybackPolicy.fromStored(value))
        assertEquals(UsbPlaybackPolicy.COMPATIBILITY, UsbPlaybackPolicy.fromStored("COMPATIBILITY"))
    }
    @Test fun modeChangeAfterFailedOpenKeepsOutputClosed() = runBlocking {
        var failOpen = true
        val output = Output()
        val controller = UsbPlaybackController { check(!failOpen); output }
        val selected = object : PcmTrack by track() { override val seekable = true }
        try {
            assertTrue(controller.setPausedPosition(selected, 5_000_000))
            assertFalse(controller.open(7))
            assertEquals(UsbPlaybackFailure.OUTPUT_FAILED, controller.snapshot.value.failure)
            assertTrue(controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
            assertEquals(UsbPlaybackState.CLOSED, controller.snapshot.value.state)
            assertNull(controller.snapshot.value.route)
            assertNull(controller.snapshot.value.deviceId)
            assertEquals(UsbPlaybackPolicy.COMPATIBILITY, controller.snapshot.value.usbPolicy)
            assertEquals(selected.id, controller.snapshot.value.trackId)
            assertEquals(5_000_000L, controller.snapshot.value.positionUs)
            failOpen = false
            assertTrue(controller.open(7))
            assertEquals(selected.id, controller.snapshot.value.trackId)
            assertEquals(5_000_000L, controller.snapshot.value.positionUs)
        } finally { controller.close() }
    }
    @Test fun refusedOnePlayConversionDoesNotLeaveTemporaryPermissionInTheSnapshot() = runBlocking {
        val output = Output().apply { calibrated = false }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            assertFalse(controller.start(track(), allowConversion = true))
            assertFalse(controller.snapshot.value.conversionAllowed)
            assertEquals(UsbPlaybackPolicy.STRICT, controller.snapshot.value.usbPolicy)
        } finally { controller.close() }
    }
    @Test fun compatibilityPersistsAcrossStartsAndStrictTransitionStopsBeforeChangingPermission() = runBlocking {
        val output = Output(); val saved = mutableListOf<UsbPlaybackPolicy>()
        val controller = UsbPlaybackController(saveUsbPolicy = { saved += it; true }) { output }
        try {
            assertTrue(controller.open(7)); assertFalse(controller.start(track()))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, controller.snapshot.value.failure)
            assertTrue(controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
            repeat(2) {
                assertTrue(controller.start(track())); assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
                assertFalse(controller.snapshot.value.conversionAllowed)
                assertTrue(controller.pause())
            }
            assertTrue(controller.start(track())); assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            val writes = output.writes
            assertTrue(controller.setUsbPolicy(UsbPlaybackPolicy.STRICT))
            assertEquals(UsbPlaybackState.PAUSED, controller.snapshot.value.state)
            assertEquals(writes, output.writes)
            assertFalse(controller.start(track()))
            assertEquals(listOf(UsbPlaybackPolicy.COMPATIBILITY, UsbPlaybackPolicy.STRICT), saved)
            assertTrue(controller.start(track(), true)); assertTrue(controller.pause())
            assertFalse(controller.snapshot.value.conversionAllowed)
            assertEquals(UsbPlaybackPolicy.STRICT, controller.snapshot.value.usbPolicy)
        } finally { controller.close() }
    }
    @Test fun conditionalPlansPreserveOrConvertWithoutRelaxingCalibration() = runBlocking {
        for ((source, rate, bits) in listOf(
            Triple(PcmFixture(44100, 16, 2), 44100, 16), Triple(PcmFixture(96000, 24, 2), 96000, 24),
            Triple(PcmFixture(192000, 24, 2), 96000, 24), Triple(PcmFixture(44100, 24, 2), 48000, 24),
            Triple(PcmFixture(48000, 24, 2), 48000, 16))) {
            val output = Output(rate, bits); val controller = UsbPlaybackController { output }
            try {
                controller.open(7)
                val unchanged = rate == source.sampleRate && bits >= source.significantBits
                assertEquals(unchanged, controller.start(track(source)))
                controller.pause(); assertTrue(controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
                assertTrue(controller.start(track(source)))
                assertEquals(unchanged, controller.snapshot.value.stream!!.bitPerfect)
                controller.pause(); output.calibrated = false
                assertFalse(controller.start(track(source)))
                assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED, controller.snapshot.value.failure)
            } finally { controller.close() }
        }
    }
    @Test fun followSongRateOffAuthorizesOnlyTheRateReductionInStrictMode() = runBlocking {
        val hiRes = TrackPcmFormat(96000, 24, 2, 24, "ALAC", true, 10_000_000)
        val lossyHiRes = TrackPcmFormat(96000, 32, 2, null, "AAC", false, 10_000_000)
        val saved = mutableListOf<Boolean>()
        val output = Output(48000, 24)
        val controller = UsbPlaybackController(saveFollowSongRate = { saved += it; true }) { output }
        try {
            controller.open(7)
            assertFalse("Following the song rate keeps Strict's refusal of resampling", controller.start(track(hiRes)))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, controller.snapshot.value.failure)
            assertTrue(controller.setFollowSongRate(false))
            assertEquals(listOf(false), saved)
            assertFalse(controller.snapshot.value.followSongRate)
            assertTrue(controller.start(track(hiRes))); assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            assertEquals(48000, controller.snapshot.value.stream!!.outputRate)
            assertFalse(controller.snapshot.value.stream!!.bitPerfect)
            assertFalse("The saved setting is not a one-play permission", controller.snapshot.value.conversionAllowed)
            assertEquals(UsbPlaybackPolicy.STRICT, controller.snapshot.value.usbPolicy)
            val writes = output.writes
            assertTrue(controller.setFollowSongRate(true))
            assertEquals("Changing the setting pauses direct USB", UsbPlaybackState.PAUSED, controller.snapshot.value.state)
            assertEquals(writes, output.writes)
            assertTrue(controller.setFollowSongRate(false))
            assertFalse("A lossy source still needs one-play permission", controller.start(track(lossyHiRes)))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, controller.snapshot.value.failure)
            assertEquals(listOf(false, true, false), saved)
        } finally { controller.close() }
        val shallow = Output(48000, 16)
        val reduced = UsbPlaybackController(initialFollowSongRate = false) { shallow }
        try {
            assertFalse(reduced.snapshot.value.followSongRate)
            reduced.open(7)
            assertFalse("A rate step-down never authorizes precision loss",
                reduced.start(track(TrackPcmFormat(96000, 24, 2, 24, "ALAC", true, 10_000_000))))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, reduced.snapshot.value.failure)
        } finally { reduced.close() }
    }

    @Test fun volumeSettingsAreValidatedRetainedWithoutUsbAndResetSafelyFromInvalidStorage()=runBlocking {
        val controller=UsbPlaybackController(initialVolumeLimitDb=-25f,initialUsbKeyStepDb=0.5f) {Output()}
        try {
            assertEquals(-25f,controller.snapshot.value.volumeLimitDb);assertEquals(0.5f,controller.snapshot.value.usbKeyStepDb)
            assertTrue(controller.setVolumeLimit(-40f));assertTrue(controller.setUsbKeyStep(3f))
            assertEquals(-40f,controller.snapshot.value.volumeLimitDb);assertEquals(3f,controller.snapshot.value.usbKeyStepDb)
            assertTrue(controller.close());assertEquals(-40f,controller.snapshot.value.volumeLimitDb)
            assertEquals(3f,controller.snapshot.value.usbKeyStepDb)
        } finally {controller.close()}
        val corrupt=UsbPlaybackController(initialVolumeLimitDb=Float.NaN,initialUsbKeyStepDb=1.5f) {Output()}
        try {assertEquals(0f,corrupt.snapshot.value.volumeLimitDb);assertEquals(1f,corrupt.snapshot.value.usbKeyStepDb)}
        finally {corrupt.close()}
    }
    @Test fun startVolumeIsValidatedSavedAndGivenToTheNextStream() = runBlocking {
        val output = Output(); val saved = mutableListOf<Float>(); var allowSave = true
        val controller = UsbPlaybackController(initialUsbPolicy = UsbPlaybackPolicy.COMPATIBILITY,
            initialStartVolumeDb = -12f, saveStartVolumeDb = { saved += it; allowSave }) { output }
        try {
            assertEquals(-12f, controller.snapshot.value.startVolumeDb)
            for (invalid in listOf(Float.NaN, 1f, -81f, -5.5f)) assertFalse(controller.setStartVolume(invalid))
            assertTrue(controller.setStartVolume(-20f)); assertEquals(-20f, controller.snapshot.value.startVolumeDb)
            allowSave = false
            assertFalse(controller.setStartVolume(-30f)); assertEquals(-20f, controller.snapshot.value.startVolumeDb)
            assertEquals(listOf(-20f, -30f), saved)
            assertTrue(controller.open(7)); assertEquals(-20f, controller.snapshot.value.startVolumeDb)
            assertTrue(controller.start(track())); assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            assertEquals(-20f, output.startVolumeDb)
            assertFalse("A slider target needs a number", controller.setUsbVolume(Float.NaN, controller.snapshot.value.generation))
        } finally { controller.close() }
        val fallback = UsbPlaybackController(initialStartVolumeDb = Float.NaN) { output }
        try { assertEquals(USB_START_VOLUME_DEFAULT_DB, fallback.snapshot.value.startVolumeDb) } finally { fallback.close() }
    }

    @Test fun failedFollowSongRateSaveKeepsTheOldSetting() = runBlocking {
        val output = Output()
        val controller = UsbPlaybackController(saveFollowSongRate = { false }) { output }
        try {
            controller.open(7)
            assertFalse(controller.setFollowSongRate(false))
            assertTrue(controller.snapshot.value.followSongRate)
        } finally { controller.close() }
    }

    @Test fun saveOrCleanupFailureDoesNotApplyNewModeAndCloseKeepsSavedPolicy() = runBlocking {
        val output = Output(); var saves = 0; var allowSave = false
        val controller = UsbPlaybackController(saveUsbPolicy = { saves++; allowSave }) { output }
        try {
            controller.open(7)
            assertFalse(controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
            assertEquals(UsbPlaybackPolicy.STRICT, controller.snapshot.value.usbPolicy)
            allowSave = true; assertTrue(controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
            assertTrue(controller.start(track())); assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            output.quiet = false
            assertFalse(controller.setUsbPolicy(UsbPlaybackPolicy.STRICT))
            assertEquals(2, saves)
            assertEquals(UsbPlaybackPolicy.COMPATIBILITY, controller.snapshot.value.usbPolicy)
            output.quiet = true; assertTrue(controller.close())
            assertEquals(UsbPlaybackPolicy.COMPATIBILITY, controller.snapshot.value.usbPolicy)
            controller.open(7); assertTrue(controller.start(track()))
        } finally { output.quiet = true; controller.close() }
    }

    @Test fun cancelledSavePublishesThePersistedPolicyWithoutAutoplay() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var saved = UsbPlaybackPolicy.STRICT
        val output = Output()
        val controller = UsbPlaybackController(saveUsbPolicy = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); saved = it; true
        }) { output }
        try {
            controller.open(7)
            val command = launch(Dispatchers.Default) { controller.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); command.cancel(); release.countDown(); command.join()
            assertEquals(saved, controller.snapshot.value.usbPolicy)
            assertEquals(UsbPlaybackState.PAUSED, controller.snapshot.value.state)
            assertEquals(0, output.starts)
            val restarted = UsbPlaybackController(initialUsbPolicy = saved) { Output() }
            assertEquals(UsbPlaybackPolicy.COMPATIBILITY, restarted.snapshot.value.usbPolicy)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun usbPreferenceChangeDoesNotStopAndroidOrAbandonItsFocus() = runBlocking {
        val usb = Output()
        val android = object : PlaybackOutput by usb {
            override val route = PlaybackRoute.ANDROID
            override fun startOutput(source: PcmFormat): StartedOutput {
                usb.start(source); return StartedOutput.Android(AndroidOutputInfo(source.sampleRate, source.channels, 2))
            }
        }
        val focus = object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        }
        val owner = UsbSessionOwner(UsbPlaybackController(openAndroidOutput = { android }) { usb }, focus)
        try {
            owner.selectTrack { track() }; owner.openAndroid(); assertTrue(owner.play())
            assertTrue(usb.wrote.await(5, TimeUnit.SECONDS))
            val before = owner.snapshot.value
            assertTrue(owner.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
            assertEquals(before.generation, owner.snapshot.value.generation)
            assertEquals(UsbPlaybackState.PLAYING, owner.snapshot.value.state)
            assertEquals(1L, usb.stopped.count)
            assertTrue(focus.held)
            owner.close(); assertEquals(UsbPlaybackPolicy.COMPATIBILITY, owner.snapshot.value.usbPolicy)
        } finally { owner.close() }
    }

    @Test fun cancelledBeforePolicyWorkKeepsFocusForTheUnchangedPlay() = runBlocking {
        val output = Output()
        val focus = object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        }
        val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        try {
            owner.open(7); assertTrue(owner.play())
            assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            val generation = owner.snapshot.value.generation
            launch(Dispatchers.Unconfined) {
                currentCoroutineContext().cancel()
                owner.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY)
            }.join()
            assertEquals(generation, owner.snapshot.value.generation)
            assertEquals(UsbPlaybackState.PLAYING, owner.snapshot.value.state)
            assertEquals(UsbPlaybackPolicy.STRICT, owner.snapshot.value.usbPolicy)
            assertTrue(focus.held)
        } finally { owner.close() }
    }
}
