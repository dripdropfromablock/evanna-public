package com.evanna.player.equalizer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class SoundEngineTest {
    private fun tone(hz: Double, level: Double, frames: Int, rate: Int = 48000): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (f in 0 until frames) {
                val v = (level * sin(2 * PI * hz * f / rate) * Int.MAX_VALUE).toInt()
                putInt(f * 8, v); putInt(f * 8 + 4, v)
            }
        }

    private fun rmsDb(pcm: ByteBuffer, from: Int, frames: Int): Double {
        var sum = 0.0
        for (f in from until frames) { val x = pcm.getInt(f * 8) / 2147483648.0; sum += x * x }
        return 20 * log10(sqrt(sum / (frames - from)))
    }

    private fun peak(pcm: ByteBuffer, frames: Int): Double = (0 until frames * 2).maxOf { abs(pcm.getInt(it * 4) / 2147483648.0) }

    @Test fun offOrFlatLeavesEverySampleUntouched() {
        for (config in listOf(SoundConfig(), SoundConfig(enabled = true), SoundConfig(enabled = false, graphic = List(15) { 6f }))) {
            val pcm = tone(1000.0, 0.5, 4800); val before = ByteArray(pcm.capacity()).also { pcm.duplicate().get(it) }
            val engine = SoundEngine()
            engine.process(pcm, 4800, 2, 48000, config)
            assertFalse(engine.active)
            assertArrayEquals(before, ByteArray(pcm.capacity()).also { pcm.duplicate().get(it) })
        }
    }

    @Test fun eachShapeHasItsCookbookResponse() {
        fun db(band: Band, hz: Double) = soundCurveDb(SoundConfig(enabled = true, bands = listOf(band)), doubleArrayOf(hz))[0]
        assertEquals(6.0, db(Band(FilterType.PEAK, 1000f, 6f, 1f), 1000.0), 0.01)
        assertEquals(0.0, db(Band(FilterType.PEAK, 1000f, 6f, 1f), 20.0), 0.05)
        assertEquals(-6.0, db(Band(FilterType.LOW_SHELF, 200f, -6f, 0.707f), 20.0), 0.1)
        assertEquals(4.0, db(Band(FilterType.HIGH_SHELF, 4000f, 4f, 0.707f), 18000.0), 0.15)
        assertEquals(-3.01, db(Band(FilterType.LOW_PASS, 1000f, 0f, 0.7071f), 1000.0), 0.02)
        assertTrue(db(Band(FilterType.LOW_PASS, 1000f, 0f, 0.7071f), 10000.0) < -38)
        assertTrue(db(Band(FilterType.HIGH_PASS, 1000f, 0f, 0.7071f), 100.0) < -38)
        assertEquals(0.0, db(Band(FilterType.BAND_PASS, 1000f, 0f, 2f), 1000.0), 0.01)
        assertTrue(db(Band(FilterType.NOTCH, 1000f, 0f, 2f), 1000.0) < -100)
        assertEquals(0.0, db(Band(FilterType.ALL_PASS, 1000f, 0f, 2f), 3000.0), 0.01)
        assertEquals(6.0, soundCurveDb(SoundConfig(enabled = true, graphic = List(15) { if (it == 8) 6f else 0f }), doubleArrayOf(1000.0))[0], 0.01)
    }

    @Test fun aBoostedFullScaleToneGetsItsBoostBackAsHeadroomAndNeverClips() {
        val config = SoundConfig(enabled = true, bands = listOf(Band(FilterType.PEAK, 1000f, 6f, 1f)))
        assertEquals(-6.0f, appliedPreampDb(config), 0.11f)
        val pcm = tone(1000.0, 1.0, 9600)
        val engine = SoundEngine()
        engine.process(pcm, 9600, 2, 48000, config)
        assertTrue(engine.active)
        assertEquals(-6.0f, engine.gainDb, 0.11f)
        assertTrue(peak(pcm, 9600) <= 0.978)
        // +6 dB filter and -6 dB headroom: a full-scale sine stays near -3 dB RMS; the limiter trims its 0 dBFS peaks to -0.2 dBFS.
        assertEquals(-3.2, rmsDb(pcm, 4800, 9600), 0.15)
    }

    @Test fun theLimiterHoldsTheCeilingWhenThePreampCannotCoverATransient() {
        val engine = SoundEngine()
        val pcm = tone(60.0, 1.0, 9600)
        engine.process(pcm, 9600, 2, 48000, SoundConfig(enabled = true, graphic = List(15) { if (it < 4) 15f else 0f }))
        assertTrue(peak(pcm, 9600) <= 0.978)
    }

    @Test fun aLowPassBandCutsHighTonesInRealAudio() {
        val pcm = tone(10000.0, 0.5, 9600)
        SoundEngine().process(pcm, 9600, 2, 48000, SoundConfig(enabled = true, bands = listOf(Band(FilterType.LOW_PASS, 1000f, 0f, 0.7071f))))
        assertTrue(rmsDb(pcm, 4800, 9600) < -45)
    }

    @Test fun settingsSurviveTheirStoredForm() {
        val config = SoundConfig(true, -3.5f, List(15) { it - 7f }, listOf(Band(FilterType.NOTCH, 60f, 0f, 8f, false), Band(FilterType.HIGH_SHELF, 9000f, -2.5f, 0.71f)))
        assertEquals(config, decodeSound(encodeSound(config)))
        assertEquals(SoundConfig(), decodeSound(encodeSound(SoundConfig())))
        assertThrows(IllegalArgumentException::class.java) { decodeSound("1|1|5.0|${List(15) { 0 }.joinToString(",")}|") }
        assertThrows(IllegalArgumentException::class.java) { decodeSound("2|1|0.0||") }
    }
}
