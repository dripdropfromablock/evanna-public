package com.evanna.player.equalizer

import org.junit.Assert.*
import org.junit.Test

class EqualizerControlsTest {
    @Test fun valuesReadAsInTheMockup() {
        assertEquals(listOf("+6","+0.5","0","−1.5","−15"),listOf(6f,0.5f,0.02f,-1.5f,-15f).map(::eqShort))
        assertEquals(listOf("+2.0","+1.0","−7.8","0.0"),listOf(2f,1f,-7.8f,-0.04f).map(::eqSigned))
    }

    @Test fun theFrequencySliderIsLogarithmicAndRoundTrips() {
        assertEquals(0f,frequencyPosition(20f),0f);assertEquals(1f,frequencyPosition(24000f),1e-6f)
        assertEquals(0f,frequencyPosition(10f),0f)  // a 16 Hz ladder band sits at the start
        for(hz in listOf(20f,90f,1000f,5646f,24000f))assertEquals(hz,frequencyAt(frequencyPosition(hz)),1f)
        // Equal steps along the track are equal frequency ratios.
        assertEquals(frequencyAt(0.2f)/frequencyAt(0.1f),frequencyAt(0.8f)/frequencyAt(0.7f),0.01f)
    }

    @Test fun theQSliderRunsFromATenthToTwelveOnALogScale() {
        assertEquals(0.1f,qAt(0f),0f);assertEquals(12f,qAt(1f),0f)
        for(q in listOf(0.1f,0.7f,1.41f,4.03f,12f))assertEquals(q,qAt(qPosition(q)),0.011f)
    }

    @Test fun dragsSnapToTenthsAndCatchZero() {
        assertEquals(3.4f,snapGain(3.43f),0f);assertEquals(0f,snapGain(0.2f),0f);assertEquals(-0.3f,snapGain(-0.3f),0f)
        assertEquals(15f,snapGain(18f),0f);assertEquals(-20f,snapGain(-25f,-20f..0f),0f);assertEquals(0f,snapGain(-0.1f,-20f..0f),0f)
    }
}
