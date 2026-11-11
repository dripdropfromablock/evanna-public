package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class UsbPlaybackControllerTest {
    private val fixture = PcmFixture(44100, 24, 2)
    private fun processingProfile() = com.evanna.player.user.DacProfile(com.evanna.player.usb.dacIdentity(1, 2, "DAC"),
        levelingMode = com.evanna.player.loudness.LevelingMode.TRACK, eqEnabled = true,
        equalizer = com.evanna.player.equalizer.EqualizerPreset(1, "Fixture", com.evanna.player.equalizer.EqualizerConfig(), 1),
        crossfeed = com.evanna.player.equalizer.Crossfeed.LIGHT)
    private fun assertProcessing(profile: com.evanna.player.user.DacProfile, state: UsbPlaybackSnapshot) {
        assertEquals(profile.identity.key, state.dacProfileKey)
        assertEquals(profile.levelingMode, state.levelingMode)
        assertEquals(profile.eqEnabled, state.eqEnabled)
        assertEquals(profile.equalizer, state.equalizer)
        assertEquals(profile.crossfeed, state.crossfeed)
    }

    @Test fun pausingAClosedOutputKeepsItsDacProcessingSettings() = runBlocking {
        val profile = processingProfile()
        val controller = UsbPlaybackController { Output() }
        try {
            assertTrue(controller.open(7, profile)); assertTrue(controller.close())
            assertTrue(controller.pause())
            assertProcessing(profile, controller.snapshot.value)
            assertTrue(controller.setLevelingMode(profile.levelingMode, profile.identity.key))
            assertProcessing(profile, controller.snapshot.value)
        } finally { controller.close() }
    }

    private class QueuedCaller : CoroutineDispatcher() {
        private val queued = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        fun next(): Runnable = checkNotNull(queued.poll(5, TimeUnit.SECONDS)) { "Caller continuation was not queued" }
        fun finish(job: Job) { while (!job.isCompleted) next().run() }
    }

    private class Output : UsbPlaybackOutput {
        override val description = "Synthetic stereo DAC"
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var proposed: PlaybackPlan? = PlaybackPlan(44100, 24, false, true)
        var actual = StreamInfo(44100, 24, 2,
            DacFormat(1, 1, 1, 2, 3, 24, DacFormat.SyncType.SYNC, 288, listOf(44100, 48000), 44100, 48000),
            44100, 44100, true, false)
        var readiness = UsbPlaybackReadiness(true, true, true)
        var volumePrepared = true
        var quiescent = true
        var failClose = false
        var writeAction: ((Int) -> Int)? = null
        var releaseOnStop = true
        var healthEntered: CountDownLatch? = null
        var releaseHealth: CountDownLatch? = null
        private var readinessCalls = 0
        var counters = StreamStats(0, 0, 0, false)
        var startEntered: CountDownLatch? = null
        var releaseStart: CountDownLatch? = null
        val wrote = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        var pcm: ByteArray? = null
        override fun plan(source: PcmFormat): PlaybackPlan? { calls += "plan"; return proposed }
        override fun setEqualizer(bands: List<EqBand>, preampDb: Float) {
            check(bands.isEmpty() && preampDb == 0f)
            calls += "clear-processing"
        }
        override fun prepareVolume(): Boolean { calls += "volume"; return volumePrepared }
        override fun start(source: PcmFormat): StreamInfo {
            calls += "start"
            startEntered?.countDown()
            check(releaseStart?.await(5, TimeUnit.SECONDS) != false)
            return actual
        }
        override fun readiness(): UsbPlaybackReadiness {
            calls += "readiness"
            if (++readinessCalls > 1) {
                healthEntered?.countDown()
                check(releaseHealth?.await(5, TimeUnit.SECONDS) != false)
            }
            return readiness
        }
        override fun stats() = counters
        override fun write(buffer: ByteBuffer, frames: Int): Int {
            calls += "write"
            pcm = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            wrote.countDown()
            writeAction?.let { return it(frames) }
            check(releaseWrite.await(5, TimeUnit.SECONDS))
            calls += "writer-exit"
            return -1
        }
        override fun stop(): Boolean {
            calls += "stop"
            releaseHealth?.countDown()
            if (releaseOnStop) releaseWrite.countDown()
            return quiescent
        }
        override fun close() {
            calls += "close"
            check(!failClose)
        }
    }

    private suspend fun assertNoPcm(output: Output, allowConversion: Boolean = false): UsbPlaybackSnapshot {
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7))
            assertFalse(controller.start(fixture, allowConversion))
            assertFalse(output.calls.contains("write"))
            return controller.snapshot.value
        } finally { controller.close() }
    }

    @Test fun matchingFormatClearsProcessingBeforeStartAndDeliversCanonicalPcm() = runBlocking {
        val output = Output()
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7))
            assertTrue(controller.start(fixture))
            assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("clear-processing", "plan", "volume", "start", "readiness"), output.calls.take(5))
            assertArrayEquals(byteArrayOf(0, 0, 0, -128, 0, -1, -1, 127, 0, 1, 0, 0, 0, -1, -1, -1), output.pcm!!.take(16).toByteArray())
        } finally { controller.close() }
    }

    @Test fun strictRejectsRateDepthNullAndInconsistentPlansBeforeStart() = runBlocking {
        val plans = listOf(null, PlaybackPlan(48000, 24, true, false), PlaybackPlan(44100, 16, false, false),
            PlaybackPlan(0, 24, false, true), PlaybackPlan(44100, 33, false, true),
            PlaybackPlan(48000, 24, false, true), PlaybackPlan(44100, 24, false, false))
        for (plan in plans) {
            val output = Output().apply { proposed = plan }
            assertNoPcm(output)
            assertFalse(output.calls.contains("start"))
        }
    }

    @Test fun changedOrMalformedActualStreamSubmitsNoPcmEvenWithConversionPermission() = runBlocking {
        val good = Output().actual
        val streams = listOf(good.copy(sampleRate = 48000), good.copy(bitsPerSample = 16),
            good.copy(channels = 1), good.copy(outputRate = 48000), good.copy(resampled = true),
            good.copy(bitPerfect = false), good.copy(format = good.format.copy(channels = 1)),
            good.copy(format = good.format.copy(subslotBytes = 2)),
            good.copy(format = good.format.copy(sampleRates = listOf(48000))),
            good.copy(format = good.format.copy(endpointAddress = 0x81)))
        for (stream in streams) assertNoPcm(Output().apply { actual = stream }, true)
    }

    @Test fun conflictingClockIsRejectedButUnknownReadbackStaysUnknown() = runBlocking {
        assertEquals(UsbPlaybackFailure.CLOCK_MISMATCH, assertNoPcm(Output().apply {
            actual = actual.copy(deviceRate = 48000)
        }).failure)
        val output = Output().apply { actual = actual.copy(deviceRate = 0) }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            assertTrue(controller.start(fixture))
            assertEquals(0, controller.snapshot.value.stream!!.deviceRate)
        } finally { controller.close() }
    }

    @Test fun missingVolumeOrTransportEvidenceSubmitsNoPcm() = runBlocking {
        val unsupported = Output().apply { volumePrepared = false }
        assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED, assertNoPcm(unsupported).failure)
        assertFalse(unsupported.calls.contains("start"))
        for (proof in listOf(UsbPlaybackReadiness(), UsbPlaybackReadiness(false, true, true),
            UsbPlaybackReadiness(true, false, true), UsbPlaybackReadiness(true, true, false))) {
            assertNoPcm(Output().apply { readiness = proof }, true)
        }
    }

    @Test fun conversionPermissionIsConsumedByOnlyOneStart() = runBlocking {
        val output = Output().apply {
            proposed = PlaybackPlan(48000, 16, true, false)
            actual = actual.copy(outputRate = 48000, deviceRate = 48000, resampled = true, bitPerfect = false,
                format = actual.format.copy(bitResolution = 16, subslotBytes = 2))
        }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            assertTrue(controller.start(fixture, allowConversion = true))
            assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            controller.pause()
            val writes = output.calls.count { it == "write" }
            assertFalse(controller.start(fixture))
            assertEquals(writes, output.calls.count { it == "write" })
            assertEquals(1, output.calls.count { it == "start" })
        } finally { controller.close() }
    }

    @Test fun stopReleasesBlockedWriterBeforeCloseAndDoubleCloseIsHarmless() = runBlocking {
        val output = Output()
        val controller = UsbPlaybackController { output }
        controller.open(7)
        controller.start(fixture)
        assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
        controller.close()
        controller.close()
        assertTrue(output.calls.indexOf("stop") < output.calls.indexOf("writer-exit"))
        assertTrue(output.calls.indexOf("writer-exit") < output.calls.indexOf("close"))
        assertEquals(1, output.calls.count { it == "close" })
        assertEquals(UsbPlaybackState.CLOSED, controller.snapshot.value.state)
    }

    @Test fun nonQuiescentOutputIsRetainedAndCannotRestart() = runBlocking {
        val output = Output()
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            controller.start(fixture)
            assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            output.quiescent = false
            assertFalse(controller.close())
            assertFalse(output.calls.contains("close"))
            assertFalse(controller.start(fixture))
            assertFalse(controller.pause())
            assertEquals(1, output.calls.count { it == "start" })
            assertEquals(UsbPlaybackFailure.STOP_FAILED, controller.snapshot.value.failure)
        } finally {
            output.quiescent = true
            assertTrue(controller.close())
        }
    }

    @Test fun partialWriteStopsWithoutRetryingAnAdvancedBuffer() = runBlocking {
        val output = Output().apply { writeAction = { 1 } }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            controller.start(fixture)
            val failed = withTimeout(5000) { controller.snapshot.first { it.failure == UsbPlaybackFailure.WRITE_FAILED } }
            assertEquals(UsbPlaybackState.ERROR, failed.state)
            assertEquals(1, output.calls.count { it == "write" })
            assertTrue(output.calls.contains("stop"))
        } finally { controller.close() }
    }

    @Test fun replacingConnectionNeverAutomaticallyStartsNewOutput() = runBlocking {
        val old = Output()
        val next = Output()
        val controller = UsbPlaybackController { id -> if (id == 7) old else next }
        try {
            controller.open(7)
            controller.start(fixture)
            assertTrue(old.wrote.await(5, TimeUnit.SECONDS))
            assertTrue(controller.open(8))
            assertEquals(8, controller.snapshot.value.deviceId)
            assertEquals(UsbPlaybackState.OPEN, controller.snapshot.value.state)
            assertTrue(old.calls.contains("close"))
            assertTrue(next.calls.isEmpty())
        } finally { controller.close() }
    }

    @Test fun cancelledStartupStopsBeforeWriting() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = Output().apply { startEntered = entered; releaseStart = release }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            val start = launch(Dispatchers.Default) { controller.start(fixture) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            start.cancel()
            release.countDown()
            withTimeout(5000) { start.join() }
            assertFalse(output.calls.contains("write"))
            assertTrue(output.calls.contains("stop"))
        } finally { release.countDown(); controller.close() }
    }

    @Test fun pauseDuringHealthReadDoesNotScheduleAnotherWrite() = runBlocking {
        val entered = CountDownLatch(1)
        val output = Output().apply { healthEntered = entered; releaseHealth = CountDownLatch(1) }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            controller.start(fixture)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(controller.pause())
            assertFalse(output.calls.contains("write"))
            assertEquals(UsbPlaybackState.PAUSED, controller.snapshot.value.state)
            assertEquals(UsbPlaybackReadiness(), controller.snapshot.value.readiness)
        } finally { controller.close() }
    }

    @Test fun writerJoinTimeoutRetainsOutputUntilTheWriterExits() = runBlocking {
        val output = Output().apply { releaseOnStop = false }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            controller.start(fixture)
            assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            assertFalse(controller.close())
            assertFalse(output.calls.contains("close"))
            assertEquals(UsbPlaybackFailure.STOP_FAILED, controller.snapshot.value.failure)
            assertFalse(controller.start(fixture))
        } finally {
            output.releaseWrite.countDown()
            assertTrue(controller.close())
        }
        assertTrue(output.calls.indexOf("writer-exit") < output.calls.indexOf("close"))
    }

    @Test fun lossOfFeedbackAfterOneBlockStopsBeforeTheNextBlock() = runBlocking {
        val output = Output()
        output.writeAction = { frames -> output.readiness = UsbPlaybackReadiness(true, false, true); frames }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            controller.start(fixture)
            withTimeout(5000) { controller.snapshot.first { it.failure == UsbPlaybackFailure.FEEDBACK_UNVERIFIED } }
            assertEquals(1, output.calls.count { it == "write" })
            assertTrue(output.calls.contains("stop"))
        } finally { controller.close() }
    }

    @Test fun disconnectReportedByAWriteKeepsItsActualFailureReason() = runBlocking {
        val output = Output()
        output.writeAction = { output.counters = StreamStats(0, 0, 0, true); -1 }
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7))
            assertTrue(controller.start(fixture))
            val failed = withTimeout(5000) { controller.snapshot.first { it.state == UsbPlaybackState.ERROR } }
            assertEquals(UsbPlaybackFailure.DISCONNECTED, failed.failure)
            assertTrue(failed.stats!!.disconnected)
            assertEquals(1, output.calls.count { it == "write" })
            assertTrue(output.calls.contains("stop"))
        } finally { controller.close() }
    }

    @Test fun liveDisconnectStopsBeforeAnotherBlockAndReplacementDoesNotResume() = runBlocking {
        val old = Output()
        val replacement = Output()
        old.writeAction = { frames -> old.counters = StreamStats(frames.toLong(), 0, 0, true); frames }
        val controller = UsbPlaybackController { id -> if (id == 7) old else replacement }
        try {
            assertTrue(controller.open(7))
            assertTrue(controller.start(fixture))
            withTimeout(5000) { controller.snapshot.first { it.failure == UsbPlaybackFailure.DISCONNECTED } }
            assertEquals(1, old.calls.count { it == "write" })
            assertTrue(old.calls.contains("stop"))
            assertEquals(UsbPlaybackReadiness(), controller.snapshot.value.readiness)
            assertTrue(controller.open(8))
            assertTrue(old.calls.contains("close"))
            assertEquals(UsbPlaybackState.OPEN, controller.snapshot.value.state)
            assertEquals(8, controller.snapshot.value.deviceId)
            assertFalse(replacement.calls.contains("start"))
            assertFalse(replacement.calls.contains("write"))
        } finally { controller.close() }
    }

    @Test fun disconnectedAndFailedTransportCountersPreventTheFirstBlock() = runBlocking {
        for (stats in listOf(StreamStats(0, 0, 0, true), StreamStats(0, 0, 1, false), StreamStats(0, 1, 0, false))) {
            assertNoPcm(Output().apply { counters = stats })
        }
    }

    @Test fun failedCloseKeepsOwnershipAndFailureUntilCleanupSucceeds() = runBlocking {
        val output = Output().apply { failClose = true }
        val controller = UsbPlaybackController { output }
        try {
            controller.open(7)
            assertFalse(controller.close())
            assertFalse(controller.start(fixture))
            assertFalse(controller.pause())
            assertEquals(UsbPlaybackFailure.CLOSE_FAILED, controller.snapshot.value.failure)
        } finally {
            output.failClose = false
            assertTrue(controller.close())
        }
    }

    @Test fun cancelledOpenReleasesAcquiredOutputAndCannotAutoStart() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = Output()
        val controller = UsbPlaybackController {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            output
        }
        try {
            val open = launch(Dispatchers.Default) { controller.open(7) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            open.cancel()
            release.countDown()
            withTimeout(5000) { open.join() }
            assertEquals(listOf("close"), output.calls.toList())
            assertEquals(UsbPlaybackState.CLOSED, controller.snapshot.value.state)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun failedReplacementDoesNotKeepThePreviousConnectionsMetadata() = runBlocking {
        val output = Output()
        val controller = UsbPlaybackController { id -> if (id == 7) output else error("open failed") }
        try {
            controller.open(7)
            assertFalse(controller.open(8))
            assertEquals(1, output.calls.count { it == "close" })
            assertEquals(8, controller.snapshot.value.deviceId)
            assertNull(controller.snapshot.value.description)
            assertEquals(UsbPlaybackFailure.OUTPUT_FAILED, controller.snapshot.value.failure)
            assertTrue(controller.pause())
            assertEquals(UsbPlaybackState.CLOSED, controller.snapshot.value.state)
            assertNull(controller.snapshot.value.deviceId)
        } finally { controller.close() }
    }

    @Test fun cancelledStartResultStopsTheWriterBeforeCancellationReturns() = runBlocking {
        for (quiescent in listOf(true, false)) {
            val output = Output().apply { this.quiescent = quiescent }
            val controller = UsbPlaybackController { output }
            val caller = QueuedCaller()
            var returnedNormally = false
            try {
                controller.open(7)
                val start = launch(caller) { controller.start(fixture); returnedNormally = true }
                caller.next().run()
                val delivery = caller.next()
                assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
                assertEquals(UsbPlaybackState.PLAYING, controller.snapshot.value.state)
                start.cancel()
                delivery.run()
                caller.finish(start)
                assertFalse(returnedNormally)
                assertTrue(output.calls.contains("stop"))
                assertTrue(output.calls.contains("writer-exit"))
                assertFalse(output.calls.contains("close"))
                assertEquals(if (quiescent) UsbPlaybackState.PAUSED else UsbPlaybackState.ERROR,
                    controller.snapshot.value.state)
                if (!quiescent) assertEquals(UsbPlaybackFailure.STOP_FAILED, controller.snapshot.value.failure)
            } finally { output.quiescent = true; controller.close() }
        }
    }

    @Test fun lateCancelledStartResultDoesNotStopAReplacementSession() = runBlocking {
        val old = Output()
        val next = Output()
        val controller = UsbPlaybackController { id -> if (id == 7) old else next }
        val caller = QueuedCaller()
        try {
            controller.open(7)
            val start = launch(caller) { controller.start(fixture) }
            caller.next().run()
            val delivery = caller.next()
            assertTrue(old.wrote.await(5, TimeUnit.SECONDS))
            controller.open(8)
            assertTrue(controller.start(fixture))
            assertTrue(next.wrote.await(5, TimeUnit.SECONDS))
            start.cancel()
            delivery.run()
            caller.finish(start)
            assertEquals(8, controller.snapshot.value.deviceId)
            assertEquals(UsbPlaybackState.PLAYING, controller.snapshot.value.state)
            assertFalse(next.calls.contains("stop"))
            assertFalse(next.calls.contains("close"))
        } finally { controller.close() }
    }

    @Test fun cancelledOpenResultReleasesItsAcquiredOutput() = runBlocking {
        val output = Output()
        val profile = processingProfile()
        val controller = UsbPlaybackController { output }
        val caller = QueuedCaller()
        var returnedNormally = false
        try {
            val open = launch(caller) { controller.open(7, profile); returnedNormally = true }
            caller.next().run()
            val delivery = caller.next()
            assertEquals(UsbPlaybackState.OPEN, controller.snapshot.value.state)
            open.cancel()
            delivery.run()
            caller.finish(open)
            assertFalse(returnedNormally)
            assertEquals(UsbPlaybackState.CLOSED, controller.snapshot.value.state)
            assertEquals(listOf("close"), output.calls.toList())
            assertProcessing(profile, controller.snapshot.value)
        } finally { controller.close() }
    }
}
