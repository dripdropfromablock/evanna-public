package com.evanna.player

import org.junit.Assert.*
import org.junit.Test

class DockFoldTest {
    @Test fun theTabBarNarrowsFirstAndTheMiniPlayerSlidesClearBeforeItDrops() {
        for (part in listOf(::dockTabsFold, ::dockMiniSlide, ::dockMiniDrop)) {
            assertEquals(0f, part(0f), 0f); assertEquals(1f, part(1f), 0f)
        }
        assertEquals(1f, dockTabsFold(0.6f), 0f)    // the tab bar is a round by 60%
        assertEquals(0f, dockMiniSlide(0.3f), 0f)   // the mini player waits for the tabs to narrow
        assertEquals(1f, dockMiniSlide(0.75f), 0f)  // and is clear of the round...
        assertTrue(dockMiniDrop(0.75f) < 0.5f)      // ...before it is halfway down
        assertEquals(0f, dockMiniDrop(0.55f), 0f)
        assertTrue(dockTabsFold(0.5f) > dockMiniSlide(0.5f))
    }
    @Test fun eachPartEasesInAndOutWithoutOvershooting() {
        var previous = -1f
        for (i in 0..100) {
            val t = foldPart(i / 100f, 0.2f, 0.8f)
            assertTrue(t in 0f..1f); assertTrue(t >= previous); previous = t
        }
        assertEquals(0.5f, foldPart(0.5f, 0.2f, 0.8f), 1e-6f)
        assertTrue(foldPart(0.25f, 0.2f, 0.8f) < 0.25f / 3)  // slow start
    }
}
