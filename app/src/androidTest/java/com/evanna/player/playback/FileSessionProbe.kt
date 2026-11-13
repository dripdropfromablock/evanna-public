package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.evanna.player.decoder.DecodedAudioTrack
import com.evanna.player.decoder.NativeDecoder
import com.truesample.usbaudio.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/** Real decoder/controller/Media3 with a synthetic sink; never opens a physical output. */
@UnstableApi
internal object FileSessionProbe {
    /** Run only after selecting our generated four-frame fixture in the real app picker. */
    fun runGrant(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val leases = context.getSharedPreferences("local-source-grants", 0)
        fun key(uri: android.net.Uri) = "g_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val owned = resolver.persistedUriPermissions.filter { it.isReadPermission && leases.getBoolean(key(it.uri), false) }
        check(owned.size == 1) { "Expected one selected test-file lease; owned=${owned.size}, persisted=${resolver.persistedUriPermissions.size}, leaseKeys=${leases.all.size}" }
        val permission = owned.single()
        val otherGrants = resolver.persistedUriPermissions.filter { !leases.getBoolean(key(it.uri), false) }.map { it.uri }.toSet()
        resolver.openFileDescriptor(permission.uri, "r")!!.use { fd -> NativeDecoder.open(fd.fd).use { decoder ->
            check(decoder.info.sampleRate == 44100 && decoder.info.significantBits == 24 && decoder.info.channels == 2 && decoder.info.durationUs == 91L) {
                "Select the generated four-frame FLAC fixture before running this probe"
            }
        } }
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val control = future!!.get(10, TimeUnit.SECONDS)
        try {
            var selected: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
            main { selected = control.sendCustomCommand(SessionCommand(PlaybackService.SELECT_FILE, Bundle.EMPTY),
                Bundle().apply { putString(PlaybackService.FILE_URI, permission.uri.toString()) }) }
            check(selected!!.get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
            await(main) { control.mediaItemCount == 1 }
            main {
                check(!control.isPlaying && !control.playWhenReady && control.currentMediaItem?.localConfiguration == null)
                control.stop()
            }
            await(main) { control.mediaItemCount == 0 }
            check(resolver.persistedUriPermissions.none { it.uri == permission.uri && it.isReadPermission })
            check(!leases.contains(key(permission.uri)))
            check(resolver.persistedUriPermissions.map { it.uri }.toSet().containsAll(otherGrants))
            return "Real local SAF selection retained our persisted lease without auto-play; service Stop released it, retained unowned grants and exposed opaque metadata. No output opened."
        } finally { main { control.release() } }
    }

    fun run(instrumentation: Instrumentation): String {
        val directory = File(instrumentation.targetContext.cacheDir, "file-session-probe").apply { mkdirs() }
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        val output = Output()
        val owner = UsbSessionOwner(UsbPlaybackController { output }, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        var player: EvannaPlayer? = null
        var session: MediaSession? = null
        var controller: MediaController? = null
        val closedReaders = AtomicInteger()
        try {
            main { player = EvannaPlayer(owner); session = MediaSession.Builder(instrumentation.targetContext, player!!).build() }
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { future = MediaController.Builder(instrumentation.targetContext, session!!.token).buildAsync() }
            controller = future!!.get(10, TimeUnit.SECONDS)
            val control = controller
            var opened: com.google.common.util.concurrent.ListenableFuture<Boolean>? = null
            main { opened = player!!.openDevice(7) }
            check(opened!!.get(5, TimeUnit.SECONDS))
            fun select(asset: String) {
                val file = File(directory, asset.substringAfterLast('/'))
                instrumentation.context.assets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
                var selected: com.google.common.util.concurrent.ListenableFuture<Boolean>? = null
                main { selected = player!!.selectTrack {
                    val track = DecodedAudioTrack.prepare(decoderFactory = {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) }
                    })
                    object : PcmTrack by track {
                        override fun open(): PcmReader {
                            val reader = track.open()
                            return object : PcmReader by reader {
                                override fun close() { reader.close(); closedReaders.incrementAndGet() }
                            }
                        }
                    }
                } }
                check(selected!!.get(10, TimeUnit.SECONDS))
                await(main) { control.currentMediaItem?.mediaId?.startsWith("evanna:local:") == true }
                main {
                    check(!control.isPlaying && control.currentMediaItem?.localConfiguration == null)
                    check(control.mediaMetadata.title.toString() == "Local audio file")
                    check(control.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                }
            }
            select("decoder/stereo-24.flac")
            val expected = listOf(Int.MIN_VALUE, 2147483392, -256, 256, 0, 0, 768, -1280)
            repeat(2) { play ->
                main { control.play() }
                await(main) { control.playbackState == Player.STATE_ENDED && closedReaders.get() == play + 1 }
                check(output.samples.toList() == List(play + 1) { expected }.flatten())
                check(owner.snapshot.value.framesAccepted == 4L)
            }
            check(output.drains == 2)
            select("decoder-extended/tone.mp3")
            val starts = output.starts
            main { control.play() }
            await(main) { control.playerError?.message?.contains("Strict") == true }
            check(output.starts == starts)
            var converted: com.google.common.util.concurrent.ListenableFuture<Boolean>? = null
            main { converted = player!!.playConvertedOnce() }
            check(converted!!.get(10, TimeUnit.SECONDS))
            await(main) { control.playbackState == Player.STATE_ENDED && closedReaders.get() == 3 }
            check(owner.snapshot.value.framesAccepted == 88200L)
            check(owner.snapshot.value.source?.sourceBits == null && owner.snapshot.value.source?.strictEligible == false)
            main { control.play() }
            await(main) { control.playerError?.message?.contains("Strict") == true }
            check(output.starts == starts + 1)
            main { control.stop() }
            await(main) { control.mediaItemCount == 0 && owner.selectedTrack == null }
            return "Real FLAC decoder PCM matched literal samples through MediaController play/EOF/replay; MP3 strict refusal, converted-once delivery, authorization reset, reader close and opaque metadata passed with a synthetic sink. No USB or speaker output opened."
        } finally {
            main { controller?.release(); session?.release(); player?.release() }
            runBlocking { check(owner.close()) }
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }

    private fun await(main: (() -> Unit) -> Unit, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        do { var done = false; main { done = condition() }; if (done) return; SystemClock.sleep(20) } while (SystemClock.elapsedRealtime() < deadline)
        error("File MediaController state did not settle")
    }
    private class Output : UsbPlaybackOutput {
        override val description = "Synthetic file sink"
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        var starts = 0
        var drains = 0
        override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, source.significantBits, false, true)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            starts++
            return StreamInfo(source.sampleRate, source.significantBits, source.channels,
                DacFormat(1, 1, 1, source.channels, 4, source.significantBits, DacFormat.SyncType.SYNC, 1024,
                    listOf(source.sampleRate), source.sampleRate, source.sampleRate), source.sampleRate, source.sampleRate, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(0, 0, 0, false)
        override fun write(buffer: ByteBuffer, frames: Int): Int {
            check(buffer.position() == 0 && buffer.limit() == buffer.capacity() && buffer.order() == ByteOrder.LITTLE_ENDIAN)
            check(buffer.capacity() == frames * 8)
            repeat(frames * 2) { samples += buffer.getInt(it * 4) }
            return frames
        }
        override fun drain(): Boolean { drains++; return true }
        override fun stop() = true
        override fun close() = Unit
    }
}
