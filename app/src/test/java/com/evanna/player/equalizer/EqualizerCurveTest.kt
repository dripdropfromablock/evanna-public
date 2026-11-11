package com.evanna.player.equalizer

import com.truesample.usbaudio.EqBand
import org.junit.Assert.*
import org.junit.Test

class EqualizerCurveTest {
    private val points=curveFrequencies(400)

    @Test fun theAxisRunsLogarithmicallyFromTwentyHertzToTwentyFourKilohertz() {
        assertEquals(20.0,points.first(),1e-9);assertEquals(24000.0,points.last(),1e-6)
        assertTrue(points.toList().zipWithNext().all {(a,b) -> b>a})
        assertEquals(points[1]/points[0],points[300]/points[299],1e-9)  // equal ratios: a log axis
    }

    @Test fun theCurveMatchesTheHeadroomMathWithoutThePreamp() {
        val config=EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,1000f,6f,1f),EqBand(EqBand.Type.BAND_PASS,8000f,-4f,1.5f)),preampDb=-7f)
        val probe=doubleArrayOf(31.0,1000.0,8000.0,16000.0)
        val curve=equalizerCurveDb(config,probe)
        for((i,f) in probe.withIndex()) {
            val raw=equalizerResponseDb(config,f,48000)+7.0
            assertEquals("$f Hz",if(kotlin.math.abs(raw)<0.08)0.0 else raw,curve[i],1e-9)
        }
        assertEquals(6.0,curve[1],0.05)
    }

    @Test fun aFlatOrNearlyFlatPresetDrawsAFlatLine() {
        assertTrue(equalizerCurveDb(flatGraphic(10),points).all {it==0.0})
        assertTrue(equalizerCurveDb(EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,1000f,0.05f,1f))),points).all {it==0.0})
    }

    @Test fun eachChannelDrawsOnlyItsOwnBands() {
        val leftOnly=EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,1000f,6f,1f,channels=1)))
        assertTrue(equalizerCurveDb(leftOnly,points,channel=0).any {it>5.9})
        assertTrue(equalizerCurveDb(leftOnly,points,channel=1).all {it==0.0})
    }
}
