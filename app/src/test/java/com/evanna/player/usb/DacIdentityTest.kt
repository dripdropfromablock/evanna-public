package com.evanna.player.usb

import org.junit.Assert.*
import org.junit.Test

class DacIdentityTest {
    private val jd1=dacIdentity(0x31b2,0x3214,"KT USB Audio")
    @Test fun aPluggedInDacConnectsItselfOnlyWhenEveryConditionHolds() {
        assertTrue(shouldAutoConnect(enabled=true,usedBefore=true,permitted=true,alreadyOpen=false,busy=false))
        // Each missing condition keeps the old behavior: a notice and a tap.
        assertFalse("switch off",shouldAutoConnect(enabled=false,usedBefore=true,permitted=true,alreadyOpen=false,busy=false))
        assertFalse("new DAC: first use is explicit",shouldAutoConnect(enabled=true,usedBefore=false,permitted=true,alreadyOpen=false,busy=false))
        assertFalse("no access yet: Android's popup comes first",shouldAutoConnect(enabled=true,usedBefore=true,permitted=false,alreadyOpen=false,busy=false))
        assertFalse("already open",shouldAutoConnect(enabled=true,usedBefore=true,permitted=true,alreadyOpen=true,busy=false))
        assertFalse("another connect or permission request is running",shouldAutoConnect(enabled=true,usedBefore=true,permitted=true,alreadyOpen=false,busy=true))
    }
    @Test fun reconnectMatchesStableIdentityRatherThanTransientConnectionNumber() {
        assertTrue(sameDac(jd1,dacIdentity(0x31b2,0x3214," kt usb audio ")))
        assertFalse(sameDac(jd1,dacIdentity(0x31b2,0x3214,"Different product")))
        assertFalse(sameDac(jd1,dacIdentity(0x31b3,0x3214,"KT USB Audio")))
        assertFalse(sameDac(jd1,dacIdentity(0x31b2,0x3215,"KT USB Audio")))
        assertEquals(jd1,storedDacIdentity(jd1.storedValue))
        assertEquals(jd1.key,dacIdentity(0x31b2,0x3214,"kt usb audio").key)
    }
    @Test fun preferenceParsingAndDeviceLabelsAreBounded() {
        for(value in listOf(null,"","garbage","-1:2:name","65536:1:name","1:2:"+"x".repeat(129),"1:2:bad\nname"))
            assertNull(value,storedDacIdentity(value))
        val colon=dacIdentity(1,2,"DAC: one")
        assertEquals(colon,storedDacIdentity(colon.storedValue))
        assertEquals("USB DAC",dacIdentity(1,2,null).displayName)
        assertEquals(128,dacIdentity(1,2,"a".repeat(200)).productName.length)
    }
    @Test fun returningDacDoesNotStealAnExistingSelectionAndMissingSelectionPrefersLastUsed() {
        val other=dacIdentity(1,2,"Other")
        val connections=listOf(DacConnection(8,other,false),DacConnection(19,jd1,true))
        assertEquals(8,preferredDacId(8,connections,jd1))
        assertEquals(19,preferredDacId(7,connections,jd1))
        assertEquals(19,preferredDacId(null,connections,jd1))
        assertEquals(8,preferredDacId(null,connections,null))
        assertNull(preferredDacId(19,emptyList(),jd1))
    }
    @Test fun lostUsbPacketsSuggestAndroidAudioOnceEnoughAreSeenOnTheSameDac() {
        // The count restarts per plug-in and is missing between songs, so the largest seen is kept.
        var drops=latestUsbDrops(null,"jd1",3)
        drops=latestUsbDrops(drops,"jd1",0)
        assertEquals("jd1" to 3L,drops)
        assertFalse(suggestAndroidAudio("DIRECT_USB",drops,"jd1",emptySet()))
        drops=latestUsbDrops(drops,"jd1",5)
        assertTrue(suggestAndroidAudio("DIRECT_USB",drops,"jd1",emptySet()))
        assertEquals("jd1" to 5L,latestUsbDrops(drops,"jd1",2))
        assertFalse("already on Android audio",suggestAndroidAudio("ANDROID",drops,"jd1",emptySet()))
        assertFalse("owner kept Direct USB",suggestAndroidAudio("DIRECT_USB",drops,"jd1",setOf("jd1")))
        assertFalse("another DAC",suggestAndroidAudio("DIRECT_USB",drops,"other",emptySet()))
        assertEquals("other" to 1L,latestUsbDrops(drops,"other",1))
        assertEquals(drops,latestUsbDrops(drops,null,9))
    }
}
