package com.evanna.player.playback

import android.app.Instrumentation
import android.hardware.usb.UsbManager
import android.media.AudioManager
import com.truesample.usbaudio.UsbAudioOutput
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.evanna.player.usb.dacIdentity
import com.evanna.player.user.DacProfile
import com.evanna.player.loudness.*
import com.evanna.player.equalizer.*

/** Real USB and hardware gain, generated digital silence only; never replaces the saved user queue. */
internal object UsbMusicProbe {
    fun run(instrumentation:Instrumentation):String=runBlocking {
        val context=instrumentation.targetContext
        check(!context.getSystemService(AudioManager::class.java).isMusicActive){"Pause other music before the silent USB check"}
        val manager=context.getSystemService(UsbManager::class.java)
        val device=manager.deviceList.values.filter(UsbAudioOutput::isUsbAudioOutput).single()
        check(manager.hasPermission(device)){"Grant USB access in Evanna first"}
        val output=openUsbPlaybackOutput(manager,device.deviceId)
        val profile=DacProfile(device.dacIdentity(),startVolumeDb=USB_START_VOLUME_MIN_DB)
        // Whatever the user saved, the check starts at -80 dB (the lowest savable start), or the DAC minimum when that is higher.
        val controller=UsbPlaybackController(initialStartVolumeDb=USB_START_VOLUME_MIN_DB){output}
        val owner=UsbSessionOwner(controller,object:PlaybackFocus {
            override var held=false
            override fun request():Boolean{held=true;return true}
            override fun abandon(){held=false}
        })
        val observations=mutableListOf<String>()
        try {
            check(owner.open(device.deviceId){profile})
            for(rate in listOf(44100,96000,192000)) {
                val pcm=TrackPcmFormat(rate,24,2,24,"WAV",true,1_000_000)
                check(owner.replaceQueue(QueueSource(null,"Generated digital silence",null,null,1_000_000){object:PcmTrack {
                    override val id="evanna:local:usb-zero:$rate"
                    override val format=pcm
                    override val loudnessTags=LoudnessTags(trackDb=-4f,albumDb=4f)
                    override fun open()=object:PcmReader {
                        override val format=pcm
                        var frames=0
                        override fun read(target:ByteBuffer,maxFrames:Int):Int {
                            val count=minOf(maxFrames,rate-frames)
                            repeat(count*2){target.putInt(it*4,0)}
                            frames+=count;return count
                        }
                        override fun requestCancel()=Unit
                        override fun close()=Unit
                    }
                    override fun close()=Unit
                }}))
                repeat(if(rate==44100)2 else 1) {
                    check(owner.play()){"USB music startup refused: ${owner.snapshot.value.failure}"}
                    val started=owner.snapshot.value
                    check(started.route==PlaybackRoute.DIRECT_USB && started.androidStream==null)
                    check(started.stream?.outputRate==rate && started.stream.bitPerfect && !started.stream.resampled)
                    val gain=checkNotNull(started.usbVolume)
                    // Later streams keep the last level, which this loop returns to the minimum.
                    check(gain.currentDb<=maxOf(USB_START_VOLUME_MIN_DB,gain.minDb) && gain.minDb<0){"USB check started too loud: $gain"}
                    check(!owner.adjustUsbVolume(1,started.generation+1))
                    check(owner.adjustUsbVolume(1,started.generation));check(owner.adjustUsbVolume(-1,started.generation))
                    // Slider path: an absolute target two steps up, read back, then back to the minimum.
                    check(owner.setUsbVolume(gain.minDb+2*gain.stepDb,started.generation))
                    check(owner.snapshot.value.usbVolume?.currentDb==gain.minDb+2*gain.stepDb)
                    check(!owner.setUsbVolume(gain.minDb,started.generation+1))
                    check(owner.setUsbVolume(gain.minDb,started.generation) && owner.snapshot.value.usbVolume?.currentDb==gain.minDb)
                    val end=withTimeout(15000){owner.snapshot.first{it.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR)}}
                    check(end.state==UsbPlaybackState.ENDED){"USB zero stream failed at $rate Hz: ${end.failure}, stats=${end.stats}, gain=${end.usbVolume}"}
                    val stats=checkNotNull(end.stats)
                    check(stats.framesQueued==rate.toLong() && stats.framesCompleted>=rate && stats.transferErrors==0L && !stats.disconnected)
                    observations+="$rate Hz/24-bit, gain ${gain.minDb}..${gain.maxDb} step ${gain.stepDb} dB; $rate zero frames completed"
                }
            }
            for(mode in listOf(LevelingMode.TRACK,LevelingMode.ALBUM)) {
                check(owner.setLevelingMode(mode,profile.identity.key));check(owner.play())
                val started=owner.snapshot.value
                val gain=checkNotNull(started.usbVolume)
                val expected=if(mode==LevelingMode.TRACK)-4f else 4f
                check(started.leveling.offsetDb==expected && started.stream?.bitPerfect==true && !started.conversionAllowed)
                check(started.appliedGainDb==leveledUsbGain(gain.currentDb,expected,gain))
                check(owner.setUsbVolume(minOf(gain.minDb+8f,gain.maxDb),started.generation))
                val adjusted=owner.snapshot.value;val base=checkNotNull(adjusted.usbVolume)
                check(adjusted.appliedGainDb==leveledUsbGain(base.currentDb,expected,base) && adjusted.readiness.volumeVerified)
                val end=withTimeout(15000){owner.snapshot.first {it.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR)}}
                check(end.state==UsbPlaybackState.ENDED && end.stats?.framesCompleted==192000L)
                observations+="$mode hardware offset/base/applied readback and unchanged sample-path status with zero PCM"
            }
            // The built-in EQ runs before the output, so Strict plays it; the owner's own EQ setting comes back afterwards.
            val ownerEq=SoundSettings.config.value
            try {
                SoundSettings.update {SoundConfig(enabled=true,bands=listOf(Band(FilterType.PEAK,1000f,6f,1f)))}
                check(owner.play())
                val processedEnd=withTimeout(15000){owner.snapshot.first {it.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR)}}
                check(processedEnd.state==UsbPlaybackState.ENDED && processedEnd.eqProcessed && processedEnd.eqPreampDb<0f && !processedEnd.usbSamplePathEligible())
                check(processedEnd.stats?.let {it.clippedSamples==0L && it.transferErrors==0L && it.framesQueued==192000L}==true)
                SoundSettings.update {SoundConfig(enabled=true)}
                check(owner.play() && owner.snapshot.value.stream?.let {!it.processed && it.bitPerfect}==true)
                val flatEnd=withTimeout(15000){owner.snapshot.first {it.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR)}}
                check(flatEnd.state==UsbPlaybackState.ENDED && !flatEnd.eqProcessed && flatEnd.stats?.clippedSamples==0L)
            } finally {SoundSettings.update {ownerEq}}
            observations+="Built-in EQ plays in Strict with processed status and zero clips, and on-but-flat stays an exact bypass; physical sound unmeasured"
            check(owner.setCrossfeed(Crossfeed.STRONG,profile.identity.key))
            check(!owner.play() && owner.snapshot.value.failure==UsbPlaybackFailure.CONVERSION_REQUIRED)
            check(owner.play(true) && owner.snapshot.value.stream?.let {it.processed && !it.bitPerfect}==true)
            val crossfedEnd=withTimeout(15000){owner.snapshot.first {it.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR)}}
            check(crossfedEnd.state==UsbPlaybackState.ENDED && crossfedEnd.stats?.let {it.clippedSamples==0L && it.transferErrors==0L && it.framesQueued==192000L}==true)
            check(owner.setCrossfeed(Crossfeed.OFF,profile.identity.key))
            check(owner.play() && owner.snapshot.value.stream?.let {!it.processed && it.bitPerfect}==true)
            val directEnd=withTimeout(15000){owner.snapshot.first {it.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR)}}
            check(directEnd.state==UsbPlaybackState.ENDED && directEnd.stats?.clippedSamples==0L)
            observations+="crossfeed Strict refusal, converted zero-PCM processing flags/zero clips and Off exact bypass; physical sound unmeasured"
        }finally{check(owner.close()){"USB cleanup failed; reconnect before retrying"}}
        "Direct USB silent music startup/restart, bounded gain up/down and absolute slider target with readback, and stale-command refusal passed: ${observations.joinToString("; ")}. No calibration record, nonzero PCM, Android audio output or saved queue/grant change."
    }
}
