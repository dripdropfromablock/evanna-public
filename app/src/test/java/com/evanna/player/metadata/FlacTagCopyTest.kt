package com.evanna.player.metadata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FlacTagCopyTest {
    @Test fun taggingChangesCommentsAndPreservesThePictureAndEncodedAudio() {
        val fixture = sequenceOf(File("src/androidTest/assets/artwork/cover.flac"),
            File("app/src/androidTest/assets/artwork/cover.flac")).first(File::isFile).readBytes()
        val output = ByteArrayOutputStream()
        rewriteFlacTags(ByteArrayInputStream(fixture), output, mapOf(MusicTagField.TITLE to "新 title")) {}
        val before = parts(fixture)
        val after = parts(output.toByteArray())
        assertArrayEquals(before.second, after.second)
        assertTrue(before.first.any { it.first == 6 })
        val unchanged = before.first.filter { it.first != 4 }
        assertEquals(unchanged.map { it.first }, after.first.filter { it.first != 4 }.map { it.first })
        unchanged.zip(after.first.filter { it.first != 4 }).forEach { (old, new) -> assertArrayEquals(old.second, new.second) }
        assertTrue("TITLE=新 title" in comments(after.first.single { it.first == 4 }.second))
    }

    @Test fun editsAndClearsOnlyNamedCommentsLeavingTheirVendorAndOtherValues() {
        val data = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray()); write(byteArrayOf(0, 0, 0, 34)); write(ByteArray(34))
            val comments = ByteArrayOutputStream().apply {
                fun writeInt(value: Int) { write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
                fun writeText(value: String) { val bytes = value.toByteArray(); writeInt(bytes.size); write(bytes) }
                writeText("Fixture vendor"); writeInt(4)
                listOf("genre=Old", "ARTIST=One", "ARTIST=Two", "REPLAYGAIN_TRACK_GAIN=-4.00 dB").forEach(::writeText)
            }.toByteArray()
            write(byteArrayOf(0x84.toByte(), 0, 0, comments.size.toByte())); write(comments); write(byteArrayOf(9, 8, 7))
        }.toByteArray()
        val out = ByteArrayOutputStream()
        rewriteFlacTags(ByteArrayInputStream(data), out, mapOf(MusicTagField.GENRE to null, MusicTagField.ALBUM to "Album")) {}
        val written = parts(out.toByteArray()).first.single { it.first == 4 }.second
        assertEquals(listOf("Fixture vendor", "ARTIST=One", "ARTIST=Two", "REPLAYGAIN_TRACK_GAIN=-4.00 dB", "ALBUM=Album"), comments(written))
        assertThrows(IOException::class.java) {
            rewriteFlacTags(ByteArrayInputStream(data.copyOf(40)), ByteArrayOutputStream(), mapOf(MusicTagField.ALBUM to "Album")) {}
        }
    }

    @Test fun insertsACommentBlockWhenTheFileHasNoTags() {
        val data = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + ByteArray(34) + byteArrayOf(9, 8, 7)
        val out = ByteArrayOutputStream()
        rewriteFlacTags(ByteArrayInputStream(data), out, mapOf(MusicTagField.GENRE to "Rock")) {}
        val after = parts(out.toByteArray())
        assertEquals(listOf(0, 4), after.first.map { it.first })
        assertEquals(listOf("Evanna", "GENRE=Rock"), comments(after.first[1].second))
        assertArrayEquals(byteArrayOf(9, 8, 7), after.second)
    }

    private fun parts(bytes: ByteArray): Pair<List<Pair<Int, ByteArray>>, ByteArray> {
        assertEquals("fLaC", bytes.copyOf(4).decodeToString())
        var cursor = 4
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        do {
            val header = bytes[cursor].toInt() and 255
            val size = ((bytes[cursor + 1].toInt() and 255) shl 16) or ((bytes[cursor + 2].toInt() and 255) shl 8) or (bytes[cursor + 3].toInt() and 255)
            cursor += 4
            blocks += (header and 127) to bytes.copyOfRange(cursor, cursor + size)
            cursor += size
        } while (header and 128 == 0)
        return blocks to bytes.copyOfRange(cursor, bytes.size)
    }

    private fun comments(bytes: ByteArray): List<String> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun text() = ByteArray(input.int).also { input.get(it) }.decodeToString()
        val vendor = text()
        return listOf(vendor) + List(input.int) { text() }
    }
}
