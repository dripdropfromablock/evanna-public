package com.evanna.player.equalizer

import com.truesample.usbaudio.EqBand
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class EqualizerConfigTest {
    @Test fun commandValuesAreBoundedAndRoundTripWithoutLosingPrecision() {
        val config=EqualizerConfig(listOf(EqBand(EqBand.Type.LOW_SHELF,105f,5.3f,0.7f),EqBand(EqBand.Type.BAND_PASS,62f,-3.5f,1.5f,channels=2)),-6.2f,graphic=true)
        assertEquals(config,equalizerConfigFromValues(equalizerValues(config),config.preampDb,config.graphic))
        for(values in listOf(floatArrayOf(0f),FloatArray(5*35),floatArrayOf(0.5f,100f,1f,1f,0f),floatArrayOf(0f,Float.NaN,1f,1f,0f),
            floatArrayOf(6f,100f,1f,1f,0f),floatArrayOf(0f,100f,1f,1f,3f),floatArrayOf(0f,100f,1f,1f,0.5f)))
            assertTrue(runCatching {equalizerConfigFromValues(values,0f,false)}.isFailure)
    }
    @Test fun limitsAllowThirtyFourBandsAndPoweramp_sRanges() {
        assertEquals(34,EqualizerConfig(List(34) {band(EqBand.Type.BAND_PASS,24000f,0f,12f)}).bands.size)
        for(bad in listOf(List(35) {band()},listOf(band(q=12.1f)),listOf(band(hz=24001f)),listOf(band(hz=9f)),listOf(EqBand(EqBand.Type.PEAK,1000f,1f,1f,channels=3))))
            assertTrue(runCatching {EqualizerConfig(bad)}.isFailure)
    }
    @Test fun passBandsAddToTheDrySignalAndHeadroomFollowsTheCombinedSweep() {
        // +6 dB band pass: exactly +6 dB at its centre, as in the native engine.
        assertEquals(6.0,equalizerResponseDb(EqualizerConfig(listOf(band(EqBand.Type.BAND_PASS,1000f,6f,1.5f))),1000.0,48000),1e-6)
        // A cutting high pass still has a small bump below its corner, so it needs a little headroom instead of failing.
        val treble=equalizerHeadroom(EqualizerConfig(listOf(band(EqBand.Type.HIGH_PASS,10000f,-6f,0.6f))),48000)
        assertTrue(treble.requiredDb in 0.6..1.0);assertNotNull(treble.preampDb)
        val graphic=EqualizerConfig(listOf(band(EqBand.Type.LOW_PASS,90f,4f,0.8f))+listOf(31f,62f,124f,249f,498f).mapIndexed {i,hz ->
            band(EqBand.Type.BAND_PASS,hz,6f-2*i,1.5f)}+band(EqBand.Type.PEAK,3000f,3f,2f))
        for(rate in listOf(44100,48000,96000,384000)) {
            val result=equalizerHeadroom(graphic,rate);val applied=graphic.copy(preampDb=checkNotNull(result.preampDb))
            for(i in 1..24000)assertTrue("$rate at $i",equalizerResponseDb(applied,rate/2.0*i/24000,rate)<0.001)
        }
    }
    @Test fun headroomCountsEachChannelOnItsOwn() {
        val split=EqualizerConfig(listOf(EqBand(EqBand.Type.PEAK,1000f,6f,1f,channels=1),EqBand(EqBand.Type.PEAK,1000f,6f,1f,channels=2)))
        assertEquals(6.0,equalizerResponseDb(split,1000.0,48000,channel=1),1e-6)
        assertTrue(equalizerHeadroom(split,48000).preampDb!! in -6.3f..-6f)
    }
    @Test fun inaudiblePreampUsesTheNativeBypassThreshold() {
        assertFalse(equalizerHeadroom(EqualizerConfig(preampDb=-0.0000000001f),48000).processed)
        assertTrue(equalizerHeadroom(EqualizerConfig(preampDb=-0.000001f),48000).processed)
    }
    @Test fun seededSettingsHaveFiniteConservativeBoundsWithoutUnboundedWork() {
        val random=java.util.Random(91827)
        repeat(40) {
            val config=EqualizerConfig(List(1+random.nextInt(6)) {
                band(EqBand.Type.entries[random.nextInt(3)],(20*1000.0.pow(random.nextDouble())).toFloat(),(random.nextFloat()*12-6),0.1f+random.nextFloat()*9.9f)
            })
            val result=equalizerHeadroom(config,48000)
            assertTrue(result.requiredDb.isFinite())
            for(i in 0..1200)assertTrue("Upper bound missed a combined response peak",equalizerResponseDb(config,24000.0*i/1200,48000)<=result.requiredDb+0.0001)
        }
    }
    private fun band(type:EqBand.Type=EqBand.Type.PEAK,hz:Float=1000f,gain:Float=6f,q:Float=1f)=EqBand(type,hz,gain,q)
    @Test fun importsOriginalAutoEqSyntaxAndDecimalCommasWithVisibleWarnings() {
        val result=parseAutoEq("""
            # Original test settings
            Preamp: -6,2 dB
            Filter 1: ON PK Fc 105 Hz Gain 5,3 dB Q 0,70
            Filter 2: ON LSC Fc 105 Hz Gain 4 dB Q 0.70
            Filter 3: ON HS Fc 10000 Hz Gain -2 dB Q 0.70
            Filter 4: OFF PK Fc 500 Hz Gain 10 dB Q 1
            Filter 5: ON NOTCH Fc 1000 Hz Gain -2 dB Q 1
        """.trimIndent().toByteArray())
        assertEquals(-6.2f,result.config.preampDb,0f);assertEquals(3,result.config.bands.size)
        assertEquals(listOf(EqBand.Type.PEAK,EqBand.Type.LOW_SHELF,EqBand.Type.HIGH_SHELF),result.config.bands.map {it.type})
        assertEquals(5.3f,result.config.bands.first().gainDb,0f);assertTrue(result.warnings.any {it.contains("NOTCH")})
    }
    @Test fun importLimitsRejectAmbiguityAndClampOnlyWithAWarning() {
        val result=parseAutoEq("Filter 1: ON PK Fc 10 Hz Gain 30 dB Q 20".toByteArray())
        assertEquals(band(hz=20f,gain=15f,q=10f),result.config.bands.single())
        assertEquals(-15f,result.config.preampDb,0f);assertTrue(result.warnings.isNotEmpty())
        for(text in listOf("Preamp: NaN dB","Filter 1: ON PK Fc 1000 Hz Gain Infinity dB Q 1",
            "Filter 1: ON PK Fc 1000 Hz Gain 1 dB Q 1\nFilter 1: ON PK Fc 1000 Hz Gain 1 dB Q 1",
            (1..35).joinToString("\n"){"Filter $it: ON PK Fc 1000 Hz Gain 1 dB Q 1"},
            (1..65).joinToString("\n"){"Filter $it: OFF PK Fc 1000 Hz Gain 1 dB Q 1"}))
            assertTrue(text,runCatching {parseAutoEq(text.toByteArray())}.isFailure)
        assertTrue(runCatching {parseAutoEq(ByteArray(65537))}.isFailure)
        assertTrue(runCatching {EqualizerConfig(listOf(band(gain=Float.NaN)))}.isFailure)
    }
    @Test fun flatBypassStaysExactAndOnePeakMatchesItsSpecifiedGain() {
        assertEquals(0f,equalizerHeadroom(EqualizerConfig(),48000).preampDb!!,0f)
        assertFalse(equalizerHeadroom(EqualizerConfig(),48000).processed)
        val peak=EqualizerConfig(listOf(band()))
        assertEquals(6.0,equalizerResponseDb(peak,1000.0,48000),1e-7)
        assertTrue(equalizerHeadroom(peak,48000).preampDb!!<=-6f)
        assertTrue(equalizerHeadroom(EqualizerConfig(preampDb=-10f),48000).processed)
    }
    @Test fun overlappingBoostsNeedCombinedHeadroomWhileDisjointBoostsRemainUsable() {
        val together=EqualizerConfig(listOf(band(gain=8f),band(gain=8f)))
        val overlap=equalizerHeadroom(together,48000)
        assertNotNull(overlap.preampDb);assertTrue(overlap.preampDb!!<=-16f)
        val separate=EqualizerConfig(listOf(band(hz=100f,gain=8f,q=5f),band(hz=10000f,gain=8f,q=5f)))
        val spaced=equalizerHeadroom(separate,48000)
        assertTrue(spaced.preampDb!! in -9f..-8f)
        assertNull(equalizerHeadroom(EqualizerConfig(List(3){band(gain=10f)}),48000).preampDb)
    }
    @Test fun highQShelfOvershootAndLowRateSkippedFiltersAreAccountedFor() {
        val shelf=EqualizerConfig(listOf(band(EqBand.Type.LOW_SHELF,200f,6f,5f)))
        val headroom=equalizerHeadroom(shelf,48000)
        assertTrue(headroom.requiredDb>6.0)
        val active=headroom.preampDb ?: error("Fixture needs less than 20dB")
        val applied=shelf.copy(preampDb=active)
        for(i in 0..12000)assertTrue(equalizerResponseDb(applied,24000.0*i/12000,48000)<0.001)
        assertFalse(equalizerHeadroom(EqualizerConfig(listOf(band(hz=20000f))),8000).processed)
    }
    @Test fun boundedResponseAnalysisProtectsCombinedSineGainAcrossRates() {
        val config=EqualizerConfig(listOf(band(hz=150f,gain=4f,q=0.7f),band(hz=1500f,gain=-4f,q=2f),band(EqBand.Type.HIGH_SHELF,8000f,3f,0.7f)))
        for(rate in listOf(8000,44100,48000,96000,192000,768000)) {
            val result=equalizerHeadroom(config,rate);val applied=config.copy(preampDb=checkNotNull(result.preampDb))
            for(i in 0..2400)assertTrue("$rate at $i",equalizerResponseDb(applied,rate.toDouble()*i/4800,rate)<0.001)
        }
    }
}
