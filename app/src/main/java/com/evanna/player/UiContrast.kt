package com.evanna.player

import kotlin.math.pow

/**
 * WCAG 2.x contrast math for Evanna's own palette rules (DESIGN.md "Contrast floor").
 * Plain Kotlin so JVM unit tests can pin every text/surface pair the theme ships.
 */
internal fun relativeLuminance(argb: Int): Double {
    fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val red = channel(argb shr 16 and 255)
    val green = channel(argb shr 8 and 255)
    val blue = channel(argb and 255)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

/** WCAG 2.x contrast ratio between two ARGB colors: (L-lighter + 0.05) / (L-darker + 0.05). */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
