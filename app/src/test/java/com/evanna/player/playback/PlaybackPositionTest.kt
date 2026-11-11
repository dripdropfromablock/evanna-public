package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class PlaybackPositionTest {
    private val format = TrackPcmFormat(48000, 16, 2, 16, "FLAC", true, 100_000_000)
    private class Reader(override val format: PcmFormat) : PcmReader {
        var seek = -1L
        var failSeek = false
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        override fun seekTo(positionUs: Long) { check(!failSeek); seek = positionUs }
        override fun read(target: ByteBuffer, maxFrames: Int): Int {
            reading.countDown(); check(cancelled.await(5, TimeUnit.SECONDS)); throw java.io.IOException()
        }
        override fun requestCancel() { cancelled.countDown() }
        override fun close() = Unit
    }
    private class Track(override val format: PcmFormat) : PcmTrack {
        override val id = "position-track"
        override val seekable = true
        var failSeek = false
        var reader: Reader? = null
        override fun open() = Reader(format).also { it.failSeek = failSeek; reader = it }
        override fun close() = Unit
    }
    private class Output : UsbPlaybackOutput {
        override val description = "position sink"
        var completed = 0L
        var starts = 0
        override fun setEqualizer(bands: List<EqBand>, preampDb: Float) = Unit
        override fun plan(source: PcmFormat) = PlaybackPlan(96000, 16, true, false)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            starts++; completed = 0
            return StreamInfo(48000, 16, 2, DacFormat(1, 1, 1, 2, 2, 16, DacFormat.SyncType.SYNC, 512,
                listOf(96000), 96000, 96000), 96000, 96000, false, true)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(900000, 0, 0, false, 1000000, completed)
        override fun write(buffer: ByteBuffer, frames: Int) = frames
        override fun stop() = true
        override fun close() = Unit
    }
    @Test fun pauseUsesCompletedOutputRateAndResumeSeeksBeforeStarting() = runBlocking {
        val output = Output(); val controller = UsbPlaybackController { output }; val track = Track(format)
        try {
            assertTrue(controller.open(7)); assertTrue(controller.start(track, true, 10_000_000))
            assertEquals(10_000_000L, track.reader!!.seek)
            assertTrue(track.reader!!.reading.await(5, TimeUnit.SECONDS))
            output.completed = 96000
            assertTrue(controller.pause())
            assertEquals(11_000_000L, controller.snapshot.value.positionUs)
            assertEquals(0, controller.snapshot.value.framesAccepted)
            assertTrue(controller.start(track, true, controller.snapshot.value.positionUs))
            assertEquals(11_000_000L, track.reader!!.seek)
            assertEquals(11_000_000L, controller.snapshot.value.positionUs)
        } finally { controller.close() }
    }
    @Test fun failedSeekCannotStartOutputOrLoseRequestedAnchor() = runBlocking {
        val output = Output(); val controller = UsbPlaybackController { output }; val track = Track(format).apply { failSeek = true }
        try {
            assertTrue(controller.open(7)); assertFalse(controller.start(track, true, 20_000_000))
            assertEquals(0, output.starts)
            assertEquals(20_000_000L, controller.snapshot.value.positionUs)
            assertEquals(UsbPlaybackFailure.SOURCE_SEEK_FAILED, controller.snapshot.value.failure)
            assertTrue(track.reader!!.cancelled.await(1, TimeUnit.SECONDS))
        } finally { controller.close() }
    }
    @Test fun pausedSeekDoesNotStartOutputAndStopResetsPosition() = runBlocking {
        val output = Output(); val owner = UsbSessionOwner(UsbPlaybackController { output }, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        try {
            assertTrue(owner.selectTrack { Track(format) }); assertTrue(owner.open(7))
            assertTrue(owner.seekTo(30_000_000))
            assertEquals(30_000_000L, owner.snapshot.value.positionUs)
            assertEquals(0, output.starts)
            assertFalse(owner.play()) // Conversion still requires explicit authorization.
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, owner.snapshot.value.failure)
            assertTrue(owner.play(true)); assertEquals(30_000_000L, (owner.selectedTrack as Track).reader!!.seek)
            assertTrue(owner.pause()); assertFalse(owner.play())
            assertTrue(owner.close()); assertEquals(0, owner.snapshot.value.positionUs)
        } finally { owner.close() }
    }
    @Test fun activeSeekRetainsFocusWithTheProductionObserver() = runBlocking {
        val output = Output()
        val focus = object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        }
        val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        val observer = launch(Dispatchers.Unconfined) { owner.snapshot.collect { releaseTerminalFocus(it.state, focus) } }
        try {
            assertTrue(owner.selectTrack { Track(format) }); assertTrue(owner.open(7)); assertTrue(owner.play(true))
            assertTrue(owner.seekTo(10_000_000))
            assertTrue(focus.held)
            assertEquals(UsbPlaybackState.PLAYING, owner.snapshot.value.state)
            assertEquals(2, output.starts)
        } finally { owner.close(); observer.cancelAndJoin() }
    }
    @Test fun failedOutputReopenRetainsTheSameSourceAnchorForRetry() = runBlocking {
        val output = Output()
        var failOpen = false
        val owner = UsbSessionOwner(UsbPlaybackController { check(!failOpen); output }, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        try {
            assertTrue(owner.selectTrack { Track(format) }); assertTrue(owner.open(7)); assertTrue(owner.seekTo(30_000_000))
            failOpen = true
            assertFalse(owner.open(7)); assertEquals(30_000_000L, owner.snapshot.value.positionUs)
            failOpen = false
            assertTrue(owner.open(7)); assertEquals(30_000_000L, owner.snapshot.value.positionUs)
            assertTrue(owner.selectTrack { Track(format) }); assertEquals(0L, owner.snapshot.value.positionUs)
            assertTrue(owner.play(true))
            output.completed = 96000
            assertTrue(owner.open(7))
            assertEquals(1_000_000L, owner.snapshot.value.positionUs)
        } finally { owner.close() }
    }
}
