package com.evanna.player.sources.radio

import android.app.Instrumentation
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.decoder.RadioAudioTrack
import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkPolicy
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

internal object RadioProbe {
    fun run(instrumentation: Instrumentation): String {
        val fixture = instrumentation.context.assets.open("decoder-extended/tone.aac").use { it.readBytes() }
        val local = File.createTempFile("radio-fixture-", ".aac", instrumentation.targetContext.cacheDir)
        val expected = try {
            local.writeBytes(fixture)
            ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                NativeDecoder.open(fd.fd).use { decoder -> digest(decoder.info.channels, decoder::readPcm) }
            }
        } finally {
            local.delete()
        }
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "RadioProbeServer", isDaemon = true) {
                repeat(2) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 2000
                            val request = socket.getInputStream().bufferedReader()
                            check(request.readLine()?.startsWith("GET ") == true)
                            val headers = generateSequence { request.readLine()?.takeIf(String::isNotEmpty) }.toList()
                            check(headers.any { it.equals("Icy-MetaData: 1", true) } &&
                                headers.none { it.startsWith("Range:", true) })
                            val output = socket.getOutputStream().buffered()
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/aac\r\n" +
                                "Transfer-Encoding: chunked\r\nIcy-MetaInt: 4096\r\nConnection: close\r\n\r\n").toByteArray())
                            val metadata = byteArrayOf(1) + "StreamTitle='x';".toByteArray()
                            check(metadata.size == 17)
                            var offset = 0
                            while (offset < fixture.size) {
                                val end = minOf(offset + 4096, fixture.size)
                                val audio = fixture.copyOfRange(offset, end)
                                val payload = if (audio.size == 4096) audio + metadata else audio
                                for (start in payload.indices step 777) {
                                    val count = minOf(777, payload.size - start)
                                    output.write("${count.toString(16)}\r\n".toByteArray())
                                    output.write(payload, start, count)
                                    output.write("\r\n".toByteArray())
                                }
                                offset = end
                            }
                            output.write("0\r\n\r\n".toByteArray())
                            output.flush()
                        }
                    } catch (_: IOException) {
                        // Preparing the track closes its probe as soon as a format is known.
                    }
                }
            }
            try {
                val url = "http://127.0.0.1:${server.localPort}/radio"
                RadioAudioTrack.prepare("fixture", CancelScope()) {
                    ResolvedNetworkMedia(url, mapOf("Icy-MetaData" to "1"), NetworkPolicy(url, true, true))
                }.use { track ->
                    check(!track.seekable && !track.gaplessEligible && track.format.durationUs == null)
                    track.open().use { reader ->
                        check(expected.contentEquals(digest(reader.format.channels, reader::read))) { "Radio changed decoded PCM" }
                    }
                }
            } finally {
                server.close()
                worker.join(2200)
            }
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val signal = CancellationSignal()
        try {
            val opening = executor.submit<String?> {
                started.countDown()
                runCatching { NativeDecoder.openStream(pipe[0].fd, signal).close() }.exceptionOrNull()?.message
            }
            check(started.await(2, TimeUnit.SECONDS))
            check(runCatching { opening.get(100, TimeUnit.MILLISECONDS) }.exceptionOrNull() is TimeoutException)
            signal.cancel()
            check(opening.get(700, TimeUnit.MILLISECONDS) == "DECODER_CANCELLED")
        } finally {
            signal.cancel()
            pipe.forEach { it.close() }
            executor.shutdownNow()
        }
        return "Chunked ICY AAC matches local PCM. Live duration, seeking, and gapless are disabled. A stalled native pipe open cancels promptly."
    }

    private fun digest(channels: Int, read: (ByteBuffer, Int) -> Int): ByteArray {
        val output = ByteBuffer.allocateDirect(4096 * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        val hash = MessageDigest.getInstance("SHA-256")
        var frames = 0
        while (true) {
            val count = try {read(output,4096)}catch(failure:IOException) {
                if(failure.message!="RADIO_STREAM_ENDED")throw failure
                break
            }
            if (count == 0) break
            frames += count
            hash.update(output.duplicate().apply { limit(count * channels * 4) })
        }
        check(frames > 0)
        return hash.digest()
    }
}
