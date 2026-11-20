package com.evanna.player.decoder

import android.app.Instrumentation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.evanna.player.equalizer.SoundConfig
import com.evanna.player.equalizer.SoundSettings
import com.evanna.player.playback.*
import com.truesample.usbaudio.*
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Account-free spike: fixture audio, loopback faults or internet HTTPS, synthetic USB; never opens hardware. */
internal object NetworkStreamingProbe {
    fun run(instrumentation: Instrumentation, internet: Boolean = false): String {
        val context = instrumentation.targetContext
        check(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        check(runCatching {com.evanna.player.network.NetworkPolicy("http://example.com",true).resolve("93.184.215.14")}.isFailure)
        for (forbidden in listOf("http://example.com/audio", "https://user:secret@raw.githubusercontent.com/audio", "https://raw.githubusercontent.com:444/audio")) {
            check(runCatching { HttpRangeProxy(context, URL(forbidden), 1).close() }.exceptionOrNull() is IllegalArgumentException)
        }
        val remoteUrl = URL("https://raw.githubusercontent.com/ietf-wg-cellar/flac-test-files/aa7b0c6cf32994c106ae517a08134c28a96ff5b2/subset/01%20-%20blocksize%204096.flac")
        val bytes = if (internet) downloadReference(remoteUrl) else
            instrumentation.context.assets.open("decoder-seek/seek.flac").use { it.readBytes() }
        val file = File.createTempFile("network-reference-", ".flac", context.cacheDir).apply { writeBytes(bytes) }
        fun local() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) }
        val info = local().use { it.info }
        var startupBytes = 0L
        var cancellationMs = 0L
        var pauseCancellationMs = 0L
        val buffer = ByteBuffer.allocateDirect(4096 * info.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        fun read(decoder: NativeDecoder, frames: Int = 4096): ByteArray {
            val count = decoder.readPcm(buffer, frames)
            return ByteArray(count * info.channels * 4).also { buffer.duplicate().get(it) }
        }
        fun digest(decoder: NativeDecoder): String {
            val hash = MessageDigest.getInstance("SHA-256")
            while (true) { val pcm = read(decoder); if (pcm.isEmpty()) break; hash.update(pcm) }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun stage(text: String) = instrumentation.sendStatus(1, android.os.Bundle().apply { putString("progress", text) })
        try {
            NetworkFixtureServer(bytes).use { server ->
                val mediaUrl = if (internet) remoteUrl else server.url
                stage("Comparing streamed FLAC to local decode, including seeks and EOF")
                val expected = local().use(::digest)
                HttpRangeProxy(context, mediaUrl, bytes.size.toLong()).use { source ->
                    NativeDecoder.open(source.descriptor.fd).use { remote ->
                        check(remote.info.sampleRate == info.sampleRate && remote.info.significantBits == info.significantBits)
                        check(remote.info.channels == info.channels && remote.info.lossless)
                        check(read(remote).isNotEmpty())
                        startupBytes = source.fetched.get()
                        check(source.fetched.get() < bytes.size) { "Playback required the complete download" }
                        stage("Playback started after $startupBytes/${bytes.size} compressed bytes; ${info.sampleRate} Hz/${info.significantBits} bit")
                        remote.seekTo(0)
                        check(digest(remote) == expected) { "Network decode changed PCM" }
                        local().use { reference ->
                            for (position in listOf(2_000_000L, checkNotNull(info.durationUs) * 3 / 4, 250_000L)) {
                                remote.seekTo(position); reference.seekTo(position)
                                check(read(remote).contentEquals(read(reference))) { "Network seek mismatch at $position" }
                            }
                        }
                        check(source.maxCacheBytes <= 64 * 1024)
                        check(source.requests.get() > 1)
                    }
                }
                stage("Checking delayed reads, invalid responses, cancellation and release")
                server.delayMs = 100
                HttpRangeProxy(context, server.url, bytes.size.toLong()).use { source ->
                    NativeDecoder.open(source.descriptor.fd).use { check(read(it).isNotEmpty()) }
                }
                server.delayMs = 0
                for (mode in listOf("ignore-range", "wrong-range", "short", "redirect", "stall", "trickle")) {
                    server.mode = mode
                    HttpRangeProxy(context, server.url, bytes.size.toLong()).use { source ->
                        val started = SystemClock.elapsedRealtime()
                        val failure = runCatching { NativeDecoder.open(source.descriptor.fd).use { read(it) } }.exceptionOrNull()
                        check(failure is IOException) { "Accepted $mode response: $failure" }
                        check(SystemClock.elapsedRealtime() - started < 3500) { "Unbounded $mode request" }
                    }
                }
                server.mode = "normal"
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val source = HttpRangeProxy(context, server.url, bytes.size.toLong())
                    try {
                        server.entered = CountDownLatch(1)
                        server.mode = "stall"
                        val pending = executor.submit<Boolean> {
                            runCatching { NativeDecoder.open(source.descriptor.fd).use { read(it) } }.isFailure
                        }
                        check(server.entered.await(3, TimeUnit.SECONDS))
                        val start = SystemClock.elapsedRealtime()
                        source.cancel()
                        check(pending.get(650, TimeUnit.MILLISECONDS)) { "Cancelled network read succeeded" }
                        cancellationMs = SystemClock.elapsedRealtime() - start
                        check(cancellationMs < 650) { "Cancel waited for the ordinary one-second timeout" }
                    } finally { source.close(); source.close(); server.mode = "normal" }
                } finally { executor.shutdownNow(); check(executor.awaitTermination(3, TimeUnit.SECONDS)) }
                // Wait until the deliberately stalled fixture request has left the single server thread.
                Thread.sleep(1450)
                val mismatch = NetworkFixtureTrack(context, server.url, bytes.size.toLong(), info.copy(sampleRate = 48000))
                val rejected = runCatching { mismatch.open().close() }.exceptionOrNull()
                check(rejected is IllegalStateException && rejected.message == "Network format changed") {
                    "Changed format was accepted or leaked its decoder/proxy: $rejected"
                }
                stage("Checking existing EQ/USB controller with synthetic transport; no hardware audio")
                val track = NetworkFixtureTrack(context, mediaUrl, bytes.size.toLong(), info)
                val format = track.format
                val output = CaptureOutput()
                val controller = UsbPlaybackController { output }
                val savedSound = SoundSettings.config.value
                val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
                val hadConfig = prefs.contains("config")
                val savedConfig = prefs.getString("config", null)
                try {
                    SoundSettings.update { SoundConfig() }
                    runBlocking {
                        suspend fun await(check: () -> Boolean) = withTimeout(10000) { while (!check()) delay(20) }
                        check(controller.open(7))
                        check(controller.start(track))
                        await { output.completed.get() > format.sampleRate / 10 }
                        check(controller.snapshot.value.route == PlaybackRoute.DIRECT_USB)
                        check(!controller.snapshot.value.eqProcessed)
                        val raw = local().use { read(it, format.sampleRate / 100) }
                        check(checkNotNull(output.first.get()).contentEquals(raw)) { "EQ-off samples changed" }
                        check(controller.pause())
                        val paused = output.completed.get()
                        delay(150)
                        check(output.completed.get() == paused) { "Pause left the writer running" }
                        SoundSettings.update { SoundConfig(enabled = true, preampDb = -6f) }
                        check(controller.start(track, positionUs = 2_000_000))
                        await { output.completed.get() > format.sampleRate / 10 && controller.snapshot.value.eqProcessed }
                        val reference = local().use { it.seekTo(2_000_000); read(it, format.sampleRate / 100) }
                        val changed = checkNotNull(output.first.get())
                        fun energy(data: ByteArray): Long = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).let { pcm ->
                            var total = 0L; while (pcm.hasRemaining()) total += abs(pcm.int.toLong()); total
                        }
                        val ratio = energy(changed).toDouble() / energy(reference)
                        check(ratio in 0.45..0.55) { "EQ did not attenuate streamed PCM: $ratio" }
                        check(controller.pause())
                        stage("Checking active-source connection loss, fresh retry and pause during blocked reads")
                        val faultTrack = NetworkFixtureTrack(context, server.url, bytes.size.toLong(), info)
                        SoundSettings.update { SoundConfig() }
                        server.delayMs = 100
                        check(controller.start(faultTrack))
                        await { output.completed.get() > format.sampleRate / 10 }
                        val activeSource = checkNotNull(faultTrack.lastSource)
                        val initialRequests = activeSource.requests.get()
                        await { activeSource.requests.get() > initialRequests }
                        val bufferingAt = output.completed.get()
                        await { output.completed.get() > bufferingAt + format.sampleRate / 10 }
                        check(controller.snapshot.value.state == UsbPlaybackState.PLAYING)
                        server.delayMs = 0
                        server.mode = "drop"
                        await { controller.snapshot.value.state == UsbPlaybackState.ERROR }
                        check(controller.snapshot.value.failure == UsbPlaybackFailure.SOURCE_READ_FAILED)
                        check(controller.snapshot.value.route == PlaybackRoute.DIRECT_USB && controller.snapshot.value.androidStream == null)
                        val lostAt = output.completed.get()
                        delay(150)
                        check(output.completed.get() == lostAt) { "Connection loss left the writer running" }
                        server.mode = "normal"
                        check(controller.start(faultTrack))
                        await { output.completed.get() > format.sampleRate / 10 }
                        server.entered = CountDownLatch(1)
                        server.mode = "stall"
                        check(server.entered.await(5, TimeUnit.SECONDS)) { "No active network read to cancel" }
                        val pauseAt = SystemClock.elapsedRealtime()
                        check(controller.pause())
                        pauseCancellationMs = SystemClock.elapsedRealtime() - pauseAt
                        check(pauseCancellationMs < 650) { "Pause waited for the ordinary network timeout" }
                        val pausedAt = output.completed.get()
                        delay(150)
                        check(controller.snapshot.value.state == UsbPlaybackState.PAUSED && output.completed.get() == pausedAt)
                        server.mode = "normal"
                        delay(1450)
                        check(controller.start(faultTrack))
                        await { output.completed.get() > format.sampleRate / 10 }
                        output.disconnected = true
                        await { controller.snapshot.value.state == UsbPlaybackState.ERROR }
                        check(controller.snapshot.value.failure == UsbPlaybackFailure.DISCONNECTED && controller.snapshot.value.androidStream == null)
                        val stopped = output.completed.get()
                        delay(150)
                        check(output.completed.get() == stopped) { "Disconnect did not stop the writer" }
                    }
                } finally {
                    try { check(runBlocking { controller.close() }) { "Controller failed to close" } }
                    finally {
                        track.close()
                        try { SoundSettings.update { savedSound } }
                        finally {
                            val restore = prefs.edit()
                            if (hadConfig) restore.putString("config", savedConfig) else restore.remove("config")
                            check(restore.commit()) { "EQ config restore failed" }
                        }
                    }
                }
            }
        } finally { check(file.delete()) }
        return "PASS: ${if (internet) "Internet HTTPS" else "Loopback HTTP"} FLAC starts after $startupBytes/${bytes.size} compressed bytes; PCM/seek match local; 64KiB cache; delayed reads and active buffering; invalid/short/redirect/stall/trickle responses rejected; cancellation ${cancellationMs}ms; proxy release confirmed; EQ-off PCM exact and EQ-on attenuated; connection loss stops without fallback; explicit fresh retry works; blocked-read pause ${pauseCancellationMs}ms; pause/resume/seek and simulated USB disconnect stop correctly. No physical DAC or music service tested."
    }

    private fun downloadReference(url: URL): ByteArray {
        val request = url.openConnection() as HttpsURLConnection
        request.connectTimeout = 5000; request.readTimeout = 5000
        request.instanceFollowRedirects = false; request.useCaches = false
        try {
            check(request.responseCode == 200 && request.contentLengthLong == 549954L)
            val bytes = ByteArray(549954)
            val deadline = SystemClock.elapsedRealtime() + 15000
            request.inputStream.use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Reference download timed out" }
                    val count = input.read(bytes, offset, bytes.size - offset)
                    check(count > 0) { "Truncated HTTPS reference" }; offset += count
                }
                check(input.read() == -1)
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash == "02c7e60ae7788c2cb6898a0a46985d759f1e62482ddd965a374dfd09a2a8390f") { "HTTPS reference changed" }
            return bytes
        } finally { request.disconnect() }
    }

    private class CaptureOutput : UsbPlaybackOutput {
        override val description = "Synthetic network spike USB output"
        val completed = AtomicLong()
        val first = AtomicReference<ByteArray?>()
        @Volatile var disconnected = false
        @Volatile private var running = false
        override val usbVolume = VolumeRange(-60f, 0f, 1f, -60f)
        override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, source.significantBits, false, true)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            completed.set(0); first.set(null); running = true
            return StreamInfo(source.sampleRate, source.significantBits, source.channels,
                DacFormat(1, 1, 1, source.channels, source.significantBits / 8, source.significantBits,
                    DacFormat.SyncType.SYNC, 1024, listOf(source.sampleRate), source.sampleRate, source.sampleRate),
                source.sampleRate, source.sampleRate, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(!disconnected, !disconnected, true)
        override fun stats() = StreamStats(completed.get(), 0, 0, disconnected, completed.get(), completed.get())
        override fun write(buffer: ByteBuffer, frames: Int): Int {
            Thread.sleep(10)
            if (!running || disconnected) return -1
            first.compareAndSet(null, ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) })
            completed.addAndGet(frames.toLong())
            return frames
        }
        override fun drain() = true
        override fun stop(): Boolean { running = false; return true }
        override fun close() { running = false }
    }
}
