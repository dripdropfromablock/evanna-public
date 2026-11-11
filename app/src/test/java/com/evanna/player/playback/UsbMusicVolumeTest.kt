package com.evanna.player.playback

import com.truesample.usbaudio.VolumeRange
import org.junit.Assert.*
import org.junit.Test

class UsbMusicVolumeTest {
    @Test fun ceilingCapsStartAndPreviousGainWithoutRaisingIt() {
        val range=VolumeRange(-60f,0f,1f,-10f)
        assertEquals(VolumeRange(-60f,-20f,1f,-20f),usbMusicVolume(range,startDb=-5f,limitDb=-20f))
        assertEquals(-20f,usbMusicVolume(range,previous=-5f,limitDb=-20f)!!.currentDb)
        assertEquals(-40f,usbMusicVolume(range,previous=-40f,limitDb=-20f)!!.currentDb)
        assertEquals(-40f,usbMusicVolume(range,previous=-40f,limitDb=0f)!!.currentDb)
        assertEquals(-10f,usbMusicVolume(range,limitDb=-9.7f)!!.maxDb)
        assertEquals(-10.5f,usbMusicVolume(VolumeRange(-60f,0f,1.5f,-21f),limitDb=-9.7f)!!.maxDb)
    }
    @Test fun unreachableCeilingRefusesGainWhileMinimumIsOneLegalPoint() {
        val range=VolumeRange(-60f,0f,1f,-10f)
        assertEquals(VolumeRange(-60f,-60f,1f,-60f),usbMusicVolume(range,limitDb=-60f))
        for(limit in listOf(-60.1f,-80f,Float.NaN,Float.POSITIVE_INFINITY,1f))assertNull(usbMusicVolume(range,limitDb=limit))
    }
    @Test fun savedLimitsAndButtonStepsRejectUnexpectedValues() {
        for(limit in listOf(-80f,-20f,0f))assertEquals(limit,validVolumeLimit(limit))
        for(limit in listOf(-81f,1f,-9.7f,Float.NaN))assertNull(validVolumeLimit(limit))
        for(step in listOf(0.5f,1f,2f,3f))assertEquals(step,validUsbKeyStep(step))
        for(step in listOf(0f,-1f,0.25f,1.5f,4f,Float.NaN))assertNull(validUsbKeyStep(step))
    }
    @Test fun buttonStepsUseWholeHardwareIncrementsAndStopAtCeiling() {
        val range=VolumeRange(-60f,-10f,0.5f,-20f)
        assertEquals(-17f,range.adjusted(1,3f));assertEquals(-23f,range.adjusted(-1,3f))
        assertEquals(-10f,range.copy(currentDb=-10.5f).adjusted(1,3f))
        assertEquals(-18f,VolumeRange(-60f,0f,1.5f,-21f).adjusted(1,3f))
        assertEquals(-19.5f,VolumeRange(-60f,0f,1.5f,-21f).adjusted(1,2f))
        assertEquals(-18f,VolumeRange(-60f,0f,2f,-20f).adjusted(1,0.5f))
        assertEquals(-60f,VolumeRange(-60f,-60f,1f,-60f).adjusted(-1,1f))
        assertNull(range.adjusted(0,1f));assertNull(range.adjusted(1,Float.NaN))
    }
    @Test fun newConnectionsStartAtTheSavedVolumeOnTheGridBelowZero() {
        assertEquals(VolumeRange(-60f,0f,1f,-5f),usbMusicVolume(VolumeRange(-60f,6f,1f,-10f)))
        assertEquals(VolumeRange(-9f,-1f,2f,-5f),usbMusicVolume(VolumeRange(-9f,4f,2f,1f)))
        assertEquals(-60f,usbMusicVolume(VolumeRange(-60f,0f,1f,-10f),startDb=-60f)!!.currentDb)
        // Rounds down to the DAC grid, never louder than asked; clamps to the DAC range and 0 dB.
        assertEquals(-6f,usbMusicVolume(VolumeRange(-60f,0f,1.5f,-60f))!!.currentDb)
        assertEquals(-60f,usbMusicVolume(VolumeRange(-60f,0f,1f,-10f),startDb=-80f)!!.currentDb)
        assertEquals(0f,usbMusicVolume(VolumeRange(-60f,6f,1f,-10f),startDb=3f)!!.currentDb)
        assertEquals(-60f,usbMusicVolume(VolumeRange(-60f,0f,1f,-10f),startDb=Float.NaN)!!.currentDb)
    }
    @Test fun laterStreamsKeepThePreviousGainOverTheStartVolume() {
        assertEquals(-25f,usbMusicVolume(VolumeRange(-60f,0f,1f,-10f),-25f)!!.currentDb)
        assertEquals(-25f,usbMusicVolume(VolumeRange(-60f,0f,1f,-10f),-25f,startDb=0f)!!.currentDb)
    }
    @Test fun invalidUnknownAndFixedGainControlsAreRefused() {
        val range=VolumeRange(-60f,0f,1f,-10f)
        for(invalid in listOf(null,range.copy(minDb=Float.NaN),range.copy(maxDb=Float.POSITIVE_INFINITY),
            range.copy(stepDb=0f),range.copy(stepDb=-1f),range.copy(stepDb=0.001f),range.copy(currentDb=-9.5f),
            range.copy(minDb=0f),range.copy(minDb=1f),range.copy(minDb=-129f),range.copy(maxDb=-61f))) {
            assertNull(invalid.toString(),usbMusicVolume(invalid))
        }
    }
    @Test fun changedRangeNeverRaisesPreviouslySelectedGain() {
        assertEquals(-60f,usbMusicVolume(VolumeRange(-60f,0f,2f,-10f),-25f)!!.currentDb)
        assertNull(usbMusicVolume(VolumeRange(-20f,0f,1f,-10f),-25f))
        assertNull(usbMusicVolume(VolumeRange(-60f,0f,1f,-10f),Float.NaN))
    }
    @Test fun sliderTargetsSnapDownInsideTheMusicRange() {
        val gain=VolumeRange(-60f,0f,1.5f,-30f)
        assertEquals(-10.5f,gain.snapDown(-10.2f))
        assertEquals(0f,gain.snapDown(12f))
        assertEquals(-60f,gain.snapDown(-200f))
        assertNull(gain.snapDown(Float.NaN))
        assertEquals(-10.5f,gain.nearest(-10.2f))
        assertEquals(-9f,gain.nearest(-9.6f))
        assertEquals(0f,gain.nearest(5f))
    }
    @Test fun savedStartVolumesAreWholeDecibelsFromMinusEightyToZero() {
        for(valid in listOf(-80f,-5f,0f))assertEquals(valid,validStartVolume(valid))
        for(invalid in listOf(-81f,0.5f,1f,-5.5f,Float.NaN,Float.NEGATIVE_INFINITY))assertNull(invalid.toString(),validStartVolume(invalid))
    }
}
