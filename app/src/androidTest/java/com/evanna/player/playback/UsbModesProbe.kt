package com.evanna.player.playback

import android.app.Instrumentation
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Positive conversion/mode transitions use a synthetic sink, never the connected earphones. */
@UnstableApi
internal object UsbModesProbe {
    fun run(instrumentation: Instrumentation): String {
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        val output = Output()
        val saved = mutableListOf<UsbPlaybackPolicy>()
        val owner = UsbSessionOwner(UsbPlaybackController(saveUsbPolicy = { saved += it; true }) { output }, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        val source = TrackPcmFormat(44100, 32, 2, null, "MP3", false, null)
        runBlocking { check(owner.selectTrack { object : PcmTrack {
            override val id = "evanna:local:synthetic-policy"
            override val format = source
            override fun open() = object : PcmReader {
                override val format = source
                override fun read(target: ByteBuffer, maxFrames: Int): Int { target.putInt(0, 0); target.putInt(4, 0); return 1 }
                override fun requestCancel() = Unit
                override fun close() = Unit
            }
            override fun close() = Unit
        } }) }
        lateinit var player: EvannaPlayer
        lateinit var session: MediaSession
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main {
            player = EvannaPlayer(owner)
            session = MediaSession.Builder(instrumentation.targetContext, player).build()
            future = MediaController.Builder(instrumentation.targetContext, session.token).buildAsync()
        }
        val remote = future.get(10, TimeUnit.SECONDS)
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            do { var done = false; main { done = condition() }; if (done) return; SystemClock.sleep(20) } while (SystemClock.elapsedRealtime() < deadline)
            error("Mode state did not settle: ${owner.snapshot.value.state}/${owner.snapshot.value.failure}")
        }
        fun mode(policy: UsbPlaybackPolicy) {
            lateinit var result: com.google.common.util.concurrent.ListenableFuture<Boolean>
            main { result = player.setUsbPolicy(policy) }; check(result.get(5, TimeUnit.SECONDS))
            await { remote.playlistMetadata.extras?.getString(EvannaPlayer.USB_POLICY) == policy.name }
        }
        try {
            lateinit var opened: com.google.common.util.concurrent.ListenableFuture<Boolean>
            main { opened = player.openDevice(7) }; check(opened.get(5, TimeUnit.SECONDS))
            main { remote.play() }; await { owner.snapshot.value.failure == UsbPlaybackFailure.CONVERSION_REQUIRED }
            check(output.starts == 0)
            mode(UsbPlaybackPolicy.COMPATIBILITY)
            repeat(2) {
                main { remote.play() }; await { remote.isPlaying && output.wrote.count == 0L }
                main {
                    val status = remote.playlistMetadata.extras?.getString(EvannaPlayer.SIGNAL_STATUS).orEmpty()
                    check(status.contains("resampling 44100 → 48000 Hz") && status.contains("precision reduction 32 → 16 bits"))
                    check(!status.contains("eligible at USB input"))
                }
                if (it == 0) { main { remote.pause() }; await { owner.snapshot.value.state == UsbPlaybackState.PAUSED && !remote.playWhenReady } }
            }
            mode(UsbPlaybackPolicy.STRICT)
            check(owner.snapshot.value.state == UsbPlaybackState.PAUSED && output.stopped.count == 0L && output.starts == 2)
            main { remote.play() }; await { owner.snapshot.value.failure == UsbPlaybackFailure.CONVERSION_REQUIRED }
            lateinit var converted: com.google.common.util.concurrent.ListenableFuture<Boolean>
            main { converted = player.playConvertedOnce() }; check(converted.get(5, TimeUnit.SECONDS))
            await { remote.isPlaying && owner.snapshot.value.conversionAllowed }
            main { remote.pause() }; await { !owner.snapshot.value.conversionAllowed && !remote.isPlaying }
            mode(UsbPlaybackPolicy.COMPATIBILITY)
            main { remote.stop() }; await { remote.mediaItemCount == 0 }
            main { check(remote.playlistMetadata.extras?.getString(EvannaPlayer.USB_POLICY) == "COMPATIBILITY") }
            check(saved == listOf(UsbPlaybackPolicy.COMPATIBILITY, UsbPlaybackPolicy.STRICT, UsbPlaybackPolicy.COMPATIBILITY))
            return "Synthetic MediaController strict refusal, saved compatibility/replay, conversion status, strict stop/join, one-play reset and mode after Stop passed; no physical output opened"
        } finally {
            main { remote.release(); session.release(); player.release() }
            runBlocking { check(owner.close()) }
        }
    }

    private class Output : UsbPlaybackOutput {
        override val description = "Synthetic mode output"
        var starts = 0
        var wrote = CountDownLatch(1)
        var stopped = CountDownLatch(1)
        override fun plan(source: PcmFormat) = PlaybackPlan(48000, 16, true, false)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            starts++; wrote = CountDownLatch(1); stopped = CountDownLatch(1)
            return StreamInfo(source.sampleRate, source.significantBits, source.channels,
                DacFormat(1, 1, 1, 2, 2, 16, DacFormat.SyncType.SYNC, 192, listOf(48000), 48000, 48000),
                48000, 48000, false, true)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(0, 0, 0, false)
        override fun write(buffer: ByteBuffer, frames: Int): Int { wrote.countDown(); check(stopped.await(5, TimeUnit.SECONDS)); return -1 }
        override fun stop(): Boolean { stopped.countDown(); return true }
        override fun close() = Unit
    }
}
