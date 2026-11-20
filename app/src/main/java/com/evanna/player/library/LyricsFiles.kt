package com.evanna.player.library

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.TreeMap

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
    fun readBeside(context: Context, place: SongPlace, folders: FolderIndex? = null): String? {
        val tree = Uri.parse(place.root)
        val parent = parentOf(context, tree, place.documentId) ?: run {
            if (folders != null) throw IOException("FOLDER_UNAVAILABLE")
            return null
        }
        val name = sidecarName(place.name)
        val file = child(context, tree, parent, name, folders) ?: return null
        return try {
            read(context, file)
        } catch (missing: FileNotFoundException) {
            if (folders == null) throw missing
            folders.forget(tree, parent)
            child(context, tree, parent, name, folders)?.let { read(context, it) }
        }
    }

    private fun read(context: Context, file: Uri): String? =
        context.contentResolver.openInputStream(file)?.use { readLimited(it)?.let(::lyricsText) }

    /** True when the music folder [root] lets Evanna add files. */
    fun canWrite(context: Context, root: String): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.isWritePermission && it.uri.toString() == root }

    /** Adds [text] as the .lrc beside the song. False when the folder may not be written, cannot be read or already has that .lrc. */
    fun writeBeside(context: Context, place: SongPlace, text: String, folders: FolderIndex? = null): Boolean =
        try { addBeside(context, place, text, folders) } catch (_: IOException) { false } catch (_: SecurityException) { false }

    private fun addBeside(context: Context, place: SongPlace, text: String, folders: FolderIndex?): Boolean {
        if (!canWrite(context, place.root)) return false
        val content = writableBytes(text)
        val resolver = context.contentResolver
        val tree = Uri.parse(place.root)
        val parent = parentOf(context, tree, place.documentId) ?: return false
        val name = sidecarName(place.name)
        if (child(context, tree, parent, name, folders) != null) return false
        val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent),
            "application/octet-stream", name) ?: return false
        try {
            // A provider that renamed it ("Song (1).lrc") made a file no player would pair with the song.
            if (displayName(context, created) != name) {
                folders?.forget(tree, parent)
                throw IOException("LRC_RENAMED")
            }
            resolver.openOutputStream(created, "wt")?.use { it.write(content) } ?: throw IOException("LRC_UNWRITABLE")
            folders?.record(tree, parent, name, created)
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
        try {
            temporary.writeBytes(writableBytes(text))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun deleteSaved(context: Context, id: String) { savedFile(context, id).delete() }

    private fun writableBytes(text: String): ByteArray = text.encodeToByteArray().also {
        if (it.size !in 1..LIMIT) throw IOException("LRC_TOO_LARGE")
    }

    private fun savedFile(context: Context, id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(File(context.filesDir, "lyrics"), "$name.lrc")
    }

    /** The folder holding [documentId]: Android storage's "volume:path" id or the provider's own path. */
    private fun parentOf(context: Context, tree: Uri, documentId: String): String? {
        if (tree.authority == EXTERNAL_STORAGE) {
            val volumeEnd = documentId.indexOf(':')
            if (volumeEnd >= 0) {
                val root = DocumentsContract.getTreeDocumentId(tree)
                val parent = if ('/' in documentId) documentId.substringBeforeLast('/') else documentId.take(volumeEnd + 1)
                val prefix = if (root.endsWith(':')) root else "$root/"
                if (parent == root || parent.startsWith(prefix)) return parent
            }
        }
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val path = try { DocumentsContract.findDocumentPath(context.contentResolver, document)?.path } catch (_: Exception) { null }
        return path?.takeIf { it.size >= 2 && it.last() == documentId }?.let { it[it.lastIndex - 1] }
    }

    /** A folder listing belongs to one bulk run. Successful writes also enter the listing. */
    class FolderIndex {
        private val listed = mutableMapOf<Pair<Uri, String>, MutableMap<String, Uri>>()

        fun file(context: Context, tree: Uri, parent: String, name: String): Uri? =
            listed.getOrPut(tree to parent) {
                queryChildren(context, tree, parent) { cursor ->
                    TreeMap<String, Uri>(String.CASE_INSENSITIVE_ORDER).apply {
                        while (cursor.moveToNext()) {
                            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                            val filename = cursor.getString(1) ?: continue
                            if (!filename.endsWith(".lrc", ignoreCase = true)) continue
                            val id = cursor.getString(0) ?: continue
                            putIfAbsent(filename, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        }
                    }
                }
            }[name]

        fun record(tree: Uri, parent: String, name: String, file: Uri) {
            listed[tree to parent]?.put(name, file)
        }

        fun forget(tree: Uri, parent: String) {
            listed.remove(tree to parent)
        }
    }

    /** The file named [name] (any letter case) directly in [parent]. */
    private fun child(context: Context, tree: Uri, parent: String, name: String, folders: FolderIndex?): Uri? {
        if (folders != null) return folders.file(context, tree, parent, name)
        return queryChildren(context, tree, parent) { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (cursor.getString(1)?.equals(name, ignoreCase = true) == true) {
                    return@queryChildren DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0) ?: continue)
                }
            }
            null
        }
    }

    private fun <T> queryChildren(context: Context, tree: Uri, parent: String, read: (Cursor) -> T): T {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val cursor = context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), columns,
            null, null, null) ?: throw IOException("FOLDER_UNAVAILABLE")
        return cursor.use {
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw IOException("INCOMPLETE_FOLDER")
            read(it)
        }
    }

    private fun displayName(context: Context, document: Uri): String? =
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun readLimited(input: InputStream): ByteArray? {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > LIMIT) return null
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }
}
