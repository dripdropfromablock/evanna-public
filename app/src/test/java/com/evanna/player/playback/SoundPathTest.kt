package com.evanna.player.playback

import com.truesample.usbaudio.*
import org.junit.Assert.*
import org.junit.Test

class SoundPathTest {
    private val format = DacFormat(1, 1, 1, 2, 3, 24, DacFormat.SyncType.SYNC, 288, listOf(44100, 48000), 44100, 48000)
    private val exactStream = StreamInfo(44100, 24, 2, format, 44100, 0, true, false)
    private val exact = UsbPlaybackSnapshot(route = PlaybackRoute.DIRECT_USB, state = UsbPlaybackState.PLAYING,
        source = TrackPcmFormat(44100, 24, 2, 24, "FLAC", true, 1000000), stream = exactStream,
        stats = StreamStats(10, 0, 0, false), readiness = UsbPlaybackReadiness(true, true, true),
        dacProfileName = "FIIO JD1", usbVolume = VolumeRange(-74f, 0f, 1f, -8f))
    private val converted = exact.copy(source = TrackPcmFormat(96000, 24, 2, 24, "ALAC", true, 1000000),
        stream = StreamInfo(96000, 24, 2, format, 48000, 48000, false, true), followSongRate = false, maxUsbRate = 48000)

    @Test fun anUntouchedSongIsAnExactCopyFromFileToHeadphones() {
        val path = soundPath(exact)
        assertEquals("An exact copy of your file", path.verdict)
        assertTrue(path.playing)
        assertEquals(listOf(StopKind.FILE, StopKind.EVANNA, StopKind.USB, StopKind.DAC, StopKind.HEADPHONES), path.stops.map { it.kind })
        assertEquals("Lossless · 24-bit · 44.1 kHz", path.stops[0].value)
        assertEquals(StopState.SAME, path.stops[1].state)
        assertEquals("FIIO JD1", path.stops[3].title)
        assertEquals("Plays 24-bit · 44.1 kHz at −8 dB volume", path.stops[3].value)
        assertEquals(StopState.END, path.stops.last().state)
        assertFalse(path.offerOtherOutput)
    }

    @Test fun aRateCeilingNamesTheConversionAndItsHeadroomAtEvannasStop() {
        val path = soundPath(converted)
        assertEquals("Changed once on the way", path.verdict)
        assertEquals("Your DAC is set to 48 kHz at most, so Evanna converts this 96 kHz song.", path.why)
        val evanna = path.stops.first { it.kind == StopKind.EVANNA }
        assertEquals(StopState.CHANGED, evanna.state)
        assertTrue(evanna.value, evanna.value.startsWith("Turned into 48 kHz, 2 dB quieter"))
        assertTrue(("Headroom" to "−2 dB") in evanna.details)
        assertEquals(StopState.SAME, path.stops.first { it.kind == StopKind.USB }.state)
    }

    @Test fun lostPacketsAndCutPeaksAreProblemsWithTheirOwnStop() {
        val lost = soundPath(converted.copy(stats = StreamStats(10, 0, 0, false, droppedPackets = 13, droppedSinceOpen = 20)))
        assertEquals("1 problem: lost sound", lost.verdict)
        assertTrue(lost.why.contains("13 tiny bits"))
        val usb = lost.stops.first { it.kind == StopKind.USB }
        assertEquals(StopState.PROBLEM, usb.state)
        assertTrue(("Lost packets" to "13 this song, 20 since you plugged in") in usb.details)
        assertTrue(lost.offerOtherOutput)
        val both = soundPath(converted.copy(stats = StreamStats(10, 0, 0, false, clippedSamples = 4, droppedPackets = 1)))
        assertEquals("2 problems found", both.verdict)
        assertEquals(StopState.PROBLEM, both.stops.first { it.kind == StopKind.EVANNA }.state)
    }

    @Test fun pausedAndStoppedPlaybackSayWhatIsKnown() {
        val paused = soundPath(exact.copy(state = UsbPlaybackState.PAUSED, stream = null, stats = null))
        assertEquals("Paused", paused.verdict)
        assertFalse(paused.playing)
        assertEquals("Waiting for playback", paused.stops.first { it.kind == StopKind.USB }.value)
        val stopped = soundPath(exact.copy(failure = UsbPlaybackFailure.VOLUME_UNVERIFIED))
        assertEquals("Playback stopped", stopped.verdict)
        assertEquals(StopState.PROBLEM, stopped.stops.first { it.kind == StopKind.DAC }.state)
    }

    @Test fun androidAudioNamesItsMixerAndTheDeviceAtTheEnd() {
        val speaker = soundPath(exact.copy(route = PlaybackRoute.ANDROID, stream = null, androidStream = AndroidOutputInfo(44100, 2, 2)))
        assertEquals("Android may change the sound", speaker.verdict)
        assertEquals(StopState.CHANGED, speaker.stops.first { it.kind == StopKind.ANDROID }.state)
        assertEquals(StopKind.SPEAKER, speaker.stops.last().kind)
        val usb = soundPath(exact.copy(route = PlaybackRoute.ANDROID, stream = null,
            androidStream = AndroidOutputInfo(96000, 2, 22, bitPerfect = true, bits = 24)))
        assertEquals("An exact copy, Android says", usb.verdict)
        assertEquals(listOf(StopKind.FILE, StopKind.EVANNA, StopKind.ANDROID, StopKind.DAC, StopKind.HEADPHONES), usb.stops.map { it.kind })
        assertEquals("No output picked yet", soundPath(UsbPlaybackSnapshot()).verdict)
    }

    @Test fun stopsSurviveTheSessionExtrasAndBadLinesAreRefused() {
        for (stop in soundPath(converted).stops) assertEquals(stop, decodeStop(encodeStop(stop)))
        assertNull(decodeStop("FILE"))
        assertNull(decodeStop(listOf("NOPE", "SAME", "t", "v", "").joinToString("\u001E")))
        assertEquals("44.1 kHz", khz(44100)); assertEquals("96 kHz", khz(96000)); assertEquals("352.8 kHz", khz(352800))
    }
}
