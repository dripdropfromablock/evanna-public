package com.evanna.player.diagnostics

import com.evanna.player.playback.*
import com.truesample.usbaudio.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackEvidenceTest {
    @Test fun androidEvidenceNeverClaimsUsbVerificationOrPhysicalPrecision() {
        val snapshot = UsbPlaybackSnapshot(route = PlaybackRoute.ANDROID, state = UsbPlaybackState.PLAYING,
            source = PcmFixture(48000, 24, 2), androidStream = AndroidOutputInfo(48000, 2, 2))
        val fields = playbackSignalFields(snapshot)
        assertEquals("ANDROID", fields[ReportField.ROUTE])
        assertEquals("ANDROID_SYSTEM", fields[ReportField.POLICY])
        assertEquals("PCM_FLOAT", fields[ReportField.OUTPUT_ENCODING])
        assertEquals("ANDROID_SYSTEM", fields[ReportField.VOLUME_CONTROL])
        assertEquals("UNKNOWN", fields[ReportField.OUTPUT_BITS])
        assertEquals("UNKNOWN", fields[ReportField.DEVICE_RATE])
        assertEquals("UNKNOWN", fields[ReportField.RESAMPLED])
        assertEquals("false", fields[ReportField.BIT_PERFECT_ELIGIBLE])
        assertFalse(fields.containsKey(ReportField.CLOCK_CONFIGURED))
        assertFalse(fields.containsKey(ReportField.VOLUME_VERIFIED))
        assertFalse(fields.containsKey(ReportField.USB_INPUT_ENDED))
        val safe = Incident("android", IncidentKind.HANDLED_FAILURE, 1, fields).safeFields()
        assertEquals("PCM_FLOAT", safe[ReportField.OUTPUT_ENCODING])
        assertEquals("ANDROID_SYSTEM", safe[ReportField.POLICY])
    }
    @Test fun outputQueueSubmissionAndCompletionCountersStayDistinct() {
        val fields = playbackSignalFields(UsbPlaybackSnapshot(stats = StreamStats(3, 0, 0, false, 5, 2, true)))
        assertEquals("5", fields[ReportField.FRAMES_QUEUED])
        assertEquals("3", fields[ReportField.FRAMES_SUBMITTED])
        assertEquals("2", fields[ReportField.FRAMES_COMPLETED])
        assertEquals("true", fields[ReportField.USB_INPUT_ENDED])
        val incident = Incident("drain", IncidentKind.HANDLED_FAILURE, 1, fields)
        assertEquals("true", incident.safeFields()[ReportField.USB_INPUT_ENDED])
        assertFalse(fields.containsKey(ReportField.POSITION_MS))
    }
    private val stream = StreamInfo(44100, 24, 2,
        DacFormat(1, 1, 1, 2, 3, 24, DacFormat.SyncType.SYNC, 288, listOf(44100), 44100, 44100),
        44100, 44100, true, false)
    @Test fun savedCompatibilityAndTemporaryConversionRemainDistinctInReports() {
        val snapshot = UsbPlaybackSnapshot(route = PlaybackRoute.DIRECT_USB, usbPolicy = UsbPlaybackPolicy.COMPATIBILITY)
        val fields = Incident("modes", IncidentKind.HANDLED_FAILURE, 1, playbackSignalFields(snapshot)).safeFields()
        assertEquals("COMPATIBILITY", fields[ReportField.POLICY])
        assertEquals("false", fields[ReportField.CONVERSION_AUTHORIZED])
        assertEquals("USB_HARDWARE", fields[ReportField.VOLUME_CONTROL])
        assertEquals("ANDROID_SYSTEM", playbackSignalFields(snapshot.copy(route = PlaybackRoute.ANDROID))[ReportField.POLICY])
    }
    @Test fun originalSourceAndLiveReadinessGateBitPerfectEvidence() {
        val clean = UsbPlaybackSnapshot(state = UsbPlaybackState.PLAYING, source = PcmFixture(44100, 24, 2),
            stream = stream, readiness = UsbPlaybackReadiness(true, true, true), route = PlaybackRoute.DIRECT_USB)
        assertEquals("true", playbackSignalFields(clean)[ReportField.BIT_PERFECT_ELIGIBLE])
        assertEquals("false", playbackSignalFields(clean.copy(state = UsbPlaybackState.PAUSED))[ReportField.BIT_PERFECT_ELIGIBLE])
        assertEquals("false", playbackSignalFields(clean.copy(readiness = UsbPlaybackReadiness()))[ReportField.BIT_PERFECT_ELIGIBLE])
        val processed = TrackPcmFormat(44100, 32, 2, null, "MP3", false, 2000000)
        val fields = playbackSignalFields(clean.copy(source = processed, conversionAllowed = true))
        assertEquals("false", fields[ReportField.BIT_PERFECT_ELIGIBLE])
        assertEquals("UNKNOWN", fields[ReportField.BITS])
        assertEquals("MP3", fields[ReportField.CODEC])
        assertEquals("true", fields[ReportField.CONVERSION_AUTHORIZED])
        assertFalse(fields.values.any { it.contains("content:") || it.contains("evanna:local:") })
    }
}
