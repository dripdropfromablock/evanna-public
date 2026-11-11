package com.evanna.player.library

import java.io.IOException
import java.util.UUID
import com.evanna.player.decoder.MusicTags
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val DIRECTORY_MIME = "vnd.android.document/directory"
internal const val LIBRARY_TAGS_VERSION = 3
internal const val SCAN_BATCH_SIZE = 128
private val playlistExtensions = setOf("m3u", "m3u8", "pls", "xspf", "wpl", "asx")
private val playlistMimeTypes = setOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls")
private val audioExtensions = setOf("flac", "alac", "wav", "wave", "aif", "aiff", "aifc", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wv", "ape", "tta", "dsf", "dff")
internal enum class LibraryRootState { SCANNING, READY, PAUSED, UNAVAILABLE }
internal data class LibraryDocument(val id: String, val key: String, val uri: String, val name: String,
    val mime: String, val size: Long?, val modified: Long?)
internal data class LibraryAudio(val title: String?, val artist: String?, val album: String?, val durationUs: Long?,
    val codec: String, val rate: Int, val bits: Int?, val channels: Int, val musicTags: MusicTags = MusicTags(),
    val sortTags:com.evanna.player.decoder.SortTags=com.evanna.player.decoder.SortTags())
internal data class LibraryCached(val document: LibraryDocument, val audio: LibraryAudio?, val tagsVersion: Int = 1,
    val present: Boolean = true, val cueHidden: Boolean = false) {
    fun fingerprint() = LibraryFingerprint(document.uri, document.name, document.size, document.modified,
        present, audio != null, tagsVersion, cueHidden)
}
internal data class LibraryFingerprint(val uri: String, val name: String, val size: Long?, val modified: Long?,
    val present: Boolean, val readable: Boolean, val tagsVersion: Int, val cueHidden: Boolean) {
    fun matches(document: LibraryDocument) = uri == document.uri && name == document.name &&
        size == document.size && modified == document.modified

    fun canReuse(document: LibraryDocument) = readable && tagsVersion >= LIBRARY_TAGS_VERSION &&
        size != null && size > 0 && modified != null && modified > 0 && matches(document)
}
internal data class LibraryScanResult(val checked: Int, val changed: Boolean)

/** User operations cancel the silent scan before waiting for its final database cleanup. */
internal class LibraryScanQueue {
    private val indexLock = Mutex()
    private var startupJob: Job? = null

    fun startup(scope: CoroutineScope, action: suspend () -> Unit): Job = scope.launch {
        indexLock.withLock { action() }
    }.also { startupJob = it }

    suspend fun <T> userAction(action: suspend () -> T): T {
        val startup = startupJob
        if (startup?.isActive == true) withContext(Dispatchers.IO) { startup.cancel() }
        return indexLock.withLock { action() }
    }
}
internal interface LibraryScanSource {
    val excludedDirectories:Set<String> get()=emptySet()
    suspend fun root(): LibraryDocument
    suspend fun children(id: String): List<LibraryDocument>
    suspend fun inspect(document: LibraryDocument): LibraryAudio
    /** At most [CUE_SHEET_MAX_BYTES]; larger or unreadable sheets throw IOException. */
    suspend fun readSheet(document: LibraryDocument): ByteArray = throw IOException("CUE_UNREADABLE")
}
internal interface LibraryScanIndex {
    /** CUE tracks of one indexed sibling audio file; the whole-file song is hidden while a sheet covers it. */
    fun recordCue(root: String, token: String, file: LibraryDocument, audio: LibraryAudio, sheet: CueSheet, ranges: List<CueRange>): Boolean = false
    fun begin(root: String, token: String)
    fun beginSilent(root: String, token: String) = begin(root, token)
    fun cached(root: String, key: String): LibraryCached?
    fun cachedBatch(root: String, keys: List<String>): Map<String, LibraryCached> =
        keys.mapNotNull { key -> cached(root, key)?.let { key to it } }.toMap()
    fun fingerprints(root: String, keys: List<String>): Map<String, LibraryFingerprint> =
        cachedBatch(root, keys).mapValues { it.value.fingerprint() }
    fun record(root: String, token: String, document: LibraryDocument, audio: LibraryAudio?)
    fun recordBatch(root: String, token: String, rows: List<Pair<LibraryDocument, LibraryAudio?>>) {
        for ((document, audio) in rows) record(root, token, document, audio)
    }
    fun markSeen(root: String, token: String, keys: List<String>, reveal: List<String> = emptyList()): Boolean
    fun finish(root: String, token: String, checkActive: () -> Unit = {}): Boolean
    fun interrupt(root: String, token: String, state: LibraryRootState): Boolean
}

internal suspend fun scanLibraryBatch(roots: List<String>, index: LibraryScanIndex,
    source: (String) -> LibraryScanSource, silent: Boolean = false, onChanged: () -> Unit = {},
    onRoot: suspend (String) -> Unit = {}, progress: suspend (Int) -> Unit = {}): Boolean {
    var total = 0
    var changed = false
    for (root in roots) {
        currentCoroutineContext().ensureActive()
        onRoot(root)
        val result = scanLibrary(root, index, source(root), silent, onChanged) { progress(total + it) }
        total += result.checked
        changed = changed || result.changed
    }
    return changed
}

/** Incomplete enumeration may add/update rows, but never declares unseen songs missing. */
internal suspend fun scanLibrary(root: String, index: LibraryScanIndex, source: LibraryScanSource,
    silent: Boolean = false, onChanged: () -> Unit = {}, progress: suspend (Int) -> Unit = {}): LibraryScanResult {
    val token = UUID.randomUUID().toString()
    var checked = 0
    var changed = false
    fun noteChange() { changed = true; onChanged() }
    if (silent) index.beginSilent(root, token) else index.begin(root, token)
    try {
        val directory = source.root()
        if (directory.mime != DIRECTORY_MIME) throw IOException("FOLDER_REQUIRED")
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(directory.id to 0)
        val seen = hashSetOf(directory.id)
        var visited = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (parent, depth) = pending.removeFirst()
            val sheets = mutableListOf<LibraryDocument>()
            val candidates = mutableListOf<LibraryDocument>()
            val children = source.children(parent)
            visited += children.size
            if (visited > 100000 || depth > 128) throw ScanLimit()
            if (children.any { it.mime != DIRECTORY_MIME && it.name == ".nomedia" }) continue
            for (document in children) {
                currentCoroutineContext().ensureActive()
                if (!seen.add(document.id)) continue
                if (document.mime == DIRECTORY_MIME) {
                    if(document.id !in source.excludedDirectories)pending.add(document.id to depth + 1)
                    continue
                }
                val extension = document.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                val mime = document.mime.lowercase(java.util.Locale.ROOT)
                if (extension == "cue") { sheets += document; continue }
                if (extension in playlistExtensions || mime in playlistMimeTypes) continue
                // A WavPack correction file is not playable on its own.
                if (extension != "wvc" && (mime.startsWith("audio/") || extension in audioExtensions)) candidates += document
            }
            val siblings = hashMapOf<String, Pair<LibraryDocument, LibraryAudio>>()
            val hiddenBases = hashSetOf<String>()
            for (chunk in candidates.chunked(SCAN_BATCH_SIZE)) {
                val keys = chunk.map { it.key }
                val cached = if (sheets.isEmpty()) emptyMap() else index.cachedBatch(root, keys)
                val fingerprints = if (sheets.isEmpty()) index.fingerprints(root, keys) else cached.mapValues { it.value.fingerprint() }
                val updates = mutableListOf<Pair<LibraryDocument, LibraryAudio?>>()
                val unchanged = mutableListOf<String>()
                for (document in chunk) {
                    currentCoroutineContext().ensureActive()
                    val previous = fingerprints[document.key]
                    val reuse = previous?.canReuse(document) == true
                    val audio = if (reuse) cached[document.key]?.audio else try { source.inspect(document) } catch (_: IOException) { null }
                    val sameMetadata = !reuse && previous?.matches(document) == true && when {
                        audio == null -> !previous.readable
                        previous.readable && previous.tagsVersion >= LIBRARY_TAGS_VERSION -> {
                            val saved = (cached[document.key] ?: index.cached(root, document.key))?.audio
                            saved == audio.copy(title = audio.title ?: document.name)
                        }
                        else -> false
                    }
                    if (reuse || sameMetadata) {
                        unchanged += document.key
                        if (previous?.cueHidden == true) hiddenBases += document.key
                    } else updates += document to audio
                    if (audio != null && sheets.isNotEmpty()) siblings[document.name.lowercase(java.util.Locale.ROOT)] = document to audio
                }
                currentCoroutineContext().ensureActive()
                if (updates.isNotEmpty()) {
                    index.recordBatch(root, token, updates)
                    noteChange()
                }
                if (unchanged.isNotEmpty() && index.markSeen(root, token, unchanged)) noteChange()
                checked += chunk.size
                if (!silent) progress(checked)
            }
            val cueTracks = mutableMapOf<String, MutableSet<Int>>()
            for (sheet in sheets.sortedByDescending { it.id }) {
                val parsed = try { parseCueSheet(source.readSheet(sheet)) }
                    catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
                currentCoroutineContext().ensureActive()
                // Only a bare sibling name counts; paths and near matches (other extensions) are never guessed.
                for (file in parsed?.files.orEmpty()) {
                    if ('/' in file.name || '\\' in file.name) continue
                    val (document, audio) = siblings[file.name.lowercase(java.util.Locale.ROOT)] ?: continue
                    val ranges = cueRanges(file, audio.rate, audio.durationUs) ?: continue
                    val assigned = cueTracks.getOrPut(document.key) { hashSetOf() }
                    val unique = ranges.filter { assigned.add(it.track.number) }
                    if (unique.isNotEmpty()) {
                        hiddenBases -= document.key
                        if (index.recordCue(root, token, document, audio, parsed!!, unique)) noteChange()
                    }
                }
            }
            for (keys in hiddenBases.chunked(SCAN_BATCH_SIZE)) {
                currentCoroutineContext().ensureActive()
                if (index.markSeen(root, token, emptyList(), reveal = keys)) noteChange()
            }
        }
        source.root() // Revalidate the root before a complete scan can mark unseen entries missing.
        val context = currentCoroutineContext()
        context.ensureActive()
        if (index.finish(root, token, context::ensureActive)) noteChange()
    } catch (cancelled: CancellationException) {
        if (!silent && index.interrupt(root, token, LibraryRootState.PAUSED)) noteChange()
        throw cancelled
    } catch (_: ScanLimit) { if (index.interrupt(root, token, LibraryRootState.PAUSED)) noteChange() }
    catch (_: IOException) { if (index.interrupt(root, token, LibraryRootState.UNAVAILABLE)) noteChange() }
    catch (_: SecurityException) { if (index.interrupt(root, token, LibraryRootState.UNAVAILABLE)) noteChange() }
    catch (failure: Exception) {
        if (!silent && runCatching { index.interrupt(root, token, LibraryRootState.PAUSED) }.getOrDefault(false)) noteChange()
        throw failure
    }
    return LibraryScanResult(checked, changed)
}
private class ScanLimit : IOException()
