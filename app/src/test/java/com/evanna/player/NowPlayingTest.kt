package com.evanna.player

import org.junit.Assert.*
import org.junit.Test

class NowPlayingTest {
    @Test fun lyricClockSmoothsSmallCorrectionsWithoutRewinding() {
        assertEquals(1_016L, lyricFramePosition(1_000L, 16L, 1_005L))
        assertEquals(1_002L, lyricFramePosition(1_000L, 16L, 960L))
        assertEquals(1_000L, lyricFramePosition(1_000L, 8L, 910L))
        assertEquals(1_030L, lyricFramePosition(1_000L, 16L, 1_100L))
    }

    @Test fun lyricClockSnapsAfterLargeSeeksInEitherDirection() {
        assertEquals(4_000L, lyricFramePosition(1_000L, 16L, 4_000L))
        assertEquals(200L, lyricFramePosition(1_000L, 16L, 200L))
    }

    @Test fun androidBitPerfectSignalDisablesSystemVolumeControls() {
        val bitPerfect = com.evanna.player.playback.SignalStage(
            "Volume", "Not changed by Android. DAC volume not verified", brief = true,
        )
        assertTrue(PlayerPresentation(route = "ANDROID", signalStages = listOf(bitPerfect)).androidVolumeBypassed)
        assertFalse(PlayerPresentation(route = "ANDROID", signalStages = listOf(bitPerfect.copy(brief = false))).androidVolumeBypassed)
        assertFalse(PlayerPresentation(route = "ANDROID").androidVolumeBypassed)
    }

    @Test fun sourceBadgeKeepsExactRatesAndOmitsUnknownDepth() {
        assertEquals("ALAC · 44.1 kHz · 16-bit", shortSourceQuality("ALAC · 44100 Hz · 16-bit"))
        assertEquals("FLAC · 48 kHz · 24-bit", shortSourceQuality("FLAC · 48000 Hz · 24-bit"))
        assertEquals("AAC · 22.05 kHz", shortSourceQuality("AAC · 22050 Hz · processed PCM"))
        assertEquals("", shortSourceQuality(""))
    }

    // A 9.2 s break from 10 s: each dot takes (9.2 s − 0.8 s) / 3 = 2.8 s.
    private fun fills(position: Long) = (0..2).map { breakDotFill(position, 10_000, 19_200, it) }
    private fun assertFills(expected: List<Float>, actual: List<Float>) =
        expected.zip(actual).forEach { (want, got) -> assertEquals(want, got, 1e-3f) }

    @Test fun dotsFillOneAfterAnotherAndAreFullJustBeforeTheNextLine() {
        assertFills(listOf(0f, 0f, 0f), fills(10_000))
        assertFills(listOf(1f, 0f, 0f), fills(12_800))
        val middle = fills(14_200)
        assertTrue(middle[1] > 0.01f && middle[1] < 0.99f)
        assertFills(listOf(1f, 1f, 0f), fills(15_600))
        assertFills(listOf(1f, 1f, 1f), fills(18_400))
        assertFills(listOf(1f, 1f, 1f), fills(19_200))
    }

    @Test fun dotsStayEmptyBeforeTheirBreakAndAShortBreakFillsAtOnce() {
        assertFills(listOf(0f, 0f, 0f), fills(9_000))
        assertEquals(1f, breakDotFill(5_001, 5_000, 5_500, 2), 1e-3f)
    }

    @Test fun eachDotFillsSmoothlyInItsOwnThird() {
        val steps = (10_000L..12_800L step 350).map { breakDotFill(it, 10_000, 19_200, 0) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
    }
}
