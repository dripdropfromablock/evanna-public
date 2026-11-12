package com.evanna.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

internal fun sampleArtworkAccent(cover: Bitmap): Color? {
    val side = 24
    return artworkAccent(IntArray(side * side) { index ->
        val x = (2 * (index % side) + 1) * cover.width / (2 * side)
        val y = (2 * (index / side) + 1) * cover.height / (2 * side)
        cover.getPixel(x, y)
    })
}

/** Pick the strongest family of hues without averaging unrelated colors into gray. */
internal fun artworkAccent(pixels: IntArray): Color? {
    val weights = IntArray(12)
    val reds = IntArray(weights.size)
    val greens = IntArray(weights.size)
    val blues = IntArray(weights.size)
    var visible = 0
    for (pixel in pixels) {
        val alpha = pixel ushr 24
        if (alpha < 128) continue
        visible++
        val red = pixel shr 16 and 255
        val green = pixel shr 8 and 255
        val blue = pixel and 255
        val high = maxOf(red, green, blue)
        val chroma = high - minOf(red, green, blue)
        if (chroma < 24 || high < 42) continue
        val hue = when (high) {
            red -> (green - blue).toFloat() / chroma
            green -> 2f + (blue - red).toFloat() / chroma
            else -> 4f + (red - green).toFloat() / chroma
        }
        val bucket = ((hue + 6f) * 2).roundToInt() % weights.size
        val weight = chroma * alpha / 255
        weights[bucket] += weight
        reds[bucket] += red * weight
        greens[bucket] += green * weight
        blues[bucket] += blue * weight
    }
    val best = weights.indices.maxBy { weights[it] }
    val weight = weights[best]
    if (weight == 0 || weight < visible * 4) return null
    return Color(reds[best] / weight, greens[best] / weight, blues[best] / weight)
}

internal fun EvannaPalette.withArtwork(
    seed: Color?, dark: Boolean, filledChips: Boolean,
    surfaceTint: Float = if (dark) 0.035f else 0.025f,
): EvannaPalette {
    if (seed == null) return this
    val tint = if (dark) 0.12f else 0.055f
    val highest = lerp(surfaceContainerHighest, seed, surfaceTint)
    val primary = readableAccent(seed, highest, dark)
    val onPrimary = if (dark) Color.Black else Color.White
    return copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = lerp(primaryContainer, seed, tint),
        secondaryContainer = if (filledChips) primary else lerp(secondaryContainer, seed, tint),
        onSecondaryContainer = if (filledChips) onPrimary else onSecondaryContainer,
        surface = lerp(surface, seed, surfaceTint),
        surfaceContainerLow = lerp(surfaceContainerLow, seed, surfaceTint),
        surfaceContainer = lerp(surfaceContainer, seed, surfaceTint),
        surfaceContainerHigh = lerp(surfaceContainerHigh, seed, surfaceTint),
        surfaceContainerHighest = highest,
        error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
    )
}

private fun readableAccent(seed: Color, surface: Color, dark: Boolean): Color {
    val neutral = if (dark) Color.White else Color.Black
    // Dark text and icons also sit directly on the player backdrop, which can reach luminance 0.07.
    val backdrop = Color(0xFF4B4B4B).toArgb()
    for (step in 0..20) {
        val candidate = lerp(seed, neutral, step / 20f)
        val ink = candidate.toArgb()
        if (contrastRatio(ink, surface.toArgb()) >= 4.65 && (!dark || contrastRatio(ink, backdrop) >= 4.65)) return candidate
    }
    return neutral
}
