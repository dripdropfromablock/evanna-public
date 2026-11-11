package com.evanna.player.equalizer

import com.evanna.player.user.DacProfile
import com.evanna.player.usb.dacIdentity
import org.junit.Assert.*
import org.junit.Test

class EqualizerPresetTest {
    @Test fun presetNamesAreBoundedAndTheBuiltInFlatCannotBeReplaced() {
        assertEquals("My headphones",validEqualizerName("  My headphones  "))
        for(name in listOf(""," ","a".repeat(201),"Bad\nname","Bad\u0000name"))assertNull(validEqualizerName(name))
        val flat=EqualizerPreset(0,"Flat",EqualizerConfig(),1)
        assertTrue(runCatching {flat.copy(name="Replaced")}.isFailure)
        assertTrue(runCatching {flat.copy(revision=2)}.isFailure)
        assertTrue(runCatching {flat.copy(config=EqualizerConfig(preampDb=-1f))}.isFailure)
        assertTrue(runCatching {EqualizerPreset(-1,"Name",EqualizerConfig(),1)}.isFailure)
        assertTrue(runCatching {EqualizerPreset(1,"Name",EqualizerConfig(),0)}.isFailure)
    }
    @Test fun profilesStartWithEqOffAndEnabledProfilesRequireAResolvedPreset() {
        val profile=DacProfile(dacIdentity(1,2,"DAC"))
        assertFalse(profile.eqEnabled);assertNull(profile.equalizer)
        assertTrue(runCatching {profile.copy(eqEnabled=true)}.isFailure)
        val preset=EqualizerPreset(1,"Original fixture",EqualizerConfig(preampDb=-3f),1)
        assertEquals(preset,profile.copy(eqEnabled=true,equalizer=preset).equalizer)
        assertEquals(preset,profile.copy(equalizer=preset).equalizer)
    }
}
