package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.truesample.usbaudio.*
import com.google.common.util.concurrent.ListenableFuture
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import com.evanna.player.diagnostics.CrashRecorder
import com.evanna.player.diagnostics.IncidentKind
import com.evanna.player.diagnostics.ReportField

@UnstableApi
internal object PlaybackSessionProbe {
    fun run(instrumentation: Instrumentation, realUsb: Boolean): String {
        val context = instrumentation.targetContext
        val began = System.currentTimeMillis()
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        var syntheticPlayer: EvannaPlayer? = null
        var localSession: MediaSession? = null
        val output = SyntheticOutput()
        val owner = UsbSessionOwner(UsbPlaybackController { output }, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        val future = arrayOfNulls<ListenableFuture<MediaController>>(1)
        main {
            if (realUsb) {
                future[0] = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            } else {
                val player = EvannaPlayer(owner).also { syntheticPlayer = it }
                val session = MediaSession.Builder(context, player).build().also { localSession = it }
                future[0] = MediaController.Builder(context, session.token).buildAsync()
            }
        }
        val controller = checkNotNull(future[0]).get(10, TimeUnit.SECONDS)
        val realOwner = if (realUsb) PlaybackRuntime.owner(context) else null
        val originalPolicy = realOwner?.snapshot?.value?.usbPolicy
        try {
            if (realUsb) {
                val manager = context.getSystemService(UsbManager::class.java)
                val device = manager.deviceList.values.filter(UsbAudioOutput::isUsbAudioOutput).single()
                check(manager.hasPermission(device)) { "Grant USB access in Evanna first" }
                var opened: ListenableFuture<SessionResult>? = null
                main {
                    check(!controller.isPlaying && controller.mediaItemCount == 0)
                    opened = controller.sendCustomCommand(SessionCommand(PlaybackService.OPEN_USB, Bundle.EMPTY),
                        Bundle().apply { putInt(PlaybackService.DEVICE_ID, device.deviceId) })
                }
                check(checkNotNull(opened).get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
            } else {
                var opened: ListenableFuture<Boolean>? = null
                main { opened = syntheticPlayer!!.openDevice(7) }
                check(checkNotNull(opened).get(5, TimeUnit.SECONDS))
            }
            await(main) { controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }
            main {
                check(!controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                check(!controller.isCommandAvailable(Player.COMMAND_SET_VOLUME))
            }
            if (realUsb) {
                var invalid: ListenableFuture<SessionResult>? = null
                main { invalid = controller.sendCustomCommand(SessionCommand(PlaybackService.SET_USB_POLICY, Bundle.EMPTY),
                    Bundle().apply { putString(PlaybackService.USB_POLICY, "true") }) }
                check(invalid!!.get(10, TimeUnit.SECONDS).resultCode == SessionError.ERROR_BAD_VALUE)
                check(realOwner!!.snapshot.value.usbPolicy == originalPolicy)
                for (policy in UsbPlaybackPolicy.entries) {
                    var changed: ListenableFuture<SessionResult>? = null
                    main { changed = controller.sendCustomCommand(SessionCommand(PlaybackService.SET_USB_POLICY, Bundle.EMPTY),
                        Bundle().apply { putString(PlaybackService.USB_POLICY, policy.name) }) }
                    check(changed!!.get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
                    await(main) { controller.playlistMetadata.extras?.getString(EvannaPlayer.USB_POLICY) == policy.name }
                    check(context.getSharedPreferences("playback-settings", 0).getString("usb_policy", "STRICT") == policy.name)
                    main { controller.play() }
                    await(main) { controller.playerError?.message?.contains("calibration") == true }
                    main {
                        check(!controller.isPlaying && !controller.playWhenReady)
                        val status = controller.playlistMetadata.extras?.getString(EvannaPlayer.SIGNAL_STATUS).orEmpty()
                        check(status.contains("Planned USB output") && status.contains("control unverified"))
                        check(!status.contains("eligible at USB input"))
                    }
                    check(realOwner.snapshot.value.stream == null)
                }
            } else {
                main { controller.play() }
                check(output.wrote.await(5, TimeUnit.SECONDS))
                await(main) { controller.isPlaying }
                main { controller.pause() }
                await(main) { !controller.isPlaying && owner.snapshot.value.state == UsbPlaybackState.PAUSED }
                check(output.starts == 1)
            }
            main { controller.stop() }
            await(main) { controller.mediaItemCount == 0 && !controller.isPlaying }
            if (realUsb && CrashRecorder.enabled.get()) {
                main { } // Finish prior main-loop state notifications before draining diagnostics work.
                val failures = CrashRecorder.reports().filter {
                    it.kind == IncidentKind.HANDLED_FAILURE && it.timestamp >= began &&
                        it.safeFields()[ReportField.PID] == android.os.Process.myPid().toString()
                }
                check(failures.size == 2) { "Expected two handled startup failures, found ${failures.size}" }
            }
            return if (realUsb) "Service/controller invalid-policy refusal, saved strict/compatibility commands, real USB calibration refusal under both policies and close passed; no PCM or gain change"
                else "MediaController synthetic play/pause/stop/state forwarding passed; no physical output opened"
        } finally {
            main { controller.release(); localSession?.release(); syntheticPlayer?.release() }
            runBlocking {
                if (realOwner != null) { check(realOwner.close()); check(realOwner.setUsbPolicy(checkNotNull(originalPolicy))) }
                else check(owner.close())
            }
        }
    }

    private fun await(main: (() -> Unit) -> Unit, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("MediaController state did not settle")
    }

    private class SyntheticOutput : UsbPlaybackOutput {
        override val description = "Synthetic output"
        val wrote = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        var starts = 0
        override fun plan(source: PcmFormat) = PlaybackPlan(48000, 16, false, true)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            starts++
            return StreamInfo(48000, 16, 2,
                DacFormat(1, 1, 1, 2, 2, 16, DacFormat.SyncType.SYNC, 192, listOf(48000), 48000, 48000),
                48000, 48000, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(0, 0, 0, false)
        override fun write(buffer: ByteBuffer, frames: Int): Int { wrote.countDown(); check(stopped.await(5, TimeUnit.SECONDS)); return -1 }
        override fun stop(): Boolean { stopped.countDown(); return true }
        override fun close() = Unit
    }
}
