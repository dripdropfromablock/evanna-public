package com.evanna.player

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class PlayerBackdropTest {
    @Test fun bufferIsSmallWithOverscanAndDependsOnDensity() {
        assertEquals(IntSize(59, 127), backdropBufferSize(1080, 2340, 450))  // the owner's M30s
        assertEquals(IntSize(88, 190), backdropBufferSize(1080, 2340, 320))
        assertEquals(IntSize(1, 1), backdropBufferSize(1, 1, 420))
    }
    @Test fun layersTurnAtTheirOwnSpeedAndDirection() {
        assertEquals(0f, backdropAngle(0, 120_000, -1), 0f)
        assertEquals(-90f, backdropAngle(30_000, 120_000, -1), 1e-3f)
        assertEquals(180f, backdropAngle(45_000, 90_000, 1), 1e-3f)
        assertEquals(36f, backdropAngle(77_000, 70_000, 1), 1e-3f)  // wraps after a full turn
    }
    @Test fun theLayersDriftFastAtFirstThenComeToRest() {
        assertEquals(0L, backdropDriftPhase(0))
        assertEquals(BACKDROP_DRIFT_MS, backdropDriftPhase(BACKDROP_DRIFT_MS))
        assertEquals(BACKDROP_DRIFT_MS, backdropDriftPhase(60_000))  // stays at rest
        // Starts at about three times the old turning speed, and is nearly still by the end.
        assertEquals(30L, backdropDriftPhase(10))
        assertTrue(backdropDriftPhase(BACKDROP_DRIFT_MS) - backdropDriftPhase(BACKDROP_DRIFT_MS - 100) <= 1)
        assertTrue(backdropDriftPhase(1_000) > BACKDROP_DRIFT_MS / 3)
        assertFalse(backdropSettled(BACKDROP_DRIFT_MS - 1))
        assertTrue(backdropSettled(BACKDROP_DRIFT_MS))
        // At rest the fastest layer has turned about 31 degrees and the slowest about 18.
        assertEquals(30.9f, backdropAngle(backdropDriftPhase(BACKDROP_DRIFT_MS), 70_000, 1), 0.1f)
        assertEquals(-18f, backdropAngle(backdropDriftPhase(BACKDROP_DRIFT_MS), 120_000, -1), 0.1f)
    }
    @Test fun songChangesCrossFadeOverOneSecond() {
        assertEquals(0f, backdropFade(0), 0f)
        assertEquals(1f, backdropFade(1_000), 1e-4f)
        assertEquals(1f, backdropFade(5_000), 0f)
        assertTrue(backdropFade(300) > 0.5f)  // ease-out: most of the change happens early
    }
    @Test fun threeSlowFramesInARowHoldTheBackdropStillForFiveSeconds() {
        val guard = BackdropFrameGuard()
        listOf(16L, 16L, 4L, 16L, 16L).forEachIndexed { i, ms -> guard.record(ms, i * 42L) }
        assertFalse(guard.holding(300))
        guard.record(20, 300)
        assertTrue(guard.holding(301))
        assertTrue(guard.holding(5_299))
        assertFalse(guard.holding(5_300))  // then it tries again
    }
    @Test fun brightCoversGetEnoughScrimForWhiteTextAndDarkOnesKeepTheBase() {
        val white = 0xFFFFFFFF.toInt()
        val alpha = backdropScrim(white, base = 0.5f, lift = 0.05f)
        assertTrue(alpha > 0.5f)
        assertTrue(relativeLuminance(scrimmedColor(white, alpha, 0.05f)) <= BACKDROP_MAX_LUMINANCE)
        assertEquals(0.5f, backdropScrim(0xFF202020.toInt(), base = 0.5f, lift = 0.05f), 0f)
        assertEquals(0.3f, backdropScrim(0xFF101010.toInt(), base = 0.3f, lift = 0.10f), 0f)
    }
    @Test fun boxBlurSpreadsABrightPixelAndKeepsAFlatImageFlat() {
        val flat = IntArray(30 * 20) { 0xFF336699.toInt() }
        boxBlur(flat, 30, 20, 4)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
        val dot = IntArray(21 * 21) { 0xFF000000.toInt() }.also { it[10 * 21 + 10] = 0xFFFFFFFF.toInt() }
        boxBlur(dot, 21, 21, 3)
        assertTrue(dot[10 * 21 + 10] and 0xFF in 1..254)
        assertTrue(dot[10 * 21 + 13] and 0xFF > 0)
        assertEquals(dot[10 * 21 + 7], dot[10 * 21 + 13])  // symmetric
    }
}
