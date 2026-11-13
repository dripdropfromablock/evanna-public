package com.evanna.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.evanna.player.ui.MENU_SURFACE_ALPHA
import org.junit.Assert.*
import org.junit.Test

class LookTest {
    @Test fun onlyDarkAndLightAreLeftAndTheOldEvannaLookOpensAsDark() {
        assertEquals(listOf("Dark", "Light"), EvannaLook.entries.map { it.label })
        assertEquals(EvannaLook.APPLE_DARK, storedLook(null))
        assertEquals(EvannaLook.APPLE_DARK, storedLook("EVANNA"))  // saved before Evanna dark was removed
        assertEquals(EvannaLook.APPLE_LIGHT, storedLook("APPLE_LIGHT"))
        assertEquals(EvannaLook.APPLE_DARK, storedLook("APPLE_DARK"))
    }
    @Test fun animationSpeedDefaultsToMediumAndRespectsAndroidMotionScale() {
        assertEquals(AnimationSpeed.MEDIUM, storedAnimationSpeed(null))
        assertEquals(AnimationSpeed.MEDIUM, storedAnimationSpeed("unknown"))
        assertEquals(AnimationSpeed.FAST, storedAnimationSpeed("FAST"))
        val motion = AppMotionScale()
        assertEquals(1f, motion.scaleFactor, 0f)
        motion.systemScale = 2f
        motion.speed = AnimationSpeed.SLOW
        assertEquals(3f, motion.scaleFactor, 0f)
        motion.speed = AnimationSpeed.FAST
        assertEquals(4f / 3f, motion.scaleFactor, 0f)
        motion.systemScale = 0f
        assertEquals(0f, motion.scaleFactor, 0f)
    }

    @Test fun everyLookStartsMonochrome() {
        for (look in EvannaLook.entries) {
            val c = look.colors
            val roles = listOf(c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer, c.secondaryContainer,
                c.onSecondaryContainer, c.background, c.onBackground, c.surface, c.onSurface, c.onSurfaceVariant,
                c.surfaceContainerLow, c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest, c.outline, c.outlineVariant, c.error)
            for (color in roles) assertTrue("$look contains $color", color.red == color.green && color.green == color.blue)
        }
    }

    @Test fun artworkPicksAColorFromTheDominantHueFamily() {
        val blue = 0xFF2463C3.toInt()
        val orange = 0xFFDB6021.toInt()
        val pixels = IntArray(100) { when (it) {
            in 0..44 -> blue
            in 45..59 -> orange
            else -> 0xFFB0B0B0.toInt()
        } }
        assertEquals(Color(blue), artworkAccent(pixels))
        assertNull(artworkAccent(intArrayOf(0xFF202020.toInt(), 0xFFEEEEEE.toInt(), 0x0077FF55)))
        assertNull(artworkAccent(intArrayOf()))
    }

    @Test fun artworkChangesBothTheAccentAndSurfacesInEveryLook() {
        for (look in EvannaLook.entries) {
            val warm = look.colors.withArtwork(Color(0xFFCB5525), look.dark)
            val cool = look.colors.withArtwork(Color(0xFF1550AD), look.dark)
            assertTrue(warm.primary.red > warm.primary.blue)
            assertTrue(cool.primary.blue > cool.primary.red)
            assertNotEquals(warm.surfaceContainer, cool.surfaceContainer)
            assertEquals(look.colors.background, cool.background)
            assertEquals(look.colors, look.colors.withArtwork(null, look.dark))
        }
    }

