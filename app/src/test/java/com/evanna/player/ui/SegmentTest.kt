package com.evanna.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class SegmentTest {
    @Test fun aFingerOverASegmentPicksThatSegment() {
        // Four segments across 400 px: each is 100 px wide, centred at 50, 150, 250 and 350.
        assertEquals(0f, segmentUnder(50f, 400f, 4), 0.001f)
        assertEquals(2f, segmentUnder(250f, 400f, 4), 0.001f)
        assertEquals(1, segmentUnder(140f, 400f, 4).roundToInt())
        assertEquals(2, segmentUnder(210f, 400f, 4).roundToInt())
    }

    @Test fun theCapsuleStopsAtTheEnds() {
        assertEquals(0f, segmentUnder(-80f, 400f, 4), 0f)
        assertEquals(0f, segmentUnder(10f, 400f, 4), 0f)
        assertEquals(3f, segmentUnder(399f, 400f, 4), 0f)
        assertEquals(3f, segmentUnder(900f, 400f, 4), 0f)
    }

    @Test fun anUnmeasuredControlStaysAtTheStart() {
        assertEquals(0f, segmentUnder(50f, 0f, 4), 0f)
        assertEquals(0f, segmentUnder(50f, 400f, 0), 0f)
    }
}
