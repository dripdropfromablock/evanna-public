package com.evanna.player

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun onlyNewerVersionsPrompt() {
        assertFalse(shouldPromptUpdate(1, 2, 0, 0, 0))
        assertFalse(shouldPromptUpdate(2, 2, 0, 0, 0))
        assertTrue(shouldPromptUpdate(3, 2, 0, 0, 0))
    }

    @Test fun snoozeExpiresAtItsDeadline() {
        val start = 5_000_000L
        for (hours in listOf(1, 24, 168)) {
            val deadline = start + hours * 3_600_000L
            assertFalse(shouldPromptUpdate(2, 1, 2, deadline, deadline - 1))
            assertTrue(shouldPromptUpdate(2, 1, 2, deadline, deadline))
            assertTrue(shouldPromptUpdate(3, 1, 2, deadline, deadline - 1))
        }
    }

    @Test fun compatibleApkFollowsDeviceAbiPriority() {
        val preferred = AppUpdate(2, "0.2.0", "arm64")
        val fallback = AppUpdate(3, "0.3.0", "arm32")
        val assets = mapOf("arm64-v8a" to preferred, "armeabi-v7a" to fallback)
        assertEquals(preferred, compatibleUpdate(1, listOf("arm64-v8a", "armeabi-v7a"), assets::get))
        assertNull(compatibleUpdate(2, listOf("arm64-v8a", "armeabi-v7a"), assets::get))
        assertEquals(fallback, compatibleUpdate(1, listOf("x86", "armeabi-v7a"), assets::get))
        assertNull(compatibleUpdate(1, listOf("x86"), assets::get))
    }

    @Test fun onlySinglePathSegmentsAreAccepted() {
        assertEquals("$EVANNA_REPOSITORY/releases/download/v0.2.0/Evanna.apk", releaseAssetUrl("v0.2.0", "Evanna.apk"))
        assertNull(releaseAssetUrl("../other", "Evanna.apk"))
        assertNull(releaseAssetUrl("v0.2.0", "some%2fother.apk"))
        assertNull(releaseAssetUrl("v0.2.0", "https://example.com/Evanna.apk"))
    }
}