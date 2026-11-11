package com.evanna.player.playback

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController as PlatformController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.truesample.usbaudio.StreamStats
import com.truesample.usbaudio.UsbAudioOutput
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Separate owner, generated zero PCM and optional real DAC. Never replaces the saved queue or uses user music. */
@UnstableApi
internal object VolumeKeyProbe {
    fun run(instrumentation:Instrumentation,realUsb:Boolean,awakeNegative:Boolean=false):String=runBlocking {
        if(android.os.Build.VERSION.SDK_INT<29)error("This debug key probe requires Android 10 or newer")
        val context=instrumentation.targetContext
        val audio=context.getSystemService(AudioManager::class.java)
        check(!audio.isMusicActive) {"Pause other music before the silent key check"}
        val saved=PlaybackQueueStore(context).use {it.load()}
        val manager=context.getSystemService(UsbManager::class.java)
        val device=if(realUsb)manager.deviceList.values.filter(UsbAudioOutput::isUsbAudioOutput).singleOrNull()
            ?: error("Connect exactly one USB DAC for the silent key check") else null
        if(device!=null)check(manager.hasPermission(device)) {"Grant Evanna USB access before the silent key check"}
        val main:(()->Unit)->Unit={instrumentation.runOnMainSync {it()}}
        fun stage(value:String)=instrumentation.sendStatus(1,Bundle().apply {putString("stage",value)})
        fun shell(command:String) {ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {it.readBytes()}}
        val power=context.getSystemService(PowerManager::class.java)
        val wasInteractive=power.isInteractive
        val wake=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Evanna:volume-key-probe")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val focusHeld=AtomicBoolean()
        lateinit var owner:UsbSessionOwner
        val focusRequest=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({change->
                focusHeld.set(change==AudioManager.AUDIOFOCUS_GAIN)
                if(change!=AudioManager.AUDIOFOCUS_GAIN)scope.launch(start=CoroutineStart.UNDISPATCHED) {owner.pause()}
            },Handler(Looper.getMainLooper())).build()
        owner=UsbSessionOwner(UsbPlaybackController(openAndroidOutput={AndroidSink()},initialStartVolumeDb=USB_START_VOLUME_MIN_DB) {id->
            if(realUsb)openUsbPlaybackOutput(manager,id) else PlayerUiProbe.Output()
        },object:PlaybackFocus {
            override val held get()=focusHeld.get()
            override fun request():Boolean=(audio.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also {focusHeld.set(it)}
            override fun abandon() {focusHeld.set(false);audio.abandonAudioFocusRequest(focusRequest)}
        })
        var player:EvannaPlayer?=null;var session:MediaSession?=null;var activity:MainActivity?=null
        var mediaBaseline:Pair<Int,Boolean>?=null
        fun media()=audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.isStreamMute(AudioManager.STREAM_MUSIC)
        fun restoreMedia(value:Pair<Int,Boolean>) {
            if(media().first!=value.first)audio.setStreamVolume(AudioManager.STREAM_MUSIC,value.first,0)
            if(media().second!=value.second)audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,if(value.second)AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,0)
            check(media()==value) {"Android media volume was not restored"}
        }
        suspend fun await(label:String,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+10000
            while(!condition()) {
                check(owner.snapshot.value.failure==null) {"Silent key check stopped: ${owner.snapshot.value.failure}"}
                check(SystemClock.elapsedRealtime()<deadline) {label};delay(30)
            }
        }
        fun gain()=checkNotNull(owner.snapshot.value.usbVolume).currentDb
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        try {
            wake.acquire(1920000)
            main {
                player=EvannaPlayer(owner)
                session=MediaSession.Builder(context,player!!).setId("volume-key-${if(realUsb)"usb" else "synthetic"}")
                    .setCallback(object:MediaSession.Callback {
                        override fun onConnect(s:MediaSession,c:MediaSession.ControllerInfo):MediaSession.ConnectionResult {
                            if(c.uid!=context.applicationInfo.uid && !c.isTrusted)return MediaSession.ConnectionResult.reject()
                            val commands=MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            if(c.uid==context.applicationInfo.uid)commands.add(SessionCommand(PlaybackService.SET_USB_VOLUME,Bundle.EMPTY))
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(commands.build()).build()
                        }
                        override fun onCustomCommand(s:MediaSession,c:MediaSession.ControllerInfo,command:SessionCommand,args:Bundle):ListenableFuture<SessionResult> {
                            if(c.uid!=context.applicationInfo.uid || command.customAction!=PlaybackService.SET_USB_VOLUME)
                                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                            return Futures.transform(player!!.setUsbVolume(args.getFloat(PlaybackService.VOLUME_DB,Float.NaN),args.getLong(PlaybackService.VOLUME_GENERATION,-1)),
                                {SessionResult(if(it==true)SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN,PlaybackService.volumeReadback(owner.snapshot.value))},Runnable::run)
                        }
                    }).build()
            }
            check(owner.replaceQueue(PlayerUiProbe.source("Generated digital silence")))
            check(owner.open(device?.deviceId ?: 7));check(owner.play())
            val initial=checkNotNull(owner.snapshot.value.usbVolume)
            val ceiling=kotlin.math.ceil(initial.minDb+maxOf(5f,initial.stepDb)).toFloat().coerceIn(-80f,0f)
            check(owner.setVolumeLimit(ceiling))
            val range=checkNotNull(owner.snapshot.value.usbVolume);val scale=checkNotNull(keyVolumeScale(range))
            check(scale.positions>1)
            val platform=PlatformController(context,checkNotNull(session).platformToken)
            await("Platform session did not become remote") {platform.playbackInfo?.playbackType==PlatformController.PlaybackInfo.PLAYBACK_TYPE_REMOTE}
            stage("remote-provider")
            val point=scale.dbAt(1)
            platform.setVolumeTo(1,0);await("Remote absolute hardware point was not applied") {gain()==point}
            await("Remote slider did not report exact hardware gain") {platform.playbackInfo?.currentVolume==1}
            platform.adjustVolume(AudioManager.ADJUST_RAISE,0)
            val raised=checkNotNull(range.copy(currentDb=point).adjusted(1,1f))
            await("Remote relative adjustment ignored saved step") {gain()==raised}
            platform.setVolumeTo(scale.positions-1,0);await("Remote maximum did not obey ceiling") {gain()==range.maxDb}
            platform.setVolumeTo(0,0);await("Remote minimum did not apply") {gain()==range.minDb}
            main {player!!.setDeviceMuted(true,0);check(!player!!.isDeviceMuted)}
            check(gain()==range.minDb)
            if(range.minDb%1f==0f && range.minDb>=-80f) {
                check(owner.setVolumeLimit(range.minDb))
                await("Singleton did not publish fixed remote volume") {platform.playbackInfo?.let {it.playbackType==PlatformController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && it.maxVolume==0 && it.volumeControl==android.media.VolumeProvider.VOLUME_CONTROL_FIXED}==true}
                check(owner.setVolumeLimit(ceiling))
                await("Expanded ceiling did not restore adjustable provider") {platform.playbackInfo?.maxVolume==scale.positions-1}
                check(gain()==range.minDb)
            }
            await("Remote provider retained the old volume range") {platform.playbackInfo?.maxVolume==scale.positions-1}
            // Wait for USB claiming/routing to settle before comparing the current Android stream's volume.
            delay(300);mediaBaseline=media()
            if(!realUsb) {
                check(owner.openAndroid());check(owner.play())
                await("Android output retained remote volume") {platform.playbackInfo?.playbackType==PlatformController.PlaybackInfo.PLAYBACK_TYPE_LOCAL}
            }
            val driver=PlayerUiProbe.Driver(instrumentation)
            if(!realUsb) {
                activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                main {activity!!.connectPlayback(checkNotNull(session).token)}
                driver.await("MainActivity did not bind silent key session") {driver.visible("Generated digital silence")}
                driver.clickDescription("Open Now Playing")
                stage("android-key-passthrough")
                val before=media();val direction=if(before.first>audio.getStreamMinVolume(AudioManager.STREAM_MUSIC))KeyEvent.KEYCODE_VOLUME_DOWN else KeyEvent.KEYCODE_VOLUME_UP
                instrumentation.sendKeyDownUpSync(direction)
                await("Android-route key did not reach normal system volume") {media()!=before}
                restoreMedia(before)
                check(owner.open(7));check(owner.play())
                await("Resumed USB session did not become remote") {platform.playbackInfo?.playbackType==PlatformController.PlaybackInfo.PLAYBACK_TYPE_REMOTE}
                mediaBaseline=media()
            }
            val base=gain();val up=checkNotNull(owner.snapshot.value.usbVolume!!.adjusted(1,1f))
            if(!realUsb) {
                stage("foreground-keys")
                driver.await("Live USB key controls were not ready") {driver.find {it.contentDescription?.toString()=="USB louder" && it.isEnabled && it.isVisibleToUser}!=null}
                instrumentation.waitForIdleSync()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP);await("Foreground key did not change checked DAC gain") {gain()==up}
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN);await("Foreground key did not return gain") {gain()==base}
            }
            shell("input keyevent KEYCODE_HOME")
            if(realUsb || awakeNegative) {
                if(awakeNegative) {
                    check(power.isInteractive) {"Negative guard check requires screen on"}
                    shell("input keyevent KEYCODE_VOLUME_UP")
                } else {
                    shell("input keyevent KEYCODE_SLEEP");await("Phone did not turn its screen off") {!power.isInteractive}
                    stage("READY: screen off, digital silence only; press physical side Volume Up once, wait one second, then Volume Down once")
                }
                val deadline=SystemClock.elapsedRealtime()+1800000
                while(gain()!=up) {check(SystemClock.elapsedRealtime()<deadline) {"Physical screen-off Volume Up was not observed"};check(owner.snapshot.value.state==UsbPlaybackState.PLAYING);delay(30)}
                val upWhileOff=!power.isInteractive
                stage("Volume Up read back; screenOff=$upWhileOff; waiting for Volume Down")
                if(awakeNegative)shell("input keyevent KEYCODE_VOLUME_DOWN")
                while(gain()!=base) {check(SystemClock.elapsedRealtime()<deadline) {"Physical screen-off Volume Down was not observed"};check(owner.snapshot.value.state==UsbPlaybackState.PLAYING);delay(30)}
                check(upWhileOff && !power.isInteractive) {"SCREEN_OFF_NOT_VERIFIED: both physical key readbacks require the display to remain off"}
                stage("Side keys changed checked gain; upWhileScreenOff=$upWhileOff, downWhileScreenOff=${!power.isInteractive}")
                shell("input keyevent KEYCODE_WAKEUP")
                delay(300)
            } else {
                stage("background-keys")
                shell("input keyevent KEYCODE_VOLUME_UP");await("Background key did not reach remote provider") {gain()==up}
                shell("input keyevent KEYCODE_VOLUME_DOWN");await("Background key did not return gain") {gain()==base}
            }
            check(media()==mediaBaseline) {"Direct-USB keys changed Android media volume"}
            val old=owner.snapshot.value.generation
            check(owner.pause());await("Paused direct USB retained remote provider") {platform.playbackInfo?.playbackType==PlatformController.PlaybackInfo.PLAYBACK_TYPE_LOCAL}
            check(!owner.setUsbVolume(base,old))
            main {check(player!!.deviceInfo.playbackType==DeviceInfo.PLAYBACK_TYPE_LOCAL);check(!player!!.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))}
            check(PlaybackQueueStore(context).use {it.load()}==saved)
            return@runBlocking "${if(realUsb)"Real JD1/digital-zero" else "Synthetic foreground"}, exact remote slider, saved relative step, background${if(realUsb)"/physical screen-off" else "/Android pass-through"}, ceiling and paused/local transition passed; Android stream volume and saved user queue unchanged."
        } finally {
            try {mediaBaseline?.let(::restoreMedia)} finally {
                try {check(owner.close()) {"Silent USB cleanup failed"}} finally {
                    main {activity?.finish();session?.release();player?.release()}
                    if(wasInteractive && !power.isInteractive)shell("input keyevent KEYCODE_WAKEUP")
                    if(wake.isHeld)wake.release()
                    scope.cancel();instrumentation.uiAutomation.dropShellPermissionIdentity()
                }
            }
        }
    }

    private class AndroidSink:PlaybackOutput {
        override val route=PlaybackRoute.ANDROID
        override val description="Synthetic Android key pass-through output"
        private val frames=AtomicLong();@Volatile private var active=false
        override fun startOutput(source:PcmFormat):StartedOutput {active=true;frames.set(0);return StartedOutput.Android(AndroidOutputInfo(source.sampleRate,source.channels,2))}
        override fun ready()=active
        override fun stats()=StreamStats(frames.get(),0,0,false,frames.get(),frames.get())
        override fun write(buffer:ByteBuffer,frames:Int):Int {SystemClock.sleep(10);if(!active)return -1;this.frames.addAndGet(frames.toLong());return frames}
        override fun drain()=true
        override fun stop():Boolean {active=false;return true}
        override fun close() {active=false}
    }
}
