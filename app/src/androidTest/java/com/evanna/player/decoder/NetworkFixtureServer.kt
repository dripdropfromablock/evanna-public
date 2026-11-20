package com.evanna.player.decoder

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Serves test bytes with controllable delay and protocol failures. */
internal class NetworkFixtureServer(
    private val audio: ByteArray,
    address: InetAddress = InetAddress.getByName("127.0.0.1"),
) : Closeable {
    private val server = ServerSocket(0, 8, address)
    val url = URL("http", address.hostAddress, server.localPort, "/generated.flac")
    @Volatile var mode = "normal"
    @Volatile var delayMs = 0L
    @Volatile var entered = CountDownLatch(1)
    @Volatile private var client: Socket? = null
    private val worker = thread(name = "StreamingFixture", isDaemon = true) {
        while (!server.isClosed) {
            try {
                server.accept().use { socket ->
                    client = socket
                    socket.soTimeout = 1500
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val first = input.readLine() ?: return@use
                    check(first.startsWith("GET /generated.flac "))
                    var range: String? = null
                    var complete = false
                    for (header in 0 until 64) {
                        val line = input.readLine() ?: throw IOException("Incomplete headers")
                        if (line.isEmpty()) { complete = true; break }
                        if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                        if (line.length > 8192) throw IOException("Header too long")
                    }
                    if (!complete) throw IOException("Too many headers")
                    val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range ?: "") ?: throw IOException("Range required")
                    val start = match.groupValues[1].toInt()
                    val end = match.groupValues[2].toInt()
                    require(start in audio.indices && end in start..audio.lastIndex)
                    val requestMode = mode
                    if (requestMode == "drop") return@use
                    if (requestMode == "stall") { entered.countDown(); Thread.sleep(1400) }
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val status = if (requestMode == "ignore-range") "200 OK" else if (requestMode == "redirect") "302 Found" else "206 Partial Content"
                    val from = if (requestMode == "wrong-range") start + 1 else start
                    val headers = "HTTP/1.1 $status\r\nContent-Range: bytes $from-$end/${audio.size}\r\n" +
                        "Content-Length: ${end - start + 1}\r\nConnection: close\r\nLocation: http://127.0.0.1:1/unexpected\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII))
                        if (requestMode == "trickle") {
                            flush()
                            for (position in start..end) { write(audio[position].toInt()); flush(); Thread.sleep(100) }
                        } else write(audio, start, if (requestMode == "short") (end - start + 1) / 2 else end - start + 1)
                        flush()
                    }
                }
            } catch (_: IOException) { }
            catch (_: InterruptedException) { if (server.isClosed) return@thread else throw IllegalStateException("Server interrupted") }
            finally { client = null }
        }
    }
    override fun close() {
        server.close()
        client?.close()
        worker.interrupt()
        worker.join(2000)
        check(!worker.isAlive)
    }
}
