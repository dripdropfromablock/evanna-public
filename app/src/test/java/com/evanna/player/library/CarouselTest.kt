package com.evanna.player.library

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CarouselTest {
    @Test fun albumPagesWrapInBothDirectionsAndUseTheNearestCopy() {
        assertEquals(3, carouselAlbumIndex(99, 4))
        assertEquals(0, carouselAlbumIndex(100, 4))
        assertEquals(99, carouselPageForAlbum(100, 3, 4))
        assertEquals(100, carouselPageForAlbum(99, 0, 4))
        assertEquals(101, carouselPageForAlbum(100, 1, 2))
        assertEquals(3, carouselAlbumIndex(carouselStartPage(3, 10_000), 10_000))
        assertEquals(0, carouselStartPage(0, 1))
    }

    @Test fun diceCanReachEveryOtherAlbumAndHandlesShortLibraries() {
        val random = Random(42)
        assertNull(randomCarouselAlbum(0, 0, random))
        assertNull(randomCarouselAlbum(0, 1, random))
        assertEquals(1, randomCarouselAlbum(0, 2, random))
        assertEquals((0..8).toSet() - 4, List(200) { randomCarouselAlbum(4, 9, random) }.toSet())
    }
}
