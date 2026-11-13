package com.evanna.player.playback

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.evanna.player.decoder.DecodedAudioTrack
import com.evanna.player.decoder.NativeDecoder
import com.truesample.usbaudio.*
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

@UnstableApi
internal object GaplessProbe {
    fun run(instrumentation:Instrumentation):String {
        val directory=File(instrumentation.targetContext.cacheDir,"gapless-probe").apply{mkdirs()}
        val main:(()->Unit)->Unit={action->instrumentation.runOnMainSync{action()}}
        var cases=0
        try {
            val unmarked=File(directory,"unmarked.m4a")
            val bytes=instrumentation.context.assets.open("decoder-extended/tone.m4a").use{it.readBytes()}
            var removed=0
            fun removeEdits(from:Int,until:Int) {
                var offset=from
                while(offset+8<=until) {
                    val size=ByteBuffer.wrap(bytes,offset,4).order(ByteOrder.BIG_ENDIAN).int
                    check(size>=8 && size<=until-offset)
                    val type=String(bytes,offset+4,4,Charsets.US_ASCII)
                    if(type=="edts"){"free".toByteArray(Charsets.US_ASCII).copyInto(bytes,offset+4);removed++}
                    else if(type in listOf("moov","trak"))removeEdits(offset+8,offset+size)
                    offset+=size
                }
            }
            removeEdits(0,bytes.size);check(removed>0);unmarked.writeBytes(bytes)
            ParcelFileDescriptor.open(unmarked,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->
                NativeDecoder.open(fd.fd).use{check(!it.info.gaplessEligible){"AAC without edit/priming metadata must use ordinary transitions"}}
            }
            for(asset in listOf("decoder/stereo-24.wav","decoder/stereo-24.flac","decoder/stereo-24.m4a",
                "decoder/stereo-24.aiff","decoder-extended/tone.mp3","decoder-extended/tone.m4a",
                "decoder-extended/tone.ogg","decoder-extended/tone.opus")) {
                val file=File(directory,asset.substringAfterLast('/'))
                instrumentation.context.assets.open(asset).use{input->file.outputStream().use(input::copyTo)}
                val output=Output()
                val owner=UsbSessionOwner(UsbPlaybackController{output},object:PlaybackFocus {
                    override var held=false
                    override fun request():Boolean{held=true;return true}
                    override fun abandon(){held=false}
                })
                lateinit var player:EvannaPlayer;lateinit var session:MediaSession;lateinit var future:ListenableFuture<MediaController>
                main{player=EvannaPlayer(owner);session=MediaSession.Builder(instrumentation.targetContext,player).build();future=MediaController.Builder(instrumentation.targetContext,session.token).buildAsync()}
                val remote=future.get(10,TimeUnit.SECONDS)
                fun track()=DecodedAudioTrack.prepare({ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{NativeDecoder.open(it.fd)}})
                try {
                    val pcm=track().use{check(it.gaplessEligible){"Unverified trim for $asset"};it.format}
                    output.format=pcm
                    runBlocking {
                        repeat(2){check(owner.enqueue(QueueSource(null,"Track $it",null,null,pcm.durationUs,create=::track)))}
                        check(owner.open(7))
                        if(!pcm.strictEligible)check(owner.setUsbPolicy(UsbPlaybackPolicy.COMPATIBILITY))
                    }
                    val readyDeadline=SystemClock.elapsedRealtime()+10000
                    var ready=false
                    while(SystemClock.elapsedRealtime()<readyDeadline) {
                        main{ready=remote.mediaItemCount==2 && remote.currentMediaItemIndex==0 && remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)}
                        if(ready)break
                        SystemClock.sleep(10)
                    }
                    check(ready){"Gapless controller did not receive settled open state"}
                    main{remote.play()}
                    val deadline=SystemClock.elapsedRealtime()+15000
                    while(SystemClock.elapsedRealtime()<deadline) {
                        var ended=false
                        main{ended=remote.playbackState==Player.STATE_ENDED && remote.currentMediaItemIndex==1}
                        if(ended)break
                        SystemClock.sleep(10)
                    }
                    var ended=false
                    main{ended=remote.playbackState==Player.STATE_ENDED && remote.currentMediaItemIndex==1}
                    check(ended){"Gapless $asset: ${owner.snapshot.value}"}
                    check(output.starts==1 && output.drains==1){"Gapless restarted/sealed at $asset boundary"}
                    if(asset.startsWith("decoder/")) {
                        val literal=listOf(Int.MIN_VALUE,Int.MAX_VALUE-255,-256,256,0,0,768,-1280)
                        check(output.samples==literal+literal){"Lossless split/reference PCM mismatch"}
                    }else {
                        val reference=instrumentation.context.assets.open("$asset.ref.f32le").use{it.readBytes()}
                        val floats=ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN)
                        val expected=List(reference.size/4){floats.float.toDouble()}
                        check(output.samples.size==expected.size*2){"Padding length mismatch for $asset: ${output.samples.size}/${expected.size*2}"}
                        output.samples.forEachIndexed{index,sample->check(abs(sample.toDouble()/2147483648.0-expected[index%expected.size])<0.003){"Padding/boundary sample mismatch for $asset at $index"}}
                    }
                    check(owner.positionUs<=checkNotNull(pcm.durationUs))
                    cases++
                }finally{main{remote.release();session.release();player.release()};runBlocking{check(owner.close())}}
            }
            return "$cases real-decoder/MediaController pairs matched literal lossless or independent lossy length/sample references across boundaries with one output start/one final drain; last occurrence/position passed. Synthetic output only. Raw ADTS and untagged MP3 are not claimed gapless."
        }finally{directory.listFiles()?.forEach{it.delete()};directory.delete()}
    }
    private class Output:UsbPlaybackOutput {
        lateinit var format:PcmFormat
        override val description="Synthetic gapless output"
        var starts=0;var drains=0;var sent=0L
        val samples=mutableListOf<Int>()
        override fun plan(source:PcmFormat)=PlaybackPlan(source.sampleRate,source.significantBits,false,true)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo{starts++;return StreamInfo(source.sampleRate,source.significantBits,source.channels,
            DacFormat(1,1,1,source.channels,(source.significantBits+7)/8,source.significantBits,DacFormat.SyncType.SYNC,4096,listOf(source.sampleRate),source.sampleRate,source.sampleRate),source.sampleRate,source.sampleRate,true,false)}
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(sent,0,0,false,sent,sent,false)
        override fun write(buffer:ByteBuffer,frames:Int):Int{repeat(frames*format.channels){samples+=buffer.getInt(it*4)};sent+=frames;return frames}
        override fun drain():Boolean{drains++;return true}
        override fun stop()=true
        override fun close()=Unit
    }
}
