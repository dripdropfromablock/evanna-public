package com.evanna.player.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.truesample.usbaudio.*

class AndroidAudioOutputTest {
    private val format = TrackPcmFormat(48000, 32, 2, null, "MP3", false, 10_000_000)
    private class Sink(override val deviceId: Int = 7) : AndroidTrackSink {
        override val sampleRate = 48000
        override val channels = 2
        override val primingFrames = 8
        override val deviceType = 2
        override var routeChanged = false
        override var playbackHead = 8
        override val underruns = 0
        var chunk = 2
        var zero = false
        var error = false
        var playing = false
        var failPause = false
        var failRelease = false
        var released = false
        var autoAdvance = false
        var loseRouteOnWrite = false
        val samples = mutableListOf<Float>()
        override fun play() { playing = true }
        override fun ready() = playing && !routeChanged
        override fun write(data: FloatArray, offset: Int, count: Int): Int {
            if (error) return -6
            if (zero) return 0
            val accepted = minOf(chunk, count)
            repeat(accepted) { samples.add(data[offset + it]) }
            if (autoAdvance) playbackHead += accepted / channels
            if (loseRouteOnWrite) routeChanged = true
            return accepted
        }
        override fun pauseAndFlush() { check(!failPause); playing = false }
        override fun release() { check(!failRelease); released = true }
    }
    private fun pcm(vararg values: Int) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEachIndexed { i, value -> putInt(i * 4, value) }
    }
    @Test fun convertsS32AndHandlesPartialWritesWithoutFakeUsbEvidence() {
        val sink = Sink(); val output = AndroidAudioOutput({ _, _ -> sink })
        try {
            val started = output.startOutput(format) as StartedOutput.Android
            assertEquals(48000, started.info.sampleRate)
            assertEquals(PlaybackRoute.ANDROID, output.route)
            assertTrue(output.ready())
            assertEquals(2, output.write(pcm(Int.MIN_VALUE, Int.MAX_VALUE, 0, 1073741824), 2))
            assertEquals(listOf(-1f, 1f, 0f, 0.5f), sink.samples)
            assertEquals(2L, output.stats().framesSent)
            assertEquals(0L, output.stats().framesCompleted)
            sink.playbackHead = 10
            assertTrue(output.drain()); assertTrue(output.drain())
            assertEquals(2L, output.stats().framesCompleted)
            assertEquals(-1, output.write(pcm(0, 0), 1))
        } finally { output.stop(); output.close() }
        assertTrue(sink.released)
    }
    @Test fun errorsMisalignmentRouteChangeAndNoProgressFailBoundedly() {
        for (scenario in 0..3) {
            val sink = Sink(); var time = 0L
            val output = AndroidAudioOutput({ _, _ -> sink }, { time }, { time += 100_000_000 })
            try {
                output.startOutput(format)
                when (scenario) { 0 -> sink.error = true; 1 -> sink.chunk = 1; 2 -> sink.routeChanged = true; 3 -> sink.zero = true }
                assertEquals(-1, output.write(pcm(0, 0), 1))
                assertFalse(output.drain())
                assertFalse(output.ready())
            } finally { output.stop(); output.close() }
        }
    }
    @Test fun eofNeedsPlaybackHeadAndTimeoutDoesNotReleaseTheSink() {
        val sink = Sink(); var time = 0L
        val output = AndroidAudioOutput({ _, _ -> sink }, { time }, { time += 100_000_000 })
        try {
            output.startOutput(format); assertEquals(1, output.write(pcm(0, 0), 1))
            assertFalse(output.drain()); assertFalse(sink.released)
            assertTrue(output.stats().transferErrors > 0)
        } finally { output.stop(); output.close() }
    }
    @Test fun stopInterruptsDrainAndFreezesPositionBeforeHeadReset() {
        val sink = Sink(); val waiting = CountDownLatch(1); val release = CountDownLatch(1)
        val output = AndroidAudioOutput({ _, _ -> sink }, idle = { waiting.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            output.startOutput(format); output.write(pcm(0, 0), 1)
            val drain = executor.submit<Boolean> { output.drain() }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            sink.playbackHead = 9
            assertTrue(output.stop()); sink.playbackHead = 0; release.countDown()
            assertFalse(drain.get(2, TimeUnit.SECONDS))
            assertEquals(1L, output.stats().framesCompleted)
        } finally { release.countDown(); executor.shutdownNow(); output.stop(); output.close() }
    }
    @Test fun failedCleanupRetainsSinkAndRestartRejectsAChangedPinnedDevice() {
        val sink = Sink(); val output = AndroidAudioOutput({ _, pinned -> check(pinned == null || pinned == 7); sink })
        output.startOutput(format)
        sink.failPause = true
        assertFalse(output.stop()); assertFalse(sink.released)
        sink.failPause = false; assertTrue(output.stop())
        sink.failRelease = true
        assertTrue(runCatching { output.close() }.isFailure)
        assertFalse(sink.released)
        sink.failRelease = false; output.close(); assertTrue(sink.released)
    }
    @Test fun playbackHeadTreatsSignedBoundaryAsUnsignedAndRejectsReset() {
        val head = PlaybackHeadCounter()
        assertEquals(2147483647L, head.update(Int.MAX_VALUE))
        assertEquals(2147483648L, head.update(Int.MIN_VALUE))
        assertEquals(4294967295L, head.update(-1))
        assertEquals(4294967298L, head.update(2))
        assertTrue(runCatching { head.update(1) }.isFailure)
    }
    @Test fun restartingCannotAdoptAnUnselectedDevice() {
        val old = Sink(); val replacement = Sink(8); var first = true
        val output = AndroidAudioOutput({ _, pinned ->
            if (first) { first = false; old } else { assertEquals(7, pinned); replacement }
        })
        try {
            output.startOutput(format); assertTrue(output.stop())
            assertTrue(runCatching { output.startOutput(format) }.isFailure)
            assertFalse(output.ready())
        } finally { output.stop(); output.close() }
    }
    @Test fun invalidProgressDoesNotQuarantineASuccessfullyStoppedTrack() {
        val sink = Sink(); val output = AndroidAudioOutput({ _, _ -> sink })
        try {
            output.startOutput(format); output.write(pcm(0, 0), 1)
            sink.playbackHead = 9
            assertEquals(1L, output.stats().framesCompleted)
            sink.playbackHead = 0
            assertTrue(output.stats().transferErrors > 0)
            assertFalse(output.ready())
            assertTrue(output.stop())
            assertEquals(1L, output.stats().framesCompleted)
            output.close(); assertTrue(sink.released)
        } finally {
            if (!sink.released) { sink.playbackHead = 9; output.stop(); output.close() }
        }
    }
    @Test fun commonOwnerPlaysAndroidWithoutUsbProofAndSwitchBackStillRequiresCalibration() = runBlocking {
        var usbWrites = 0
        val usb = object : UsbPlaybackOutput {
            override val description = "Uncalibrated USB"
            override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, 32, false, true)
            override fun setEqualizer(bands: List<EqBand>, preampDb: Float) = Unit
            override fun prepareVolume() = false
            override fun start(source: PcmFormat): StreamInfo = error("USB must remain blocked")
            override fun readiness() = UsbPlaybackReadiness()
            override fun stats() = StreamStats(0, 0, 0, false)
            override fun write(buffer: ByteBuffer, frames: Int): Int { usbWrites++; return frames }
            override fun stop() = true
            override fun close() = Unit
        }
        val sink = Sink().apply { autoAdvance = true }
        val controller = UsbPlaybackController(openAndroidOutput = { AndroidAudioOutput({ _, _ -> sink }) }) { usb }
        val owner = UsbSessionOwner(controller, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        var opens = 0
        val track = object : PcmTrack {
            override val id = "android-source"
            override val format = this@AndroidAudioOutputTest.format
            override fun open(): PcmReader { opens++; return object : PcmReader {
                override val format = this@AndroidAudioOutputTest.format
                var done = false
                override fun read(target: ByteBuffer, maxFrames: Int): Int {
                    if (done) return 0
                    done = true; target.putInt(0, 1); target.putInt(4, -1); return 1
                }
                override fun requestCancel() = Unit
                override fun close() = Unit
            } }
            override fun close() = Unit
        }
        try {
            assertTrue(owner.openAndroid()); assertFalse(owner.play()); assertEquals(0, opens)
            assertTrue(owner.selectTrack { track }); assertTrue(owner.openAndroid()); assertTrue(owner.play())
            val end = withTimeout(5000) { owner.snapshot.first { it.state in listOf(UsbPlaybackState.ENDED, UsbPlaybackState.ERROR) } }
            assertEquals(UsbPlaybackState.ENDED, end.state)
            assertEquals(PlaybackRoute.ANDROID, end.route)
            assertNull(end.stream); assertNull(end.plan); assertNotNull(end.androidStream)
            assertEquals(UsbPlaybackReadiness(), end.readiness)
            assertTrue(owner.open(7)); assertFalse(owner.play())
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, owner.snapshot.value.failure)
            assertFalse(owner.play(true)); assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED, owner.snapshot.value.failure)
            assertEquals(1, opens); assertEquals(0, usbWrites)
        } finally { owner.close() }
    }
    @Test fun lostAndroidRouteStopsWithoutOpeningUsbOrStartingAnotherDevice() = runBlocking {
        val sink = Sink().apply { loseRouteOnWrite = true }
        var androidOpens = 0
        var usbOpens = 0
        val controller = UsbPlaybackController(openAndroidOutput = {
            androidOpens++; AndroidAudioOutput({ _, _ -> sink })
        }) { usbOpens++; error("No automatic USB fallback") }
        val track = object : PcmTrack {
            override val id = "route-loss"
            override val format = this@AndroidAudioOutputTest.format
            override fun open() = object : PcmReader {
                override val format = this@AndroidAudioOutputTest.format
                override fun read(target: ByteBuffer, maxFrames: Int): Int { target.putInt(0, 0); target.putInt(4, 0); return 1 }
                override fun requestCancel() = Unit
                override fun close() = Unit
            }
            override fun close() = Unit
        }
        try {
            assertTrue(controller.openAndroid()); assertTrue(controller.start(track))
            val stopped = withTimeout(5000) { controller.snapshot.first { it.state == UsbPlaybackState.ERROR } }
            assertEquals(UsbPlaybackFailure.ROUTE_CHANGED, stopped.failure)
            assertEquals(1, androidOpens); assertEquals(0, usbOpens)
            assertTrue(sink.samples.all { it == 0f })
        } finally { controller.close() }
    }
}
