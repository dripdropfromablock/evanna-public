package com.evanna.player.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidBitPerfectTest {
    private val flac24 = TrackPcmFormat(96000, 24, 2, 24, "FLAC", true, 1_000_000)
    private val mp3 = TrackPcmFormat(96000, 32, 2, null, "MP3", false, 1_000_000)

    /** Records the integers it is given; plays at the source rate like Android's bit-perfect mixer. */
    private class Sink(override val bitPerfect: Boolean = true, override val encodingBits: Int = 24) : AndroidTrackSink {
        override val sampleRate = 96000
        override val channels = 2
        override val primingFrames = 0
        override val deviceId = 3
        override val deviceType = 11
        override val routeChanged = false
        override val playbackHead = 0
        override val underruns = 0
        var playing = false
        val written = mutableListOf<Int>()
        override fun play() { playing = true }
        override fun ready() = playing
        override fun write(data: FloatArray, offset: Int, count: Int): Int = error("Floats must not reach a bit-perfect sink")
        override fun writePcm(data: ByteBuffer, offset: Int, count: Int): Int { repeat(count) { written += data.getInt((offset + it) * 4) }; return count }
        override fun pauseAndFlush() { playing = false }
        override fun release() = Unit
    }

    @Test fun onlyUnchangedIntegerSourcesMatchAnExactBitPerfectOffer() {
        val offers = listOf(MixerOffer(96000, 2, 24, false), MixerOffer(48000, 2, 24, true), MixerOffer(96000, 2, 24, true))
        assertEquals(MixerOffer(96000, 2, 24, true), bitPerfectOffer(offers, flac24))
        assertNull(bitPerfectOffer(offers, mp3))
        assertNull(bitPerfectOffer(offers.dropLast(1), flac24))
        assertNull(bitPerfectOffer(offers, flac24.copy(channels = 1)))
    }

    @Test fun packingKeepsEverySignificantBitInLittleEndianOrder() {
        val source = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x12345600).putInt(4, Int.MIN_VALUE)
        val target = ByteArray(8)
        assertEquals(6, packPcm(source, 0, 2, 24, target))
        assertArrayEquals(byteArrayOf(0x56, 0x34, 0x12, 0, 0, 0x80.toByte()), target.copyOf(6))
        assertEquals(4, packPcm(source, 0, 2, 16, target))
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0, 0x80.toByte()), target.copyOf(4))
        assertEquals(4, packPcm(source, 1, 1, 32, target))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0x80.toByte()), target.copyOf(4))
    }

    @Test fun streamingBuffersContainWholeFramesForPacked24BitOutput() {
        for (rate in listOf(32000, 64000)) {
            val size = audioBufferSize(minimum = 1, sampleRate = rate, channels = 2, bytesPerSample = 3)
            assertEquals(rate / 10 * 6, size)
            assertEquals(0, size % 6)
        }
        assertEquals(19206, audioBufferSize(minimum = 19201, sampleRate = 32000, channels = 2, bytesPerSample = 3))
        assertEquals(25600, audioBufferSize(minimum = 1, sampleRate = 32000, channels = 2, bytesPerSample = 4))
    }

    @Test fun outputWritesUnchangedIntegersAndReportsTheConfirmedRoute() {
        val sink = Sink()
        val output = AndroidAudioOutput({ _, _ -> sink }, bitPerfect = true)
        val info = (output.startOutput(flac24) as StartedOutput.Android).info
        assertTrue(info.bitPerfect); assertEquals(24, info.bits)
        val pcm = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x7fffff00).putInt(4, -256).putInt(8, 256).putInt(12, 0)
        assertEquals(2, output.write(pcm, 2))
        assertEquals(listOf(0x7fffff00, -256, 256, 0), sink.written)
        assertTrue(playbackSignalStatus(UsbPlaybackSnapshot(route = PlaybackRoute.ANDROID, androidStream = info)).contains("bit-perfect USB mixer"))
        assertTrue(runCatching { AndroidAudioOutput({ _, _ -> Sink(bitPerfect = false) }, bitPerfect = true).startOutput(flac24) }.isFailure)
        assertTrue(runCatching { AndroidAudioOutput({ _, _ -> Sink() }, bitPerfect = true).startOutput(mp3) }.isFailure)
    }

    @Test fun theExplicitRouteRefusesConversionAndTheOrdinaryRouteIsUnchanged() = runBlocking {
        fun track(format: TrackPcmFormat) = object : PcmTrack {
            override val id = "android-bit-perfect"
            override val format = format
            override fun open(): PcmReader = object : PcmReader {
                override val format = format
                override fun read(target: ByteBuffer, maxFrames: Int): Int { Thread.sleep(5); return 1 }
                override fun requestCancel() = Unit
                override fun close() = Unit
            }
            override fun close() = Unit
        }
        val controller = UsbPlaybackController(openAndroidOutput = { bitPerfect -> AndroidAudioOutput({ _, _ -> Sink(bitPerfect) }, bitPerfect = bitPerfect) }) { error("USB unused") }
        try {
            assertTrue(controller.openAndroid(true))
            assertFalse(controller.start(track(mp3), allowConversion = true))
            assertEquals(UsbPlaybackFailure.UNSUPPORTED_FORMAT, controller.snapshot.value.failure)
            assertTrue(controller.start(track(flac24)))
            assertTrue(controller.snapshot.value.androidStream!!.bitPerfect)
        } finally { controller.close() }
    }
}
