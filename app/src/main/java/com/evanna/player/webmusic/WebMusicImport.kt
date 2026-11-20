package com.evanna.player.webmusic

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.library.isLocalTree
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val WEB_AUDIO_MAX_BYTES = 64L * 1024 * 1024
private val supportedFormats = mapOf("flac" to "audio/flac", "mp3" to "audio/mpeg")
private val forbiddenNameCharacters = Regex("""[\p{Cc}\p{Cf}<>:"|?*]""")

internal data class StagedWebAudio(
    val file: File, val name: String, val format: String, val recordingCheck: RecordingCheck,
)

internal class WebImportError(message: String) : IOException(message)

internal fun httpsWebAddress(value: String): HttpUrl? = value.toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty()
}

internal fun webAudioCodec(signature: ByteArray): String? {
    if (signature.size < 4) return null
    return when {
        signature.contentEquals("fLaC".encodeToByteArray()) -> "flac"
        signature.copyOf(3).contentEquals("ID3".encodeToByteArray()) ||
            (signature[0].toInt() and 255) == 255 && (signature[1].toInt() and 224) == 224 -> "mp3"
        else -> null
    }
}

internal fun webRequestHeaders(agent: String, cookie: String?): Map<String, String> = buildMap {
    put("User-Agent", agent)
    if (!cookie.isNullOrBlank() && cookie.length <= 8192) put("Cookie", cookie)
}

internal object WebMusicImport {
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "WebImportTimeout").apply { isDaemon = true }
    }

    fun downloadConnected(media: ResolvedNetworkMedia, file: File, name: String, expected: RecordingEvidence?,
                          cancel: CancelScope, progress: (Long, Long?) -> Unit): StagedWebAudio {
        val timer = deadlines.schedule({ cancel.cancel() }, 10, TimeUnit.MINUTES)
        var complete = false
        try {
            val signature = ByteArray(4)
            val access = HttpAccess(checkNotNull(media.policy))
            access.openStream(checkNotNull(media.url), media.headers, cancel).use { response ->
                when (response.code) {
                    200 -> Unit
                    401, 403 -> throw WebImportError("Reconnect the music server and retry.")
                    in 300..399 -> throw WebImportError("The music server redirected the audio request.")
                    else -> throw WebImportError("The music server could not provide a download (HTTP ${response.code}).")
                }
                val mime = response.headers["Content-Type"].orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("xml")) {
                    throw WebImportError("The music server returned a webpage instead of audio.")
                }
                val length = response.headers["Content-Length"]?.toLongOrNull()
                val input = BufferedInputStream(response.input)
                input.mark(signature.size)
                try { DataInputStream(input).readFully(signature) } catch (_: EOFException) {
                    throw WebImportError("The download ended before the whole file arrived.")
                }
                input.reset()
                if (webAudioCodec(signature) == null) throw WebImportError("The server did not return MP3 or FLAC audio.")
                if (length != null && length !in 1..WEB_AUDIO_MAX_BYTES)
                    throw WebImportError("This track exceeds the 64 MiB download limit.")
                val received = FileOutputStream(file).use { output ->
                    val bytes = copyAudio(input, output, cancel, WEB_AUDIO_MAX_BYTES) { progress(it, length) }
                    output.fd.sync()
                    bytes
                }
                if (received == 0L || length != null && received != length) {
                    throw WebImportError("The download ended before the whole file arrived.")
                }
            }
            val download = stageAudio(file, name, expected, cancel)
            cancel.checkActive()
            complete = true
            return download
        } finally {
            timer.cancel(false)
            if (!complete) file.delete()
        }
    }

    fun stageAudio(file: File, name: String, expected: RecordingEvidence?, cancel: CancelScope): StagedWebAudio {
        val (format, recordingCheck) = try {
            verifyAudio(file, expected, cancel)
        } catch (failure: Exception) {
            cancel.checkActive()
            throw failure as? WebImportError ?: WebImportError("This file could not be decoded as a complete MP3 or FLAC.")
        }
        return StagedWebAudio(file, importedDownloadName(name, format), format, recordingCheck)
    }

    @Synchronized
    fun saveSearchTrack(context: Context, folder: Uri, audio: StagedWebAudio, cancel: CancelScope): Uri {
        if (!isLocalTree(context, folder)) throw WebImportError("Choose a local folder on this phone or SD card.")
        val resolver = context.contentResolver
        val id = DocumentsContract.getTreeDocumentId(folder)
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, id)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, id)
        val names = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.count > 100000 || cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
                throw WebImportError("The folder could not be read completely. Choose a different folder.")
            val taken = hashMapOf<String, Pair<String, Long?>>()
            while (cursor.moveToNext()) {
                cancel.checkActive()
                cursor.getString(0)?.let { name ->
                    taken[name.lowercase(Locale.ROOT)] = cursor.getString(1) to
                        (if (cursor.isNull(2)) null else cursor.getLong(2))
                }
            }
            taken
        } ?: throw WebImportError("This folder is unavailable. Choose a different folder.")
        val name = importedDownloadName(audio.name, audio.format, names.keys)
        // A process can stop after the SAF rename but before the queue commits its receipt.
        val base = importedDownloadName(audio.name, audio.format)
        val numbered = Regex(Regex.escape(base.substringBeforeLast('.')) + """ \(\d+\)\.""" + audio.format,
            RegexOption.IGNORE_CASE)
        val digest by lazy { audio.file.inputStream().use { digestOf(it, cancel) } }
        for ((existing, document) in names) {
            if (!existing.equals(base, ignoreCase = true) && !numbered.matches(existing)) continue
            if (document.second != null && document.second != audio.file.length()) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(folder, document.first)
            val hash = resolver.openInputStream(uri)?.use { digestOf(it, cancel) }
            if (hash != null && digest.contentEquals(hash)) return uri
        }
        cancel.checkActive()
        val partial = DocumentsContract.createDocument(resolver, parent, "application/octet-stream",
            ".evanna-${UUID.randomUUID()}.part") ?: throw IOException("Could not create document")
        lateinit var published: Uri
        publishVerifiedAudio(audio.file::inputStream,
            { resolver.openOutputStream(partial, "w") ?: throw IOException("Cannot write document") },
            { resolver.openInputStream(partial) ?: throw IOException("Cannot read document") },
            cancel,
            publish = { published = checkNotNull(DocumentsContract.renameDocument(resolver, partial, name)) },
            discard = { DocumentsContract.deleteDocument(resolver, partial) }
        )
        return published
    }

    private fun verifyAudio(file: File, expectedRecording: RecordingEvidence?,
                            cancel: CancelScope): Pair<String, RecordingCheck> {
        val signature = ByteArray(4)
        DataInputStream(file.inputStream()).use { it.readFully(signature) }
        val codec = webAudioCodec(signature) ?: throw WebImportError("Choose a standalone MP3 or FLAC file.")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            NativeDecoder.open(fd.fd).use { decoder ->
                val info = decoder.info
                if (codec != info.codec) throw WebImportError("Choose a standalone MP3 or FLAC file.")
                val stop = cancel.watch(decoder::requestCancel)
                try {
                    val pcm = ByteBuffer.allocateDirect(4096 * info.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    var frames = 0L
                    while (true) {
                        cancel.checkActive()
                        val count = decoder.readPcm(pcm, 4096)
                        if (count == 0) break
                        frames += count
                        pcm.clear()
                    }
                    check(frames > 0)
                    val actual = RecordingEvidence(info.title, info.artist, frames * 1_000 / info.sampleRate)
                    info.codec to checkRecording(expectedRecording, actual)
                } finally {
                    stop()
                }
            }
        }
    }
}

