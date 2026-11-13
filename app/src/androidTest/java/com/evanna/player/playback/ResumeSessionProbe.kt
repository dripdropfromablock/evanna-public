package com.evanna.player.playback

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.evanna.player.decoder.DecodedAudioTrack
import com.evanna.player.decoder.NativeDecoder
import com.truesample.usbaudio.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

/** Real decoder and MediaController with a paced synthetic output, never USB/speakers. */
@UnstableApi
internal object ResumeSessionProbe {
    fun run(instrumentation: Instrumentation): String {
        val file = File(instrumentation.targetContext.cacheDir, "resume-session.flac")
        instrumentation.context.assets.open("decoder-seek/seek.flac").use { input -> file.outputStream().use(input::copyTo) }
        fun decoder() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) }
        val output = Output()
        val owner = UsbSessionOwner(UsbPlaybackController { output }, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        var player: EvannaPlayer? = null
        var session: MediaSession? = null
        var control: MediaController? = null
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < deadline) {
                var done = false; main { done = condition() }; if (done) return
                SystemClock.sleep(20)
            }
            error("Resume session did not settle: ${owner.snapshot.value.state}/${owner.snapshot.value.failure}")
        }
        try {
            main { player = EvannaPlayer(owner); session = MediaSession.Builder(instrumentation.targetContext, player!!).build() }
            var connection: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { connection = MediaController.Builder(instrumentation.targetContext, session!!.token).buildAsync() }
            control = connection!!.get(10, TimeUnit.SECONDS)
            val remote = control
            runBlocking { check(owner.selectTrack { DecodedAudioTrack.prepare(::decoder) }); check(owner.open(7)) }
            await { remote.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && remote.duration == 12000L }
            main { remote.seekTo(6000) }
            await { remote.currentPosition == 6000L && !remote.isPlaying }
            check(output.starts == 0)
            repeat(2) {
                val beforePlay = owner.snapshot.value.generation
                main { remote.play() }
                await { owner.snapshot.value.generation > beforePlay && remote.isPlaying && output.completed.get() >= 4410 }
                main { remote.pause() }
                await { owner.snapshot.value.state == UsbPlaybackState.PAUSED && !remote.playWhenReady }
                val anchor = owner.snapshot.value.positionUs
                check(anchor >= 6_100_000)
                main { check(remote.currentPosition == anchor / 1000) }
                SystemClock.sleep(100)
                main { check(remote.currentPosition == anchor / 1000) }
                val pausedGeneration = owner.snapshot.value.generation
                main { remote.play() }
                await { owner.snapshot.value.generation > pausedGeneration && remote.isPlaying && output.firstLeft != null }
                decoder().use { reference ->
                    val target = (anchor * 44100 + 999999) / 1000000
                    val pcm = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
                    var skipped = 0L
                    while (skipped < target) skipped += reference.readPcm(pcm, minOf(4096L, target - skipped).toInt())
                    check(reference.readPcm(pcm, 1) == 1 && output.firstLeft == pcm.getInt(0)) {
                        "Resume boundary mismatch: anchor=$anchor actual=${output.firstLeft} expected=${pcm.getInt(0)} state=${owner.snapshot.value.state}"
                    }
                }
                main { remote.pause() }; await { owner.snapshot.value.state == UsbPlaybackState.PAUSED && !remote.playWhenReady }
            }
            val beforeFinalPlay = owner.snapshot.value.generation
            main { remote.play() }; await { owner.snapshot.value.generation > beforeFinalPlay && remote.isPlaying }
            main { remote.seekTo(11800) }
            await { remote.playbackState == Player.STATE_ENDED }
            check(owner.snapshot.value.positionUs == 12_000_000L)
            main { remote.stop() }; await { remote.mediaItemCount == 0 }
            check(owner.snapshot.value.positionUs == 0L)
            return "Real FLAC MediaController duration/paused seek, two sample-checked pause/resume cycles, active seek/EOF and Stop reset passed with paced synthetic output"
        } finally {
            main { control?.release(); session?.release(); player?.release() }
            runBlocking { check(owner.close()) }; file.delete()
        }
    }

    private class Output : UsbPlaybackOutput {
        override val description = "Paced resume sink"
        val completed = AtomicLong()
        @Volatile var firstLeft: Int? = null
        @Volatile var running = false
        var starts = 0
        override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, source.significantBits, false, true)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            starts++; completed.set(0); firstLeft = null; running = true
            return StreamInfo(source.sampleRate, source.significantBits, source.channels,
                DacFormat(1, 1, 1, source.channels, 4, source.significantBits, DacFormat.SyncType.SYNC, 512, listOf(source.sampleRate), source.sampleRate, source.sampleRate),
                source.sampleRate, source.sampleRate, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(completed.get(), 0, 0, false, completed.get(), completed.get())
        override fun write(buffer: ByteBuffer, frames: Int): Int {
            SystemClock.sleep(10)
            if (!running) return -1
            if (firstLeft == null) firstLeft = buffer.getInt(0)
            completed.addAndGet(frames.toLong()); return frames
        }
        override fun drain() = true
        override fun stop(): Boolean { running = false; return true }
        override fun close() = Unit
    }
}
