package com.evanna.player.decoder

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DecoderProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        var cases = 0
        val directory = File(context.cacheDir, "decoder-probe").apply { mkdirs() }
        try {
            val names = instrumentation.context.assets.list("decoder")!!.toList()
            check(names.size == 17)
            for (name in names) {
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("progress", "Checking $name") })
                val file = File(directory, name)
                instrumentation.context.assets.open("decoder/$name").use { input -> file.outputStream().use(input::copyTo) }
                val bits = name.substringAfter('-').substringBefore('.').toInt()
                val channels = if (name.startsWith("mono")) 1 else 2
                val low = 1L shl (32 - bits)
                val expected = longArrayOf(-2147483648, 2147483648 - low, -low, low, 0, 0, 3 * low, -5 * low)
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val decoder = NativeDecoder.open(descriptor.fd)
                descriptor.close() // NativeDecoder must own a duplicate before open returns.
                decoder.use {
                    check(it.info.sampleRate == 44100 && it.info.significantBits == bits && it.info.channels == channels) { "Unexpected format $name: ${it.info}" }
                    check(it.info.seekable && it.info.codec.isNotBlank())
                    val buffer = ByteBuffer.allocateDirect(3 * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    var sample = 0
                    while (true) {
                        val frames = it.readPcm(buffer, if (sample == 0) 1 else 3)
                        if (frames == 0) break
                        check(frames in 1..3)
                        repeat(frames * channels) { index ->
                            check(sample < expected.size && buffer.getInt(index * 4).toLong() == expected[sample]) {
                                "Sample mismatch in $name at $sample"
                            }
                            sample++
                        }
                    }
                    check(sample == expected.size && it.readPcm(buffer, 1) == 0)
                }
                expectFailure { decoder.readPcm(ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN), 1) }
                decoder.close()
                cases++
            }
            // A hybrid WavPack block decodes lossy (FFmpeg never reads .wvc correction files), so it is never original audio.
            val wavpack = File(directory, "stereo-16.wv").readBytes()
            check(String(wavpack, 0, 4, Charsets.US_ASCII) == "wvpk")
            wavpack[24] = (wavpack[24].toInt() or 8).toByte()
            val hybrid = File(directory, "hybrid.wv").apply { writeBytes(wavpack) }
            ParcelFileDescriptor.open(hybrid, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val certified = try { NativeDecoder.open(fd.fd).use { it.info.lossless || it.info.significantBits != null } } catch (_: IOException) { false }
                check(!certified) { "Hybrid WavPack was certified as original integer audio" }
            }
            val file = File(directory, "stereo-24.flac")
            instrumentation.sendStatus(1, android.os.Bundle().apply { putString("progress", "Checking ownership, cancellation, invalid buffers and corrupt/truncated input") })
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                NativeDecoder.open(fd.fd).use { decoder ->
                    expectFailure { decoder.readPcm(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN), 1) }
                    expectFailure { decoder.readPcm(ByteBuffer.allocateDirect(8), 1) }
                    expectFailure { decoder.readPcm(ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN), 4097) }
                    expectFailure { decoder.readPcm(ByteBuffer.allocateDirect(9).apply { position(1) }.slice().order(ByteOrder.LITTLE_ENDIAN), 1) }
                    decoder.requestCancel()
                    expectFailure { decoder.readPcm(ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN), 1) }
                }
                // Failed operations must not consume/close the caller's descriptor.
                check(fd.statSize == file.length())
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.system.Os.lseek(fd.fileDescriptor, 3, android.system.OsConstants.SEEK_SET)
                NativeDecoder.open(fd.fd).use { it.readPcm(ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN), 1) }
                check(android.system.Os.lseek(fd.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR) == 3L)
            }
            val pipe = ParcelFileDescriptor.createPipe()
            try { expectFailure { NativeDecoder.open(pipe[0].fd) } } finally { pipe.forEach { it.close() } }
            val corrupt = File(directory, "corrupt").apply { writeBytes(ByteArray(128) { 0x7f }) }
            val descriptorsBefore = File("/proc/self/fd").list()!!.size
            repeat(20) {
                ParcelFileDescriptor.open(corrupt, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> expectFailure { NativeDecoder.open(fd.fd) } }
            }
            check(File("/proc/self/fd").list()!!.size <= descriptorsBefore + 2) { "Failed open leaked descriptors" }
            val flac = file.readBytes()
            var offset = 4
            while (true) {
                val last = flac[offset].toInt() and 128 != 0
                val length = ((flac[offset + 1].toInt() and 255) shl 16) or
                    ((flac[offset + 2].toInt() and 255) shl 8) or (flac[offset + 3].toInt() and 255)
                offset += 4 + length
                if (last) break
            }
            val truncated = File(directory, "truncated.flac").apply { writeBytes(flac.copyOf(offset)) }
            ParcelFileDescriptor.open(truncated, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                expectFailure { NativeDecoder.open(fd.fd).use { it.readPcm(ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN), 4) } }
            }
            val unknownLength = flac.copyOf(offset).apply {
                this[21] = (this[21].toInt() and 0xf0).toByte()
                for (index in 22..25) this[index] = 0
            }
            val unknown = File(directory, "empty-unknown-length.flac").apply { writeBytes(unknownLength) }
            // Two complete PCM packets remain, but the RIFF/data sizes promise twice as much audio.
            val wav = ByteBuffer.allocate(44 + 32768).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + 65536); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(2); putInt(44100); putInt(44100 * 4)
                putShort(4); putShort(16); put("data".toByteArray()); putInt(65536)
            }
            val shortenedWav = File(directory, "shortened.wav").apply { writeBytes(wav.array()) }
            rejectFile(shortenedWav, "Packet-aligned truncated WAV was accepted")
            rejectFile(unknown, "Empty unknown-length FLAC was accepted")
            check(!isLocalDocument(context, android.net.Uri.parse("content://cloud.example/document/music")))
            check(!isLocalDocument(context, android.net.Uri.parse("file:///sdcard/Music/private.flac")))
            check(!isLocalDocument(context, android.net.Uri.parse("https://example.com/audio.flac")))
            expectFailure { NativeDecoder.open(-1) }
            return "$cases lossless files (WAV, AIFF, FLAC, ALAC, WavPack, APE, TTA) matched all literal S32 samples; hybrid WavPack was not certified; EOF, partial reads, descriptor ownership, closed/cancelled decoder, bad buffers, pipe and corrupt-input rejection passed. No audio output opened."
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    private fun rejectFile(file: File, message: String) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                NativeDecoder.open(fd.fd).use { decoder ->
                    val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
                    var count = 0
                    while (decoder.readPcm(buffer, 4096) != 0) check(++count < 20)
                }
            }
        } catch (_: IOException) { return }
        error(message)
    }

    private fun expectFailure(action: () -> Unit) {
        try { action() } catch (_: IOException) { return } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        error("Expected a checked decoder rejection")
    }
}
