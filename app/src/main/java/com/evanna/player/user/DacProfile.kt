package com.evanna.player.user

import com.evanna.player.playback.*
import com.evanna.player.usb.DacIdentity
import com.evanna.player.usb.dacIdentity
import com.evanna.player.usb.sameDac
import com.evanna.player.loudness.LevelingMode
import com.evanna.player.equalizer.Crossfeed
import com.evanna.player.equalizer.EqualizerPreset

internal fun isValidMaxUsbRate(rate:Int?):Boolean=rate==null || rate in listOf(48000,96000,192000)

internal data class DacProfile(val identity:DacIdentity,val maxRate:Int?=null,
    val startVolumeDb:Float=USB_START_VOLUME_DEFAULT_DB,val volumeLimitDb:Float=USB_VOLUME_LIMIT_DEFAULT_DB,
    val keyStepDb:Float=USB_KEY_STEP_DEFAULT_DB,val levelingMode:LevelingMode=LevelingMode.OFF,
    val equalizer:EqualizerPreset?=null,val eqEnabled:Boolean=false,val crossfeed:Crossfeed=Crossfeed.OFF) {
    init {
        require(isValidMaxUsbRate(maxRate));require(validStartVolume(startVolumeDb)!=null)
        require(validVolumeLimit(volumeLimitDb)!=null);require(validUsbKeyStep(keyStepDb)!=null)
        require(!eqEnabled || equalizer!=null)
    }
}
internal data class LegacyUsbSettings(val followSongRate:Boolean,val startVolumeDb:Float,val volumeLimitDb:Float,val keyStepDb:Float)
internal fun isKnownJd1(identity:DacIdentity)=sameDac(identity,dacIdentity(0x31b2,0x3214,"FIIO JD1"))

internal fun initialDacProfile(identity:DacIdentity,legacy:LegacyUsbSettings?):DacProfile {
    if(!isKnownJd1(identity))return DacProfile(identity)
    if(legacy==null)return DacProfile(identity,maxRate=48000)
    return DacProfile(identity,if(legacy.followSongRate)null else 48000,
        validStartVolume(legacy.startVolumeDb) ?: USB_START_VOLUME_DEFAULT_DB,
        validVolumeLimit(legacy.volumeLimitDb) ?: USB_VOLUME_LIMIT_DEFAULT_DB,
        validUsbKeyStep(legacy.keyStepDb) ?: USB_KEY_STEP_DEFAULT_DB)
}
