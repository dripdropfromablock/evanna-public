package com.evanna.player.casting

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.decoder.isLocalDocument
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.hasTreeReadGrant
import com.evanna.player.network.CancelScope
import com.evanna.player.playback.QueueSource
import com.evanna.player.sources.SourceCatalog
import com.evanna.player.sources.SourceKind
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun relayRange(header: String?, length: Long): LongRange? {
    if (length <= 0) return null
    if (header == null) return 0L until length
    val match = Regex("bytes=([0-9]{0,19})-([0-9]{0,19})", RegexOption.IGNORE_CASE).matchEntire(header) ?: return null
    val (start, end) = match.destructured
    if (start.isEmpty()) {
        val suffix = end.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return (length - minOf(length, suffix)) until length
    }
    val first = start.toLongOrNull()?.takeIf { it < length } ?: return null
    val last = if (end.isEmpty()) length - 1 else minOf(end.toLongOrNull() ?: return null, length - 1)
    return (first..last).takeUnless { it.isEmpty() }
}

/** Opens one read-only byte source. HTTP requests never name files or upstream URLs. */
internal class PrivateTrackRelay private constructor(
    bind: InetAddress, private val peer: InetAddress, val mime: String, val durationMs: Long,
    private val contentLength: Long, private val source: Closeable, private val cancel: CancelScope,
    private val read: (Long, ByteArray, Int) -> Int,
) : Closeable {
    private val token = "/" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
    private val listener = ServerSocket().apply {
        try { soTimeout = 250; bind(InetSocketAddress(bind, 0), 4) }
        catch (failure: Exception) { close(); throw failure }
    }
    private val address = "${bind.hostAddress}:${listener.localPort}"
    val uri = "http://$address$token"
    private val connections = ConcurrentHashMap<Socket, Long>()
    private val closed = AtomicBoolean()
    @Volatile private var readFailed = false
    private val workers = ThreadPoolExecutor(0, 4, 15, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "Evanna relay request").apply { isDaemon = true }
    }
    // shortcut: each item lease is at most 12 hours, until long-form playback is supported.
    private val expiresAt = SystemClock.elapsedRealtime() + 12L * 60 * 60 * 1000
    val healthy get() = !closed.get() && !readFailed && !cancel.cancelled && SystemClock.elapsedRealtime() < expiresAt

    private val server = Thread({
        try {
            while (healthy) {
                val now = SystemClock.elapsedRealtime()
                connections.forEach { (socket, deadline) -> if (now >= deadline) runCatching { socket.close() } }
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                if (!healthy || socket.inetAddress != peer) { socket.close(); continue }
                // Reap stalled clients while allowing a large file to stream continuously.
                connections[socket] = now + REQUEST_TIMEOUT_MS
                try {
                    workers.execute {
                        try { socket.use(::serve) } catch (_: Exception) { }
                        finally { connections.remove(socket) }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    connections.remove(socket)
                    socket.close()
                }
            }
        } catch (_: IOException) {
            // Closing the listener wakes accept(), including when the session is revoked normally.
        } finally {
            revoke()
        }
    }, "Evanna relay listener").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3000
        val input = BufferedInputStream(socket.getInputStream())
        var remaining = 8192
        fun line(): String {
            val result = StringBuilder()
            while (remaining-- > 0) {
                val byte = input.read()
                if (byte == 13) {
                    require(remaining-- > 0 && input.read() == 10)
                    return result.toString()
                }
                require(byte in 32..126 || byte == 9)
                result.append(byte.toChar())
            }
            throw IOException("RELAY_HEADERS_TOO_LARGE")
        }
        val request = line().split(' ')
        if (request.size != 3 || request[2] !in setOf("HTTP/1.1", "HTTP/1.0")) return
        val method = request[0]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val value = line()
            if (value.isEmpty()) break
            val parts = value.split(':', limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9_-]+")))
            require(headers.put(parts[0].lowercase(), parts[1].trim()) == null)
        }
        val range = relayRange(headers["range"], contentLength)
        val status = when {
            !healthy || request[1] != token -> "404 Not Found"
            headers["host"]?.let { it != address } == true || request[2] == "HTTP/1.1" && headers["host"] == null ||
                "transfer-encoding" in headers || headers["content-length"]?.let { it != "0" } == true -> "400 Bad Request"
            method != "GET" && method != "HEAD" -> "405 Method Not Allowed"
            range == null -> "416 Range Not Satisfiable"
            "range" in headers -> "206 Partial Content"
            else -> "200 OK"
        }
        val bodyRange = if (status.startsWith("20")) range else null
        val count = if (bodyRange == null) 0 else bodyRange.last - bodyRange.first + 1
        val output = socket.getOutputStream()
        val response = buildString {
            append("HTTP/1.1 $status\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Length: $count\r\n")
            if (bodyRange != null) {
                append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\n")
                if (status.startsWith("206")) append("Content-Range: bytes ${bodyRange.first}-${bodyRange.last}/$contentLength\r\n")
            }
            if (status.startsWith("416")) append("Content-Range: bytes */$contentLength\r\n")
            if (status.startsWith("405")) append("Allow: GET, HEAD\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.US_ASCII))
        if (method != "GET" || bodyRange == null) return
        var offset = bodyRange.first
        val buffer = ByteArray(64 * 1024)
        while (healthy && offset <= bodyRange.last) {
            val wanted = minOf(buffer.size.toLong(), bodyRange.last + 1 - offset).toInt()
            val read = try { read(offset, buffer, wanted).also { require(it in 1..wanted) } }
                catch (failure: Exception) { readFailed = true; throw failure }
            output.write(buffer, 0, read)
            offset += read
            connections.computeIfPresent(socket) { _, _ -> SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS }
        }
    }

    fun revoke() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        connections.keys.forEach { runCatching { it.close() } }
        cancel.cancel()
        workers.shutdownNow()
        runCatching { source.close() }
    }

    override fun close() {
        revoke()
        if (Thread.currentThread() != server) server.join(1000)
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 15_000L

        fun open(context: Context, client: DlnaRendererClient, item: QueueSource, prepare: CancelScope): PrivateTrackRelay {
            val link = checkNotNull(client.link)
            link.checkActive()
            val cancel = CancelScope()
            val unlink = prepare.watch { cancel.cancel() }
            var source: Closeable? = null
            try {
                val media = item.sourceRef?.let { ref ->
                    val catalog = SourceCatalog(context)
                    require(catalog.store.connection(ref.connectionId)?.kind != SourceKind.RADIO) { "RENDERER_FINITE_MEDIA_REQUIRED" }
                    catalog.resolve(ref, cancel).open(context, cancel)
                }
                source = media
                val file = media?.descriptor ?: localFile(context, item).also { source = it }
                cancel.checkActive()
                val fd = file.fileDescriptor
                val length = Os.lseek(fd, 0, OsConstants.SEEK_END)
                require(length > 0) { "RENDERER_FINITE_MEDIA_REQUIRED" }
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
                val header = ByteArray(minOf(length, 64).toInt())
                val count = Os.pread(fd, header, 0, header.size, 0)
                val info = NativeDecoder.open(file.fd).use { it.info }
                require(info.seekable && (info.durationUs ?: 0) > 0) { "RENDERER_FINITE_MEDIA_REQUIRED" }
                val mime = client.mimeFor(mediaTypes(header, count, info.codec)) ?: throw IOException("RENDERER_FORMAT_UNSUPPORTED")
                cancel.checkActive()
                link.checkActive()
                val relay = PrivateTrackRelay(link.local, link.peer, mime, checkNotNull(info.durationUs) / 1000,
                    length, checkNotNull(source), cancel) { offset, buffer, wanted ->
                    cancel.checkActive()
                    Os.pread(fd, buffer, 0, wanted, offset)
                }
                source = null
                return relay
            } finally {
                unlink()
                source?.let {
                    cancel.cancel()
                    runCatching { it.close() }
                }
            }
        }

        internal fun fixture(bind: InetAddress, bytes: ByteArray) = PrivateTrackRelay(bind, bind, "audio/flac", 100_000,
            bytes.size.toLong(), Closeable {}, CancelScope()) { offset, buffer, wanted ->
            bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + wanted)
            wanted
        }

        private fun localFile(context: Context, item: QueueSource): ParcelFileDescriptor {
            val libraryId = item.libraryId
            val uri = if (libraryId != null) {
                val db = LocalLibrary.database(context)
                val song = db.song(libraryId)?.takeIf { it.available && it.startFrame == null && it.endFrame == null }
                    ?: throw IOException("RENDERER_LOCAL_ITEM_UNAVAILABLE")
                val place = db.songPlace(libraryId) ?: throw IOException("RENDERER_LOCAL_ITEM_UNAVAILABLE")
                require(hasTreeReadGrant(context, Uri.parse(place.root)))
                Uri.parse(song.uri)
            } else {
                val selected = item.fileUri?.let(Uri::parse) ?: throw IOException("RENDERER_LOCAL_ITEM_UNAVAILABLE")
                require(isLocalDocument(context, selected))
                selected
            }
            return context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("RENDERER_LOCAL_ITEM_UNAVAILABLE")
        }

        private fun mediaTypes(header: ByteArray, count: Int, codec: String): List<String> {
            fun prefix(text: String, at: Int = 0): Boolean =
                count >= at + text.length && text.indices.all { header[at + it] == text[it].code.toByte() }
            return when {
                prefix("fLaC") -> listOf("audio/flac", "audio/x-flac")
                (prefix("RIFF") || prefix("RF64")) && prefix("WAVE", 8) -> listOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
                prefix("OggS") -> listOf("audio/ogg", "application/ogg")
                prefix("ftyp", 4) -> listOf("audio/mp4", "audio/x-m4a")
                prefix("FORM") && (prefix("AIFF", 8) || prefix("AIFC", 8)) -> listOf("audio/aiff", "audio/x-aiff")
                prefix("DSD ") -> listOf("audio/dsf", "audio/x-dsf")
                prefix("FRM8") && prefix("DSD ", 12) -> listOf("audio/dff", "audio/x-dff")
                codec == "mp3" -> listOf("audio/mpeg")
                codec == "aac" && count >= 2 && header[0].toInt() and 0xff == 0xff && header[1].toInt() and 0xf6 == 0xf0 ->
                    listOf("audio/aac", "audio/aacp")
                else -> emptyList()
            }
        }
    }
}
