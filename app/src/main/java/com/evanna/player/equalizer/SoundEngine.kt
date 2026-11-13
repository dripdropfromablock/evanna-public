package com.evanna.player.equalizer

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter shapes from Robert Bristow-Johnson's Audio EQ Cookbook. The pass, notch and all-pass shapes have no gain. */
internal enum class FilterType(val label: String, val usesGain: Boolean) {
    PEAK("Peak", true), LOW_SHELF("Low shelf", true), HIGH_SHELF("High shelf", true),
    LOW_PASS("Low pass", false), HIGH_PASS("High pass", false), BAND_PASS("Band pass", false),
    NOTCH("Notch", false), ALL_PASS("All pass", false),
}

internal data class Band(val type: FilterType = FilterType.PEAK, val frequency: Float = 1000f, val gainDb: Float = 0f,
    val q: Float = 1f, val on: Boolean = true) {
    init {
        require(frequency.isFinite() && frequency in 20f..20000f && gainDb.isFinite() && gainDb in -15f..15f &&
            q.isFinite() && q in 0.1f..12f)
    }
}

/** ISO 2/3-octave centres from 25 Hz to 16 kHz. */
internal val GRAPHIC_HZ = floatArrayOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f)
/** The Q of a 2/3-octave band: sqrt(2^N) / (2^N - 1) with N = 2/3. */
private val GRAPHIC_Q = 2.0.pow(1 / 3.0) / (2.0.pow(2 / 3.0) - 1)
internal const val MAX_BANDS = 16
/** The limiter keeps every sample at or below this level (-0.2 dBFS). */
private const val CEILING = 0.977

/** Bass is a low shelf and Air a high shelf, both with a gentle slope (Q 0.707). */
internal const val BASS_HZ = 100f
internal const val AIR_HZ = 10000f
internal const val TONE_MAX_DB = 12f
internal const val WIDTH_MAX = 200f

/**
 * Everything the built-in EQ plays, all at once: the 15 graphic sliders, up to 16 parametric bands, Bass, Air and
 * stereo Width in percent (100 leaves the stereo image alone, 0 is mono, 200 doubles the side signal).
 */
internal data class SoundConfig(val enabled: Boolean = false, val preampDb: Float = 0f,
    val graphic: List<Float> = List(GRAPHIC_HZ.size) { 0f }, val bands: List<Band> = emptyList(),
    val bassDb: Float = 0f, val airDb: Float = 0f, val width: Float = 100f) {
    init {
        require(preampDb.isFinite() && preampDb in -20f..0f)
        require(graphic.size == GRAPHIC_HZ.size && graphic.all { it.isFinite() && it in -15f..15f })
        require(bands.size <= MAX_BANDS)
        require(bassDb.isFinite() && bassDb in 0f..TONE_MAX_DB && airDb.isFinite() && airDb in 0f..TONE_MAX_DB)
        require(width.isFinite() && width in 0f..WIDTH_MAX)
    }
}

