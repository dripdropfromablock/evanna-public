package com.evanna.player.decoder

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Repeated raw frames create >14 MiB streams in cache; only small seeds ship in the test APK. */
internal object LongCompressedSeekProbe {
    fun run(instrumentation: Instrumentation): String {
        val failures = mutableListOf<String>()
        for (name in listOf("scan-seed.mp3", "scan-seed.aac")) {
            val file = File(instrumentation.targetContext.cacheDir, "long-$name")
            try {
                val seed = instrumentation.context.assets.open("decoder-seek/$name").use { it.readBytes() }
                check(seed.size >= 70000)
                file.outputStream().use { out -> repeat((14 * 1024 * 1024 + seed.size - 1) / seed.size) { out.write(seed) } }
                fun open() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) }
                val targets = listOf(0L, 300_000_001L, 307_000_017L)
                val frames = 2048
                val expected = targets.associateWith { IntArray(frames * 2) }
                open().use { decoder ->
                    check(decoder.info.sampleRate == 44100 && decoder.info.channels == 2)
                    val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
                    var position = 0L
                    val last = (targets.last() * 44100 + 999999) / 1000000 + frames
                    while (position < last) {
                        val count = decoder.readPcm(buffer, 4096)
                        check(count > 0) { "$name short stream" }
                        for (us in targets) {
                            val first = (us * 44100 + 999999) / 1000000
                            val from = maxOf(position, first)
                            val end = minOf(position + count, first + frames)
                            for (frame in from until end) repeat(2) { channel ->
                                expected.getValue(us)[((frame - first) * 2 + channel).toInt()] = buffer.getInt(((frame - position) * 2 + channel).toInt() * 4)
                            }
                        }
                        position += count
                    }
                }
                open().use { decoder ->
                    val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
                    for (us in listOf(targets[1], targets[2], targets[1], 0L)) {
                        instrumentation.sendStatus(1, android.os.Bundle().apply { putString("progress", "$name seek to ${us / 1000000} seconds") })
                        decoder.seekTo(us)
                        check(decoder.stats().positionFrames == (us * 44100 + 999999) / 1000000)
                        var read = 0
                        while (read < frames) {
                            val count = decoder.readPcm(buffer, frames - read)
                            check(count > 0)
                            repeat(count * 2) { sample -> check(buffer.getInt(sample * 4) == expected.getValue(us)[read * 2 + sample]) { "$name late seek sample mismatch at $us" } }
                            read += count
                        }
                    }
                    decoder.seekTo(0)
                    check(runCatching { decoder.seekTo(1_000_000_000) }.isFailure)
                    check(decoder.stats().positionFrames == 0L)
                    check(decoder.readPcm(buffer, 1) == 1 && buffer.getInt(0) == expected.getValue(0L)[0]) { "$name failed seek lost original decoder" }
                }
            } catch (failure: Exception) { failures += "$name: ${failure.message}" }
            finally { file.delete() }
        }
        for (name in listOf("seek.wv", "tone.dsf", "tone.dff")) {
            val file = File(instrumentation.targetContext.cacheDir, "long-$name")
            try {
                val folder = if (name.endsWith(".wv")) "decoder-seek" else "decoder-dsd"
                val seed = instrumentation.context.assets.open("$folder/$name").use { it.readBytes() }
                val expanded = when (name) {
                    "seek.wv" -> expandWavPack(seed, 14 * 1024 * 1024)
                    "tone.dsf" -> expandDsf(seed, 14 * 1024 * 1024)
                    else -> expandDff(seed, 14 * 1024 * 1024)
                }
                check(expanded.size >= 14 * 1024 * 1024)
                file.writeBytes(expanded)
                checkGenericIndex(instrumentation, file)
            } catch (failure: Exception) { failures += "$name: ${failure.message}" }
            finally { file.delete() }
        }
        check(failures.isEmpty()) { failures.joinToString("; ") }
        return "Long >14 MiB MP3/ADTS AAC, WavPack, DSF and DFF fresh-reader, forward/backward/repeated seeks matched linear PCM; failed-seek rollback passed; no output opened"
    }

    private fun checkGenericIndex(instrumentation: Instrumentation, file: File) {
        fun open() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) }
        val info = open().use { it.info }
        val duration = checkNotNull(info.durationUs) { "${file.name} duration unavailable" }
        val targets = listOf(0L, duration * 3 / 4, duration * 9 / 10)
        check(targets[1] > 3_000_000L && targets[1] != targets[2]) { "${file.name} is too short" }
        val frames = 2048
        val channels = info.channels
        val rate = info.sampleRate
        val expected = targets.associateWith { IntArray(frames * channels) }
        open().use { decoder ->
            val buffer = ByteBuffer.allocateDirect(4096 * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
            var position = 0L
            val last = (targets.last() * rate + 999999) / 1000000 + frames
            while (position < last) {
                val count = decoder.readPcm(buffer, 4096)
                check(count > 0) { "${file.name} short stream" }
                for (us in targets) {
                    val first = (us * rate + 999999) / 1000000
                    val from = maxOf(position, first)
                    val end = minOf(position + count, first + frames)
                    for (frame in from until end) repeat(channels) { channel ->
                        expected.getValue(us)[((frame - first) * channels + channel).toInt()] =
                            buffer.getInt(((frame - position) * channels + channel).toInt() * 4)
                    }
                }
                position += count
            }
        }
        open().use { decoder ->
            val buffer = ByteBuffer.allocateDirect(4096 * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (us in listOf(targets[1], targets[2], targets[1], 0L)) {
                instrumentation.sendStatus(1, android.os.Bundle().apply {
                    putString("progress", "${file.name} seek to ${us / 1000000} seconds")
                })
                decoder.seekTo(us)
                val first = (us * rate + 999999) / 1000000
                check(decoder.stats().positionFrames == first) { "${file.name} seek position at $us" }
                var read = 0
                while (read < frames) {
                    val count = decoder.readPcm(buffer, frames - read)
                    check(count > 0)
                    repeat(count * channels) { sample ->
                        check(buffer.getInt(sample * 4) == expected.getValue(us)[read * channels + sample]) {
                            "${file.name} late seek sample mismatch at $us"
                        }
                    }
                    read += count
                }
            }
        }
    }

    private fun expandWavPack(seed: ByteArray, minimum: Int): ByteArray {
        val blocks = mutableListOf<Pair<Int, Int>>()
        var offset = 0
        var samplesPerRepeat = 0L
        while (offset + 32 <= seed.size && magic(seed, offset, "wvpk")) {
            val size = intAt(seed, offset + 4) + 8
            check(size >= 32 && offset + size <= seed.size)
            blocks += offset to size
            samplesPerRepeat += intAt(seed, offset + 20)
            offset += size
        }
        check(blocks.isNotEmpty() && samplesPerRepeat > 0)
        val repeats = (minimum + offset - 1) / offset
        val totalSamples = samplesPerRepeat * repeats
        check(totalSamples <= Int.MAX_VALUE)
        val output = java.io.ByteArrayOutputStream(repeats * offset)
        var blockIndex = 0L
        repeat(repeats) {
            for ((start, size) in blocks) {
                val block = seed.copyOfRange(start, start + size)
                putInt(block, 12, totalSamples.toInt())
                putInt(block, 16, blockIndex.toInt())
                output.write(block)
                blockIndex += intAt(block, 20)
            }
        }
        check(blockIndex == totalSamples)
        return output.toByteArray()
    }

    private fun expandDsf(seed: ByteArray, minimum: Int): ByteArray {
        var cursor = 28
        var dataHeader = -1
        var audioStart = -1
        var audioLength = 0
        var sampleCount = -1
        while (cursor + 12 <= seed.size) {
            val length = longAt(seed, cursor + 4)
            check(length >= 12 && cursor + length <= seed.size)
            if (magic(seed, cursor, "fmt ")) sampleCount = cursor + 12 + 24
            if (magic(seed, cursor, "data")) {
                dataHeader = cursor
                audioStart = cursor + 12
                audioLength = (length - 12).toInt()
                break
            }
            cursor += length.toInt()
        }
        check(dataHeader >= 0 && audioLength > 0 && sampleCount >= 0)
        val repeats = (minimum + audioLength - 1) / audioLength
        val totalBytes = audioLength.toLong() * repeats
        val prefix = seed.copyOf(audioStart)
        putLong(prefix, 12, audioStart + totalBytes)
        putLong(prefix, dataHeader + 4, 12 + totalBytes)
        putLong(prefix, sampleCount, longAt(seed, sampleCount) * repeats)
        val output = java.io.ByteArrayOutputStream((audioStart + totalBytes).toInt())
        output.write(prefix)
        repeat(repeats) { output.write(seed, audioStart, audioLength) }
        return output.toByteArray()
    }

    private fun expandDff(seed: ByteArray, minimum: Int): ByteArray {
        check(magic(seed, 0, "FRM8"))
        var cursor = 16
        var dataHeader = -1
        var audioStart = -1
        var audioLength = 0
        while (cursor + 12 <= seed.size) {
            val length = longAt(seed, cursor + 4, little = false)
            check(length >= 0 && cursor + 12 + length <= seed.size)
            if (magic(seed, cursor, "DSD ")) {
                dataHeader = cursor
                audioStart = cursor + 12
                audioLength = length.toInt()
                break
            }
            cursor += 12 + (length + (length and 1)).toInt()
        }
        check(dataHeader >= 0 && audioLength > 0)
        val repeats = (minimum + audioLength - 1) / audioLength
        val totalBytes = audioLength.toLong() * repeats
        val prefix = seed.copyOf(audioStart)
        val outputSize = audioStart + totalBytes + (totalBytes and 1)
        putLong(prefix, 4, outputSize - 12, little = false)
        putLong(prefix, dataHeader + 4, totalBytes, little = false)
        val output = java.io.ByteArrayOutputStream(outputSize.toInt())
        output.write(prefix)
        repeat(repeats) { output.write(seed, audioStart, audioLength) }
        if ((totalBytes and 1) != 0L) output.write(0)
        return output.toByteArray()
    }

    private fun magic(data: ByteArray, offset: Int, expected: String) =
        String(data, offset, 4, Charsets.US_ASCII) == expected

    private fun intAt(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun longAt(data: ByteArray, offset: Int, little: Boolean = true): Long =
        ByteBuffer.wrap(data, offset, 8).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).long

    private fun putLong(data: ByteArray, offset: Int, value: Long, little: Boolean = true) {
        ByteBuffer.wrap(data, offset, 8).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).putLong(value)
    }
}
