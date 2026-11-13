package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsbSessionOwnerTest {
    @Test fun startupOpenStaysPausedAndLeavesAnExistingOutputAlone() = runBlocking {
        val usb = Output()
        val focus = Focus()
        val android = object : PlaybackOutput by Output() {
            override val route = PlaybackRoute.ANDROID
        }
        val owner = UsbSessionOwner(UsbPlaybackController(openAndroidOutput = { android }) { usb }, focus)
        try {
            assertTrue(owner.enqueue(QueueSource(null, "Saved song", null, null, 1_000L) { Track() }))
            val queue = owner.queue.value
            assertTrue(owner.open(7, onlyIfUnselected = true))
            assertEquals(UsbPlaybackState.OPEN, owner.snapshot.value.state)
            assertFalse(owner.openAndroid(onlyIfUnselected = true))
            assertEquals(PlaybackRoute.DIRECT_USB, owner.snapshot.value.route)
            assertTrue(owner.openAndroid())
            assertFalse(owner.open(7, onlyIfUnselected = true))
            assertEquals(PlaybackRoute.ANDROID, owner.snapshot.value.route)
            assertEquals(queue, owner.queue.value)
            assertEquals(0, usb.starts)
            assertEquals(0, focus.requested)
        } finally { owner.close() }
    }

    private class Track : PcmTrack {
        override val id = "evanna:local:test"
        override val format = TrackPcmFormat(48000, 16, 2, 16, "FLAC", true, 1000)
        var closes = 0
        var failClose = false
        override fun open(): PcmReader = error("Selection must not open a playback reader")
        override fun close() { closes++; check(!failClose) }
    }
    @Test fun selectingAndReplacingFilesDoesNotStartPlaybackAndReleasesOldTrack() = runBlocking {
        val output = Output(); val owner = UsbSessionOwner(UsbPlaybackController { output }, Focus())
        val first = Track(); val second = Track()
        try {
            assertTrue(owner.open(7)); assertTrue(owner.selectTrack { first })
            assertSame(first, owner.selectedTrack)
            assertEquals(0, output.starts)
            assertTrue(owner.selectTrack { second })
            assertEquals(1, first.closes)
            assertTrue(owner.close())
            assertEquals(1, second.closes)
            assertNull(owner.selectedTrack)
        } finally { owner.close() }
    }
    @Test fun failedFileSelectionCannotFallBackToTheAnalysisFixture() = runBlocking {
        val output = Output(); val owner = UsbSessionOwner(UsbPlaybackController { output }, Focus())
        try {
            assertTrue(owner.open(7))
            assertFalse(owner.selectTrack { throw java.io.IOException() })
            assertTrue(owner.sourceSelectionFailed)
            assertFalse(owner.play())
            assertEquals(0, output.starts)
        } finally { owner.close() }
    }
    @Test fun cancelledSelectionRetainsTrackWhenItsCleanupFails() = runBlocking {
        val owner = UsbSessionOwner(UsbPlaybackController { Output() }, Focus())
        val track = Track().apply { failClose = true }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.async {
                owner.selectTrack { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); track }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel(); release.countDown(); job.join()
            assertTrue(owner.sourceSelectionFailed)
            assertSame(track, owner.selectedTrack)
            track.failClose = false
            assertTrue(owner.close())
            assertEquals(2, track.closes)
        } finally { release.countDown(); track.failClose = false; scope.cancel(); owner.close() }
    }
    private class Focus : PlaybackFocus {
        var allowed = true
        var requested = 0
        var abandoned = 0
        override var held = false
        override fun request(): Boolean { requested++; held = allowed; return allowed }
        override fun abandon() { abandoned++; held = false }
    }
    private class Output : UsbPlaybackOutput {
        override val description = "Synthetic output"
        val wrote = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var starts = 0
        var closes = 0
        var calibrated = true
        override fun plan(source: PcmFormat) = PlaybackPlan(48000, 16, false, true)
        override fun prepareVolume() = calibrated
        override fun start(source: PcmFormat): StreamInfo {
            starts++
            return StreamInfo(48000, 16, 2,
                DacFormat(1, 1, 1, 2, 2, 16, DacFormat.SyncType.SYNC, 192, listOf(48000), 48000, 48000),
                48000, 48000, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(0, 0, 0, false)
        override fun write(buffer: ByteBuffer, frames: Int): Int { wrote.countDown(); check(stopped.await(5, TimeUnit.SECONDS)); return -1 }
        override fun stop(): Boolean { stopped.countDown(); return true }
        override fun close() { closes++ }
    }

    @Test fun deniedFocusCannotStartTheOutput() = runBlocking {
        val output = Output(); val focus = Focus().apply { allowed = false }
        val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        try {
            assertTrue(owner.open(7))
            assertFalse(owner.play())
            assertTrue(owner.focusDenied)
            assertEquals(0, output.starts)
        } finally { owner.close() }
    }

    @Test fun missingCalibrationIsNotBypassedBySessionPlay() = runBlocking {
        val output = Output().apply { calibrated = false }; val focus = Focus()
        val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        try {
            assertTrue(owner.open(7))
            val before = focus.abandoned
            assertFalse(owner.play())
            assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED, owner.snapshot.value.failure)
            assertEquals(0, output.starts)
            assertEquals(1L, output.wrote.count)
            assertTrue(focus.abandoned > before)
        } finally { owner.close() }
    }

    @Test fun focusLossPausesTheSameWriterAndDoesNotResume() = runBlocking {
        val output = Output(); val focus = Focus()
        val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        try {
            assertTrue(owner.open(7)); assertTrue(owner.play())
            assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            assertTrue(owner.pause())
            assertEquals(UsbPlaybackState.PAUSED, owner.snapshot.value.state)
            assertEquals(1, output.starts)
            assertTrue(focus.abandoned > 0)
            assertTrue(owner.close())
            assertEquals(1, output.closes)
            assertEquals(UsbPlaybackState.CLOSED, owner.snapshot.value.state)
        } finally { owner.close() }
    }

    @Test fun duplicatePlayDoesNotRestartTheWriter() = runBlocking {
        val output = Output(); val focus = Focus()
        val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        try {
            assertTrue(owner.open(7)); assertTrue(owner.play())
            assertTrue(output.wrote.await(5, TimeUnit.SECONDS))
            assertTrue(owner.play())
            assertEquals(1, output.starts)
            assertEquals(1, focus.requested)
        } finally { owner.close() }
    }

    @Test fun playWithoutAnOpenedDeviceDoesNotRequestFocus() = runBlocking {
        val focus = Focus()
        val owner = UsbSessionOwner(UsbPlaybackController { error("Must not open automatically") }, focus)
        assertFalse(owner.play())
        assertEquals(0, focus.requested)
        assertTrue(owner.close())
    }
}
