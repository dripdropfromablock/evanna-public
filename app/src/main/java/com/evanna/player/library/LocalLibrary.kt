package com.evanna.player.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.evanna.player.decoder.*
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

private const val IDLE_STATUS = "Saved on this device. Tap a song to queue this list and play from there."
private const val READY_STATUS = "Scan complete. Saved songs are ready to browse."

internal object LocalLibrary {
    @Volatile private var database: LibraryDatabase? = null
    @Synchronized fun database(context: Context): LibraryDatabase = database
        ?: LibraryDatabase(context.applicationContext).also { database = it }
    private var artwork: EmbeddedArtwork? = null
    @Synchronized fun artwork(context: Context): EmbeddedArtwork = artwork
        ?: EmbeddedArtwork(context.applicationContext, database(context)).also { artwork = it }
    private var lyrics: EmbeddedLyrics? = null
    @Synchronized fun lyrics(context: Context): EmbeddedLyrics = lyrics
        ?: EmbeddedLyrics(database(context)).also { lyrics = it }

    // Folder scans belong to the app, not to the Library screen, so switching tabs never stops one.
    private val scans = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanJob: Job? = null
    private var tagsRefreshAttempted = false
    var scanning by mutableStateOf(false)
        private set
    var managingFolders by mutableStateOf(false)
        private set
    /** The Library screen's status line: scan progress and results, or the last queue add. */
    var status by mutableStateOf(IDLE_STATUS)
    /** True while the status only says all is well, so the list can leave that line out. */
    val statusIsIdle get() = status == IDLE_STATUS || status == READY_STATUS
    /** Counts finished scans; lists and covers reload when it changes. */
    var scansFinished by mutableIntStateOf(0)
        private set

    /** Main thread only. Adds the folder first when [add]; a request while a scan runs is ignored. */
    fun scan(context: Context, uri: Uri, add: Boolean) {
        startScans(context, { listOf(uri) }, add)
    }

    /** One attempt per process; a cancelled/unavailable folder remains a manual rescan. */
    fun refreshTagsIfNeeded(context: Context) {
        if (scanning || managingFolders || tagsRefreshAttempted) return
        tagsRefreshAttempted = true
        val app = context.applicationContext
        startScans(app, { database(app).rootsNeedingTagRefresh().map { Uri.parse(it.uri) } }, false)
    }

    private fun startScans(context: Context, roots: suspend () -> List<Uri>, add: Boolean) {
        if (scanning || managingFolders) return
        val app = context.applicationContext
        scanning = true
        status = "Scanning folder…"
        scanJob = scans.launch {
            try {
                val ready = withContext(Dispatchers.IO) {
                    releaseRemovedGrants(app,database(app))
                    val selected = roots()
                    if (add) add(app, selected.single())
                    scanLibraryBatch(selected.map { it.toString() }, database(app), { SafLibrarySource(app, Uri.parse(it),database(app).excludedFolders(it).map {folder->folder.id}.toSet()) }) { count ->
                        if (count % 10 == 0) withContext(Dispatchers.Main) { status = "Scanning… $count audio files checked." }
                    }
                    val readyRoots = database(app).roots().filter { it.state == LibraryRootState.READY }.map { it.uri }.toSet()
                    selected.all { it.toString() in readyRoots }
                }
                status = if (ready) READY_STATUS
                    else "Scan incomplete. Saved songs were kept. Reconnect the folder and rescan."
            } catch (cancelled: CancellationException) {
                status = "Scan cancelled. Saved songs were kept."
                throw cancelled
            } catch (_: Exception) {
                status = "The folder could not be indexed. Use a local phone or SD-card folder, or select it again to renew access. Saved songs were kept."
            } finally { scanning = false; scansFinished++ }
        }
    }

    fun cancelScan() { if(!managingFolders){tagsRefreshAttempted = true; scanJob?.cancel()} }

