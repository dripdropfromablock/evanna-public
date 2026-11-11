package com.evanna.player.loudness

import com.truesample.usbaudio.VolumeRange
import org.junit.Assert.*
import org.junit.Test

class LoudnessTest {
    @Test fun decisionsUseOnlyTheSelectedModeAndCompleteMeasuredAlbumFallback() {
        val tags=LoudnessTags(trackDb=-4f,albumDb=-2f)
        assertEquals(0f,levelingDecision(LevelingMode.OFF,tags).offsetDb,0f)
        assertEquals(-4f,levelingDecision(LevelingMode.TRACK,tags).offsetDb,0f)
        assertEquals(-2f,levelingDecision(LevelingMode.ALBUM,tags).offsetDb,0f)
        val measurement=LoudnessMeasurement(-23.0,0.2,1_000_000)
        assertEquals(5f,levelingDecision(LevelingMode.TRACK,LoudnessTags(),measurement).offsetDb,0f)
        assertEquals(0f,levelingDecision(LevelingMode.ALBUM,LoudnessTags(),measurement).offsetDb,0f)
        assertEquals(5f,levelingDecision(LevelingMode.ALBUM,LoudnessTags(),album=listOf(measurement)).offsetDb,0f)
        assertEquals(0f,levelingDecision(LevelingMode.ALBUM,LoudnessTags(),album=listOf(null,measurement)).offsetDb,0f)
    }
    @Test fun r128UsesOnlySixAsciiCharactersWithNoWhitespace() {
        for(raw in listOf(" 256","256 ","0000256","２５６","256.0")) {
            assertNull(loudnessTagsFromText(listOf(null,null,null,null,raw,null,null)).trackDb)
        }
        assertEquals(6f,loudnessTagsFromText(listOf(null,null,null,null,"+00256",null,null)).trackDb!!,0f)
    }
    @Test fun tagPriorityUnitsAndUntrustedValuesAreExplicit() {
        val tags=loudnessTagsFromText(listOf("-6.25 dB","+1.0 DB","0.9","1","-2560","0","soundcheck"))
        assertEquals(-6.25f,tags.trackDb!!,0f);assertEquals(1f,tags.albumDb!!,0f)
        assertEquals(0.9,tags.trackPeak!!,0.0);assertTrue(tags.soundCheckPresent)
        val r128=loudnessTagsFromText(listOf(null,null,null,null,"-2560","256",null))
        assertEquals(-5f,r128.trackDb!!,0f);assertEquals(6f,r128.albumDb!!,0f)
        val bad=loudnessTagsFromText(listOf("NaN","31 dB","Infinity","-1","32768","not an integer",null))
        assertNull(bad.trackDb);assertNull(bad.albumDb);assertNull(bad.trackPeak);assertNull(bad.albumPeak)
        assertNull(loudnessTagsFromText(listOf("1".repeat(129),null,null,null,null,null,null)).trackDb)
    }
    @Test fun energyWeightedAlbumIncludesQuietDurationWithoutInventingSilenceLoudness() {
        val a=LoudnessMeasurement(-20.0,0.5,2_000_000);val b=LoudnessMeasurement(-10.0,0.9,1_000_000)
        assertEquals(-13.9794001,albumIntegratedLufs(listOf(a,b))!!,0.00001)
        val quiet=LoudnessMeasurement(null,0.0,2_000_000)
        assertEquals(-23.0102999,albumIntegratedLufs(listOf(a,quiet))!!,0.00001)
        assertNull(albumIntegratedLufs(listOf(quiet)));assertNull(albumIntegratedLufs(emptyList()))
        assertNull(albumIntegratedLufs(listOf(a,LoudnessMeasurement(null,1.0,10))))
        assertNull(levelingOffset(null));assertEquals(2f,levelingOffset(-20.0)!!,0f)
        assertEquals(30f,levelingOffset(-90.0)!!,0f)
    }
    @Test fun appliedGainKeepsBaseSeparateAndSnapsDownInsideTheSavedCeiling() {
        val range=VolumeRange(-74f,-10f,0.5f,-20f)
        assertEquals(-12f,leveledUsbGain(-20f,8f,range)!!,0f)
        assertEquals(-10f,leveledUsbGain(-12f,8f,range)!!,0f)
        assertEquals(-24.5f,leveledUsbGain(-20f,-4.25f,range)!!,0f)
        assertEquals(-74f,leveledUsbGain(-72f,-8f,range)!!,0f)
        assertNull(leveledUsbGain(-20.1f,0f,range));assertNull(leveledUsbGain(-20f,Float.NaN,range))
        assertEquals(-20f,range.currentDb,0f)
        assertEquals(-74f,leveledUsbGain(-74f,30f,VolumeRange(-74f,-74f,0.5f,-74f))!!,0f)
    }
    @Test fun invalidMeasurementsFailBeforeTheyCanBeStoredOrApplied() {
        for(build in listOf<()->Unit>({LoudnessMeasurement(Double.NaN,0.1,1)},{LoudnessMeasurement(-20.0,1.01,1)},
            {LoudnessMeasurement(-20.0,0.1,0)},{LoudnessMeasurement(-20.0,0.1,Long.MAX_VALUE)}))assertTrue(runCatching(build).isFailure)
    }
}
