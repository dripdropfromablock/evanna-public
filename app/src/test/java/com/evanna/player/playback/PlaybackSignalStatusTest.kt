package com.evanna.player.playback

import com.truesample.usbaudio.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSignalStatusTest {
    @Test fun eqProcessingIsReportedAndCannotClaimAnUnchangedUsbSamplePath() {
        val processed=clean.copy(eqProcessed=true,eqPreampDb=-6.3f,stream=stream.copy(bitPerfect=false,processed=true))
        val status=playbackSignalStatus(processed)
        assertTrue(status.contains("EQ on") && status.contains("preamp -6.3 dB"));assertFalse(processed.usbSamplePathEligible())
        assertFalse(processed.copy(stream=stream.copy(bitPerfect=true,processed=true)).usbSamplePathEligible())
        val clipped=clean.copy(stats=StreamStats(0,0,0,false,clippedSamples=2))
        assertFalse(clipped.usbSamplePathEligible());assertTrue(playbackSignalStatus(clipped).contains("clipped float samples 2"))
    }
    @Test fun droppedUsbPacketsAreAProblemAndEndTheUnchangedClaim() {
        assertTrue(clean.copy(stats=StreamStats(10,0,0,false)).usbSamplePathEligible())
        val dropped=clean.copy(stats=StreamStats(10,0,0,false,droppedPackets=3,droppedSinceOpen=7))
        assertFalse(dropped.usbSamplePathEligible())
        assertTrue(playbackSignalStatus(dropped).contains("dropped packets 3"))
        assertEquals("Problem found. See Advanced",signalSummary(dropped).last().value)
    }
    @Test fun hardwareLevelingShowsBaseAndAppliedGainWithoutChangingSampleEligibility() {
        val state=clean.copy(levelingMode=com.evanna.player.loudness.LevelingMode.TRACK,
            leveling=com.evanna.player.loudness.LevelingDecision(com.evanna.player.loudness.LevelingMode.TRACK,-4f,"Track gain tag"),
            usbVolume=VolumeRange(-74f,0f,0.5f,-20f),appliedGainDb=-24f)
        assertTrue(state.usbSamplePathEligible())
        val status=playbackSignalStatus(state)
        assertTrue(status.contains("base -20.0 dB") && status.contains("applied -24.0 dB"))
        assertTrue(status.contains("Samples unchanged") && status.contains("Track gain tag"))
        assertTrue(playbackSignalStatus(state.copy(leveling=com.evanna.player.loudness.LevelingDecision(),stream=null)).contains("waiting for the next playback start"))
        assertTrue(playbackSignalStatus(state.copy(route=PlaybackRoute.ANDROID)).contains("Off on Android audio"))
    }
    @Test fun stagesUseObservedEvidenceAndSeparateConversionFromHardwareLevel() {
        val stages=signalStages(clean.copy(usbVolume=VolumeRange(-74f,0f,0.5f,-20f),
            stats=StreamStats(10,0,0,false)))
        assertFalse(stages.first {it.label=="Active processing"}.changed)
        assertFalse(stages.first {it.label=="Volume"}.changed)
        assertTrue(stages.first {it.label=="USB endpoint"}.value.contains("alt 1"))
        assertTrue(stages.first {it.label=="Health"}.value.contains("underruns 0"))
        val converted=signalStages(clean.copy(stream=stream.copy(outputRate=48000,bitPerfect=false,resampled=true)))
        assertTrue(converted.first {it.label=="Rate"}.changed)
        assertTrue(converted.first {it.label=="Active processing"}.changed)
        assertTrue(signalStages(clean.copy(route=PlaybackRoute.ANDROID)).first {it.label=="Processing"}.changed)
    }
    @Test fun minimalSummaryIsFourPlainLinesFromTheSameEvidenceAndStaysOutOfAdvancedAndSettings() {
        val healthy=clean.copy(usbVolume=VolumeRange(-74f,0f,0.5f,-20f),stats=StreamStats(10,0,0,false))
        val summary=signalSummary(healthy)
        assertEquals(listOf("Output","Processing","Volume","Health"),summary.map {it.label})
        assertEquals(listOf("Direct USB · 44100 Hz · 24-bit","None. Samples unchanged at the USB input",
            "DAC hardware volume -20.0 dB","No problems found"),summary.map {it.value})
        assertTrue(summary.all {it.brief} && summary.none {it.changed})
        assertTrue(signalStages(healthy).none {it.brief})
        assertFalse(playbackSignalStatus(healthy).contains("No problems found"))
        // Same honesty rule as the full rows: no claim of physical bit-perfect delivery.
        assertTrue(summary.none {it.value.contains("bit-perfect",ignoreCase=true)})
    }
    @Test fun minimalSummaryFlagsConversionsProblemsAndUnconfirmedStates() {
        val converted=signalSummary(clean.copy(stream=stream.copy(outputRate=48000,bitPerfect=false,resampled=true),stats=StreamStats(10,0,0,false)))
        assertEquals("Resampling 44100 → 48000 Hz with 2 dB headroom",converted.first {it.label=="Processing"}.value)
        assertTrue(converted.first {it.label=="Processing"}.changed && converted.first {it.label=="Output"}.changed)
        val unhealthy=signalSummary(clean.copy(stats=StreamStats(10,3,0,false))).last()
        assertEquals("Health",unhealthy.label);assertEquals("Problem found. See Advanced",unhealthy.value);assertTrue(unhealthy.changed)
        val paused=signalSummary(clean.copy(state=UsbPlaybackState.PAUSED))
        assertEquals(listOf("Last output","Processing","Volume","Health"),paused.map {it.label})
        assertEquals("Not confirmed yet",paused[1].value);assertEquals("DAC hardware volume not confirmed yet",paused[2].value)
        assertEquals("Not confirmed yet",paused[3].value)
        val planned=signalSummary(UsbPlaybackSnapshot(route=PlaybackRoute.DIRECT_USB,source=TrackPcmFormat(44100,32,2,null,"MP3",false,null),
            plan=PlaybackPlan(48000,16,true,false)))
        assertEquals(listOf("Planned output","Processing","Volume"),planned.map {it.label})
        assertEquals("Processed decode / S32 quantization; resampling 44100 → 48000 Hz with 2 dB headroom; precision reduction 32 → 16 bits",planned[1].value)
    }
    @Test fun minimalSummaryNeverBorrowsUsbClaimsForAndroidOrAnUnselectedOutput() {
        val android=signalSummary(clean.copy(route=PlaybackRoute.ANDROID,stream=null,androidStream=AndroidOutputInfo(44100,2,22)))
        assertEquals(listOf("Output","Processing","Volume"),android.map {it.label})
        assertEquals("Android audio · 44100 Hz",android[0].value)
        assertTrue(android[1].changed);assertEquals("Android system volume",android[2].value)
        val exact=signalSummary(clean.copy(route=PlaybackRoute.ANDROID,stream=null,androidStream=AndroidOutputInfo(44100,2,22,bitPerfect=true,bits=24)))
        assertEquals("Android audio · 44100 Hz · 24-bit",exact[0].value)
        assertFalse(exact[1].changed);assertTrue(exact[1].value.contains("Not verified by Evanna"))
        val idle=signalSummary(clean.copy(route=PlaybackRoute.ANDROID,stream=null,androidStream=null))
        assertEquals("Android audio · not started",idle[0].value);assertFalse(idle[0].changed)
        assertEquals(listOf(SignalStage("","No output selected. Select a local file and an output.",false,true)),signalSummary(UsbPlaybackSnapshot()))
    }
    @Test fun selectedProfileRateCeilingIsReportedRatherThanTheOldFixedCap() {
        val text=playbackSignalStatus(UsbPlaybackSnapshot(route=PlaybackRoute.DIRECT_USB,followSongRate=false,maxUsbRate=96000))
        assertTrue(text,text.contains("96 kHz"));assertFalse(text.contains("above 48 kHz"))
    }
    private val stream = StreamInfo(44100, 24, 2,
        DacFormat(1, 1, 1, 2, 3, 24, DacFormat.SyncType.SYNC, 288, listOf(44100), 44100, 44100),
        44100, 0, true, false)
    private val clean = UsbPlaybackSnapshot(route = PlaybackRoute.DIRECT_USB, state = UsbPlaybackState.PLAYING,
        source = TrackPcmFormat(44100, 24, 2, 24, "FLAC", true, 1000000), stream = stream,
        readiness = UsbPlaybackReadiness(true, true, true))

    @Test fun activeEligibilityIsSeparateFromPolicyAndPhysicalProof() {
        for (policy in UsbPlaybackPolicy.entries) {
            val status = playbackSignalStatus(clean.copy(usbPolicy = policy))
            assertTrue(status.contains("Source: FLAC · 44100 Hz · 24-bit · 2 channels"))
            assertTrue(status.contains("S32 container · 24 significant bits"))
            assertTrue(status.contains("Active USB output: 44100 Hz · 24-bit"))
            assertTrue(status.contains("Device-reported rate: unknown"))
            assertTrue(status.contains("no sample conversion"))
            assertTrue(status.contains("eligible at USB input"))
            assertTrue(status.contains("not a physical bit-perfect measurement"))
        }
        assertFalse(playbackSignalStatus(clean.copy(state = UsbPlaybackState.PAUSED)).contains("eligible at USB input"))
        assertTrue(playbackSignalStatus(clean.copy(state = UsbPlaybackState.PAUSED)).contains("Last USB output"))
        assertFalse(playbackSignalStatus(clean.copy(readiness = UsbPlaybackReadiness())).contains("eligible at USB input"))
    }

    @Test fun songRateSettingIsShownOnlyWhenOff() {
        assertFalse(playbackSignalStatus(clean).contains("Follow song rate"))
        val status = playbackSignalStatus(clean.copy(followSongRate = false))
        assertTrue(status.contains("Follow song rate: off"))
        assertTrue("A capped stream is never shown as eligible bit-perfect", playbackSignalStatus(clean.copy(followSongRate = false,
            stream = stream.copy(outputRate = 48000, bitPerfect = false, resampled = true))).contains("no active strict eligibility"))
    }

    @Test fun plannedConversionsAreExplainedWithoutInventingSourcePrecision() {
        val status = playbackSignalStatus(UsbPlaybackSnapshot(route = PlaybackRoute.DIRECT_USB,
            source = TrackPcmFormat(44100, 32, 2, null, "MP3", false, null),
            plan = PlaybackPlan(48000, 16, true, false), failure = UsbPlaybackFailure.VOLUME_UNVERIFIED))
        for (expected in listOf("original precision unknown", "processed S32", "Planned USB output: 48000 Hz · 16-bit",
            "Planned processing", "processed decode / S32 quantization", "resampling 44100 → 48000 Hz", "precision reduction 32 → 16 bits", "control unverified"))
            assertTrue("Missing $expected in $status", status.contains(expected))
        assertFalse(status.contains("Active USB output"))
        assertFalse(status.contains("eligible at USB input"))
    }

    @Test fun androidAndUnselectedRoutesNeverInheritUsbClaims() {
        val status = playbackSignalStatus(clean.copy(route = PlaybackRoute.ANDROID, stream = null,
            androidStream = AndroidOutputInfo(44100, 2, 22), usbPolicy = UsbPlaybackPolicy.COMPATIBILITY))
        for (expected in listOf("Active Android output: 44100 Hz · PCM float", "system volume", "device rate/precision and mixer processing unknown", "S32 → float"))
            assertTrue(status.contains(expected))
        assertFalse(status.contains("eligible at USB input"))
        assertFalse(status.contains("hardware volume"))
        assertEquals("No output selected. Select a local file and an output.", playbackSignalStatus(UsbPlaybackSnapshot()))
    }
}
