package com.evanna.player.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.evanna.player.library.inspectLibraryDocument
import com.evanna.player.library.isLocalTree
import com.evanna.player.library.providerCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

internal suspend fun readTagSidecarBytes(resolver: ContentResolver, uri: Uri): ByteArray = providerCall { signal ->
    resolver.openFileDescriptor(uri, "r", signal)?.let { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                signal.throwIfCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > TAG_SIDECAR_LIMIT) throw IOException("TAGS_TOO_LARGE")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } ?: throw IOException("SIDECAR_UNAVAILABLE")
}

internal class TagSidecarFolders(private val context: Context) {
    private val resolver = context.contentResolver
    private val listed = mutableMapOf<Pair<Uri, String>, Map<String, Uri>>()

    suspend fun read(source: MusicTagSource): TagSidecar? {
        val tree = Uri.parse(source.root)
        val parent = parent(tree, Uri.parse(source.uri)) ?: return null
        val key = tree to parent
        val children = listed[key] ?: sidecars(tree, parent).also { listed[key] = it }
        val name = tagSidecarName(source.filename)
        val uri = children[name] ?: children[name + ".bak"] ?: return null
        return decodeTagSidecar(readTagSidecarBytes(resolver, uri))
    }

    suspend fun parent(tree: Uri, document: Uri): String? {
        val id = DocumentsContract.getDocumentId(document)
        val root = DocumentsContract.getTreeDocumentId(tree)
        if (tree.authority == "com.android.externalstorage.documents") {
            val separator = id.indexOf(':')
            if (separator >= 0) {
                val folder = if ('/' in id) id.substringBeforeLast('/') else id.substring(0, separator + 1)
                if (folder == root || folder.startsWith(if (root.endsWith(':')) root else "$root/")) return folder
            }
        }
        val path = providerCall {
            try { DocumentsContract.findDocumentPath(resolver, document)?.path }
            catch (_: UnsupportedOperationException) { null }
        }
        return path?.takeIf { it.firstOrNull() == root && it.lastOrNull() == id }?.dropLast(1)?.lastOrNull()
    }

    suspend fun sidecars(tree: Uri, parent: String): Map<String, Uri> = providerCall { signal ->
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE), null, null, null, signal)?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw IOException("INCOMPLETE_FOLDER")
            buildMap {
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) != Document.MIME_TYPE_DIR &&
                        name.removeSuffix(".bak").endsWith(TAG_SIDECAR_SUFFIX)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                        if (put(name, uri) != null) throw IOException("AMBIGUOUS_TAG_FILENAME")
                    }
                }
            }
        } ?: throw IOException("FOLDER_UNAVAILABLE")
    }

    suspend fun displayName(document: Uri): String? = providerCall { signal ->
        resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null, signal)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }
}

internal fun decodeTagSidecar(bytes: ByteArray): TagSidecar =
    try { TagSidecar.decode(bytes) }
    catch (failure: Exception) { throw IOException("INVALID_SIDECAR", failure) }

internal fun Context.canWriteTags(tree: Uri): Boolean = contentResolver.persistedUriPermissions
    .any { it.uri == tree && it.isReadPermission && it.isWritePermission }

private val tagWrites = Mutex()

