package com.evanna.player

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {
    @Test fun attachIntentRunsOnlyForAFreshLaunchNotRestoredFromHistory() {
        assertTrue(shouldHandleAttachIntent(restored = false, flags = 0))
        assertTrue(shouldHandleAttachIntent(restored = false, flags = Intent.FLAG_ACTIVITY_NEW_TASK))
        assertFalse(shouldHandleAttachIntent(restored = true, flags = 0))
        assertFalse(shouldHandleAttachIntent(restored = false, flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }
}
