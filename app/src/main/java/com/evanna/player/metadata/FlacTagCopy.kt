package com.evanna.player.metadata

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private const val FLAC_COMMENT_BLOCK = 4
private const val FLAC_COMMENT_LIMIT = 1024 * 1024
private val flacCommentKeys = mapOf(
    MusicTagField.TITLE to listOf("TITLE"), MusicTagField.ARTIST to listOf("ARTIST"),
    MusicTagField.ALBUM to listOf("ALBUM"), MusicTagField.ALBUM_ARTIST to listOf("ALBUMARTIST", "ALBUM_ARTIST"),
    MusicTagField.GENRE to listOf("GENRE"), MusicTagField.COMPOSER to listOf("COMPOSER"),
    MusicTagField.YEAR to listOf("DATE", "YEAR", "TYER"),
)

internal fun supportsFlacTagCopy(field: MusicTagField): Boolean = field in flacCommentKeys

/** Rewrite the Vorbis comment block and stream all other metadata and encoded frames through untouched. */
internal fun rewriteFlacTags(source: InputStream, destination: OutputStream, edits: Map<MusicTagField, String?>,
    checkActive: () -> Unit) {
    require(edits.isNotEmpty() && edits.all { (field, value) -> supportsFlacTagCopy(field) && (value == null || field.accepts(value)) })
    val input = DataInputStream(source)
    val output = DataOutputStream(destination)
    val marker = ByteArray(4).also(input::readFully)
    if (!marker.contentEquals("fLaC".toByteArray())) throw IOException("FLAC_HEADER_REQUIRED")
    output.write(marker)
    val buffer = ByteArray(64 * 1024)
    var foundComments = false
    var first = true
    do {
        checkActive()
        val header = input.readUnsignedByte()
        val kind = header and 0x7f
        val last = header and 0x80 != 0
        val length = (input.readUnsignedByte() shl 16) or input.readUnsignedShort()
        if ((first && (kind != 0 || length != 34)) || (!first && kind == 0) || kind == 127) {
            throw IOException("FLAC_INVALID_METADATA")
        }
        first = false
        if (kind == FLAC_COMMENT_BLOCK) {
            if (foundComments || length > FLAC_COMMENT_LIMIT) throw IOException("FLAC_INVALID_COMMENTS")
            foundComments = true
            val comments = ByteArray(length).also(input::readFully)
            val changed = rewriteFlacComments(comments, edits)
            output.writeFlacBlock(kind, last, changed.size)
            output.write(changed)
        } else {
            output.writeFlacBlock(kind, last && foundComments, length)
            var remaining = length
            while (remaining > 0) {
                checkActive()
                val count = minOf(remaining, buffer.size)
                input.readFully(buffer, 0, count)
                output.write(buffer, 0, count)
                remaining -= count
            }
            if (last && !foundComments) {
                val comments = rewriteFlacComments(null, edits)
                output.writeFlacBlock(FLAC_COMMENT_BLOCK, true, comments.size)
                output.write(comments)
            }
        }
    } while (!last)

    var hasAudio = false
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        hasAudio = true
    }
    if (!hasAudio) throw EOFException("FLAC_AUDIO_REQUIRED")
}

private fun rewriteFlacComments(block: ByteArray?, edits: Map<MusicTagField, String?>): ByteArray {
    val comments = mutableListOf<ByteArray>()
    val vendor = if (block == null) "Evanna".toByteArray() else {
        val input = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        val existingVendor = input.readCommentBytes()
        if (input.remaining() < 4) throw EOFException("FLAC_COMMENT_COUNT_REQUIRED")
        val count = input.int
        if (count !in 0..(input.remaining() / 4)) throw IOException("FLAC_INVALID_COMMENT_COUNT")
        val replacedKeys = edits.keys.flatMap { flacCommentKeys.getValue(it) }.toSet()
        repeat(count) {
            val raw = input.readCommentBytes()
            val equals = raw.indexOf('='.code.toByte())
            val name = if (equals < 0) "" else String(raw, 0, equals, Charsets.UTF_8).uppercase(Locale.ROOT)
            if (name !in replacedKeys) comments.add(raw)
        }
        if (input.hasRemaining()) throw IOException("FLAC_TRAILING_COMMENT_DATA")
        existingVendor
    }
    for ((field, value) in edits) {
        if (value != null) comments.add("${flacCommentKeys.getValue(field).first()}=$value".toByteArray())
    }
    val size = 8 + vendor.size + comments.sumOf { 4 + it.size }
    if (size > FLAC_COMMENT_LIMIT) throw IOException("FLAC_COMMENTS_TOO_LARGE")
    val output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    output.putInt(vendor.size).put(vendor).putInt(comments.size)
    for (comment in comments) output.putInt(comment.size).put(comment)
    return output.array()
}

private fun ByteBuffer.readCommentBytes(): ByteArray {
    if (remaining() < 4) throw EOFException("FLAC_COMMENT_LENGTH_REQUIRED")
    val size = int
    if (size !in 0..remaining()) throw IOException("FLAC_INVALID_COMMENT_LENGTH")
    return ByteArray(size).also { get(it) }
}

private fun DataOutputStream.writeFlacBlock(kind: Int, last: Boolean, size: Int) {
    writeByte(kind or if (last) 0x80 else 0)
    writeByte(size ushr 16)
    writeShort(size and 0xffff)
}