internal suspend fun saveTagSidecar(context: Context, file: TagFilePreview, origin: TagOrigin?): String =
    withContext(Dispatchers.IO) {
        tagWrites.withLock {
            val (source, changes) = file
            val tree = Uri.parse(source.root)
            val audio = Uri.parse(source.uri)
            if (!isLocalTree(context, tree) || !context.canWriteTags(tree)) throw SecurityException("WRITE_GRANT_REQUIRED")
            val resolver = context.contentResolver
            val folders = TagSidecarFolders(context)
            if (folders.displayName(audio) != source.filename) throw IOException("SOURCE_CHANGED")
            val parent = folders.parent(tree, audio) ?: throw IOException("FOLDER_UNAVAILABLE")
            val name = tagSidecarName(source.filename)
            val backupName = name + ".bak"
            val siblings = folders.sidecars(tree, parent)
            val current = siblings[name]
            val previousBackup = siblings[backupName]
            val previous = current ?: previousBackup
            fun isAudio(uri: Uri): Boolean = uri.authority == audio.authority &&
                DocumentsContract.getDocumentId(uri) == DocumentsContract.getDocumentId(audio)
            if (previous?.let(::isAudio) == true || previousBackup?.let(::isAudio) == true) {
                throw IOException("SIDECAR_IS_AUDIO")
            }
            val originalBytes = previous?.let { readTagSidecarBytes(resolver, it) }
            val sidecar = originalBytes?.let(::decodeTagSidecar) ?: TagSidecar()
            if (current != null && previousBackup != null) {
                decodeTagSidecar(readTagSidecarBytes(resolver, previousBackup))
            }
            val embedded = inspectLibraryDocument(resolver, audio).tagValues()
            val effective = sidecar.applyTo(embedded)
            if (changes.any { effective[it.field] != it.before }) throw IOException("SOURCE_CHANGED")
            val expected = changes.associate { change ->
                change.field to (change.after?.let { TagChange.Set(it) } ?: TagChange.Clear)
            }
            if (previewTagEdits(listOf(TagAuditEntry(source, effective)), expected, false).singleOrNull()?.changes != changes) {
                throw IOException("SOURCE_CHANGED")
            }
            val bytes = sidecar.edited(changes, origin).encode()
            val directory = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
            var stage = DocumentsContract.createDocument(resolver, directory, "application/octet-stream",
                ".evanna-${UUID.randomUUID()}.partial") ?: throw IOException("FOLDER_UNWRITABLE")
            var backup = previousBackup
            var committed = false
            try {
                if (isAudio(stage) || stage == current || stage == previousBackup) throw IOException("INVALID_STAGE")
                resolver.openOutputStream(stage, "wt")?.use { it.write(bytes) } ?: throw IOException("FOLDER_UNWRITABLE")
                if (!bytes.contentEquals(readTagSidecarBytes(resolver, stage))) throw IOException("WRITE_VERIFY_FAILED")
                val latest = folders.sidecars(tree, parent)
                if (folders.displayName(audio) != source.filename ||
                    latest[name] != current || latest[backupName] != previousBackup ||
                    (previous != null && !originalBytes!!.contentEquals(readTagSidecarBytes(resolver, previous)))) {
                    throw IOException("SOURCE_CHANGED")
                }
                coroutineContext.ensureActive()
                withContext(NonCancellable) {
                    if (current != null) {
                        backup?.let { old ->
                            if (!DocumentsContract.deleteDocument(resolver, old)) throw IOException("BACKUP_DELETE_FAILED")
                            backup = null
                        }
                        backup = DocumentsContract.renameDocument(resolver, current, backupName)
                            ?: throw IOException("BACKUP_RENAME_FAILED")
                        if (folders.sidecars(tree, parent)[backupName] != backup) throw IOException("BACKUP_RENAME_FAILED")
                    }
                    stage = DocumentsContract.renameDocument(resolver, stage, name) ?: throw IOException("SIDECAR_RENAME_FAILED")
                    if (isAudio(stage) || folders.sidecars(tree, parent)[name] != stage) throw IOException("SIDECAR_RENAME_FAILED")
                    committed = true
                    backup?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                }
                name
            } finally {
                if (!committed && !isAudio(stage) && stage != current && stage != previousBackup) {
                    runCatching { DocumentsContract.deleteDocument(resolver, stage) }
                    // The scanner can still find the backup if the provider refuses to restore it.
                    if (current != null) backup?.let { old ->
                        runCatching { DocumentsContract.renameDocument(resolver, old, name) }
                    }
                }
            }
        }
    }
