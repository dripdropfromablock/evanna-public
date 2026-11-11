package com.evanna.player.diagnostics

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class TraceResult(val status: String, val stack: String)

/** Original bounded reader for approved Android tombstone fields; never returns raw fallback data.
 * Schema: AOSP system/core, android-12.0.0_r1, debuggerd/proto/tombstone.proto. */
internal object TraceEvidence {
    fun readBounded(input: InputStream, limit: Int = ReportStore.MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            if (count == 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun native(bytes: ByteArray): TraceResult {
        if (bytes.size > ReportStore.MAX_BYTES) return TraceResult("TRUNCATED", "")
        return try {
            val tombstone = fields(bytes, mapOf(6 to 0, 16 to 2))
            val tid = tombstone.firstOrNull { it.key == 6 }?.number ?: return TraceResult("FILTERED_NO_FRAMES", "")
            val frames = mutableListOf<String>()
            var truncated = false
            for (threadEntry in tombstone.filter { it.key == 16 }) {
                val entry = fields(threadEntry.bytes, mapOf(1 to 0, 2 to 2))
                if (entry.firstOrNull { it.key == 1 }?.number != tid) continue
                val thread = entry.firstOrNull { it.key == 2 } ?: continue
                val backtrace = fields(thread.bytes, mapOf(4 to 2))
                if (backtrace.size > 64) truncated = true
                for (frame in backtrace.take(64)) {
                    val data = fields(frame.bytes, mapOf(1 to 0, 6 to 2, 8 to 2))
                    val pc = data.firstOrNull { it.key == 1 }?.number ?: 0
                    val path = data.firstOrNull { it.key == 6 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
                    val module = path.substringAfterLast('/').takeIf {
                        it.matches(Regex("[A-Za-z0-9_.+-]{1,100}\\.so")) || it in listOf("app_process64", "app_process32", "linker", "linker64")
                    } ?: "UNKNOWN"
                    val build = data.firstOrNull { it.key == 8 }?.bytes?.toString(Charsets.US_ASCII)
                        ?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{1,128}")) } ?: "UNKNOWN"
                    frames += "native $module pc=0x${java.lang.Long.toUnsignedString(pc, 16)} build=$build"
                }
            }
            TraceResult(if (truncated || frames.size > 64) "TRUNCATED" else if (frames.isEmpty()) "FILTERED_NO_FRAMES" else "FILTERED",
                frames.take(64).joinToString("\n"))
        } catch (_: IllegalArgumentException) { TraceResult("MALFORMED", "") }
    }

    fun anr(bytes: ByteArray): TraceResult {
        if (bytes.size > ReportStore.MAX_BYTES) return TraceResult("TRUNCATED", "")
        val frame = Regex("\\s*at ([A-Za-z0-9_.$<>-]+)\\(([A-Za-z0-9_$-]+\\.(?:kt|java)):(\\d{1,9})\\)\\s*")
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            frame.matchEntire(line.take(1024))?.let { "at ${it.groupValues[1]}(${it.groupValues[2]}:${it.groupValues[3]})" }
        }.take(65).toList()
        return TraceResult(if (lines.size > 64) "TRUNCATED" else if (lines.isEmpty()) "FILTERED_NO_FRAMES" else "FILTERED",
            lines.take(64).joinToString("\n"))
    }

    fun elfBuildId(bytes: ByteArray): String? = try {
        require(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 69.toByte() && bytes[2] == 76.toByte() &&
            bytes[3] == 70.toByte() && bytes[4] == 2.toByte() && bytes[5] == 1.toByte())
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = data.getLong(32); val size = data.getShort(54).toInt() and 65535; val count = data.getShort(56).toInt() and 65535
        require(offset in 0..bytes.size.toLong() && size >= 56 && count <= 128 && size.toLong() * count <= bytes.size - offset)
        var result: String? = null
        repeat(count) { index ->
            val header = (offset + index * size).toInt()
            if (data.getInt(header) == 4) {
                val start = data.getLong(header + 8); val length = data.getLong(header + 32)
                require(start >= 0 && length >= 0 && start <= bytes.size && length <= bytes.size - start)
                var at = start.toInt(); val end = at + length.toInt(); var notes = 0
                while (at + 12 <= end && notes++ < 128) {
                    val names = data.getInt(at); val desc = data.getInt(at + 4); val type = data.getInt(at + 8)
                    require(names in 0..1024 && desc in 0..1024)
                    val nameAt = at + 12; val descAt = nameAt + ((names + 3) and -4)
                    val next = descAt + ((desc + 3) and -4)
                    require(next <= end)
                    if (type == 3 && names == 4 && bytes.copyOfRange(nameAt, nameAt + 4).contentEquals(byteArrayOf(71, 78, 85, 0)) && desc in 4..64)
                        result = bytes.copyOfRange(descAt, descAt + desc).joinToString("") { "%02x".format(it.toInt() and 255) }
                    at = next
                }
            }
        }
        result
    } catch (_: IllegalArgumentException) { null } catch (_: IndexOutOfBoundsException) { null }

    private data class Field(val key: Int, val number: Long = 0, val bytes: ByteArray = byteArrayOf())
    private fun fields(bytes: ByteArray, keep: Map<Int, Int>): List<Field> {
        var at = 0
        fun variable(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(at < bytes.size)
                val part = bytes[at++].toInt() and 255
                require(shift != 63 || part <= 1)
                value = value or ((part and 127).toLong() shl shift)
                if (part and 128 == 0) return value
            }
            throw IllegalArgumentException("Invalid varint")
        }
        val result = mutableListOf<Field>()
        var count = 0
        while (at < bytes.size) {
            require(count++ < 4096)
            val tag = variable(); val fieldNumber = tag ushr 3; val wire = (tag and 7).toInt()
            require(fieldNumber in 1..536870911L)
            val key = fieldNumber.toInt()
            if (key in keep) require(wire == keep[key])
            when (wire) {
                0 -> { val value = variable(); if (key in keep) result += Field(key, value) }
                1, 5 -> { val length = if (wire == 1) 8 else 4; require(length <= bytes.size - at); at += length }
                2 -> {
                    val length = variable(); require(length >= 0 && length <= bytes.size - at)
                    if (key in keep) result += Field(key, bytes = bytes.copyOfRange(at, at + length.toInt()))
                    at += length.toInt()
                }
                else -> throw IllegalArgumentException("Unsupported protobuf wire")
            }
        }
        return result
    }
}
