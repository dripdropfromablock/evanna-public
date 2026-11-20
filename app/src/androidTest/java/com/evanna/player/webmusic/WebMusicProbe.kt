package com.evanna.player.webmusic

import android.app.Instrumentation
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.evanna.player.network.CancelScope
import kotlinx.coroutines.flow.first
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

internal object WebMusicProbe {
    fun siteLists(instrumentation: Instrumentation): String {
        val file = File.createTempFile("web-site-list-qa-", ".cache", instrumentation.targetContext.cacheDir)
        val address = "https://example.com/sites.json"
        val song = CatalogRecording("qa", "A & B", "Björk", null, null, null)
        val valid = """{"version":1,"sites":[
            {"name":"Public search","search":"https://example.com/find?q={query}","accepts":"name","files":["mp3","zip"]},
            {"name":"Link tool","search":"https://example.com/links","accepts":"link","files":["mp3"]}
        ]}"""
        var manifest: String? = valid
        val store = WebSiteListStore(file) { url, _ ->
            check(url.toString() == address)
            manifest?.encodeToByteArray() ?: throw IOException("Fixture unavailable")
        }
        try {
            val installed = store.update(address, CancelScope())
            check(installed.sites.first().searchUrl(song)?.queryParameter("q") == "A & B Björk")
            check(installed.sites.last().searchUrl(song) == null)
            val saved = file.readBytes()
            for (broken in listOf(null, valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"files\":[\"mp3\",\"zip\"]", "\"script\":{}"),
                valid.replace("Link tool", "PUBLIC SEARCH"), "[".repeat(20_000))) {
                manifest = broken
                check(runCatching { store.update(address, CancelScope()) }.isFailure)
                check(file.readBytes().contentEquals(saved))
                check(WebSiteListStore(file).current() == installed)
            }
            manifest = valid.replace("Public search", "Updated search")
            check(store.update(address, CancelScope()).sites.first().name == "Updated search")
            store.remove()
            check(store.current() == null)
            return "PASS: source-list parsing, typed URLs, refresh, failed-refresh retention, and removal"
        } finally {
            file.delete()
        }
    }

    fun seekStream(instrumentation: Instrumentation): String {
        val url = checkNotNull(httpsWebAddress(
            "https://dn710605.ca.archive.org/0/items/mohawkvalley_rm_librivox/mohawkvalley_00_reid_64kb.mp3"))
        val entry = WebStreams.prepare(instrumentation.targetContext, url, "Preface.mp3", "Evanna playback test", null,
            CancelScope())
        entry.source.create().use { track ->
            check(track.format.codec == "MP3" && track.seekable)
            track.open().use { reader ->
                val pcm = ByteBuffer.allocateDirect(4096 * track.format.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                check(reader.read(pcm, 4096) > 0)
                reader.seekTo(134_275_000)
                pcm.clear()
                check(reader.read(pcm, 4096) > 0)
            }
        }
        return "PASS: Archive MP3 provides decoded PCM before and after seeking to 2:14"
    }

    fun run(instrumentation: Instrumentation, manual: Boolean): String {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, WebMusicProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("controlled", !manual)
        ) as WebMusicProbeActivity
        val reference = java.io.File.createTempFile("web-reference-", ".flac", context.cacheDir)
        try {
            val fixture = instrumentation.context.assets.open("decoder-seek/seek.flac").use { it.readBytes() }
            reference.writeBytes(fixture)
            if (manual) {
                instrumentation.runOnMainSync { activity.importFixture = reference }
                return activity.finished.get(25, java.util.concurrent.TimeUnit.MINUTES)
            }
            val before = context.cacheDir.list().orEmpty().filter { it.startsWith("web-probe-") }.toSet()
                val expected = CaptureProbe.inspect(reference, CancelScope())
                check(expected.frames > 0)
                FixtureServer(fixture).use { server ->
                    fun visit(path: String): CaptureObservation {
                        instrumentation.runOnMainSync { activity.openFixture(server.url(path)) }
                        return checkNotNull(activity.observations.poll(20, SECONDS)) { "No capture event for $path" }
                    }
                    val capture = visit("/page")
                    check(capture.outcome == "decoded") { "Direct capture: ${capture.summary()}" }
                    check(capture.bytes == fixture.size.toLong())
                    check(checkNotNull(capture.file).readBytes().contentEquals(fixture))
                    check(capture.audio == expected) { "Browser capture changed decoded PCM" }
                    check(capture.range == "valid range")
                    fun rendererEvent(path: String): String {
                        instrumentation.runOnMainSync { activity.openFixture(server.url(path)) }
                        val observed = activity.observations.poll(4, SECONDS)?.outcome ?: return "no event"
                        check(observed == "unsupported ${path.drop(1)}")
                        return observed
                    }
                    val blob = rendererEvent("/blob")
                    val data = rendererEvent("/data")
                    check(visit("/html").outcome == "invalid content")
                    check(visit("/short").outcome == "incomplete transfer")
                    check(visit("/truncated").outcome == "invalid audio")
                    fun capturePath(path: String, cancel: CancelScope = CancelScope()) =
                        CaptureProbe.capture(context, CaptureRequest(server.url(path), server.url("/page"), null, null),
                            cancel, allowLoopback = true)
                    val whole = capturePath("/whole")
                    try {
                        check(whole.outcome == "decoded" && whole.range == "full file only")
                    } finally {
                        whole.file?.delete()
                    }
                    for (path in listOf("/bad-range", "/range-login")) {
                        val badRange = capturePath(path)
                        try {
                            check(badRange.outcome == "decoded" && badRange.range == "invalid range response")
                        } finally {
                            badRange.file?.delete()
                        }
                    }
                    for (path in listOf("/auth", "/forbidden")) {
                        check(capturePath(path).outcome == "authorization required or expired")
                    }
                    check(capturePath("/redirect").outcome == "redirect to 127.0.0.1")
                    val cancel = CancelScope()
                    val pending = CompletableFuture.supplyAsync { capturePath("/stall", cancel) }
                    check(server.stalled.await(5, SECONDS))
                    cancel.cancel()
                    check(pending.get(2, SECONDS).outcome == "cancelled")
                    check(context.cacheDir.list().orEmpty().filter { it.startsWith("web-probe-") }.toSet() == before)
                    return "PASS: browser direct audio matches fixture bytes and PCM; blob: $blob; data: $data; " +
                        "HTML and incomplete/corrupt audio rejected; range, authorization, redirect and cancellation checked"
                }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            reference.delete()
        }
    }

    private class FixtureServer(private val audio: ByteArray) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val stalled = CountDownLatch(1)

        init {
            workers.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                    workers.execute handle@{
                        socket.use {
                            try {
                                it.soTimeout = 4000
                                val input = it.getInputStream().bufferedReader()
                                val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@handle
                                val headers = generateSequence(input::readLine).takeWhile { line -> line.isNotEmpty() }
                                    .take(64).toList()
                                val range = headers.any { line -> line.equals("Range: bytes=0-0", true) }
                                val result = reply(path, range)
                                val output = it.getOutputStream()
                                output.write(result.first.toByteArray(Charsets.US_ASCII))
                                output.flush()
                                if (path == "/stall") {
                                    stalled.countDown()
                                    input.read()
                                } else {
                                    output.write(result.second)
                                }
                            } catch (_: java.io.IOException) { }
                        }
                    }
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

        private fun reply(path: String, range: Boolean): Pair<String, ByteArray> {
            fun response(status: String, type: String, data: ByteArray, length: Int = data.size, extra: String = "") =
                "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\n$extra\r\n" to data
            fun page(script: String) = response("200 OK", "text/html",
                "<html><script>onload=()=>{$script}</script></html>".toByteArray())
            val attachment = "Content-Disposition: attachment; filename=\"probe.flac\"\r\n"
            return when (path) {
                "/page" -> page("document.location='/audio'")
                "/blob", "/data" -> page(
                    """const a=document.createElement('a'); a.download='fixture.flac';
                      a.href=${if (path == "/blob") "URL.createObjectURL(new Blob(['test'], {type:'audio/flac'}))"
                        else "'data:audio/flac;base64,dGVzdA=='"};
                      document.body.append(a); a.click();"""
                )
                "/html" -> response("200 OK", "text/html", "<html>Login</html>".toByteArray(), extra = attachment)
                "/short" -> response("200 OK", "audio/flac", audio.copyOf(128), audio.size, attachment)
                "/truncated" -> response("200 OK", "audio/flac", audio.copyOf(audio.size / 2), extra = attachment)
                "/auth" -> response("401 Unauthorized", "text/plain", byteArrayOf())
                "/forbidden" -> response("403 Forbidden", "text/plain", byteArrayOf())
                "/redirect" -> response("302 Found", "text/plain", byteArrayOf(), extra = "Location: /audio\r\n")
                "/stall" -> response("200 OK", "audio/flac", byteArrayOf(), audio.size, attachment)
                "/range-login" -> if (range) response("200 OK", "text/html", "<html>Login</html>".toByteArray())
                    else response("200 OK", "audio/flac", audio, extra = attachment)
                "/audio", "/whole", "/bad-range" -> if (range && path != "/whole") {
                    val end = if (path == "/bad-range") "1-1" else "0-0"
                    response("206 Partial Content", "audio/flac", audio.copyOf(1),
                        extra = "Content-Range: bytes $end/${audio.size}\r\n")
                } else response("200 OK", "audio/flac", audio, extra = attachment)
                else -> response("404 Not Found", "text/plain", byteArrayOf())
            }
        }

        override fun close() {
            server.close()
            workers.shutdownNow()
            check(workers.awaitTermination(5, SECONDS))
        }
    }
}
