package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.media.AudioManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.decoder.DecodedAudioTrack
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.diagnostics.ReportField
import com.evanna.player.diagnostics.playbackSignalFields
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Actual service, decoder, audio focus and AudioTrack. Every source sample is zero. */
@UnstableApi
internal object AndroidOutputProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val smoke = openAndroidAudioOutput(context)
        try {
            repeat(2) { cycle ->
                smoke.startOutput(PcmFixture(48000, 16, 2))
                val zeros = ByteBuffer.allocateDirect(480 * 8).order(ByteOrder.LITTLE_ENDIAN)
                val accepted = smoke.write(zeros, 480)
                check(accepted == 480) { "cycle=$cycle write=$accepted, stats=${smoke.stats()}" }
                check(smoke.drain()) { "cycle=$cycle drain stats=${smoke.stats()}" }
                check(smoke.stop())
            }
        } catch (failure: Exception) {
            val at = failure.stackTrace.firstOrNull { it.className.startsWith("com.evanna.player") }
            throw IllegalStateException("Silent AudioTrack startup: ${failure.javaClass.simpleName}: ${failure.message}; ${at?.fileName}:${at?.lineNumber}", failure)
        } finally { check(smoke.stop()); smoke.close() }
        val file = File(context.cacheDir, "android-silence.wav")
        val frames = 480000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 4); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(48000); putInt(192000); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(frames * 4)
        }
        file.outputStream().use { out -> out.write(header.array()); out.write(ByteArray(frames * 4)) }
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        var control: MediaController? = null
        val owner = PlaybackRuntime.owner(context)
        val originalPolicy = owner.snapshot.value.usbPolicy
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < deadline) {
                var done = false; main { done = condition() }; if (done) return
                SystemClock.sleep(20)
            }
            val snapshot = owner.snapshot.value
            error("Android ${snapshot.state}/${snapshot.failure}; accepted=${snapshot.framesAccepted}; sent=${snapshot.stats?.framesSent}; head=${snapshot.stats?.framesCompleted}; errors=${snapshot.stats?.transferErrors}; deviceType=${snapshot.androidStream?.deviceType}")
        }
        try {
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            control = future!!.get(10, TimeUnit.SECONDS)
            val remote = control
            var selected: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
            main { selected = remote.sendCustomCommand(SessionCommand(PlaybackService.OPEN_ANDROID, Bundle.EMPTY), Bundle.EMPTY) }
            check(selected!!.get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
            main { remote.play() }
            SystemClock.sleep(100)
            check(owner.snapshot.value.androidStream == null) { "No-file Play must not start an analysis fixture" }
            runBlocking { check(owner.selectTrack { DecodedAudioTrack.prepare(decoderFactory = {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) }
            }) }) }
            await { remote.mediaItemCount == 1 && remote.duration == 10000L }
            main { remote.play() }
            await { remote.isPlaying && owner.snapshot.value.positionUs >= 100_000 }
            val started = owner.snapshot.value
            check(started.route == PlaybackRoute.ANDROID && started.deviceId == null && started.stream == null && started.plan == null)
            check(started.readiness == UsbPlaybackReadiness() && started.androidStream?.sampleRate == 48000)
            val fields = playbackSignalFields(started)
            check(fields[ReportField.BIT_PERFECT_ELIGIBLE] == "false" && fields[ReportField.VOLUME_CONTROL] == "ANDROID_SYSTEM")
            check(ReportField.VOLUME_VERIFIED !in fields && ReportField.CLOCK_CONFIGURED !in fields)
            for (policy in listOf(if (originalPolicy == UsbPlaybackPolicy.STRICT) UsbPlaybackPolicy.COMPATIBILITY else UsbPlaybackPolicy.STRICT, originalPolicy)) {
                var changed: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
                main { changed = remote.sendCustomCommand(SessionCommand(PlaybackService.SET_USB_POLICY, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.USB_POLICY, policy.name) }) }
                check(changed!!.get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
                await { remote.playlistMetadata.extras?.getString(EvannaPlayer.USB_POLICY) == policy.name }
                check(owner.snapshot.value.generation == started.generation && owner.snapshot.value.state == UsbPlaybackState.PLAYING)
                check(PlaybackRuntime.diagnosticFocusHeld() == true)
                check(playbackSignalFields(owner.snapshot.value)[ReportField.POLICY] == "ANDROID_SYSTEM")
            }
            main { remote.pause() }; await { owner.snapshot.value.state == UsbPlaybackState.PAUSED }
            val paused = owner.snapshot.value.positionUs
            SystemClock.sleep(100); check(owner.snapshot.value.positionUs == paused)
            main { remote.seekTo(9000) }; await { remote.currentPosition == 9000L && !remote.isPlaying }
            main { remote.play() }; await { remote.playbackState == Player.STATE_ENDED }
            check(owner.snapshot.value.positionUs == 10_000_000L && owner.snapshot.value.stats?.framesCompleted == 48000L)
            main { remote.stop() }; await { remote.mediaItemCount == 0 }
            check(owner.snapshot.value.route == null && owner.selectedTrack == null)
            check(manager.getStreamVolume(AudioManager.STREAM_MUSIC) == volume)
            return "Real Android service/AudioTrack silent PCM: no-file refusal, USB mode changes keep generation/playback/focus, Android policy evidence, playback-head progress, pause, seek/resume/EOF, Stop and system volume unchanged passed; deviceType=${started.androidStream!!.deviceType}; no gain or nonzero samples"
        } finally {
            main { control?.release() }
            runBlocking { check(owner.close()); check(owner.setUsbPolicy(originalPolicy)) }
            file.delete()
        }
    }
}