    fun cleanupRemovedFolders(context:Context) = folderAction(context,catalogChange=false) {app,store ->
        val pending=store.pendingGrantReleases().size
        if(pending==0)null else if(releaseRemovedGrants(app,store))"Removed folder access released."
        else "A removed folder's access could not be released yet. Evanna will retry when Library opens."
    }
    fun removeFolder(context:Context,root:LibraryRoot) = folderAction(context,"Removing music folder…") {app,store ->
        store.removeRoot(root.uri)
        if(releaseRemovedGrants(app,store))"Folder removed from Evanna. Music files and personal lists were kept."
        else "Folder removed; music files kept. Access cleanup is saved and will retry when Library opens."
    }
    fun includeFolder(context:Context,root:String,id:String) = folderAction(context,"Including subfolder…") {_,store ->
        store.includeFolder(root,id);"Folder included again. Rescan its music folder to restore the songs."
    }
    fun excludeFolder(context:Context,root:String,directory:LibraryDocument) = folderAction(context,"Checking subfolder contents…") {app,store ->
        require(directory.mime==DIRECTORY_MIME && directory.id.length<=8192)
        val tree=Uri.parse(root);val source=SafLibrarySource(app,tree)
        val top=source.root()
        require(directory.id!=top.id)
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,top.id)
        val selected=DocumentsContract.buildDocumentUriUsingTree(tree,directory.id)
        check(isDescendant(app,parent,selected))
        val descendants=buildSet {
            for((key,id) in store.folderDocuments(root)) {
                currentCoroutineContext().ensureActive()
                require(id.length<=8192)
                if(isDescendant(app,selected,DocumentsContract.buildDocumentUriUsingTree(tree,id)))add(key)
            }
        }
        currentCoroutineContext().ensureActive()
        store.excludeFolder(root,directory.id,directory.name,descendants)
        "Subfolder excluded. Music files and personal lists were kept."
    }
    private fun folderAction(context:Context,message:String?=null,catalogChange:Boolean=true,action:suspend (Context,LibraryDatabase)->String?):Job? {
        if(scanning || managingFolders)return null
        // Grant-journal housekeeping changes no indexed music and must not invalidate ongoing loudness analysis.
        val app=context.applicationContext;scanning=catalogChange;managingFolders=true
        message?.let {status=it}
        // Folder edits belong to the app scope and finish their transaction even if the dialog closes.
        return scans.launch {
            try {withContext(Dispatchers.IO){action(app,database(app))}?.let {status=it}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){status="The folder change could not finish. Reconnect access or check free storage, then try again. No music files were deleted."}
            finally {managingFolders=false;if(catalogChange){scanning=false;scansFinished++}}
        }
    }
    private fun releaseRemovedGrants(context:Context,store:LibraryDatabase):Boolean {
        var complete=true
        for(root in store.pendingGrantReleases()) {
            if(store.roots().any {it.uri==root}){store.acknowledgeGrantRelease(root);continue}
            val uri=Uri.parse(root)
            if(!isLocalTree(context,uri)){complete=false;continue}
            try {
                if(hasTreeReadGrant(context,uri))context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                check(!hasTreeReadGrant(context,uri));store.acknowledgeGrantRelease(root)
            }catch(_:Exception){complete=false}
        }
        return complete
    }

    suspend fun add(context: Context, uri: Uri) {
        if (!isLocalTree(context, uri)) throw IOException("LOCAL_FOLDER_REQUIRED")
        val resolver = context.applicationContext.contentResolver
        val existing = hasTreeReadGrant(context, uri)
        try {
            if (!existing) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val root = SafLibrarySource(context, uri).root()
            if (root.mime != DIRECTORY_MIME) throw IOException("FOLDER_REQUIRED")
            database(context).addRoot(uri.toString(), root.name, !existing)
        } catch (failure: Throwable) {
            if (!existing) runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            throw failure
        }
    }
}

internal fun isLocalTree(context: Context, uri: Uri): Boolean =
    isLocalDocumentProvider(context, uri) && DocumentsContract.isTreeUri(uri) &&
        uri.pathSegments.size == 2 && uri.pathSegments[0] == "tree" && uri.toString().length <= 8192

