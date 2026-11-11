package com.evanna.player.library

import java.io.IOException
import java.util.UUID
import com.evanna.player.decoder.MusicTags
import kotlinx.coroutines.*

internal const val DIRECTORY_MIME = "vnd.android.document/directory"
internal const val LIBRARY_TAGS_VERSION = 3
internal enum class LibraryRootState { SCANNING, READY, PAUSED, UNAVAILABLE }
internal data class LibraryDocument(val id: String, val key: String, val uri: String, val name: String,
    val mime: String, val size: Long?, val modified: Long?)
internal data class LibraryAudio(val title: String?, val artist: String?, val album: String?, val durationUs: Long?,
    val codec: String, val rate: Int, val bits: Int?, val channels: Int, val musicTags: MusicTags = MusicTags(),
    val sortTags:com.evanna.player.decoder.SortTags=com.evanna.player.decoder.SortTags())
internal data class LibraryCached(val document: LibraryDocument, val audio: LibraryAudio?, val tagsVersion: Int = 1)
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
    fun recordCue(root: String, token: String, file: LibraryDocument, audio: LibraryAudio, sheet: CueSheet, ranges: List<CueRange>) {}
    fun begin(root: String, token: String)
    fun cached(root: String, key: String): LibraryCached?
    fun record(root: String, token: String, document: LibraryDocument, audio: LibraryAudio?)
    fun exclude(root: String, token: String, key: String)
    fun finish(root: String, token: String)
    fun interrupt(root: String, token: String, state: LibraryRootState)
}

internal suspend fun scanLibraryBatch(roots: List<String>, index: LibraryScanIndex,
    source: (String) -> LibraryScanSource, progress: suspend (Int) -> Unit = {}) {
    var total = 0
    for (root in roots) {
        currentCoroutineContext().ensureActive()
        total += scanLibrary(root, index, source(root)) { progress(total + it) }
    }
}

/** Incomplete enumeration may add/update rows, but never declares unseen songs missing. */
internal suspend fun scanLibrary(root: String, index: LibraryScanIndex, source: LibraryScanSource,
    progress: suspend (Int) -> Unit = {}): Int {
    val token = UUID.randomUUID().toString()
    var inspected = 0
    index.begin(root, token)
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
            val siblings = hashMapOf<String, Pair<LibraryDocument, LibraryAudio>>()
            for (document in source.children(parent)) {
                currentCoroutineContext().ensureActive()
                if (++visited > 100000 || depth > 128) throw ScanLimit()
                if (!seen.add(document.id)) continue
                if (document.mime == DIRECTORY_MIME) {
                    if(document.id !in source.excludedDirectories)pending.add(document.id to depth + 1)
                    continue
                }
                val extension = document.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                val mime = document.mime.lowercase(java.util.Locale.ROOT)
                if (extension == "cue") { sheets += document; continue }
                if (extension in listOf("m3u", "m3u8", "pls", "xspf", "wpl", "asx") ||
                    mime in listOf("audio/x-mpegurl", "audio/mpegurl", "audio/x-scpls")) {
                    index.exclude(root, token, document.key)
                    continue
                }
                // A WavPack correction file is not playable on its own.
                if (extension == "wvc" || !mime.startsWith("audio/") && extension !in
                    listOf("flac", "alac", "wav", "wave", "aif", "aiff", "aifc", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wv", "ape", "tta", "dsf", "dff")) continue
                val previous = index.cached(root, document.key)
                val unchanged = previous?.audio != null && previous.tagsVersion >= LIBRARY_TAGS_VERSION && document.size != null && document.size > 0 &&
                    document.modified != null && document.modified > 0 && previous.document.name == document.name &&
                    previous.document.size == document.size && previous.document.modified == document.modified
                val audio = if (unchanged) previous!!.audio else try { source.inspect(document) }
                    catch (_: IOException) { null }
                currentCoroutineContext().ensureActive()
                index.record(root, token, document, audio)
                if (audio != null) siblings[document.name.lowercase(java.util.Locale.ROOT)] = document to audio
                progress(++inspected)
            }
            for (sheet in sheets) {
                val parsed = try { parseCueSheet(source.readSheet(sheet)) }
                    catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
                currentCoroutineContext().ensureActive()
                // Only a bare sibling name counts; paths and near matches (other extensions) are never guessed.
                for (file in parsed?.files.orEmpty()) {
                    if ('/' in file.name || '\\' in file.name) continue
                    val (document, audio) = siblings[file.name.lowercase(java.util.Locale.ROOT)] ?: continue
                    cueRanges(file, audio.rate, audio.durationUs)?.let { index.recordCue(root, token, document, audio, parsed!!, it) }
                }
            }
        }
        source.root() // Revalidate the root before a complete scan can mark unseen entries missing.
        currentCoroutineContext().ensureActive()
        index.finish(root, token)
    } catch (cancelled: CancellationException) {
        index.interrupt(root, token, LibraryRootState.PAUSED)
        throw cancelled
    } catch (_: ScanLimit) { index.interrupt(root, token, LibraryRootState.PAUSED) }
    catch (_: IOException) { index.interrupt(root, token, LibraryRootState.UNAVAILABLE) }
    catch (_: SecurityException) { index.interrupt(root, token, LibraryRootState.UNAVAILABLE) }
    catch (failure: Exception) { runCatching { index.interrupt(root, token, LibraryRootState.PAUSED) }; throw failure }
    return inspected
}
private class ScanLimit : IOException()
