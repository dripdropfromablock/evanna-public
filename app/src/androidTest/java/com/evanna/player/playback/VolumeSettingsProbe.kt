package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Real preference/session checks belong only to the disposable installation; no output is opened. */
@UnstableApi
internal object VolumeSettingsProbe {
    fun run(instrumentation:Instrumentation,restored:Boolean):String {
        val context=instrumentation.targetContext
        check(context.packageName=="com.evanna.player.validation") {"Requires isolated validation application"}
        val owner=PlaybackRuntime.owner(context)
        runBlocking {owner.initialize()}
        check(owner.queue.value.items.isEmpty() && owner.snapshot.value.route==null)
        val settings=context.getSharedPreferences("playback-settings",0)
        if(restored) {
            check(owner.snapshot.value.volumeLimitDb == -20f && owner.snapshot.value.usbKeyStepDb == 3f)
            check(owner.selectedTrack==null && owner.snapshot.value.state==UsbPlaybackState.CLOSED)
            return "After process restart, saved -20dB ceiling and 3dB step restored with no source, route or playback opened."
        }
        val originalStart=owner.snapshot.value.startVolumeDb
        lateinit var future:ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
        }
        val controller=future.get(10,TimeUnit.SECONDS)
        fun command(action:String,value:Float?):Int {
            lateinit var result:ListenableFuture<SessionResult>
            instrumentation.runOnMainSync {result=controller.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),Bundle().apply {
                value?.let {putFloat(PlaybackService.VOLUME_DB,it)}
            })}
            return result.get(10,TimeUnit.SECONDS).resultCode
        }
        try {
            for(invalid in listOf(null,Float.NaN,1f,-81f,-9.7f))check(command(PlaybackService.SET_USB_VOLUME_LIMIT,invalid)==SessionError.ERROR_BAD_VALUE)
            for(invalid in listOf(null,Float.NaN,0f,1.5f,4f))check(command(PlaybackService.SET_USB_KEY_STEP,invalid)==SessionError.ERROR_BAD_VALUE)
            check(owner.snapshot.value.volumeLimitDb==0f && owner.snapshot.value.usbKeyStepDb==1f)
            check(command(PlaybackService.SET_USB_VOLUME_LIMIT,-20f)==SessionResult.RESULT_SUCCESS)
            check(command(PlaybackService.SET_USB_KEY_STEP,3f)==SessionResult.RESULT_SUCCESS)
            val deadline=SystemClock.elapsedRealtime()+10000
            while(true) {
                var visible=false
                instrumentation.runOnMainSync {visible=controller.playlistMetadata.extras?.let {
                    it.getFloat(EvannaPlayer.USB_VOLUME_LIMIT)== -20f && it.getFloat(EvannaPlayer.USB_KEY_STEP)==3f
                }==true}
                if(visible)break
                check(SystemClock.elapsedRealtime()<deadline) {"Saved volume settings did not reach controller metadata"}
                SystemClock.sleep(30)
            }
            check(settings.getFloat("usb_volume_limit_db",0f)== -20f && settings.getFloat("usb_key_step_db",1f)==3f)
            check(owner.snapshot.value.startVolumeDb==originalStart && owner.snapshot.value.route==null && owner.selectedTrack==null)
            return "Actual app-only volume commands reject missing/nonfinite/out-of-range values, save ceiling/step, publish metadata and preserve starting gain; no output or source opened. Restart the validation process, then run volume-settings-restore."
        } finally {instrumentation.runOnMainSync {controller.release()}}
    }
}
