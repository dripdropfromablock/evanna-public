package com.evanna.player.decoder

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Generated local WAV; no physical output and no large packaged asset. */
internal object LongSeekProbe {
    fun run(instrumentation: Instrumentation): String {
        val file = File(instrumentation.targetContext.cacheDir, "long-seek.wav")
        val rate = 48000
        val frames = rate * 70
        fun value(frame: Int) = ((frame * 37) % 65536 - 32768).toShort()
        try {
            var flac: List<Int>? = null
            for (name in listOf("seek.flac", "seek-alac.m4a", "seek.mp3", "seek.m4a", "seek.aac", "seek.ogg", "seek.opus", "seek.wv", "seek.ape", "seek.tta")) {
                val encoded = File(instrumentation.targetContext.cacheDir, name)
                try {
                    instrumentation.context.assets.open("decoder-seek/$name").use { input -> encoded.outputStream().use(input::copyTo) }
                    ParcelFileDescriptor.open(encoded, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> NativeDecoder.open(fd.fd).use { decoder ->
                        val pcm = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
                        val linear = ArrayList<Int>()
                        while (true) {
                            val count = decoder.readPcm(pcm, 4096)
                            if (count == 0) break
                            repeat(count * decoder.info.channels) { linear.add(pcm.getInt(it * 4)) }
                        }
                        if (name == "seek.flac") flac = linear
                        if (name.substringAfterLast('.') in listOf("wv", "ape", "tta")) {
                            check(decoder.info.lossless && !decoder.info.quantized && decoder.info.significantBits == 24) { "$name is not original 24-bit audio" }
                            check(linear == flac) { "$name samples differ from the FLAC source" }
                        }
                        for (us in listOf(8_000_001L, 4_000_017L, 10_000_000L, 0L)) {
                            decoder.seekTo(us)
                            val first = (us * decoder.info.sampleRate + 999999) / 1000000
                            check(decoder.stats().positionFrames == first) { "$name seek position" }
                            var cursor = first.toInt()
                            repeat(4) {
                                val count = decoder.readPcm(pcm, 4096)
                                check(count > 0)
                                repeat(count * decoder.info.channels) { n ->
                                    check(pcm.getInt(n * 4) == linear[cursor * decoder.info.channels + n]) {
                                        "$name indexed seek sample mismatch at $us sample $n"
                                    }
                                }
                                cursor += count
                            }
                        }
                    } }
                } finally { encoded.delete() }
            }
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                fun text(s: String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
                fun int(n: Int) { out.writeInt(Integer.reverseBytes(n)) }
                fun short(n: Int) { out.writeShort(java.lang.Short.reverseBytes(n.toShort()).toInt()) }
                text("RIFF"); int(36 + frames * 4); text("WAVEfmt "); int(16); short(1); short(2)
                int(rate); int(rate * 4); short(4); short(16); text("data"); int(frames * 4)
                val block = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)
                var first = 0
                while (first < frames) {
                    block.clear()
                    val count = minOf(4096, frames - first)
                    repeat(count) { n -> val sample = value(first + n); block.putShort(sample); block.putShort(sample) }
                    out.write(block.array(), 0, count * 4); first += count
                }
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> NativeDecoder.open(fd.fd).use { decoder ->
                for (us in listOf(60_000_001L, 10_000_000L, 65_000_017L, 0L)) {
                    decoder.seekTo(us)
                    val first = (us * rate + 999999) / 1000000
                    check(decoder.stats().positionFrames == first)
                    val pcm = ByteBuffer.allocateDirect(31 * 8).order(ByteOrder.LITTLE_ENDIAN)
                    val count = decoder.readPcm(pcm, 31)
                    repeat(count * 2) { n -> check(pcm.getInt(n * 4) == value(first.toInt() + n / 2).toInt() shl 16) }
                }
                val before = decoder.stats().positionFrames
                check(runCatching { decoder.seekTo(80_000_000) }.isFailure)
                check(decoder.stats().positionFrames == before)
                val pcm = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                check(decoder.readPcm(pcm, 1) == 1 && pcm.getInt(0) == value(before.toInt()).toInt() shl 16)
            } }
            return "Ten compressed/lossless codec families matched exact linear seek slices; WavPack/APE/TTA matched the FLAC source exactly; long indexed WAV seeking, backward/repeated seek and failed-seek rollback passed"
        } finally { file.delete() }
    }
}
