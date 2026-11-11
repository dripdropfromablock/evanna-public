package com.evanna.player.playback

import com.truesample.usbaudio.VolumeRange
import org.junit.Assert.*
import org.junit.Test

class UsbVolumeKeysTest {
    private val gain=VolumeRange(-74f,0f,0.5f,-20.5f)
    private fun frame(db:Float=-60f,generation:Long=7,step:Float=1f)=
        UsbKeyContext(generation,VolumeRange(-60f,0f,1f,db),step)
    @Test fun remoteScaleUsesEveryHardwarePointIncludingOffKeyStepGainAndCeiling() {
        val scale=checkNotNull(keyVolumeScale(gain))
        assertEquals(149,scale.positions);assertEquals(107,scale.indexFor(-20.5f))
        assertEquals(-20.5f,scale.dbAt(107));assertEquals(-74f,scale.dbAt(-1));assertEquals(0f,scale.dbAt(Int.MAX_VALUE))
        assertEquals(0,scale.indexFor(-100f));assertEquals(148,scale.indexFor(10f));assertEquals(0,scale.indexFor(Float.NaN))
        val capped=checkNotNull(keyVolumeScale(gain.copy(maxDb=-10f)))
        assertEquals(129,capped.positions);assertEquals(-10f,capped.dbAt(128));assertEquals(0,capped.indexFor(-73.75f))
        val fractional=checkNotNull(keyVolumeScale(VolumeRange(-60f,-9f,1.5f,-21f)))
        assertEquals(35,fractional.positions);assertEquals(-9f,fractional.dbAt(34))
        assertNull(keyVolumeScale(VolumeRange(-60f,-10f,1.5f,-21f)))
    }
    @Test fun invalidScalesAreRefusedAndSingletonIsFixedRemoteVolume() {
        for(invalid in listOf(gain.copy(stepDb=0f),gain.copy(stepDb=0.001f),gain.copy(minDb=Float.NaN),
            gain.copy(currentDb=-20.3f),gain.copy(maxDb=1f),gain.copy(currentDb=1f),gain.copy(minDb=-129f)))
            assertNull(invalid.toString(),keyVolumeScale(invalid))
        val fixed=checkNotNull(keyVolumeScale(VolumeRange(-60f,-60f,1f,-60f)))
        assertEquals(1,fixed.positions);assertEquals(-60f,fixed.dbAt(99));assertEquals(0,fixed.indexFor(0f))
    }
    @Test fun onlyHealthyLiveDirectUsbPublishesRemoteVolume() {
        val live=UsbPlaybackSnapshot(generation=7,state=UsbPlaybackState.PLAYING,route=PlaybackRoute.DIRECT_USB,
            usbVolume=gain,readiness=UsbPlaybackReadiness(true,true,true))
        assertEquals(107,usbDeviceVolume(live)?.index)
        for(state in UsbPlaybackState.entries)assertEquals(state.name,state in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING),
            usbDeviceVolume(live.copy(state=state))!=null)
        for(invalid in listOf(live.copy(route=PlaybackRoute.ANDROID),live.copy(route=null),live.copy(usbVolume=null),
            live.copy(readiness=UsbPlaybackReadiness(false,true,true)),live.copy(readiness=UsbPlaybackReadiness(true,false,true)),
            live.copy(readiness=UsbPlaybackReadiness(true,true,false)),live.copy(failure=UsbPlaybackFailure.VOLUME_UNVERIFIED),
            live.copy(volumeLimitDb=-30f),live.copy(volumeLimitDb=Float.NaN),live.copy(generation=-1),live.copy(usbKeyStepDb=1.5f)))assertNull(usbDeviceVolume(invalid))
        assertEquals(1,usbDeviceVolume(live.copy(usbVolume=VolumeRange(-60f,-60f,1f,-60f)))?.scale?.positions)
    }
    @Test fun quickPressesKeepOnlyOneActiveCommandAndOneLatestTarget() {
        val keys=UsbVolumeKeys();val first=checkNotNull(keys.press(frame(),1))
        assertEquals(-59f,first.db)
        repeat(3) {assertNull(keys.press(frame(),1))}
        assertNull(keys.complete(first,frame(-59f)))
        val second=checkNotNull(keys.observe(frame(-59f)))
        assertEquals(-56f,second.db);assertEquals(7L,second.generation)
        assertNull(keys.complete(second,frame(-56f)))
        assertNull(keys.observe(frame(-56f)));assertFalse(keys.isCurrent(second))
    }
    @Test fun directionOrderAndBoundsArePreservedWithoutNetStepErrors() {
        val keys=UsbVolumeKeys()
        assertNull(keys.press(frame(0f),1))
        val first=checkNotNull(keys.press(frame(0f),-1));assertEquals(-1f,first.db)
        assertNull(keys.press(frame(0f),1));assertNull(keys.press(frame(0f),-1))
        assertNull(keys.complete(first,frame(-1f)))
        assertNull(keys.observe(frame(-1f)))
        val up=checkNotNull(keys.press(frame(-59f,step=3f),1));assertEquals(-56f,up.db)
        repeat(1000) {assertNull(keys.press(frame(-59f,step=3f),1))}
        assertNull(keys.complete(up,frame(-56f,step=3f)))
        val cap=checkNotNull(keys.observe(frame(-56f,step=3f)));assertEquals(0f,cap.db)
        assertNull(keys.complete(cap,frame(0f,step=3f)))
        assertNull(keys.observe(frame(0f,step=3f)))
        assertNull(keys.press(frame(-60f),-1));assertNull(keys.press(frame(),0))
    }
    @Test fun failedMismatchedOrResetAcknowledgementsCannotSendMoreCommands() {
        for(outcome in listOf<UsbKeyContext?>(null,frame(-58f),frame(-59f,generation=8),frame(-59f,step=3f),
            frame(-59f).copy(gain=VolumeRange(-60f,-20f,1f,-59f)))) {
            val keys=UsbVolumeKeys();val request=checkNotNull(keys.press(frame(),1))
            keys.press(frame(),1);assertNull(keys.complete(request,outcome))
        }
        val keys=UsbVolumeKeys();val old=checkNotNull(keys.press(frame(),1));keys.press(frame(),1);keys.reset()
        val current=checkNotNull(keys.press(frame(-40f,generation=8),-1));assertEquals(-41f,current.db)
        assertNull(keys.complete(old,frame(-59f)));assertNull(keys.complete(current,frame(-41f,generation=8)))
    }
    @Test fun observedExternalGainOrPolicyChangesDiscardPendingRepeats() {
        val keys=UsbVolumeKeys();val first=checkNotNull(keys.press(frame(),1));keys.press(frame(),1)
        keys.observe(frame(-59f));assertTrue(keys.isCurrent(first))
        keys.observe(frame(-20f));assertFalse(keys.isCurrent(first));assertNull(keys.complete(first,frame(-59f)))
        val second=checkNotNull(keys.press(frame(-20f),-1));keys.press(frame(-20f),-1)
        keys.observe(frame(-20f,step=3f));assertFalse(keys.isCurrent(second))
        val third=checkNotNull(keys.press(frame(-20f),-1));keys.observe(null);assertFalse(keys.isCurrent(third))
    }
    @Test fun confirmedReplyBeforeUiUpdateRemainsTheBaseForTheNextPress() {
        val keys=UsbVolumeKeys();val first=checkNotNull(keys.press(frame(),1))
        assertNull(keys.complete(first,frame(-59f)))
        assertEquals(-59f,keys.confirmedGain(frame()))
        assertNull(keys.press(frame(),1));assertTrue(keys.isCurrent(first))
        val next=checkNotNull(keys.observe(frame(-59f)))
        assertEquals(-58f,next.db)
        val capped=UsbVolumeKeys();val last=checkNotNull(capped.press(frame(-1f),1))
        assertNull(capped.complete(last,frame(0f)));assertNull(capped.press(frame(-1f),1))
        assertNull(capped.observe(frame(0f)));assertFalse(capped.isCurrent(last))
    }
    @Test fun delayedIntermediateUiCannotRollConfirmedKeysBack() {
        val keys=UsbVolumeKeys();val first=checkNotNull(keys.press(frame(),1))
        assertNull(keys.complete(first,frame(-59f)))
        assertNull(keys.press(frame(),1));assertTrue(keys.isCurrent(first))
        val second=checkNotNull(keys.observe(frame(-59f)));assertEquals(-58f,second.db)
        assertNull(keys.complete(second,frame(-58f)))
        assertNull(keys.observe(frame(-59f)));assertTrue(keys.isCurrent(second))
        assertNull(keys.press(frame(-59f),1))
        val third=checkNotNull(keys.observe(frame(-58f)))
        assertEquals(-57f,third.db)
    }
    @Test fun uiMayPublishReadbackBeforeTheCommandReply() {
        val keys=UsbVolumeKeys();val first=checkNotNull(keys.press(frame(),1));keys.press(frame(),1)
        assertNull(keys.observe(frame(-59f)));assertTrue(keys.isCurrent(first))
        val next=checkNotNull(keys.complete(first,frame(-59f)));assertEquals(-58f,next.db)
        keys.complete(next,null);assertFalse(keys.isCurrent(next))
    }
}