/** Normalised biquad taps b0, b1, b2, a1, a2 (a0 = 1). */
internal fun biquad(type: FilterType, frequency: Double, gainDb: Double, q: Double, rate: Int): DoubleArray {
    val w = 2 * PI * frequency / rate
    val c = cos(w)
    val alpha = sin(w) / (2 * q)
    val a = 10.0.pow(gainDb / 40)
    val s = 2 * sqrt(a) * alpha
    val t = when (type) {
        FilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        FilterType.LOW_SHELF -> doubleArrayOf(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
            (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
        FilterType.HIGH_SHELF -> doubleArrayOf(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
            (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
        FilterType.LOW_PASS -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        FilterType.HIGH_PASS -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        FilterType.BAND_PASS -> doubleArrayOf(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
        FilterType.NOTCH -> doubleArrayOf(1.0, -2 * c, 1.0, 1 + alpha, -2 * c, 1 - alpha)
        FilterType.ALL_PASS -> doubleArrayOf(1 - alpha, -2 * c, 1 + alpha, 1 + alpha, -2 * c, 1 - alpha)
    }
    return doubleArrayOf(t[0] / t[3], t[1] / t[3], t[2] / t[3], t[4] / t[3], t[5] / t[3])
}

/** The filters [config] runs at [rate], in order: keys name each one so its memory survives a change elsewhere. */
private class Chain(val keys: IntArray, val taps: DoubleArray)

private fun chain(config: SoundConfig, rate: Int): Chain {
    val keys = ArrayList<Int>()
    val taps = ArrayList<Double>()
    val top = rate * 0.49
    fun add(key: Int, k: DoubleArray) { keys += key; k.forEach { taps += it } }
    GRAPHIC_HZ.forEachIndexed { i, hz ->
        val gain = config.graphic[i]
        if (abs(gain) >= 0.05f && hz < top) add(i, biquad(FilterType.PEAK, hz.toDouble(), gain.toDouble(), GRAPHIC_Q, rate))
    }
    config.bands.forEachIndexed { i, b ->
        if (b.on && b.frequency < top && (!b.type.usesGain || abs(b.gainDb) >= 0.05f))
            add(100 + i, biquad(b.type, b.frequency.toDouble(), b.gainDb.toDouble(), b.q.toDouble(), rate))
    }
    if (config.bassDb >= 0.05f) add(200, biquad(FilterType.LOW_SHELF, BASS_HZ.toDouble(), config.bassDb.toDouble(), 0.7071, rate))
    if (config.airDb >= 0.05f && AIR_HZ < top) add(201, biquad(FilterType.HIGH_SHELF, AIR_HZ.toDouble(), config.airDb.toDouble(), 0.7071, rate))
    return Chain(keys.toIntArray(), taps.toDoubleArray())
}

/** Combined gain in dB of the filters at angular frequency [w]. */
private fun responseDb(chain: Chain, w: Double): Double {
    val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
    var db = 0.0
    val t = chain.taps
    for (s in chain.keys.indices) {
        val o = s * 5
        val nr = t[o] + t[o + 1] * c1 + t[o + 2] * c2; val ni = -(t[o + 1] * s1 + t[o + 2] * s2)
        val dr = 1 + t[o + 3] * c1 + t[o + 4] * c2; val di = -(t[o + 3] * s1 + t[o + 4] * s2)
        db += 10 * log10(max(nr * nr + ni * ni, 1e-30) / (dr * dr + di * di))
    }
    return db
}

/**
 * The cut that keeps the loudest frequency at or below 0 dB, from a dense log sweep plus every band centre.
 * shortcut: a sampled peak, not a strict bound; the limiter catches what the sweep misses. Use an interval bound if it clips often.
 */
private fun headroomDb(chain: Chain, config: SoundConfig, rate: Int): Double {
    if (chain.keys.isEmpty()) return 0.0
    val top = minOf(20000.0, rate * 0.49)
    var peak = 0.0
    fun at(hz: Double) { if (hz in 20.0..top) peak = max(peak, responseDb(chain, 2 * PI * hz / rate)) }
    val span = log10(top / 20.0)
    for (i in 0..480) at(20.0 * 10.0.pow(span * i / 480))
    GRAPHIC_HZ.forEach { at(it.toDouble()) }
    config.bands.forEach { at(it.frequency.toDouble()) }
    return if (peak > 0.0) kotlin.math.ceil(peak * 10) / 10 else 0.0
}

/** What the screen draws: the filters' gain in dB at each frequency, before the preamp and headroom. */
internal fun soundCurveDb(config: SoundConfig, frequencies: DoubleArray, rate: Int = 48000): DoubleArray {
    val active = chain(config, rate)
    return DoubleArray(frequencies.size) { responseDb(active, 2 * PI * frequencies[it].coerceIn(1.0, rate / 2.0) / rate) }
}

/** The preamp that plays for [config]: the user's preamp minus the auto headroom, in dB. */
internal fun appliedPreampDb(config: SoundConfig, rate: Int = 48000): Float =
    (config.preampDb - headroomDb(chain(config, rate), config, rate)).toFloat()

/**
 * Runs the EQ on interleaved signed 32-bit PCM in place, for every output. One instance per writer thread.
 * Off, or flat with no preamp, leaves the samples untouched, so bit-perfect playback stays bit-perfect.
 */
internal class SoundEngine {
    private var applied: SoundConfig? = null
    private var rate = 0
    private var channels = 0
    private var chain = Chain(IntArray(0), DoubleArray(0))
    private var z = DoubleArray(0)
    private var frame = DoubleArray(0)
    private var gain = 1.0
    private var target = 1.0
    private var limiter = 1.0
    private var smoothing = 1.0
    private var release = 1.0
    private var wide = false
    private var side = 1.0

    /** Whether the last [process] call changed samples. */
    var active = false
        private set
    /** Preamp plus auto headroom in dB, as last applied. */
    var gainDb = 0f
        private set

    fun process(pcm: ByteBuffer, frames: Int, channels: Int, rate: Int, config: SoundConfig) {
        require(channels > 0 && rate > 0 && frames >= 0 && pcm.limit() >= frames * channels * 4)
        if (config !== applied || rate != this.rate || channels != this.channels) configure(config, rate, channels)
        if (!active) return
        val t = chain.taps
        val sections = chain.keys.size
        for (f in 0 until frames) {
            gain += (target - gain) * smoothing
            var peak = 0.0
            for (c in 0 until channels) {
                var y = pcm.getInt((f * channels + c) * 4) * (gain / 2147483648.0)
                for (s in 0 until sections) {  // transposed direct form II
                    val o = s * 5
                    val m = (s * channels + c) * 2
                    val out = t[o] * y + z[m]
                    z[m] = t[o + 1] * y - t[o + 3] * out + z[m + 1]
                    z[m + 1] = t[o + 2] * y - t[o + 4] * out
                    y = out
                }
                frame[c] = y
            }
            if (wide) {  // mid/side: keep (L+R)/2, scale (L-R)/2
                val mid = (frame[0] + frame[1]) / 2; val sideSignal = (frame[0] - frame[1]) / 2 * side
                frame[0] = mid + sideSignal; frame[1] = mid - sideSignal
            }
            for (c in 0 until channels) peak = max(peak, abs(frame[c]))
            // Peak limiter: cuts at once to the ceiling, so nothing clips, then lets go over about 150 ms.
            val rise = limiter + (1 - limiter) * release
            limiter = if (peak * rise > CEILING) CEILING / peak else rise
            for (c in 0 until channels) pcm.putInt((f * channels + c) * 4, Math.rint(frame[c] * limiter * 2147483648.0).toInt())
        }
    }

    private fun configure(config: SoundConfig, rate: Int, channels: Int) {
        val sameFormat = rate == this.rate && channels == this.channels
        val next = chain(config, rate)
        val memory = DoubleArray(next.keys.size * channels * 2)
        // A section that stays keeps its memory, so dragging one slider does not click.
        if (sameFormat) next.keys.forEachIndexed { i, key ->
            val old = chain.keys.indexOf(key)
            if (old >= 0) System.arraycopy(z, old * channels * 2, memory, i * channels * 2, channels * 2)
        }
        val db = if (config.enabled) config.preampDb - headroomDb(next, config, rate) else 0.0
        val wasActive = active
        // shortcut: widening has no auto headroom; the limiter catches its louder side peaks.
        wide = config.enabled && channels == 2 && abs(config.width - 100f) >= 0.5f
        side = config.width / 100.0
        active = config.enabled && (next.keys.isNotEmpty() || abs(db) >= 0.05 || wide)
        target = 10.0.pow(db / 20)
        // shortcut: switching off is instant and may click once; fade out over a few ms if users notice.
        if (!wasActive || !sameFormat) { gain = target; limiter = 1.0 }
        smoothing = 1 - exp(-1 / (0.005 * rate))
        release = 1 - exp(-1 / (0.15 * rate))
        chain = next; z = memory; frame = DoubleArray(channels)
        gainDb = if (active) db.toFloat() else 0f
        applied = config; this.rate = rate; this.channels = channels
    }
}

/** One line per config, so a JVM test can read it back: version|on|preamp|15 graphic gains|bands|bass|air|width. */
internal fun encodeSound(config: SoundConfig): String = listOf("2", if (config.enabled) "1" else "0", config.preampDb.toString(),
    config.graphic.joinToString(","),
    config.bands.joinToString(";") { "${it.type.name}:${it.frequency}:${it.gainDb}:${it.q}:${if (it.on) 1 else 0}" },
    config.bassDb.toString(), config.airDb.toString(), config.width.toString()).joinToString("|")

/** Reads [encodeSound]'s line; version 1 (step 1, no Bass, Air or Width) still loads. */
internal fun decodeSound(text: String): SoundConfig {
    require(text.length <= 4096)
    val parts = text.split("|")
    require(((parts.size == 5 && parts[0] == "1") || (parts.size == 8 && parts[0] == "2")) && parts[1] in listOf("0", "1"))
    val bands = if (parts[4].isEmpty()) emptyList() else parts[4].split(";").map {
        val v = it.split(":")
        require(v.size == 5 && v[4] in listOf("0", "1"))
        Band(FilterType.valueOf(v[0]), v[1].toFloat(), v[2].toFloat(), v[3].toFloat(), v[4] == "1")
    }
    val base = SoundConfig(parts[1] == "1", parts[2].toFloat(), parts[3].split(",").map(String::toFloat), bands)
    return if (parts.size == 5) base else base.copy(bassDb = parts[5].toFloat(), airDb = parts[6].toFloat(), width = parts[7].toFloat())
}
