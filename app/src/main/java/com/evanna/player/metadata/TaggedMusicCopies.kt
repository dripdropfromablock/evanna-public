package com.evanna.player.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.decoder.metadataText
import com.evanna.player.library.isLocalTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal fun TagFilePreview.canSaveTaggedCopy(): Boolean =
    source.filename.endsWith(".flac", ignoreCase = true) && changes.all { supportsFlacTagCopy(it.field) }

/** The source URI is only opened for reading. A new document is published after the staged copy verifies. */
internal suspend fun saveTaggedMusicCopy(context: Context, folder: Uri, file: TagFilePreview): String = withContext(Dispatchers.IO) {
    require(file.canSaveTaggedCopy())
    if (!isLocalTree(context, folder)) throw IOException("LOCAL_FOLDER_REQUIRED")
    val resolver = context.contentResolver
    val source = Uri.parse(file.source.uri)
    val active = { coroutineContext.ensureActive() }
    val stage = File.createTempFile("tagged-", ".flac", context.cacheDir)
    try {
        val patch = file.changes.associate { it.field to it.after }
        val signal = CancellationSignal()
        val descriptor = resolver.openFileDescriptor(source, "r", signal) ?: throw IOException("SOURCE_UNAVAILABLE")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).buffered().use { input ->
            val info = NativeDecoder.inspect(descriptor.fd, signal)
            val current = info.tagValues()
            if (info.codec != "flac" || file.changes.any { current[it.field] != it.before }) throw IOException("SOURCE_CHANGED")
            stage.outputStream().buffered().use { rewriteFlacTags(input, it, patch, active) }
        }
        ParcelFileDescriptor.open(stage, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val info = NativeDecoder.inspect(fd.fd, signal)
            val actual = info.tagValues()
            if (info.codec != "flac" || patch.any { (field, value) -> actual[field] != value?.let { metadataText(it.toByteArray()) } }) {
                throw IOException("TAGS_DID_NOT_VERIFY")
            }
        }
        active()
        publishTaggedCopy(resolver, folder, source, file.source.filename, stage, active)
    } finally {
        stage.delete()
    }
}

private fun publishTaggedCopy(resolver: ContentResolver, folder: Uri, source: Uri, sourceName: String, stage: File,
    checkActive: () -> Unit): String {
    val directory = DocumentsContract.getTreeDocumentId(folder)
    val parent = DocumentsContract.buildDocumentUriUsingTree(folder, directory)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, directory)
    val knownNames = resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
        buildSet<String> {
            while (cursor.moveToNext()) {
                checkActive()
                cursor.getString(0)?.let { add(it.lowercase(Locale.ROOT)) }
            }
        }
    } ?: throw IOException("DESTINATION_UNAVAILABLE")
    val basename = sourceName.substringBeforeLast('.').replace('/', '_').replace('\\', '_').take(150).ifBlank { "Audio" }
    val name = (1..1000).asSequence().map { number ->
        if (number == 1) "$basename (tagged).flac" else "$basename (tagged $number).flac"
    }.firstOrNull { it.lowercase(Locale.ROOT) !in knownNames } ?: throw IOException("DESTINATION_NAME_UNAVAILABLE")
    checkActive()
    var created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", ".${UUID.randomUUID()}.partial")
        ?: throw IOException("DESTINATION_UNWRITABLE")
    if (sameMusicDocument(source, created)) throw IOException("DESTINATION_IS_SOURCE")
    var success = false
    try {
        val length = stage.length()
        val expected = resolver.openOutputStream(created, "wt")?.buffered()?.use { output ->
            stage.inputStream().buffered().use { input -> transferTaggedBytes(input, output, length, checkActive) }
        } ?: throw IOException("DESTINATION_UNWRITABLE")
        val actual = resolver.openInputStream(created)?.buffered()?.use { transferTaggedBytes(it, null, length, checkActive) }
            ?: throw IOException("DESTINATION_UNREADABLE")
        if (!actual.contentEquals(expected)) throw IOException("DESTINATION_COPY_MISMATCH")
        checkActive()
        created = DocumentsContract.renameDocument(resolver, created, name) ?: throw IOException("DESTINATION_RENAME_FAILED")
        if (sameMusicDocument(source, created)) throw IOException("DESTINATION_IS_SOURCE")
        success = true
        return runCatching {
            resolver.query(created, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: name
    } finally {
        if (!success && !sameMusicDocument(source, created)) runCatching { DocumentsContract.deleteDocument(resolver, created) }
    }
}

private fun sameMusicDocument(left: Uri, right: Uri): Boolean = left.authority == right.authority &&
    DocumentsContract.getDocumentId(left) == DocumentsContract.getDocumentId(right)

private fun transferTaggedBytes(input: InputStream, output: OutputStream?, length: Long, checkActive: () -> Unit): ByteArray {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        copied += count
        if (copied > length) throw IOException("UNEXPECTED_COPY_SIZE")
        hash.update(buffer, 0, count)
        output?.write(buffer, 0, count)
    }
    if (copied != length) throw IOException("INCOMPLETE_TAGGED_COPY")
    return hash.digest()
}
