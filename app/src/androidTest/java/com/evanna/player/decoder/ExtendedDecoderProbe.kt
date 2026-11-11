package com.evanna.player.decoder

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

internal object ExtendedDecoderProbe {
    fun run(instrumentation: Instrumentation): String {
        val directory = File(instrumentation.targetContext.cacheDir, "decoder-extended-probe").apply { mkdirs() }
        fun file(name: String): File = File(directory, name).also { target ->
            instrumentation.context.assets.open("decoder-extended/$name").use { input -> target.outputStream().use(input::copyTo) }
        }
        try {
            for (name in listOf("tone.mp3", "tone.m4a", "tone.aac", "tone.ogg", "tone.opus")) {
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("progress", "Checking $name reference and seeking") })
                val encoded = file(name)
                val reference = ByteBuffer.wrap(file("$name.ref.f32le").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                open(encoded).use { decoder ->
                    check(!decoder.info.lossless && decoder.info.quantized && decoder.info.significantBits == null)
                    check(decoder.info.gaplessEligible==(name!="tone.aac")){"Unexpected gapless eligibility for $name"}
                    val linear = readAll(decoder, reference.capacity() / 4)
                    check(linear.size == reference.capacity() / 4) { "$name decoded length differs from desktop reference" }
                    var squareError = 0.0
                    linear.forEach { sample ->
                        val error = sample.toDouble() / 2147483648.0 - reference.float.toDouble()
                        check(abs(error) < 0.003) { "$name reference peak error exceeded tolerance" }
                        squareError += error * error
                    }
                    check(sqrt(squareError / linear.size) < 0.0001) { "$name reference RMS error exceeded tolerance" }
                    check(decoder.stats().quantizedSamples == linear.size.toLong() && decoder.stats().clippedSamples == 0L)
                    for (position in listOf(0L, 333_333L, 1_000_001L, 17L, 0L)) {
                        val first = (position * decoder.info.sampleRate + 999_999) / 1_000_000
                        decoder.seekTo(position)
                        check(decoder.stats().positionFrames == first)
                        val buffer = ByteBuffer.allocateDirect(31 * decoder.info.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                        val frames = decoder.readPcm(buffer, 31)
                        repeat(frames * decoder.info.channels) { index ->
                            check(buffer.getInt(index * 4) == linear[first.toInt() * decoder.info.channels + index]) { "$name seek sample differs" }
                        }
                    }
                    val oldPosition = decoder.stats().positionFrames
                    expectIo { decoder.seekTo(10_000_000) }
                    expectIo { decoder.seekTo(Long.MAX_VALUE) }
                    check(decoder.stats().positionFrames == oldPosition)
                    val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                    check(decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == linear[oldPosition.toInt() * 2])
                }
            }
            open(file("hires-384k.tta")).use { decoder ->
                check(decoder.info.sampleRate == 384_000 && decoder.info.significantBits == 24 && decoder.info.lossless)
                val pcm = readAll(decoder, 384_000 * 2)
                check(decoder.stats().positionFrames == 384_000L && pcm.any { it != 0 })
                decoder.seekTo(500_000)
                val first = 192_000
                check(decoder.stats().positionFrames == first.toLong())
                val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                check(decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == pcm[first * 2])
            }
            // One original DSD64 bitstream as DSF and DSDIFF: always converted PCM at 1/8 of the DSD rate, no added gain.
            val dsd = listOf("tone.dsf", "tone.dff").map { name ->
                val target = File(directory, name).also { t -> instrumentation.context.assets.open("decoder-dsd/$name").use { input -> t.outputStream().use(input::copyTo) } }
                open(target).use { decoder ->
                    check(decoder.info.sampleRate == 352_800 && !decoder.info.lossless && decoder.info.quantized && decoder.info.significantBits == null) { "Unexpected DSD format ${decoder.info}" }
                    check(sourceCodec(decoder.info.codec, decoder.info.sampleRate) == "DSD64")
                    val pcm = readAll(decoder, 36_864 * 2)
                    check(pcm.size == 36_864 * 2 && decoder.stats().clippedSamples == 0L)
                    val peak = pcm.maxOf { abs(it.toDouble()) } / 2147483648.0
                    check(peak in 0.5..0.6) { "DSD level changed: $peak" }
                    decoder.seekTo(50_000)
                    val first = (50_000L * 352_800 + 999_999) / 1_000_000
                    val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                    check(decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == pcm[first.toInt() * 2]) { "$name seek sample differs" }
                    pcm
                }
            }
            check(dsd[0].contentEquals(dsd[1])) { "DSF and DSDIFF decoded the same bitstream differently" }
            val expected = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, -1073741824, -1, 0, 1, 1073741824, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
            for (bits in listOf(32, 64)) {
                open(file("finite-f$bits.wav")).use { decoder ->
                    check(decoder.info.lossless && decoder.info.quantized && decoder.info.significantBits == null)
                    check(readAll(decoder, expected.size).contentEquals(expected))
                    check(decoder.stats().clippedSamples == 4L && decoder.stats().quantizedSamples == expected.size.toLong())
                    decoder.seekTo(21) // ceil(21 us * 48000 Hz) = frame 2.
                    val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
                    check(decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == expected[2])
                }
                for (variant in listOf("nan", "infinity")) open(file("$variant-f$bits.wav")).use { decoder ->
                    expectIo { readAll(decoder, 3) }
                    expectIo { decoder.readPcm(ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN), 1) }
                }
            }
            // Exercise exact integer preservation and EOF reset through the new seek path too.
            val lossless = File(directory, "lossless.wav")
            instrumentation.context.assets.open("decoder/stereo-24.wav").use { input -> lossless.outputStream().use(input::copyTo) }
            open(lossless).use { decoder ->
                check(!decoder.info.quantized && decoder.info.lossless)
                val linear = readAll(decoder, 8)
                decoder.seekTo(23)
                check(decoder.stats().positionFrames == 2L)
                val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                check(decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == linear[4] && buffer.getInt(4) == linear[5])
                decoder.seekTo(90)
                check(decoder.readPcm(buffer, 1) == 0)
                decoder.seekTo(0)
                check(decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == Int.MIN_VALUE)
            }
            val large = File(directory, "large-sparse.wav")
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + 12_000_000); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(2); putInt(48000); putInt(48000 * 4)
                putShort(4); putShort(16); put("data".toByteArray()); putInt(12_000_000)
            }
            java.io.RandomAccessFile(large, "rw").use { it.write(header.array()); it.setLength(44 + 12_000_000L) }
            open(large).use { decoder ->
                val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                check(decoder.readPcm(buffer, 1) == 1)
                val before = File("/proc/self/fd").list()!!.size
                decoder.seekTo(60_000_000)
                check(decoder.stats().positionFrames == 2_880_000L && decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == 0)
                val position = decoder.stats().positionFrames
                check(runCatching { decoder.seekTo(90_000_000) }.isFailure)
                check(decoder.stats().positionFrames == position && decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == 0)
                check(File("/proc/self/fd").list()!!.size <= before + 2) { "Failed candidate seek leaked descriptors" }
            }
            repeat(20) { attempt ->
                val decoder = open(File(directory, "tone.mp3"))
                val started = CountDownLatch(1)
                val closing = AtomicBoolean(false)
                val error = AtomicReference<Throwable?>()
                val worker = Thread {
                    try {
                        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                        check(decoder.readPcm(buffer, 1) == 1)
                        started.countDown()
                        if (attempt % 2 == 0) decoder.seekTo(1_500_000)
                        while (decoder.readPcm(buffer, 1) != 0) { /* overlap read with cancellation/close */ }
                    } catch (failure: IOException) { if (!closing.get()) error.set(failure) }
                    catch (failure: IllegalStateException) { if (!closing.get()) error.set(failure) }
                    catch (failure: Throwable) { error.set(failure) }
                    finally { started.countDown() }
                }.apply { start() }
                try {
                    check(started.await(5, TimeUnit.SECONDS))
                    closing.set(true)
                    decoder.close()
                    worker.join(5000)
                    check(!worker.isAlive && error.get() == null) { "Concurrent read/close failed" }
                } finally { decoder.close() }
            }
            return "MP3/AAC/Vorbis/Opus matched desktop reference length and bounded error; 384 kHz TTA whole-frame decode and seek passed; DSF and DSDIFF DSD64 decoded identically to converted 352.8 kHz PCM without clipping; exact seek slices, long indexed WAV seek, failed-seek rollback, lossless seeking, float rounding/clipping/non-finite rejection and 20 concurrent read/seek/close runs passed. No audio output opened."
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    private fun open(file: File): NativeDecoder = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) }
    private fun expectIo(action: () -> Unit) { try { action() } catch (_: IOException) { return }; error("Expected decoder rejection") }
    private fun readAll(decoder: NativeDecoder, expectedSamples: Int): IntArray {
        val samples = IntArray(expectedSamples)
        val buffer = ByteBuffer.allocateDirect(997 * decoder.info.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 0
        while (true) {
            val frames = decoder.readPcm(buffer, 997)
            if (frames == 0) break
            repeat(frames * decoder.info.channels) { index ->
                check(cursor < samples.size) { "Too many decoded samples" }
                samples[cursor++] = buffer.getInt(index * 4)
            }
        }
        check(cursor == samples.size) { "Too few decoded samples: $cursor of ${samples.size}" }
        return samples
    }
}
