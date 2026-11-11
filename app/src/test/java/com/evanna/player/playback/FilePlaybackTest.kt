package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class FilePlaybackTest {
    private val format = TrackPcmFormat(44100, 24, 2, 24, "FLAC", true, 91)
    private class Reader(override val format: PcmFormat) : PcmReader {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var cursor = 0
        var failRead = false
        var failClose = false
        var block = false
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        override fun read(target: ByteBuffer, maxFrames: Int): Int {
            calls += "read"
            reading.countDown()
            if (block) { check(cancelled.await(5, TimeUnit.SECONDS)); throw java.io.IOException() }
            check(!failRead)
            if (cursor == 4) return 0
            val frames = minOf(maxFrames, 4 - cursor)
            repeat(frames * 2) { target.putInt(it * 4, (cursor * 2 + it + 1) shl 8) }
            cursor += frames
            return frames
        }
        override fun requestCancel() { calls += "cancel"; cancelled.countDown() }
        override fun close() { calls += "close"; check(!failClose) }
    }
    private class Track(override val format: PcmFormat, val make: () -> PcmReader) : PcmTrack {
        override val id = "test-track"
        override fun open() = make()
        override fun close() = Unit
    }
    private class Output : UsbPlaybackOutput {
        override val description = "Synthetic finite output"
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        var calibrated = true
        var drains = 0
        var drainResult = true
        var drainAction: (() -> Boolean)? = null
        var counters = StreamStats(0, 0, 0, false)
        var afterWrite: () -> Unit = {}
        var stops = 0
        var starts = 0
        var closeResult = true
        override fun setEqualizer(bands: List<EqBand>, preampDb: Float) = Unit
        override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, source.significantBits, false, true)
        override fun prepareVolume() = calibrated
        override fun start(source: PcmFormat): StreamInfo {
            starts++
            return StreamInfo(source.sampleRate, source.significantBits, source.channels,
                DacFormat(1, 1, 1, source.channels, 4, source.significantBits, DacFormat.SyncType.SYNC, 1024,
                    listOf(source.sampleRate), source.sampleRate, source.sampleRate), source.sampleRate, source.sampleRate, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = counters
        override fun write(buffer: ByteBuffer, frames: Int): Int {
            check(buffer.isDirect && buffer.order() == ByteOrder.LITTLE_ENDIAN && buffer.position() == 0 && buffer.limit() == buffer.capacity())
            check(buffer.capacity() == frames * 8)
            repeat(frames * 2) { samples += buffer.getInt(it * 4) }
            afterWrite()
            return frames
        }
        override fun drain(): Boolean { drains++; return drainAction?.invoke() ?: drainResult }
        override fun stop(): Boolean { stops++; return true }
        override fun close() { check(closeResult) }
    }
    private suspend fun terminal(controller: UsbPlaybackController) = withTimeout(5000) {
        controller.snapshot.first { it.state in listOf(UsbPlaybackState.ENDED, UsbPlaybackState.ERROR) }
    }
    @Test fun finitePcmUsesBoundedFinalSliceAndDrainsBeforeEnded() = runBlocking {
        val reader = Reader(format); val output = Output(); val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7)); assertTrue(controller.start(Track(format) { reader }))
            val end = terminal(controller)
            assertEquals(UsbPlaybackState.ENDED, end.state)
            assertEquals((1..8).map { it shl 8 }, output.samples)
            assertEquals(4L, end.framesAccepted)
            assertEquals(1, output.drains)
            assertEquals(1, reader.calls.count { it == "close" })
        } finally { controller.close() }
    }
    @Test fun processedSourceNeedsFreshAuthorizationForEveryStart() = runBlocking {
        val processed = format.copy(significantBits = 32, sourceBits = null, codec = "MP3", strictEligible = false)
        val output = Output(); val controller = UsbPlaybackController { output }
        var opened = 0
        val track = Track(processed) { opened++; Reader(processed) }
        try {
            assertTrue(controller.open(7)); assertFalse(controller.start(track))
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED, controller.snapshot.value.failure)
            assertEquals(0, opened)
            assertTrue(controller.start(track, allowConversion = true)); terminal(controller)
            assertFalse(controller.start(track))
            assertEquals(1, opened)
            assertFalse(controller.snapshot.value.conversionAllowed)
        } finally { controller.close() }
    }
    @Test fun modeChangesAndOutputReopenAfterEofReplayFromTheBeginning() = runBlocking {
        val output = Output(); val controller = UsbPlaybackController { output }
        val owner = UsbSessionOwner(controller, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        val track = object : PcmTrack {
            override val id = "replay"
            override val format = this@FilePlaybackTest.format
            override val seekable = true
            override fun open(): PcmReader {
                val input = Reader(format)
                return object : PcmReader by input {
                    override fun seekTo(positionUs: Long) { input.cursor = ((positionUs * format.sampleRate + 999999) / 1000000).toInt() }
                }
            }
            override fun close() = Unit
        }
        try {
            assertTrue(owner.selectTrack { track }); assertTrue(owner.open(7)); assertTrue(owner.play()); terminal(controller)
            assertEquals(8, output.samples.size)
            val position = controller.snapshot.value.positionUs
            assertTrue(owner.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
            assertEquals(UsbPlaybackState.ENDED, controller.snapshot.value.state)
            assertEquals(position, controller.snapshot.value.positionUs)
            assertEquals(8, output.samples.size)
            assertTrue(owner.play()); terminal(controller)
            assertEquals(16, output.samples.size)
            assertTrue(owner.setUsbPolicy(UsbPlaybackPolicy.STRICT))
            assertEquals(UsbPlaybackState.ENDED, controller.snapshot.value.state)
            assertTrue(owner.open(7)); assertTrue(owner.play()); terminal(controller)
            assertEquals(24, output.samples.size)
        } finally { owner.close() }
    }
    @Test fun missingCalibrationDoesNotOpenTheReaderOrStartAudio() = runBlocking {
        val output = Output().apply { calibrated = false }; val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7)); assertFalse(controller.start(Track(format) { error("Reader must stay closed") }))
            assertEquals(UsbPlaybackFailure.VOLUME_UNVERIFIED, controller.snapshot.value.failure)
            assertEquals(0, output.starts)
        } finally { controller.close() }
    }
    @Test fun failedOpenAndChangedSourceFormatCannotStartTransport() = runBlocking {
        for (changed in listOf(false, true)) {
            val output = Output(); val controller = UsbPlaybackController { output }
            val reader = Reader(format.copy(channels = 1))
            try {
                assertTrue(controller.open(7))
                assertFalse(controller.start(Track(format) {
                    if (!changed) throw java.io.IOException()
                    reader
                }))
                assertEquals(UsbPlaybackFailure.SOURCE_OPEN_FAILED, controller.snapshot.value.failure)
                assertEquals(0, output.starts)
                assertTrue(output.samples.isEmpty())
                assertEquals(if (changed) 1 else 0, reader.calls.count { it == "close" })
            } finally { controller.close() }
        }
    }
    @Test fun readFailureAndUnverifiedDrainDoNotReportEnded() = runBlocking {
        for (readFailure in listOf(false, true)) {
            val output = Output().apply { drainResult = false }
            val reader = Reader(format).apply { failRead = readFailure }
            val controller = UsbPlaybackController { output }
            try {
                assertTrue(controller.open(7)); assertTrue(controller.start(Track(format) { reader }))
                val failed = terminal(controller)
                assertEquals(if (readFailure) UsbPlaybackFailure.SOURCE_READ_FAILED else UsbPlaybackFailure.DRAIN_UNVERIFIED, failed.failure)
                assertEquals(1, reader.calls.count { it == "close" })
            } finally { controller.close() }
        }
    }
    @Test fun failedDrainPreservesTheActualTransportFailure() = runBlocking {
        for (disconnected in listOf(false, true)) {
            val output = Output()
            output.drainAction = { output.counters = StreamStats(0, 0, 1, disconnected); false }
            val controller = UsbPlaybackController { output }
            try {
                assertTrue(controller.open(7)); assertTrue(controller.start(Track(format) { Reader(format) }))
                assertEquals(if (disconnected) UsbPlaybackFailure.DISCONNECTED else UsbPlaybackFailure.TRANSFER_FAILED, terminal(controller).failure)
            } finally { controller.close() }
        }
    }
    @Test fun eofFinalizesTrailingPaddingBeforeTestingUnderruns() = runBlocking {
        val output = Output()
        output.afterWrite = { output.counters = StreamStats(4, 1, 0, false) }
        output.drainAction = { output.counters = StreamStats(4, 0, 0, false); true }
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7)); assertTrue(controller.start(Track(format) { Reader(format) }))
            assertEquals(UsbPlaybackState.ENDED, terminal(controller).state)
            assertEquals(1, output.drains)
        } finally { controller.close() }
    }
    @Test fun realMidTrackGapStillStopsBeforeTheNextPcmWrite() = runBlocking {
        val output = Output()
        output.afterWrite = { output.counters = StreamStats(2, 1, 0, false) }
        val reader = Reader(format)
        val track = Track(format) { object : PcmReader by reader {
            override fun read(target: ByteBuffer, maxFrames: Int) = reader.read(target, minOf(2, maxFrames))
        } }
        val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7)); assertTrue(controller.start(track))
            assertEquals(UsbPlaybackFailure.TRANSFER_FAILED, terminal(controller).failure)
            assertEquals(0, output.drains)
            assertEquals(4, output.samples.size)
        } finally { controller.close() }
    }
    @Test fun pauseCancelsBlockedReadBeforeJoiningAndClosesItOnce() = runBlocking {
        val output = Output(); val reader = Reader(format).apply { block = true }; val controller = UsbPlaybackController { output }
        try {
            assertTrue(controller.open(7)); assertTrue(controller.start(Track(format) { reader }))
            assertTrue(reader.reading.await(5, TimeUnit.SECONDS))
            assertTrue(controller.pause())
            assertEquals(UsbPlaybackState.PAUSED, controller.snapshot.value.state)
            assertTrue(reader.calls.indexOf("cancel") < reader.calls.indexOf("close"))
            assertEquals(1, reader.calls.count { it == "close" })
            assertTrue(output.samples.isEmpty())
        } finally { controller.close() }
    }
    @Test fun failedReaderCloseRetainsOwnershipUntilCleanupRetry() = runBlocking {
        val reader = Reader(format).apply { block = true; failClose = true }; val controller = UsbPlaybackController { Output() }
        assertTrue(controller.open(7)); assertTrue(controller.start(Track(format) { reader }))
        assertTrue(reader.reading.await(5, TimeUnit.SECONDS))
        assertFalse(controller.pause())
        assertEquals(UsbPlaybackFailure.SOURCE_CLOSE_FAILED, controller.snapshot.value.failure)
        assertFalse(controller.start(Track(format) { error("Quarantined") }))
        reader.failClose = false
        assertTrue(controller.close())
    }
    @Test fun cancellingReaderAcquisitionClosesItBeforeAnyTransportStarts() = runBlocking {
        val output = Output(); val reader = Reader(format); val controller = UsbPlaybackController { output }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            assertTrue(controller.open(7))
            val job = scope.async { controller.start(Track(format) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); reader
            }) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel(); release.countDown(); job.join()
            assertEquals(0, output.starts)
            assertTrue(output.samples.isEmpty())
            assertEquals(1, reader.calls.count { it == "close" })
        } finally { release.countDown(); scope.cancel(); controller.close() }
    }
}
