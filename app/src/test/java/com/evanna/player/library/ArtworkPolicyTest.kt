package com.evanna.player.library

import org.junit.Assert.*
import org.junit.Test

class ArtworkPolicyTest {
    @Test fun dimensionsAreCheckedBeforePowerOfTwoSampling() {
        assertEquals(1, artworkSampleSize(128, 256))
        assertEquals(1, artworkSampleSize(257, 100))
        assertEquals(8, artworkSampleSize(4000, 3000))
        assertEquals(64, artworkSampleSize(16384, 1))
        assertEquals(1, artworkSampleSize(2047, 2047, ArtworkSize.LARGE.pixels))
        assertEquals(2, artworkSampleSize(2048, 1000, ArtworkSize.LARGE.pixels))
        for ((width, height) in listOf(0 to 100, -1 to 100, 100 to 0, 16385 to 1,
            9000 to 9000, Int.MAX_VALUE to Int.MAX_VALUE)) assertNull(artworkSampleSize(width, height))
    }

    @Test fun decodeStepKeepsCoversSharpAndBoundsMemory() {
        // 8000 x 8000 is the largest square the 64-megapixel guard accepts.
        for (size in ArtworkSize.entries) for (side in listOf(1, 300, 511, 512, 1023, 1024, 1400, 3000, 8000)) {
            val decoded = side / checkNotNull(artworkSampleSize(side, side, size.pixels))
            assertTrue("$side px for $size decodes to $decoded", decoded >= minOf(side, size.pixels))
            assertTrue("$side px for $size decodes to $decoded", decoded < 2 * size.pixels)
        }
    }

    @Test fun sizedKeysKeepTheListKeyAndSeparateBiggerCovers() {
        val key = artworkKey("id", "content://private/song", 5, 10, "scan-one")
        assertEquals(key, artworkSizeKey(key, ArtworkSize.SMALL))
        val keys = ArtworkSize.entries.map { artworkSizeKey(key, it) }
        assertEquals(ArtworkSize.entries.size, keys.toSet().size)
        assertTrue(keys.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun directFilesShareOneBoundedSource() {
        assertEquals(ArtworkSource("content://private/song", artworkKey("id", "content://private/song", null, null, "direct"), true),
            directSource("id", "content://private/song"))
        for ((id, uri) in listOf("" to "content://x", "x".repeat(101) to "content://x", "id" to "", "id" to "u".repeat(8193)))
            assertThrows(IllegalArgumentException::class.java) { directSource(id, uri) }
    }

    @Test fun sourceIdentityAndRescanCannotCollideOrExposeLocations() {
        val key = artworkKey("id", "content://private/song", 5, 10, "scan-one")
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertEquals(key, artworkKey("id", "content://private/song", 5, 10, "scan-one"))
        for (different in listOf(artworkKey("other", "content://private/song", 5, 10, "scan-one"),
            artworkKey("id", "content://private/replacement", 5, 10, "scan-one"),
            artworkKey("id", "content://private/song", 6, 10, "scan-one"),
            artworkKey("id", "content://private/song", 5, 11, "scan-one"),
            artworkKey("id", "content://private/song", 5, 10, "scan-two"),
            artworkKey("id", "content://private/song", null, null, "scan-one"))) assertNotEquals(key, different)
    }
}
