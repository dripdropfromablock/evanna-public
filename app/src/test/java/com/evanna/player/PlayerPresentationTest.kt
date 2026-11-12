package com.evanna.player

import org.junit.Assert.*
import org.junit.Test

class PlayerPresentationTest {
    @Test fun playerPullUsesDistanceForSlowDragsAndDirectionForFlings() {
        assertFalse(playerPullTarget(startedOpen = false, progress = 0.24f, velocityDp = -399f))
        assertTrue(playerPullTarget(startedOpen = false, progress = 0.25f, velocityDp = 0f))
        assertTrue(playerPullTarget(startedOpen = true, progress = 0.76f, velocityDp = 399f))
        assertFalse(playerPullTarget(startedOpen = true, progress = 0.75f, velocityDp = 0f))
        assertTrue(playerPullTarget(startedOpen = true, progress = 0.2f, velocityDp = -400f))
        assertFalse(playerPullTarget(startedOpen = false, progress = 0.8f, velocityDp = 400f))
    }

    @Test fun elapsedAndUnknownDurationStayReadableWithoutOverflow() {
        assertEquals("--:--", playbackTime(null))
        assertEquals("--:--", playbackTime(-1))
        assertEquals("0:00", playbackTime(0))
        assertEquals("1:01", playbackTime(61999))
        assertEquals("1:02:03", playbackTime(3723000))
        assertTrue(playbackTime(Long.MAX_VALUE).isNotBlank())
    }
    @Test fun navigationReturnsToExistingDestinationsWithoutGrowingCycles() {
        var stack = listOf(PlayerScreen.LIBRARY)
        stack = playerDestination(stack, PlayerScreen.NOW_PLAYING)
        stack = playerDestination(stack, PlayerScreen.PLAYLISTS)
        assertEquals(listOf(PlayerScreen.LIBRARY, PlayerScreen.NOW_PLAYING), playerDestination(stack, PlayerScreen.NOW_PLAYING))
        assertEquals(listOf(PlayerScreen.LIBRARY), playerDestination(stack, PlayerScreen.LIBRARY))
        assertEquals(stack, playerDestination(stack, PlayerScreen.PLAYLISTS))
    }
    @Test fun aLatePlayerDismissDoesNotPopAnotherScreenOrTheLibrary() {
        val home = PlayerScreen.LIBRARY
        assertEquals(listOf(home), playerSheetDismissed(listOf(home, PlayerScreen.NOW_PLAYING)))
        assertEquals(listOf(home, PlayerScreen.SEARCH), playerSheetDismissed(listOf(home, PlayerScreen.SEARCH, PlayerScreen.NOW_PLAYING)))
        val playlists = listOf(home, PlayerScreen.NOW_PLAYING, PlayerScreen.PLAYLISTS)
        assertEquals(playlists, playerSheetDismissed(playlists))
        assertEquals(listOf(home), playerSheetDismissed(listOf(home)))
    }
    @Test fun tabsReplaceTheStackAboveLibrarySoBackReturnsToLibrary() {
        assertEquals(listOf(PlayerScreen.LIBRARY), playerTab(PlayerScreen.LIBRARY))
        assertEquals(listOf(PlayerScreen.LIBRARY, PlayerScreen.SEARCH), playerTab(PlayerScreen.SEARCH))
        assertEquals(listOf(PlayerScreen.LIBRARY, PlayerScreen.OUTPUT), playerTab(PlayerScreen.OUTPUT))
        assertThrows(IllegalArgumentException::class.java) { playerTab(PlayerScreen.PLAYLISTS) }
        assertEquals(PlayerScreen.SEARCH, selectedTab(listOf(PlayerScreen.LIBRARY, PlayerScreen.SEARCH, PlayerScreen.NOW_PLAYING, PlayerScreen.PLAYLISTS)))
        assertEquals(PlayerScreen.LIBRARY, selectedTab(listOf(PlayerScreen.LIBRARY, PlayerScreen.NOW_PLAYING)))
        assertEquals(PlayerScreen.OUTPUT, selectedTab(listOf(PlayerScreen.LIBRARY, PlayerScreen.NOW_PLAYING, PlayerScreen.OUTPUT)))
        assertTrue(isTabRoot(listOf(PlayerScreen.LIBRARY)))
        assertTrue(isTabRoot(listOf(PlayerScreen.LIBRARY, PlayerScreen.OUTPUT)))
        assertFalse(isTabRoot(listOf(PlayerScreen.LIBRARY, PlayerScreen.NOW_PLAYING)))
        assertFalse(isTabRoot(listOf(PlayerScreen.LIBRARY, PlayerScreen.NOW_PLAYING, PlayerScreen.OUTPUT)))
    }
    @Test fun theTabUnderPushedScreensAndThePushedScreenFollowTheStack() {
        val home = PlayerScreen.LIBRARY
        assertEquals(home, baseTab(listOf(home)))
        assertEquals(PlayerScreen.SEARCH, baseTab(playerTab(PlayerScreen.SEARCH)))
        assertEquals(PlayerScreen.SEARCH, baseTab(listOf(home, PlayerScreen.SEARCH, PlayerScreen.NOW_PLAYING)))
        // Settings opened from Now Playing is pushed over Library, not a tab change.
        assertEquals(home, baseTab(listOf(home, PlayerScreen.NOW_PLAYING, PlayerScreen.OUTPUT)))
        assertEquals(PlayerScreen.OUTPUT, baseTab(listOf(home, PlayerScreen.OUTPUT, PlayerScreen.EQUALIZER)))
        assertNull(pushedScreen(listOf(home)))
        assertNull(pushedScreen(playerTab(PlayerScreen.OUTPUT)))
        assertNull(pushedScreen(listOf(home, PlayerScreen.SEARCH, PlayerScreen.NOW_PLAYING)))
        assertEquals(PlayerScreen.PLAYLISTS, pushedScreen(listOf(home, PlayerScreen.NOW_PLAYING, PlayerScreen.PLAYLISTS)))
        assertEquals(PlayerScreen.OUTPUT, pushedScreen(listOf(home, PlayerScreen.NOW_PLAYING, PlayerScreen.OUTPUT)))
        assertEquals(PlayerScreen.EQUALIZER, pushedScreen(listOf(home, PlayerScreen.OUTPUT, PlayerScreen.EQUALIZER)))
    }
    @Test fun carouselCanBeTheHomeTabUnderLibraryAndPushedScreens() {
        val carousel = PlayerScreen.CAROUSEL
        val library = PlayerScreen.LIBRARY
        assertEquals(library, storedStartPage("invalid"))
        assertEquals(library, storedStartPage("OUTPUT"))
        assertEquals(carousel, storedStartPage(carousel.name))
        assertEquals(listOf(library, carousel), playerTab(carousel, library))
        assertEquals(listOf(carousel), playerTab(carousel, carousel))
        val stack = playerTab(library, carousel)
        assertEquals(listOf(carousel, library), stack)
        assertEquals(library, baseTab(stack))
        assertEquals(carousel, baseTab(listOf(carousel, PlayerScreen.NOW_PLAYING)))
        assertNull(pushedScreen(stack + PlayerScreen.NOW_PLAYING))
        assertEquals(PlayerScreen.OUTPUT, pushedScreen(listOf(carousel, PlayerScreen.NOW_PLAYING, PlayerScreen.OUTPUT)))
    }
    @Test fun pushedScreensStayMountedWhenNowPlayingOpensOverThem() {
        val home = PlayerScreen.LIBRARY
        // The sheet must not unmount the pushed screen it opens over (scroll/list state survives).
        assertEquals(PlayerScreen.FAVORITES, pushedScreen(listOf(home, PlayerScreen.FAVORITES, PlayerScreen.NOW_PLAYING)))
        assertEquals(PlayerScreen.PLAYLISTS, pushedScreen(listOf(home, PlayerScreen.PLAYLISTS, PlayerScreen.NOW_PLAYING)))
        assertEquals(PlayerScreen.RECENT, pushedScreen(listOf(home, PlayerScreen.RECENT, PlayerScreen.NOW_PLAYING)))
        assertEquals(PlayerScreen.MOST, pushedScreen(listOf(home, PlayerScreen.MOST, PlayerScreen.NOW_PLAYING)))
        assertEquals(PlayerScreen.PLAYLISTS, pushedScreen(listOf(home, PlayerScreen.PLAYLISTS, PlayerScreen.NOW_PLAYING)))
        assertEquals(PlayerScreen.FAVORITES, pushedScreen(listOf(home, PlayerScreen.SEARCH, PlayerScreen.FAVORITES, PlayerScreen.NOW_PLAYING)))
        // Multi-hop: Playlists pushed over a covered pushed screen under the sheet.
        assertEquals(PlayerScreen.PLAYLISTS, pushedScreen(listOf(home, PlayerScreen.FAVORITES, PlayerScreen.NOW_PLAYING, PlayerScreen.PLAYLISTS)))
        // But Now Playing over a plain tab still shows no pushed screen.
        assertNull(pushedScreen(listOf(home, PlayerScreen.SEARCH, PlayerScreen.NOW_PLAYING)))
        assertNull(pushedScreen(listOf(home, PlayerScreen.NOW_PLAYING)))
    }
    @Test fun staleDragMissingDurationAndRevokedCommandNeverSeek() {
        assertNull(playbackSeekTarget("old", "new", 30, 100, true))
        assertNull(playbackSeekTarget(null, null, 30, 100, true))
        assertNull(playbackSeekTarget("same", "same", 30, null, true))
        assertNull(playbackSeekTarget("same", "same", 30, 0, true))
        assertNull(playbackSeekTarget("same", "same", 30, 100, false))
        assertEquals(100L, playbackSeekTarget("same", "same", 200, 100, true))
        assertEquals(0L, playbackSeekTarget("same", "same", -5, 100, true))
        assertEquals(30L, playbackSeekTarget("same", "same", 30, 100, true))
    }
    @Test fun wcagContrastMatchesTheStandardDefinitionOnReferenceColors() {
        assertEquals(21.0, contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(1.0, contrastRatio(0xFF808080.toInt(), 0xFF808080.toInt()), 0.0001)
        // The classic AA-boundary grey: white on #767676 is just above 4.5.
        assertTrue(contrastRatio(0xFFFFFFFF.toInt(), 0xFF767676.toInt()) > 4.5)
        // Order of arguments must not matter.
        assertEquals(contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.0)
    }
    @Test fun evannaTextColorsClearAaContrastOnTheSurfacesTheyRenderOn() {
        // Body-critical pairs from EvannaColors: text and the exact surface under it.
        assertTrue(contrastRatio(0xFFE9EFED.toInt(), 0xFF101214.toInt()) >= 4.5) // onBackground / background
        assertTrue(contrastRatio(0xFFE9EFED.toInt(), 0xFF14181A.toInt()) >= 4.5) // onSurface / surface
        assertTrue(contrastRatio(0xFFB9CBC7.toInt(), 0xFF212725.toInt()) >= 4.5) // onSurfaceVariant / surfaceContainerHigh
        assertTrue(contrastRatio(0xFFB9CBC7.toInt(), 0xFF1A201F.toInt()) >= 4.5) // onSurfaceVariant / surfaceContainer (cards)
        assertTrue(contrastRatio(0xFF0A2420.toInt(), 0xFF9ADACB.toInt()) >= 4.5) // onPrimary / primary (play button)
        assertTrue(contrastRatio(0xFFC9F5E9.toInt(), 0xFF1F4A44.toInt()) >= 4.5) // onPrimaryContainer / primaryContainer (active toggles)
    }
    @Test fun playerTextKeepsAaContrastOnTheBrightestAllowedBackdrop() {
        // PlayerBackdrop never lets its brightest point exceed BACKDROP_MAX_LUMINANCE; check both player text whites there.
        val grey = (0..255).first { relativeLuminance((0xFF shl 24) or (it * 0x010101)) > BACKDROP_MAX_LUMINANCE } - 1
        val backdrop = (0xFF shl 24) or (grey * 0x010101)
        fun white(alpha: Double): Int { val v = (255 * alpha + grey * (1 - alpha)).toInt(); return (0xFF shl 24) or (v * 0x010101) }
        assertTrue(contrastRatio(white(0.94), backdrop) >= 4.5)
        assertTrue(contrastRatio(white(0.64), backdrop) >= 4.5)
    }
}
