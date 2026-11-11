package com.evanna.player.decoder

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import com.evanna.player.loudness.LoudnessTags
import java.security.MessageDigest

/** Generated private files only; no library, persisted access, queue or output operations. */
internal object LoudnessProbe {
    fun run(instrumentation:Instrumentation):String {
        val file=File(instrumentation.targetContext.cacheDir,"loudness-generated-probe.wav")
        fun generate(quiet:Boolean) {
            val frames=480000;val bytes=frames*4
            file.outputStream().use {output ->
                val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(bytes+36).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
                    .put("data".toByteArray()).putInt(bytes)
                output.write(header.array())
                val block=ByteBuffer.allocate(4000).order(ByteOrder.LITTLE_ENDIAN)
                for(start in 0 until frames step 1000) {
                    block.clear()
                    repeat(1000) {frame ->
                        val sample=if(quiet)0 else (sin((start+frame)*2*PI*1000/48000)*10.0.pow(-23.0/20)*32767).roundToInt()
                        block.putShort(sample.toShort()).putShort(sample.toShort())
                    };output.write(block.array())
                }
            }
        }
        fun <T> decoder(action:(NativeDecoder)->T):T = ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd ->NativeDecoder.open(fd.fd).use(action)}
        try {
            fun readAsset(asset:String):Pair<LoudnessTags,String> {
                instrumentation.context.assets.open(asset).use {input->file.outputStream().use(input::copyTo)}
                return decoder {active ->
                    check(active.stats().positionFrames==0L)
                    val tags=active.info.loudnessTags
                    val pcm=ByteBuffer.allocateDirect(4096*active.info.channels*4).order(ByteOrder.LITTLE_ENDIAN)
                    val hash=MessageDigest.getInstance("SHA-256");var frames=0L
                    while(true) {
                        pcm.clear();val count=active.readPcm(pcm,4096);if(count==0)break
                        frames+=count;check(frames<=2_000_000)
                        val bytes=ByteArray(count*active.info.channels*4);pcm.get(bytes);hash.update(bytes)
                    }
                    tags to hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
                }
            }
            for((name,original) in listOf("gain.flac" to "decoder/stereo-16.flac","gain.mp3" to "decoder-extended/tone.mp3",
                "gain.ogg" to "decoder-extended/tone.ogg","gain.m4a" to "decoder/stereo-16.m4a","precedence.m4a" to "decoder/stereo-16.m4a",
                "r128.opus" to "decoder-extended/tone.opus","bounded.flac" to "decoder/stereo-16.flac")) {
                val tagged=readAsset("loudness-tags/$name")
                val expected=when(name) {
                    "r128.opus" -> LoudnessTags(-5f,6f)
                    "bounded.flac" -> LoudnessTags()
                    else -> LoudnessTags(if(name=="precedence.m4a")-7.25f else -4.25f,-2.5f,0.5,0.9,true)
                }
                check(tagged.first==expected){"Gain tag mapping differs for $name"}
                check(tagged.second==readAsset(original).second){"Gain tags changed PCM for $name"}
            }
            generate(false)
            val result=decoder {it.measureLoudness()}
            check(kotlin.math.abs(checkNotNull(result.integratedLufs)+23)<0.1)
            check(kotlin.math.abs(result.samplePeak-10.0.pow(-23.0/20))<0.0001 && result.durationUs==10_000_000L)
            decoder {it.readPcm(ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN),4);check(runCatching {it.measureLoudness()}.isFailure)}
            decoder {it.requestCancel();check(runCatching {it.measureLoudness()}.isFailure)}
            generate(true);check(decoder {it.measureLoudness()}.let {it.integratedLufs==null && it.samplePeak==0.0 && it.durationUs==10_000_000L})
            return "Generated-only gain tags/aliases/stream precedence/limits and unchanged PCM, plus loudness/peak/duration/quiet/fresh-state/cancellation checks pass. No owner data, grants or audio output touched."
        }finally {file.delete()}
    }
}
