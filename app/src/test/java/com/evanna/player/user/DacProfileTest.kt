package com.evanna.player.user

import com.evanna.player.usb.dacIdentity
import org.junit.Assert.*
import org.junit.Test

class DacProfileTest {
    private val jd1=dacIdentity(0x31b2,0x3214,"FIIO JD1")
    @Test fun legacySettingsMigrateOnlyToTheExactKnownJd1Identity() {
        val legacy=LegacyUsbSettings(false,-22f,-30f,3f)
        val migrated=initialDacProfile(jd1,legacy)
        assertEquals(48000,migrated.maxRate);assertEquals(-22f,migrated.startVolumeDb)
        assertEquals(-30f,migrated.volumeLimitDb);assertEquals(3f,migrated.keyStepDb)
        for(other in listOf(dacIdentity(1,2,"Other"),dacIdentity(0x31b2,0x3214,"Other"),dacIdentity(0x31b2,0x3214,null))) {
            val profile=initialDacProfile(other,legacy)
            assertNull(profile.maxRate);assertEquals(-5f,profile.startVolumeDb);assertEquals(0f,profile.volumeLimitDb);assertEquals(1f,profile.keyStepDb)
        }
        assertEquals(48000,initialDacProfile(jd1,null).maxRate)
        assertNull(initialDacProfile(jd1,legacy.copy(followSongRate=true)).maxRate)
    }
    @Test fun corruptLegacyValuesUseValidatedDefaultsAndProfilesRejectInvalidEdits() {
        val normalized=initialDacProfile(jd1,LegacyUsbSettings(false,Float.NaN,-999f,1.5f))
        assertEquals(-5f,normalized.startVolumeDb);assertEquals(0f,normalized.volumeLimitDb);assertEquals(1f,normalized.keyStepDb)
        val valid=DacProfile(jd1)
        for(rate in listOf(0,44100,384000,-1))assertTrue(runCatching {valid.copy(maxRate=rate)}.isFailure)
        assertTrue(runCatching {valid.copy(startVolumeDb=Float.POSITIVE_INFINITY)}.isFailure)
        assertTrue(runCatching {valid.copy(volumeLimitDb=1f)}.isFailure)
        assertTrue(runCatching {valid.copy(keyStepDb=1.5f)}.isFailure)
        for(rate in listOf(null,48000,96000,192000))assertEquals(rate,valid.copy(maxRate=rate).maxRate)
    }
}
