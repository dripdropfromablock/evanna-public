package com.evanna.player.diagnostics

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class TraceEvidenceTest {
    private fun variable(value: Long): ByteArray {
        var rest = value
        return ByteArrayOutputStream().apply { do { val part = (rest and 127).toInt(); rest = rest ushr 7; write(part or if (rest != 0L) 128 else 0) } while (rest != 0L) }.toByteArray()
    }
    private fun number(key: Int, value: Long) = variable((key * 8).toLong()) + variable(value)
    private fun blob(key: Int, value: ByteArray) = variable((key * 8 + 2).toLong()) + variable(value.size.toLong()) + value
    private fun text(key: Int, value: String) = blob(key, value.toByteArray())

    @Test fun nativeTraceKeepsOnlyCrashingThreadRelativeFramesAndBuildIds() {
        val frame = number(1, 0x1234) + text(6, "/private/SECRET/libusbaudio.so") + text(8, "abcdef123456")
        val thread = text(2, "private track.flac") + blob(4, frame) + text(5, "Bearer TOKEN memory dump")
        val entry = number(1, 42) + blob(2, thread)
        val proto = number(6, 42) + text(14, "PRIVATE abort message") + blob(16, entry) + text(18, "secret logcat")
        val evidence = TraceEvidence.native(proto)
        assertEquals("FILTERED", evidence.status)
        assertTrue(evidence.stack.contains("libusbaudio.so pc=0x1234 build=abcdef123456"))
        for (secret in listOf("SECRET", "PRIVATE", "TOKEN", "track.flac", "/private", "logcat")) assertFalse(evidence.stack.contains(secret))
    }

    @Test fun malformedAndTruncatedProtobufNeverProducesRawFallbackText() {
        for (input in listOf(byteArrayOf(0x82.toByte()), byteArrayOf(0x82.toByte(), 1, 127), ByteArray(15) { 0xff.toByte() },
            variable((536870912L shl 3)) + variable(42), text(6, "wrong wire type"))) {
            val evidence = TraceEvidence.native(input)
            assertEquals("MALFORMED", evidence.status)
            assertEquals("", evidence.stack)
        }
    }

    @Test fun boundedReadersRejectOversizedInputAndTolerateRandomGarbage() {
        val input = java.io.ByteArrayInputStream(ByteArray(100))
        assertEquals(17, TraceEvidence.readBounded(input, 16).size)
        assertEquals("TRUNCATED", TraceEvidence.native(ByteArray(ReportStore.MAX_BYTES + 1)).status)
        val random = java.util.Random(42)
        repeat(200) {
            val bytes = ByteArray(64).also(random::nextBytes)
            assertTrue(TraceEvidence.native(bytes).stack.isEmpty())
            assertNull(TraceEvidence.elfBuildId(bytes))
        }
    }

    @Test fun frameLimitsAreReportedAsTruncatedInsteadOfCompleteFilteredEvidence() {
        val anr = (1..70).joinToString("\n") { " at com.evanna.Test.run(Test.kt:$it)" }
        val anrResult = TraceEvidence.anr(anr.toByteArray())
        assertEquals("TRUNCATED", anrResult.status)
        assertEquals(64, anrResult.stack.lines().size)
        val frame = number(1, 12) + text(6, "libusbaudio.so")
        val thread = (1..70).fold(byteArrayOf()) { bytes, _ -> bytes + blob(4, frame) }
        val proto = number(6, 42) + blob(16, number(1, 42) + blob(2, thread))
        val native = TraceEvidence.native(proto)
        assertEquals("TRUNCATED", native.status)
        assertEquals(64, native.stack.lines().size)
    }

    @Test fun anrExtractionDropsNamesMessagesPathsAndMemory() {
        val input = "\"main\" prio=5 SECRET\n at com.evanna.player.MainActivity.onCreate(MainActivity.kt:42)\n" +
            "Bearer TOKEN /sdcard/Private.flac\n0xdeadbeef PRIVATE memory\n"
        val evidence = TraceEvidence.anr(input.toByteArray())
        assertTrue(evidence.stack.contains("MainActivity.onCreate"))
        assertFalse(evidence.stack.contains("SECRET")); assertFalse(evidence.stack.contains("Private.flac"))
        assertFalse(evidence.stack.contains("deadbeef"))
    }

    @Test fun elfBuildIdReadsOnlyTheGnuNoteAndRejectsInvalidBounds() {
        val elf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        elf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1))
        elf.putLong(32, 64); elf.putShort(54, 56); elf.putShort(56, 1)
        elf.putInt(64, 4); elf.putLong(72, 128); elf.putLong(96, 20)
        elf.position(128); elf.putInt(4); elf.putInt(4); elf.putInt(3); elf.put(byteArrayOf(71, 78, 85, 0)); elf.put(byteArrayOf(1, 2, 3, 4))
        assertEquals("01020304", TraceEvidence.elfBuildId(elf.array()))
        elf.putLong(72, Long.MAX_VALUE)
        assertNull(TraceEvidence.elfBuildId(elf.array()))
        assertNull(TraceEvidence.elfBuildId(byteArrayOf(1, 2, 3)))
    }
}
