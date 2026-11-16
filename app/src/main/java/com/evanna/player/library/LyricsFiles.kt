package com.evanna.player.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where a song's own file sits in a music folder, for the .lrc beside it. */
internal data class SongPlace(val root: String, val documentId: String, val name: String)

/** The .lrc that belongs to a song file: "Song.flac" → "Song.lrc". */
internal fun sidecarName(fileName: String): String {
    val base = fileName.substringBeforeLast('.', fileName)
    return (if (base.isEmpty()) fileName else base) + ".lrc"
}

/** Lyrics text in UTF-8, or UTF-16 when the bytes start with its mark. */
internal fun lyricsText(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    else -> bytes.toString(Charsets.UTF_8)
}

/**
 * The .lrc file beside a song in a music folder, and Evanna's own copy for lyrics that could not go there (a folder Evanna
 * may only read, a CUE track or a single opened file). Song files are never written.
 */
internal object LyricsFiles {
    private const val LIMIT = 1 shl 20
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** The .lrc beside the song; null when there is none. Throws when the folder cannot be read. */
    fun readBeside(context: Context, place: SongPlace): String? {
        val tree = Uri.parse(place.root)
        val parent = parentOf(context, tree, place.documentId) ?: return null
        val file = child(context, tree, parent, sidecarName(place.name)) ?: return null
        return context.contentResolver.openInputStream(file)?.use { lyricsText(readLimited(it)) }
    }

    /** True when the music folder [root] lets Evanna add files. */
    fun canWrite(context: Context, root: String): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.isWritePermission && it.uri.toString() == root }

    /** Adds [text] as the .lrc beside the song. False when the folder may not be written, cannot be read or already has that .lrc. */
    fun writeBeside(context: Context, place: SongPlace, text: String): Boolean =
        try { addBeside(context, place, text) } catch (_: IOException) { false } catch (_: SecurityException) { false }

    private fun addBeside(context: Context, place: SongPlace, text: String): Boolean {
        if (!canWrite(context, place.root)) return false
        val resolver = context.contentResolver
        val tree = Uri.parse(place.root)
        val parent = parentOf(context, tree, place.documentId) ?: return false
        val name = sidecarName(place.name)
        if (child(context, tree, parent, name) != null) return false
        val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent),
            "application/octet-stream", name) ?: return false
        try {
            // A provider that renamed it ("Song (1).lrc") made a file no player would pair with the song.
            if (displayName(context, created) != name) throw IOException("LRC_RENAMED")
            resolver.openOutputStream(created, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException("LRC_UNWRITABLE")
            return true
        } catch (failure: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw failure
        }
    }

    fun readSaved(context: Context, id: String): String? =
        savedFile(context, id).takeIf { it.isFile && it.length() in 1..LIMIT.toLong() }?.let { lyricsText(it.readBytes()) }

    /** Keeps [text] in Evanna, written whole or not at all. */
    fun writeSaved(context: Context, id: String, text: String) {
        val file = savedFile(context, id)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(file)) { temporary.delete(); throw IOException("LYRICS_NOT_SAVED") }
    }

    fun deleteSaved(context: Context, id: String) { savedFile(context, id).delete() }

    private fun savedFile(context: Context, id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(File(context.filesDir, "lyrics"), "$name.lrc")
    }

    /** The folder holding [documentId]: the provider's own path when it gives one, else Android storage's "volume:path" id. */
    private fun parentOf(context: Context, tree: Uri, documentId: String): String? {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val path = try { DocumentsContract.findDocumentPath(context.contentResolver, document)?.path } catch (_: Exception) { null }
        if (path != null && path.size >= 2 && path.last() == documentId) return path[path.size - 2]
        if (tree.authority == EXTERNAL_STORAGE && '/' in documentId) return documentId.substringBeforeLast('/')
        return null
    }

    /** The file named [name] (any letter case) directly in [parent]. */
    private fun child(context: Context, tree: Uri, parent: String, name: String): Uri? {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (cursor.getString(1)?.equals(name, ignoreCase = true) == true) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0) ?: continue)
                }
            }
        } ?: throw IOException("FOLDER_UNAVAILABLE")
        return null
    }

    private fun displayName(context: Context, document: Uri): String? =
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun readLimited(input: InputStream): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > LIMIT) throw IOException("LRC_TOO_LARGE")
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }
}