    @Test fun everyLookKeepsTheContrastFloorWithAndWithoutArtwork() {
        val seeds = (0 until 360 step 15).flatMap { hue ->
            listOf(Color.hsv(hue.toFloat(), 1f, 1f), Color.hsv(hue.toFloat(), 0.7f, 0.25f))
        } + listOf(Color.Black, Color.White)
        for (look in EvannaLook.entries) {
            assertTextContrast(look, look.colors)
            for (seed in seeds) {
                val colors = look.colors.withArtwork(seed, look.dark)
                assertTextContrast(look, colors)
                val artwork = if (look.dark) Color.White else Color.Black
                val menu = colors.surfaceContainerHigh.copy(alpha = MENU_SURFACE_ALPHA).compositeOver(artwork)
                assertTrue("$look $seed muted menu text over artwork",
                    contrastRatio(colors.onSurfaceVariant.toArgb(), menu.toArgb()) >= 4.5)
                val player = playerColorScheme(seed)
                val backdrop = Color(0xFF4B4B4B)
                val playerMenu = player.surfaceContainerHigh.copy(alpha = MENU_SURFACE_ALPHA).compositeOver(backdrop)
                val outputSheet = player.surfaceContainer.copy(alpha = 0.74f).compositeOver(backdrop)
                val selectedOutput = player.surfaceContainerHighest.copy(alpha = 0.72f).compositeOver(outputSheet)
                for (surface in listOf(playerMenu, selectedOutput, player.surfaceContainerHigh)) {
                    assertTrue("$look $seed overlay text over $surface",
                        contrastRatio(player.onSurfaceVariant.toArgb(), surface.toArgb()) >= 4.5)
                }
                assertTrue("$look $seed on artwork backdrop",
                    contrastRatio(player.primary.toArgb(), backdrop.toArgb()) >= 4.5)
            }
        }
    }

    @Test fun playerOverlaysShowMoreArtworkColorThanTheRestOfTheApp() {
        val seed = Color(0xFF18AA50)
        for (look in EvannaLook.entries) {
            val player = playerColorScheme(seed)
            val neutral = playerColorScheme(null)
            val regular = neutral.withArtwork(seed, dark = true)
            for ((surface, regularSurface) in listOf(
                player.surfaceContainer to regular.surfaceContainer,
                player.surfaceContainerHigh to regular.surfaceContainerHigh,
                player.surfaceContainerHighest to regular.surfaceContainerHighest,
            )) {
                assertTrue("$look overlay should visibly pick up the artwork hue",
                    (surface.green - surface.red) - (regularSurface.green - regularSurface.red) >= 0.045f)
            }
        }
    }

    private fun assertTextContrast(look: EvannaLook, c: EvannaPalette) {
        val pairs = listOf(
            "onBackground/background" to (c.onBackground to c.background),
            "onSurface/surfaceContainer" to (c.onSurface to c.surfaceContainer),
            "onSurface/surfaceContainerHigh" to (c.onSurface to c.surfaceContainerHigh),
            "onSurfaceVariant/background" to (c.onSurfaceVariant to c.background),
            "onSurfaceVariant/surfaceContainer" to (c.onSurfaceVariant to c.surfaceContainer),
            "onSurfaceVariant/surfaceContainerHigh" to (c.onSurfaceVariant to c.surfaceContainerHigh),
            "onSurfaceVariant/surfaceContainerHighest" to (c.onSurfaceVariant to c.surfaceContainerHighest),
            "primary/background" to (c.primary to c.background),
            "primary/surfaceContainer" to (c.primary to c.surfaceContainer),
            "primary/surfaceContainerHighest" to (c.primary to c.surfaceContainerHighest),
            "onPrimary/primary" to (c.onPrimary to c.primary),
            "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer)) +
            if (look == EvannaLook.APPLE_LIGHT) listOf("primary/grouped" to (c.primary to LightGroupedBackground),
                "onSurfaceVariant/grouped" to (c.onSurfaceVariant to LightGroupedBackground)) else emptyList()
        for ((name, pair) in pairs) {
            val ratio = contrastRatio(pair.first.compositeOver(pair.second).toArgb(), pair.second.toArgb())
            assertTrue("$look $name is ${"%.2f".format(ratio)}", ratio >= 4.5)
        }
    }
    @Test fun lightAndDarkFlagsMatchTheBackgrounds() {
        for (look in EvannaLook.entries)
            assertEquals(look.name, look.dark, relativeLuminance(look.colors.background.toArgb()) < 0.2)
        assertEquals(Color.Black, EvannaLook.APPLE_DARK.colors.background)
    }
    @Test fun unknownOrMissingStoredLooksFallBackToDark() {
        assertEquals(EvannaLook.APPLE_LIGHT, storedLook("APPLE_LIGHT"))
        for (value in listOf(null, "", "apple_light", 3)) assertEquals(EvannaLook.APPLE_DARK, storedLook(value))
    }
}
