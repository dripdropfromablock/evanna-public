package com.evanna.player.webmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAdBlockerTest {
    private val blocker = WebAdBlocker({ setOf("doubleclick.net") }, enabled = true)

    @Test fun blocksAnAdHostAndItsSubdomains() {
        assertTrue(blocker.blocks("doubleclick.net", mainFrame = false))
        assertTrue(blocker.blocks("PUBLISHER.DoubleClick.Net.", mainFrame = false))
    }

    @Test fun permitsUnlistedHostsEvenWhenTheirNamesLookSimilar() {
        for (host in listOf("notdoubleclick.net", "doubleclick.net.example.org", "archive.org", "dig.ccmixter.org", null)) {
            assertFalse(blocker.blocks(host, mainFrame = false))
        }
    }

    @Test fun permitsTopLevelRequestsAndDisablingTheFilter() {
        assertFalse(blocker.blocks("doubleclick.net", mainFrame = true))
        blocker.enabled = false
        assertFalse(blocker.blocks("doubleclick.net", mainFrame = false))
    }
}
