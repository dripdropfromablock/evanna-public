package com.evanna.player.decoder

import android.app.Instrumentation
import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.evanna.player.equalizer.SoundConfig
import com.evanna.player.equalizer.SoundSettings
import com.evanna.player.playback.*
import com.evanna.player.usb.dacIdentity
import com.evanna.player.user.DacProfile
import com.truesample.usbaudio.UsbAudioOutput
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Physical USB only, generated low-amplitude audio, verified minimum hardware gain, no saved queue. */
internal object NetworkUsbProbe {
    fun run(instrumentation: Instrumentation, unplug: Boolean): String = runBlocking {
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        check(!audio.isMusicActive) { "Pause other music before the network USB check" }
        val manager = context.getSystemService(UsbManager::class.java)
        val device = manager.deviceList.values.filter(UsbAudioOutput::isUsbAudioOutput).single()
        check(manager.hasPermission(device)) { "Grant USB access in Evanna first" }
        val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
        val hadConfig = prefs.contains("config")
        val savedConfig = prefs.getString("config", null)
        val savedSound = SoundSettings.config.value
        val bytes = instrumentation.context.assets.open("decoder-seek/seek.flac").use { it.readBytes() }
        val file = File.createTempFile("network-usb-", ".flac", context.cacheDir).apply { writeBytes(bytes) }
        fun stage(text: String) = instrumentation.sendStatus(1, Bundle().apply { putString("progress", text) })
        try {
            val info = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                NativeDecoder.open(fd.fd).use { it.info }
            }
            NetworkFixtureServer(bytes).use { server ->
                val track = NetworkFixtureTrack(context, server.url, bytes.size.toLong(), info)
                val native = openUsbPlaybackOutput(manager, device.deviceId)
                val output = object : UsbPlaybackOutput by native {
                    override fun write(buffer: ByteBuffer, frames: Int): Int {
                        val gain = checkNotNull(native.usbVolume) { "Hardware gain is not verified" }
                        check(gain.minDb < 0 && gain.currentDb <= maxOf(USB_START_VOLUME_MIN_DB, gain.minDb)) {
                            "Refusing PCM above the probe's minimum hardware gain"
                        }
                        val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        while (pcm.hasRemaining()) check(abs(pcm.int.toLong()) <= 429_496_729L) {
                            "Refusing test PCM above 20% digital full scale"
                        }
                        return native.write(buffer, frames)
                    }
                }
                val controller = UsbPlaybackController(initialStartVolumeDb = USB_START_VOLUME_MIN_DB) { output }
                val profile = DacProfile(device.dacIdentity(), maxRate = 48000, startVolumeDb = USB_START_VOLUME_MIN_DB)
                val observations = mutableListOf<String>()
                try {
                    SoundSettings.update { SoundConfig() }
                    check(controller.open(device.deviceId, profile)) { "USB open refused: ${controller.snapshot.value}" }
                    suspend fun await(message: String, condition: () -> Boolean) {
                        withTimeout(15000) {
                            while (!condition()) {
                                check(controller.snapshot.value.state != UsbPlaybackState.ERROR) { "$message: ${controller.snapshot.value}" }
                                delay(20)
                            }
                        }
                    }
                    suspend fun play(positionUs: Long = 0) {
                        check(controller.start(track, allowConversion = true, positionUs = positionUs)) {
                            "Network USB start refused: ${controller.snapshot.value}"
                        }
                        await("USB did not advance") { (controller.snapshot.value.stats?.framesCompleted ?: 0) > 0 }
                        val snapshot = controller.snapshot.value
                        check(snapshot.route == PlaybackRoute.DIRECT_USB && snapshot.androidStream == null)
                        val gain = checkNotNull(snapshot.usbVolume)
                        check(gain.currentDb <= maxOf(USB_START_VOLUME_MIN_DB, gain.minDb))
                        val stream = checkNotNull(snapshot.stream)
                        observations += "source ${info.sampleRate}Hz/${info.significantBits}bit/${info.channels}ch; DAC ${stream.outputRate}Hz/${stream.format.bitResolution}bit; resampled=${stream.resampled}; driverBitPerfect=${stream.bitPerfect}; gain=${gain.currentDb}dB"
                    }
                    fun healthy(snapshot: UsbPlaybackSnapshot = controller.snapshot.value, allowEnding: Boolean = false): UsbPlaybackSnapshot {
                        check(snapshot.route == PlaybackRoute.DIRECT_USB && snapshot.androidStream == null)
                        val running = snapshot.state == UsbPlaybackState.PLAYING ||
                            allowEnding && snapshot.state in listOf(UsbPlaybackState.DRAINING, UsbPlaybackState.STOPPING)
                        check(running && snapshot.failure == null) { "USB stream stopped: $snapshot" }
                        val stats = checkNotNull(snapshot.stats)
                        check(!stats.disconnected && stats.transferErrors == 0L && stats.underruns == 0L &&
                            stats.clippedSamples == 0L && stats.droppedPackets == 0L && stats.droppedSinceOpen == 0L) { "USB transfer error: $stats" }
                        return snapshot
                    }
                    if (!unplug) {
                        stage("Physical network USB: EQ off, verified low gain, native output format")
                        play()
                        val sourceBytes = checkNotNull(track.lastSource).fetched.get()
                        check(sourceBytes < bytes.size) { "USB playback waited for a full download" }
                        delay(1000)
                        val raw = healthy()
                        check(!raw.eqProcessed)
                        observations += "EQ off stats=${raw.stats}"
                        check(controller.pause())
                        check(controller.snapshot.value.state == UsbPlaybackState.PAUSED)
                        val pausedFrames = native.stats().framesCompleted
                        delay(200)
                        check(native.stats().framesCompleted == pausedFrames) { "USB continued after pause" }
                        stage("Physical network USB: EQ on and seek/resume at 2 seconds")
                        SoundSettings.update { SoundConfig(enabled = true, preampDb = -6f) }
                        play(2_000_000)
                        delay(1000)
                        val processed = healthy()
                        check(processed.eqProcessed && processed.eqPreampDb <= -6f && processed.startPositionUs == 2_000_000L)
                        check(!processed.usbSamplePathEligible()) { "Processed stream was labelled unchanged" }
                        observations += "EQ on stats=${processed.stats}"
                        check(controller.pause())
                        stage("Physical network USB: delayed HTTP ranges and backward seek")
                        SoundSettings.update { SoundConfig() }
                        server.delayMs = 50
                        play(500_000)
                        delay(1200)
                        val end = healthy()
                        check(!end.eqProcessed && end.startPositionUs == 500_000L)
                        observations += "startup $sourceBytes/${bytes.size} compressed bytes; stats=${end.stats}"
                        check(controller.pause())
                        "PASS: real network-to-USB playback, verified low gain, EQ off/on, forward/backward seek, pause/resume and delayed ranges. ${observations.joinToString("; ")}. No analog measurement or service integration."
                    } else {
                        play()
                        stage("READY_TO_UNPLUG: physical network USB is active at minimum gain. Unplug JD1 now; leave it disconnected. Timeout 180 seconds.")
                        val deadline = SystemClock.elapsedRealtime() + 180000
                        var disconnectAt = 0L
                        var stopMs = 0L
                        var endingAt = 0L
                        var stopped = false
                        while (SystemClock.elapsedRealtime() < deadline) {
                            val snapshot = controller.snapshot.value
                            val missing = manager.deviceList.values.none { it.deviceId == device.deviceId }
                            val disconnecting = missing || snapshot.failure == UsbPlaybackFailure.DISCONNECTED || snapshot.stats?.disconnected == true
                            if (disconnecting && disconnectAt == 0L) disconnectAt = SystemClock.elapsedRealtime()
                            if (missing && snapshot.state == UsbPlaybackState.ERROR) {
                                check(snapshot.failure == UsbPlaybackFailure.DISCONNECTED || snapshot.stats?.disconnected == true) { "Unexpected disconnect error: $snapshot" }
                                check(snapshot.androidStream == null && snapshot.route == PlaybackRoute.DIRECT_USB)
                                stopMs = SystemClock.elapsedRealtime() - disconnectAt
                                check(stopMs < 3000) { "USB stop was too slow" }
                                stopped = true; break
                            }
                            if (disconnectAt > 0) check(SystemClock.elapsedRealtime() - disconnectAt < 3000) { "Physical disconnect did not settle" }
                            if (!disconnecting) {
                                if (snapshot.state == UsbPlaybackState.ENDED) {
                                    play(); endingAt = 0L
                                } else {
                                    if (snapshot.state in listOf(UsbPlaybackState.DRAINING, UsbPlaybackState.STOPPING)) {
                                        if (endingAt == 0L) endingAt = SystemClock.elapsedRealtime()
                                        check(SystemClock.elapsedRealtime() - endingAt < 3000) { "USB completion stalled" }
                                    } else endingAt = 0L
                                    healthy(snapshot, allowEnding = true)
                                }
                            }
                            delay(30)
                        }
                        check(stopped) { "No physical unplug observed before timeout" }
                        val accepted = controller.snapshot.value.framesAccepted
                        delay(300)
                        check(controller.snapshot.value.framesAccepted == accepted && !audio.isMusicActive) { "Playback continued or fell back to Android output" }
                        "PASS: physical JD1 unplug stopped network USB playback; observed disconnect-to-stop ${stopMs}ms (30ms polling), no Android media fallback; ${observations.first()}. No analog measurement."
                    }
                } finally { check(controller.close()) { "USB cleanup failed; reconnect before retrying" }; track.close() }
            }
        } finally {
            try { SoundSettings.update { savedSound } }
            finally {
                val restore = prefs.edit()
                if (hadConfig) restore.putString("config", savedConfig) else restore.remove("config")
                check(restore.commit()) { "EQ settings restore failed" }
                check(file.delete())
            }
        }
    }
}
