package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.media.AudioManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.evanna.player.decoder.DecodedAudioTrack
import com.evanna.player.decoder.NativeDecoder
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Real service/focus/AudioTrack, two repeating decoder sources; every sample is zero. */
@UnstableApi
internal object GaplessAndroidProbe {
    fun run(instrumentation:Instrumentation,endurance:Boolean,disconnect:Boolean=false):String {
        val context=instrumentation.targetContext
        val file=File(context.cacheDir,"gapless-silence.wav")
        val frames=480000
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+frames*4);put("WAVEfmt ".toByteArray());putInt(16)
            putShort(1);putShort(2);putInt(48000);putInt(192000);putShort(4);putShort(16)
            put("data".toByteArray());putInt(frames*4)
        }
        file.outputStream().use{it.write(header.array());it.write(ByteArray(frames*4))}
        val main:(()->Unit)->Unit={action->instrumentation.runOnMainSync{action()}}
        lateinit var future:ListenableFuture<MediaController>
        main{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val remote=future.get(10,TimeUnit.SECONDS)
        val owner=PlaybackRuntime.owner(context)
        val manager=context.getSystemService(AudioManager::class.java)
        val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun await(condition:()->Boolean){val deadline=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<deadline){var done=false;main{done=condition()};if(done)return;SystemClock.sleep(20)};error("Silent sequence failed: ${owner.snapshot.value}")}
        try {
            main{remote.stop()};await{remote.mediaItemCount==0}
            runBlocking {
                owner.setShuffle(false);owner.setRepeat(QueueRepeat.QUEUE)
                repeat(2){n->check(owner.enqueue(QueueSource(null,"Silent track $n",null,null,10_000_000) {
                    DecodedAudioTrack.prepare({ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{NativeDecoder.open(it.fd)}})
                }))}
                check(owner.openAndroid())
            }
            main{remote.play()};await{remote.isPlaying && owner.snapshot.value.positionUs>=200_000}
            val generation=owner.snapshot.value.generation
            val route=owner.snapshot.value.androidStream
            if(disconnect)check(route?.deviceType in listOf(11,22)){"USB Android output required for unplug check"}
            val start=SystemClock.elapsedRealtime()
            val duration=if(disconnect)90000L else if(endurance)30*60*1000L else 24000L
            var previous=0L;var nextReport=start;var observedSecond=false
            var detached=false
            if(disconnect)instrumentation.sendStatus(1,Bundle().apply{putString("progress","READY_TO_UNPLUG: zero-valued Android sequence is active; unplug JD1 within90seconds and leave disconnected")})
            while(SystemClock.elapsedRealtime()-start<duration) {
                val snapshot=owner.snapshot.value
                if(disconnect) {
                    val missing=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none{it.type in listOf(11,22)}
                    val stopped=snapshot.state in listOf(UsbPlaybackState.PAUSED,UsbPlaybackState.ERROR)
                    if(missing && stopped && PlaybackRuntime.diagnosticFocusHeld()==false){detached=true;break}
                    // Noisy/route callbacks can stop playback before the public device list changes.
                    if(missing || stopped){SystemClock.sleep(50);continue}
                }
                check(snapshot.generation==generation && snapshot.state==UsbPlaybackState.PLAYING && snapshot.failure==null){"Sequence restarted/stopped: $snapshot"}
                check(snapshot.route==PlaybackRoute.ANDROID && snapshot.androidStream==route && snapshot.stream==null)
                check(PlaybackRuntime.diagnosticFocusHeld()==true)
                val stats=checkNotNull(snapshot.stats)
                check(!stats.disconnected && stats.transferErrors==0L && stats.underruns==0L){"Sequence output error: $stats"}
                check(stats.framesCompleted>=previous);previous=stats.framesCompleted
                if(owner.queue.value.index==1)observedSecond=true
                if(SystemClock.elapsedRealtime()>=nextReport) {
                    instrumentation.sendStatus(1,Bundle().apply{putString("progress","Silent Android sequence ${((SystemClock.elapsedRealtime()-start)/1000)}s, completed=$previous, generation=$generation, underruns=${stats.underruns}; screen may be turned off")})
                    nextReport=SystemClock.elapsedRealtime()+60000
                }
                SystemClock.sleep(100)
            }
            if(disconnect) {
                check(detached){"Unplug did not produce a stopped route with released focus within90seconds"}
                val generation=owner.snapshot.value.generation
                main{remote.play()}
                await{owner.snapshot.value.generation!=generation && owner.snapshot.value.state==UsbPlaybackState.ERROR}
                check(owner.snapshot.value.state!=UsbPlaybackState.PLAYING && owner.snapshot.value.androidStream==null)
                return "Actual Android zero-PCM sequence paused/stopped and released focus after physical USB removal; retry refused the missing pinned device without opening a replacement route. Explicit output selection required."
            }
            check(observedSecond && previous>=48000*(duration/1000-1))
            check(manager.getStreamVolume(AudioManager.STREAM_MUSIC)==volume)
            main{remote.pause()};await{!remote.playWhenReady && owner.snapshot.value.state==UsbPlaybackState.PAUSED}
            return "Actual Android service/decoder/focus completed ${duration/1000}s of zero-valued two-track Repeat Queue with continuous generation, completed-frame progress, both occurrences, stable route, zero underruns/errors and unchanged system volume. Physical sound/fidelity not measured."
        }finally {
            runBlocking{check(owner.close());owner.setRepeat(QueueRepeat.OFF);owner.setShuffle(false)}
            main{remote.release()};file.delete()
        }
    }
}
