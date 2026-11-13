package com.evanna.player.equalizer

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class SoundPresetsTest {
    // A short file in AutoEq's ParametricEQ.txt layout (values made up for the test).
    private val autoEq = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.0 dB Q 0.70
        Filter 2: ON PK Fc 2400 Hz Gain -3.1 dB Q 1.41
        Filter 3: OFF HSC Fc 10000 Hz Gain 2.5 dB Q 0.70
        Filter 4: ON PK Fc 30000 Hz Gain 40 dB Q 50
        Filter 5: ON XX Fc 500 Hz Gain 1 dB Q 1
    """.trimIndent()

    @Test fun readsAutoEqParametricFiles() {
        val found = parseAutoEq(autoEq)  // the Preamp line is ignored, not counted as skipped
        assertEquals(listOf(Band(FilterType.LOW_SHELF, 105f, 6f, 0.7f), Band(FilterType.PEAK, 2400f, -3.1f, 1.41f),
            Band(FilterType.HIGH_SHELF, 10000f, 2.5f, 0.7f, on = false), Band(FilterType.PEAK, 20000f, 15f, 12f)), found.bands)
        assertEquals(1, found.skipped)
    }

    @Test fun refusesFilesWithoutFiltersAndKeepsOnlySixteen() {
        assertThrows(IllegalArgumentException::class.java) { parseAutoEq("GraphicEQ: 20 -1.0; 40 2.0") }
        assertThrows(IllegalArgumentException::class.java) { parseAutoEq("Preamp: -3 dB") }
        val many = (1..20).joinToString("\n") { "Filter $it: ON PK Fc ${it * 100} Hz Gain 1 dB Q 1" }
        parseAutoEq(many).let { assertEquals(MAX_BANDS, it.bands.size); assertEquals(4, it.skipped) }
    }

    @Test fun readsSmallTextAndRefusesBigFiles() {
        assertEquals("abc", readSmallText(ByteArrayInputStream("abc".toByteArray())))
        assertEquals(65536, readSmallText(ByteArrayInputStream(ByteArray(65536) { 'a'.code.toByte() })).length)
        assertThrows(IllegalArgumentException::class.java) { readSmallText(ByteArrayInputStream(ByteArray(65537))) }
    }

    @Test fun builtInPresetsSetTasteAndKeepTheCorrection() {
        val correction = SoundConfig(preampDb = -4f, bands = listOf(Band(FilterType.PEAK, 3000f, -2f, 2f)), width = 150f)
        val warm = BUILT_IN_PRESETS.first { it.name == "Warm" }
        val after = warm.applyTo(correction)
        assertTrue(after.enabled)
        assertEquals(correction.bands, after.bands)
        assertEquals(-4f, after.preampDb)
        assertEquals(100f, after.width)
        assertTrue(warm.matches(after))
        assertFalse(BUILT_IN_PRESETS.first { it.name == "Flat" }.matches(after))
    }

    @Test fun savedPresetsSurviveTheirStoredForm() {
        val mine = listOf(SoundPreset("HD 600", SoundConfig(true, -2f, bassDb = 3f), false), SoundPreset("Car", SoundConfig(true), false))
        val back = decodePresets(encodePresets(mine))
        assertEquals(mine.map { it.name }, back.map { it.name })
        assertEquals(mine.map { it.config }, back.map { it.config })
        assertEquals(emptyList<String>(), decodePresets("Warm\t${encodeSound(SoundConfig())}\nbad line\n\tx").map { it.name })
        assertEquals("", cleanPresetName("  flat "))
        assertEquals("My\u0000 sound".filterNot { it.isISOControl() }, cleanPresetName("My\u0000 sound "))
    }
}