internal fun hasTreeReadGrant(context: Context, tree: Uri): Boolean = context.contentResolver.persistedUriPermissions
    .any { it.isReadPermission && it.uri == tree }

internal class SafLibrarySource(context: Context, private val tree: Uri,override val excludedDirectories:Set<String> = emptySet()) : LibraryScanSource {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)

    override suspend fun root(): LibraryDocument {
        if (!isLocalTree(app, tree) || !hasTreeReadGrant(app, tree)) throw IOException("FOLDER_ACCESS_REQUIRED")
        return query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)).singleOrNull() ?: throw IOException("FOLDER_UNAVAILABLE")
    }
    override suspend fun children(id: String) = query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id))

    private suspend fun query(uri: Uri): List<LibraryDocument> = providerCall { signal ->
        resolver.query(uri, columns, null, null, null, signal)?.use { cursor ->
            if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw IOException("INCOMPLETE_FOLDER")
            buildList {
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    if (size >= 100000) throw IOException("FOLDER_LIMIT_EXCEEDED")
                    val id = cursor.getString(0) ?: throw IOException("INVALID_DOCUMENT")
                    if (id.length > 8192) throw IOException("INVALID_DOCUMENT")
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    if (documentUri.toString().length > 8192) throw IOException("INVALID_DOCUMENT")
                    val key = MessageDigest.getInstance("SHA-256").digest((tree.authority + "\u0000" + id).toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    add(LibraryDocument(id, key, documentUri.toString(), cursor.getString(1)?.take(512) ?: "Audio file",
                        cursor.getString(2) ?: "", if (cursor.isNull(3)) null else cursor.getLong(3), if (cursor.isNull(4)) null else cursor.getLong(4)))
                }
            }
        } ?: throw IOException("FOLDER_UNAVAILABLE")
    }
    override suspend fun inspect(document: LibraryDocument): LibraryAudio = providerCall { signal ->
        resolver.openFileDescriptor(Uri.parse(document.uri), "r", signal)?.use { descriptor ->
            NativeDecoder.open(descriptor.fd).use { decoder ->
                signal.throwIfCanceled()
                val info = decoder.info
                LibraryAudio(info.title, info.artist, info.album, info.durationUs, com.evanna.player.decoder.sourceCodec(info.codec, info.sampleRate), info.sampleRate, info.significantBits, info.channels, info.musicTags,info.sortTags)
            }
        } ?: throw IOException("DOCUMENT_UNAVAILABLE")
    }
    override suspend fun readSheet(document: LibraryDocument): ByteArray = providerCall { signal ->
        resolver.openFileDescriptor(Uri.parse(document.uri), "r", signal)?.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    signal.throwIfCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                    if (bytes.size() > CUE_SHEET_MAX_BYTES) throw IOException("CUE_TOO_LARGE")
                }
                bytes.toByteArray()
            }
        } ?: throw IOException("DOCUMENT_UNAVAILABLE")
    }
}

/** Providers own document ancestry; opaque IDs are never treated as filesystem prefixes. */
private fun isDescendant(context:Context,parent:Uri,child:Uri):Boolean {
    if(Build.VERSION.SDK_INT>=29)return DocumentsContract.isChildDocument(context.contentResolver,parent,child)
    val path=DocumentsContract.findDocumentPath(context.contentResolver,child)?.path ?: throw IOException("FOLDER_ANCESTRY_UNAVAILABLE")
    check(path.firstOrNull()==DocumentsContract.getTreeDocumentId(child) && path.lastOrNull()==DocumentsContract.getDocumentId(child))
    val parentId=DocumentsContract.getDocumentId(parent)
    return parentId!=path.last() && parentId in path
}

/** Blocking provider work stays on the caller's IO lane; cancellation signals Binder without leaking resources. */
private suspend fun <T> providerCall(action: (CancellationSignal) -> T): T = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    continuation.invokeOnCancellation { signal.cancel() }
    try { continuation.resume(action(signal)) }
    catch (failure: Exception) { continuation.resumeWithException(failure) }
}
