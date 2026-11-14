package com.evanna.player.ui.glass

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class GlassTest {
    @Test fun eachAndroidVersionGetsTheGlassItCanDraw() {
        assertEquals(GlassTier.FROSTED, glassTier(30, enabled = true, reduceTransparency = false))  // the M30s
        assertEquals(GlassTier.FROSTED, glassTier(28, enabled = true, reduceTransparency = false))
        assertEquals(GlassTier.BLUR, glassTier(31, enabled = true, reduceTransparency = false))
        assertEquals(GlassTier.BLUR, glassTier(32, enabled = true, reduceTransparency = false))
        assertEquals(GlassTier.FULL, glassTier(33, enabled = true, reduceTransparency = false))
        assertEquals(GlassTier.FULL, glassTier(36, enabled = true, reduceTransparency = false))  // the Xiaomi
    }
    @Test fun switchesOverrideTheDevice() {
        assertEquals(GlassTier.SOLID, glassTier(36, enabled = false, reduceTransparency = false))
        assertEquals(GlassTier.SOLID, glassTier(30, enabled = false, reduceTransparency = true))
        assertEquals(GlassTier.FROSTED, glassTier(36, enabled = true, reduceTransparency = true))
        // Only the live tiers pay for recording the page.
        assertEquals(listOf(GlassTier.BLUR, GlassTier.FULL), GlassTier.entries.filter { it.samplesBackdrop })
    }
    @Test fun frostIsDenseAndReduceTransparencyDenser() {
        assertTrue(frostColor(dark = true, reduceTransparency = false).alpha >= 0.9f)
        assertTrue(frostColor(dark = true, reduceTransparency = true).alpha > frostColor(dark = true, reduceTransparency = false).alpha)
        assertTrue(glassTint(dark = false).alpha < frostColor(dark = false, reduceTransparency = false).alpha)
        val (top, bottom) = rimColors(dark = true)
        assertTrue(top.alpha > bottom.alpha)  // the light falls on the top edge
    }
    @Test fun theFloorFadesInWithoutALine() {
        assertEquals(0f, floorAlpha(0f), 0f)
        assertEquals(0.94f, floorAlpha(1f), 1e-6f)
        assertEquals(0f, floorAlpha(-1f), 0f)
        var previous = -1f
        for (i in 0..20) { val a = floorAlpha(i / 20f); assertTrue(a >= previous); previous = a }
        assertTrue(floorAlpha(0.1f) < 0.05f)  // the top edge is near invisible
    }
    @Test fun increaseContrastEdgesStandOutFromThePageAndTheFill() {
        for (dark in listOf(true, false)) {
            val fill = contrastFill(dark)
            val ratio = com.evanna.player.contrastRatio(contrastEdge(dark).toArgb(), fill.toArgb())
            // Fill and page are the same black or white, so one check covers both; 3:1 is the floor for a control's edge.
            assertTrue("dark=$dark edge is ${"%.2f".format(ratio)}:1", ratio >= 3.0)
        }
        assertEquals(androidx.compose.ui.graphics.Color.Black, contrastFill(dark = true))
        assertEquals(androidx.compose.ui.graphics.Color.White, contrastFill(dark = false))
    }
    // Shape outlines need android.graphics.Path, which JVM tests lack; the corners are checked on the phone instead.
}