private fun digestOf(input: InputStream, cancel: CancelScope): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    copyAudio(input, null, cancel, WEB_AUDIO_MAX_BYTES, digest)
    return digest.digest()
}

internal fun importedDownloadName(source: String, format: String, taken: Set<String> = emptySet()): String {
    require(format in supportedFormats)
    val suggested = source.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    val stem = suggested.replace(forbiddenNameCharacters, "").trim(' ', '.').take(70).trimEnd(' ', '.')
        .ifEmpty { "Audio" }
    var name = "$stem.$format"
    var suffix = 2
    while (name.lowercase(Locale.ROOT) in taken) name = "$stem (${suffix++}).$format"
    return name
}

internal fun publishVerifiedAudio(source: () -> InputStream, target: () -> OutputStream,
                                  readBack: () -> InputStream, cancel: CancelScope,
                                  publish: () -> Unit, discard: () -> Unit) {
    var committed = false
    try {
        cancel.checkActive()
        val written = MessageDigest.getInstance("SHA-256")
        val length = source().use { input -> target().use { output ->
            copyAudio(input, output, cancel, WEB_AUDIO_MAX_BYTES, digest = written)
        } }
        val copied = MessageDigest.getInstance("SHA-256")
        val copiedLength = readBack().use { copyAudio(it, null, cancel, length, digest = copied) }
        if (length == 0L || copiedLength != length || !written.digest().contentEquals(copied.digest())) {
            throw IOException("The saved copy did not match the original")
        }
        cancel.checkActive()
        publish()
        committed = true
    } finally {
        if (!committed) runCatching(discard)
    }
}

private fun copyAudio(input: InputStream, output: OutputStream?, cancel: CancelScope, limit: Long,
                      digest: MessageDigest? = null, progress: (Long) -> Unit = {}): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        cancel.checkActive()
        val count = input.read(buffer)
        if (count < 0) return total
        total += count
        if (total > limit) throw WebImportError("The file exceeds the expected size.")
        output?.write(buffer, 0, count)
        digest?.update(buffer, 0, count)
        progress(total)
    }
}
