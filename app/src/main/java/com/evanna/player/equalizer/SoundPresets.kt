package com.evanna.player.equalizer

import java.io.InputStream

/**
 * A named sound. Built-in presets are taste only: they set the graphic sliders, Bass, Air and Width and leave the
 * parametric bands and preamp alone, so a headphone correction survives. A saved preset restores everything.
 */
internal class SoundPreset(val name: String, val config: SoundConfig, val builtIn: Boolean) {
    fun applyTo(current: SoundConfig): SoundConfig = if (builtIn)
        current.copy(enabled = true, graphic = config.graphic, bassDb = config.bassDb, airDb = config.airDb, width = config.width)
        else config.copy(enabled = true)

    fun matches(current: SoundConfig): Boolean = applyTo(current) == current.copy(enabled = true)
}

private fun builtIn(name: String, bass: Float = 0f, air: Float = 0f, vararg graphic: Pair<Int, Float>) =
    SoundPreset(name, SoundConfig(graphic = List(GRAPHIC_HZ.size) { i -> graphic.firstOrNull { it.first == i }?.second ?: 0f },
        bassDb = bass, airDb = air), builtIn = true)

/** Evanna's own starting curves. Graphic indexes: 0 = 25 Hz ... 8 = 1 kHz ... 14 = 16 kHz. */
internal val BUILT_IN_PRESETS = listOf(
    builtIn("Flat"),
    builtIn("Bass boost", bass = 6f),
    builtIn("Warm", 0f, 0f, 0 to 2f, 1 to 2f, 2 to 2f, 3 to 1.5f, 4 to 1f, 5 to 0.5f, 11 to -1f, 12 to -1.5f, 13 to -2f, 14 to -2f),
    builtIn("Bright", 0f, 4f, 10 to 0.5f, 11 to 1.5f, 12 to 1f),
    builtIn("Vocal", 0f, 0f, 3 to -1.5f, 4 to -1f, 7 to 1f, 8 to 2f, 9 to 2.5f, 10 to 2f, 11 to 1f),
    builtIn("Loudness", bass = 5f, air = 3f),
)

internal const val MAX_PRESETS = 30
private const val NAME_LENGTH = 32

/** A preset name without control characters or edge spaces, at most 32 characters; empty if it clashes with a built-in. */
internal fun cleanPresetName(name: String): String {
    val clean = name.filterNot { it.isISOControl() }.trim().take(NAME_LENGTH).trim()
    return if (BUILT_IN_PRESETS.any { it.name.equals(clean, ignoreCase = true) }) "" else clean
}

/** One preset per line: name, a tab, then [encodeSound]. Lines that fail to read are dropped. */
internal fun encodePresets(presets: List<SoundPreset>): String = presets.joinToString("\n") { "${it.name}\t${encodeSound(it.config)}" }

internal fun decodePresets(text: String): List<SoundPreset> = text.lineSequence().mapNotNull { line ->
    val name = cleanPresetName(line.substringBefore('\t', ""))
    if (name.isEmpty()) null else runCatching { SoundPreset(name, decodeSound(line.substringAfter('\t')), builtIn = false) }.getOrNull()
}.distinctBy { it.name.lowercase() }.take(MAX_PRESETS).toList()

/** What an AutoEq file gave: its first 16 filters, and how many filter lines were left out. */
internal class AutoEqImport(val bands: List<Band>, val skipped: Int)

private fun autoEqType(word: String?): FilterType? = when (word?.uppercase()) {
    "PK", "PEQ", "MODAL" -> FilterType.PEAK
    "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
    "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
    "LP", "LPQ" -> FilterType.LOW_PASS
    "HP", "HPQ" -> FilterType.HIGH_PASS
    "BP" -> FilterType.BAND_PASS
    "NO" -> FilterType.NOTCH
    "AP" -> FilterType.ALL_PASS
    else -> null
}

/**
 * Reads AutoEq's ParametricEQ.txt, which is Equalizer APO's text form:
 * `Preamp: -6.2 dB` then lines like `Filter 1: ON PK Fc 105 Hz Gain 6.5 dB Q 0.70`. Values are kept inside Evanna's ranges.
 * The file's preamp is ignored: Evanna's auto headroom already covers the boosts, and both together would play too quiet.
 * shortcut: GraphicEQ.txt and bandwidth (BW Oct) filters are not read; they count as skipped.
 */
internal fun parseAutoEq(text: String): AutoEqImport {
    require(text.length <= 65536) { "too long" }
    val bands = ArrayList<Band>()
    var skipped = 0
    for (raw in text.lineSequence()) {
        val words = raw.substringBefore('#').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || !words[0].startsWith("Filter", ignoreCase = true)) continue
        val state = words.indexOfFirst { it.equals("ON", true) || it.equals("OFF", true) }
        fun number(key: String): Float? = words.indexOfFirst { it.equals(key, true) }.takeIf { it >= 0 }
            ?.let { words.getOrNull(it + 1)?.toFloatOrNull() }?.takeIf { it.isFinite() }
        val type = if (state >= 0) autoEqType(words.getOrNull(state + 1)) else null
        val fc = number("Fc")
        if (type == null || fc == null || bands.size >= MAX_BANDS) { skipped++; continue }
        val gain = if (type.usesGain) (number("Gain") ?: 0f).coerceIn(-15f, 15f) else 0f
        bands += Band(type, fc.coerceIn(20f, 20000f), gain, (number("Q") ?: 0.7071f).coerceIn(0.1f, 12f), words[state].equals("ON", true))
    }
    require(bands.isNotEmpty()) { "no filters" }
    return AutoEqImport(bands, skipped)
}

/** Reads at most 64 KB of UTF-8 text; a larger file is refused. */
internal fun readSmallText(stream: InputStream): String {
    val bytes = ByteArray(65536)
    var n = 0
    while (n < bytes.size) { val r = stream.read(bytes, n, bytes.size - n); if (r < 0) break; n += r }
    require(n < bytes.size || stream.read() < 0) { "too big" }
    return String(bytes, 0, n, Charsets.UTF_8)
}
