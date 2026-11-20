package com.evanna.player.webmusic

import android.content.Context
import com.evanna.player.network.CancelScope
import com.evanna.player.sources.SourceCatalog
import com.evanna.player.sources.SourceKind
import com.evanna.player.sources.SourceReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.UUID

internal val webDownloadId = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
internal fun webDownloadDirectory(context: Context) = File(context.noBackupFilesDir, "web-downloads")

internal enum class WebDownloadStatus(val label: String) {
    WAITING("Waiting"), RUNNING("Downloading."), INTERRUPTED("Interrupted"), FAILED("Failed"),
    SAVING("Saving to your music folder."), SAVED("Saved to your music folder"),
}

internal data class WebDownloadEntry(
    val id: String, val name: String, val status: WebDownloadStatus,
    val expected: RecordingEvidence?, val canRetry: Boolean,
    val download: StagedWebAudio? = null, val detail: String = status.label,
    val folder: String, val savedUri: String? = null, val album: String? = null,
    val candidates: List<SearchDownloadCandidate> = emptyList(), val provider: String? = null, val audioFormat: String? = null,
)

internal class WebDownloadQueue(
    private val directory: File,
    private val publishTrack: (String, StagedWebAudio, CancelScope) -> String = { _, _, _ -> error("Missing destination") },
    private val savedExists: (String) -> Boolean = { true },
    private val onSaved: (String, String, Boolean) -> Unit = { _, _, _ -> },
    private val downloadConnected: (SourceReference, String, RecordingEvidence?, File, CancelScope, (Long, Long?) -> Unit) -> StagedWebAudio,
) {
    private val worker = CoroutineScope(Dispatchers.IO)
    private val index = File(directory, "queue.properties")
    private val playWhenSaved = mutableSetOf<String>()
    private var active: Pair<String, CancelScope>? = null
    private val changes = MutableStateFlow(readEntries())
    val entries = changes.asStateFlow()

    init {
        // Keep verified stages across a failed publish. Interrupted transfers restart from zero.
        val owned = changes.value.mapNotNull { it.download?.file?.name }.toSet()
        directory.listFiles().orEmpty().filter {
            it.name.startsWith("download-") && it.name.endsWith(".stage") &&
                webDownloadId.matches(it.name.removePrefix("download-").removeSuffix(".stage")) && it.name !in owned
        }.forEach { it.delete() }
    }

    @Synchronized
    fun enqueueSearch(folder: String, tracks: List<SearchDownload>, play: Boolean = false): Int {
        require(folder.isNotBlank() && tracks.isNotEmpty() && (!play || tracks.size == 1))
        if (tracks.size > MAX_SEARCH_DOWNLOADS) throw WebImportError("Select up to $MAX_SEARCH_DOWNLOADS tracks.")
        val next = changes.value.associateByTo(LinkedHashMap()) { it.id }
        var added = 0
        var playId: String? = null
        var alreadySaved: String? = null
        for (track in tracks.distinctBy { it.key }) {
            val first = track.candidates.first()
            require(track.key.isNotBlank() && track.candidates.size <= 8 && track.candidates.all { it.file.format == first.file.format })
            val id = UUID.nameUUIDFromBytes((folder + "\u0000" + track.key).encodeToByteArray()).toString()
            if (play) playId = id
            val previous = next[id]
            if (previous?.status == WebDownloadStatus.SAVED && previous.savedUri?.let(savedExists) == true) {
                if (play) alreadySaved = previous.savedUri
                continue
            }
            if (previous?.status in setOf(WebDownloadStatus.RUNNING, WebDownloadStatus.SAVING, WebDownloadStatus.WAITING))
                continue
            val stage = previous?.download?.takeIf {
                track.candidates.any { candidate -> candidate.provider == previous.provider && candidate.file.format == it.format }
            }
            next[id] = WebDownloadEntry(id, track.name, WebDownloadStatus.WAITING, track.expected, canRetry = true,
                download = stage, folder = folder, album = track.album?.take(512), candidates = track.candidates,
                provider = if (stage == null) first.provider else previous?.provider, audioFormat = first.file.format)
            added++
        }
        if (next.size > MAX_SEARCH_DOWNLOADS) {
            throw WebImportError("Downloads is full. Remove finished entries before adding this album.")
        }
        if (added > 0) commit(next.values.toList())
        if (playId != null && alreadySaved == null) playWhenSaved.add(playId)
        if (alreadySaved != null) onSaved(folder, alreadySaved, true)
        startNext()
        return added
    }

    @Synchronized
    fun retry(id: String): Boolean {
        val entry = changes.value.firstOrNull { it.id == id } ?: return false
        if (entry.status !in setOf(WebDownloadStatus.FAILED, WebDownloadStatus.INTERRUPTED)) return false
        if (!entry.canRetry) return false
        commit(replace(entry.copy(status = WebDownloadStatus.WAITING, detail = WebDownloadStatus.WAITING.label)))
        startNext()
        return true
    }

    @Synchronized
    fun cancel(id: String): Boolean {
        val running = active
        if (running?.first == id) {
            running.second.cancel()
            changes.value = changes.value.map { if (it.id == id) it.copy(detail = "Cancelling.") else it }
            return true
        }
        val entry = changes.value.firstOrNull { it.id == id && it.status == WebDownloadStatus.WAITING } ?: return false
        playWhenSaved.remove(id)
        commit(replace(entry.copy(status = WebDownloadStatus.FAILED, detail = "Cancelled.")))
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        if (active?.first == id) return false
        val entry = changes.value.firstOrNull { it.id == id } ?: return false
        removeEntry(entry)
        return true
    }

    private fun removeEntry(entry: WebDownloadEntry) {
        commit(changes.value.filterNot { it.id == entry.id })
        playWhenSaved.remove(entry.id)
        entry.download?.file?.delete()
    }

    private fun startNext() {
        if (active != null) return
        var entry = changes.value.firstOrNull { it.status == WebDownloadStatus.WAITING } ?: return
        val file = audioFile(entry.id)
        val cancel = CancelScope()
        active = entry.id to cancel
        val status = if (entry.download != null) WebDownloadStatus.SAVING else WebDownloadStatus.RUNNING
        changes.value = replace(entry.copy(status = status, detail = status.label))
        worker.launch {
            var lastUpdate = 0L
            var verified = entry.download
            val transfer = runCatching {
                if (verified != null) return@runCatching checkNotNull(verified)
                val progress: (Long, Long?) -> Unit = { bytes, length ->
                    val now = System.nanoTime()
                    if (now - lastUpdate >= 500_000_000 || bytes == length) {
                        lastUpdate = now
                        val detail = when {
                            bytes == length -> "Checking audio."
                            length != null -> "Downloading. ${bytes * 100 / length}%"
                            else -> "Downloading. ${bytes / 1024} KB"
                        }
                        synchronized(this@WebDownloadQueue) {
                            if (!cancel.cancelled) {
                                changes.value = replace(entry.copy(status = WebDownloadStatus.RUNNING, detail = detail))
                            }
                        }
                    }
                }
                var lastFailure: IOException? = null
                for ((index, candidate) in entry.candidates.withIndex()) {
                    cancel.checkActive()
                    entry = entry.copy(provider = candidate.provider)
                    lastUpdate = 0L
                    synchronized(this@WebDownloadQueue) {
                        changes.value = replace(entry.copy(status = WebDownloadStatus.RUNNING, detail = WebDownloadStatus.RUNNING.label))
                    }
                    Files.deleteIfExists(file.toPath())
                    try {
                        val audio = downloadConnected(candidate.file.ref, entry.name, entry.expected, file, cancel, progress)
                        if (audio.format != candidate.file.format)
                            throw WebImportError("The source returned ${audio.format.uppercase()} instead of ${candidate.file.format.uppercase()}.")
                        if (index != 0) audio.recordingCheck.warning?.let { throw WebImportError(it) }
                        return@runCatching audio
                    } catch (failure: IOException) {
                        cancel.checkActive()
                        lastFailure = failure
                    }
                }
                throw checkNotNull(lastFailure)
            }
            val result = transfer.mapCatching { staged ->
                cancel.checkActive()
                synchronized(this@WebDownloadQueue) {
                    verified = staged
                    commit(replace(entry.copy(status = WebDownloadStatus.SAVING, detail = WebDownloadStatus.SAVING.label,
                        download = staged, name = staged.name)))
                }
                publishTrack(entry.folder, staged, cancel)
            }
            synchronized(this@WebDownloadQueue) {
                val published = result.getOrNull()
                val failure = result.exceptionOrNull()
                val updated = if (published != null) {
                    entry.copy(status = WebDownloadStatus.SAVED, download = null, name = checkNotNull(verified).name,
                        savedUri = published, detail = WebDownloadStatus.SAVED.label, canRetry = false,
                        audioFormat = checkNotNull(verified).format)
                } else {
                    if (verified == null) file.delete()
                    entry.copy(status = WebDownloadStatus.FAILED, download = verified, detail = when {
                        cancel.cancelled -> "Cancelled or timed out. Tap Retry to continue."
                        failure is WebImportError -> failure.message.orEmpty()
                        verified != null -> "Could not save in your music folder. Restore folder access and retry."
                        else -> "Could not download this file."
                    })
                }
                try {
                    commit(replace(updated))
                    if (published != null) {
                        file.delete()
                        onSaved(entry.folder, published, playWhenSaved.remove(entry.id))
                    }
                } catch (_: IOException) {
                    if (verified == null) file.delete()
                    changes.value = replace(entry.copy(status = WebDownloadStatus.FAILED, download = verified,
                        canRetry = verified != null || entry.candidates.isNotEmpty(),
                        detail = "Could not save this download in the queue."))
                } finally {
                    if (cancel.cancelled) playWhenSaved.remove(entry.id)
                    active = null
                    startNext()
                }
            }
        }
    }

    private fun audioFile(id: String) = File(directory, "download-$id.stage")

    private fun replace(entry: WebDownloadEntry) = changes.value.map { if (it.id == entry.id) entry else it }

    private fun readEntries(): List<WebDownloadEntry> {
        if (!index.isFile) return emptyList()
        val stored = Properties().apply { index.inputStream().use(::load) }
        return stored.getProperty("order").orEmpty().splitToSequence(',')
            .filter(webDownloadId::matches).distinct().mapNotNull { id ->
                fun get(key: String) = stored.getProperty("$id.$key")
                val name = get("name") ?: "Audio"
                val folder = get("folder") ?: return@mapNotNull null
                val savedUri = get("saved").takeIf { get("status") == "SAVED" }
                val candidates = (0 until (get("sources")?.toIntOrNull()?.coerceIn(0, 8) ?: 0)).mapNotNull source@{ index ->
                    val ref = SourceReference.parse(get("source.$index.ref")) ?: return@source null
                    val provider = get("source.$index.name") ?: return@source null
                    val codec = get("source.$index.format")?.takeIf { it in searchDownloadFormats } ?: return@source null
                    SearchDownloadCandidate(provider, SearchDownloadFile(codec, ref))
                }
                // Older completed rows did not keep a source reference. They only point to a saved local file.
                if (savedUri == null && candidates.isEmpty()) return@mapNotNull null
                val expected = RecordingEvidence(get("title"), get("artist"), get("duration")?.toLongOrNull())
                val provider = get("provider")
                val format = get("format")?.takeIf { it in searchDownloadFormats }
                val file = audioFile(id)
                val staged = if (savedUri == null && format != null && candidates.any { it.provider == provider } &&
                    file.isFile && file.length() in 1..WEB_AUDIO_MAX_BYTES) {
                    val check = RecordingCheck.entries.firstOrNull { it.name == get("check") } ?: RecordingCheck.UNKNOWN
                    StagedWebAudio(file, importedDownloadName(name, format), format, check)
                } else null
                val status = when {
                    savedUri != null -> WebDownloadStatus.SAVED
                    get("status") == "FAILED" -> WebDownloadStatus.FAILED
                    else -> WebDownloadStatus.INTERRUPTED
                }
                val detail = when (status) {
                    WebDownloadStatus.INTERRUPTED -> if (staged != null) "Audio checked. Retry saving to your music folder."
                        else "Interrupted. Retry from the beginning."
                    WebDownloadStatus.FAILED -> get("detail") ?: status.label
                    else -> status.label
                }
                val displayProvider = if (savedUri != null || staged != null) provider else candidates.first().provider
                WebDownloadEntry(id, name, status, expected, savedUri == null, staged, detail, folder, savedUri,
                    get("album"), candidates, displayProvider, get("audioFormat") ?: candidates.firstOrNull()?.file?.format)
            }.take(MAX_SEARCH_DOWNLOADS).toList()
    }

    private fun commit(next: List<WebDownloadEntry>) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot open Downloads")
        val stored = Properties().apply { setProperty("order", next.joinToString(",") { it.id }) }
        for (entry in next) {
            fun put(key: String, value: String?) { if (value != null) stored.setProperty("${entry.id}.$key", value) }
            put("name", entry.name)
            put("status", entry.status.name)
            put("folder", entry.folder)
            put("saved", entry.savedUri)
            put("album", entry.album)
            put("provider", entry.provider)
            put("audioFormat", entry.audioFormat)
            if (entry.candidates.isNotEmpty()) put("sources", entry.candidates.size.toString())
            entry.candidates.forEachIndexed { index, candidate ->
                put("source.$index.name", candidate.provider)
                put("source.$index.format", candidate.file.format)
                put("source.$index.ref", candidate.file.ref.encoded)
            }
            put("title", entry.expected?.title)
            put("artist", entry.expected?.artist)
            put("duration", entry.expected?.durationMillis?.toString())
            put("format", entry.download?.format)
            put("check", entry.download?.recordingCheck?.name)
            if (entry.status == WebDownloadStatus.FAILED) put("detail", entry.detail)
        }
        val temporary = File(directory, "queue.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                stored.store(output, null)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), index.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        changes.value = next
    }

    companion object {
        private const val MAX_SEARCH_DOWNLOADS = 1_000
        private var instance: WebDownloadQueue? = null

        @Synchronized
        fun get(context: Context): WebDownloadQueue = instance ?: run {
            val app = context.applicationContext
            val catalog = SourceCatalog(app)
            WebDownloadQueue(webDownloadDirectory(app),
                publishTrack = { folder, audio, cancel ->
                    WebMusicImport.saveSearchTrack(app, android.net.Uri.parse(folder), audio, cancel).toString()
                },
                savedExists = { saved ->
                    app.contentResolver.query(android.net.Uri.parse(saved),
                        arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                        ?.use { it.moveToFirst() } ?: throw WebImportError("Your music folder is unavailable.")
                },
                onSaved = { folder, saved, play -> app.mainExecutor.execute {
                    com.evanna.player.library.LocalLibrary.scanImportedFolder(app, android.net.Uri.parse(folder))
                    if (play) playSavedDownload(app, saved)
                } },
                downloadConnected = { ref, name, expected, file, cancel, progress ->
                    val media = try {
                        if (catalog.activeConnection(ref.connectionId).kind != SourceKind.JELLYFIN)
                            throw IOException("CONNECTION_LOGIN_REQUIRED")
                        catalog.resolve(ref, cancel, preferOffline = false)
                    } catch (failure: IOException) {
                        if (failure.message == "CONNECTION_LOGIN_REQUIRED") throw WebImportError("Reconnect the Jellyfin source and retry.")
                        throw failure
                    }
                    WebMusicImport.downloadConnected(media, file, name, expected, cancel, progress)
                })
                .also { instance = it }
        }
    }
}
