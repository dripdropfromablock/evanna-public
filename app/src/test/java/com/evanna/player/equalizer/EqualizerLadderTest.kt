package com.evanna.player.equalizer

import com.truesample.usbaudio.EqBand
import org.junit.Assert.*
import org.junit.Test

class EqualizerLadderTest {
    @Test fun tenBandsAreTheIsoLadderAtQOneAndAHalf() {
        assertEquals(listOf(31f,62f,124f,249f,498f,996f,1995f,3993f,7993f,16000f),graphicFrequencies(10))
        assertEquals(1.5f,graphicQ(10),0.002f)
    }
    @Test fun thirtyTwoBandsRunFromSixteenHertzToTwentyKilohertzAtQ3_33() {
        val bands=graphicFrequencies(32)
        assertEquals(16f,bands.first(),0f);assertEquals(20000f,bands.last(),0f)
        assertEquals(3.33f,graphicQ(32),0.001f)
    }
    @Test fun everyCountBuildsAFlatGraphicPresetThatBypassesProcessing() {
        for(count in GRAPHIC_BAND_COUNTS) {
            val flat=flatGraphic(count)
            assertTrue(flat.graphic);assertEquals(count+2,flat.bands.size)
            assertEquals(listOf(EqBand.Type.LOW_PASS,EqBand.Type.HIGH_PASS),flat.bands.take(2).map {it.type})
            val centres=flat.bands.drop(2).map {it.frequencyHz}
            assertTrue("$count",centres.zipWithNext().all {(a,b) -> a<b} && flat.bands.drop(2).all {it.type==EqBand.Type.BAND_PASS && it.q==graphicQ(count)})
            assertFalse(equalizerHeadroom(flat,48000).processed)
        }
        assertTrue(runCatching {graphicFrequencies(11)}.isFailure)
    }
    @Test fun slidersAndTheCountChooserUseTheMockupLabels() {
        assertEquals(listOf("31","62","125","250","500","1k","2k","4k","8k","16k"),graphicFrequencies(10).map(::graphicLabel))
        assertEquals("1.3k",graphicLabel(1250f));assertEquals("12.5k",graphicLabel(12500f));assertEquals("20k",graphicLabel(20000f))
        assertEquals(listOf("5","10 (ISO)","12","15 (ISO)","16","24","31 (ISO)","32"),GRAPHIC_BAND_COUNTS.map(::graphicCountLabel))
    }
    @Test fun bassAndTrebleOnlyBoost() {
        assertEquals(EqBand(EqBand.Type.LOW_PASS,90f,0f,0.8f),bassBand(-3f))
        assertEquals(EqBand(EqBand.Type.HIGH_PASS,10000f,15f,0.6f),trebleBand(20f))
    }
}
