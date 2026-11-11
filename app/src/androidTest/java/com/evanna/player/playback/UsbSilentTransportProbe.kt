package com.evanna.player.playback

import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.truesample.usbaudio.UsbAudioOutput

/** Test-only native silence: no calibration, fixture, write, gain or mute command. */
internal object UsbSilentTransportProbe {
    private var owned: UsbAudioOutput? = null

    fun run(manager: UsbManager, allModes: Boolean = false, awaitDisconnect: Boolean = false,
            progress: (String) -> Unit = {}): String {
        require(!allModes || !awaitDisconnect) { "Disconnect and matrix probes are separate" }
        check(owned == null) { "Previous probe cleanup incomplete; do not reopen" }
        try {
            val device = manager.deviceList.values.filter(UsbAudioOutput::isUsbAudioOutput).single()
            check(manager.hasPermission(device)) { "Grant USB access in Evanna first" }
            val output = UsbAudioOutput.open(manager, device).also { owned = it }
            try {
                val baseline = output.outputModes.firstOrNull {
                    it.sampleRate == 48000 && it.bitsPerSample == 16 && it.channels == 2
                } ?: error("No exact 48 kHz/16-bit/stereo mode")
                val modes = if (allModes) output.outputModes.filter {
                    it.bitsPerSample in listOf(16, 24, 32) && it.channels in 1..2
                }.also { check(it.size == output.outputModes.size) { "Unsupported source format in advertised modes" } }
                    else if (awaitDisconnect) listOf(baseline) else listOf(baseline, baseline)
                check(modes.isNotEmpty()) { "No eligible output modes" }
                val observations = mutableListOf<String>()
                modes.forEachIndexed { cycle, mode ->
                    val label = "${mode.sampleRate}Hz/${mode.bitsPerSample}bit/${mode.channels}ch/format=${mode.formatIndex}"
                    val plan = output.plan(mode.sampleRate, mode.bitsPerSample, mode.channels, mode)
                    check(plan != null && !plan.resampled && plan.bitPerfect &&
                        plan.outputRate == mode.sampleRate && plan.dacBits == mode.bitsPerSample) { "Invalid plan: $label" }
                    val stream = try { output.start(mode.sampleRate, mode.bitsPerSample, mode.channels, mode = mode) }
                        catch (failure: Exception) { throw IllegalStateException("Start failed: $label", failure) }
                    check(stream.format == output.formats[mode.formatIndex] && stream.outputRate == mode.sampleRate &&
                        !stream.resampled && stream.bitPerfect && (stream.deviceRate == 0 || stream.deviceRate == mode.sampleRate)) {
                        "Actual mode mismatch: $label"
                    }
                    repeat(20) {
                        SystemClock.sleep(100)
                        check(output.clockConfigured() && output.feedbackReady() && !output.volumeVerified()) {
                            "Silent transport lost readiness or unexpectedly verified gain: $label"
                        }
                        val stats = output.stats()
                        check(!stats.disconnected && stats.transferErrors == 0L && stats.underruns == 0L &&
                            stats.framesQueued == 0L && stats.framesSent == 0L && stats.framesCompleted == 0L && !stats.inputEnded) {
                            "Unexpected silent transport counters: $label $stats"
                        }
                    }
                    if (awaitDisconnect) {
                        progress("READY_TO_UNPLUG: silent stream healthy; unplug the DAC within 60 seconds and leave it unplugged")
                        val deadline = SystemClock.elapsedRealtime() + 60_000
                        while (SystemClock.elapsedRealtime() < deadline &&
                            (!output.stats().disconnected || manager.deviceList.values.any { it.deviceId == device.deviceId })) {
                            SystemClock.sleep(100)
                        }
                        check(output.stats().disconnected && manager.deviceList.values.none { it.deviceId == device.deviceId }) {
                            "No confirmed native and Android disconnect within 60 seconds"
                        }
                        check(!output.clockConfigured() && !output.feedbackReady() && !output.volumeVerified()) {
                            "Disconnect did not clear readiness"
                        }
                        val stats = output.stats()
                        check(stats.framesSent == 0L && stats.underruns == 0L) { "Unexpected PCM consumption during unplug: $stats" }
                        observations += "native and Android disconnect confirmed; transferErrors=${stats.transferErrors}"
                    } else {
                        check(output.drain() && output.drain()) { "Empty silent drain was not verified: $label" }
                        val stats = output.stats()
                        check(stats.inputEnded && stats.framesQueued == 0L && stats.framesSent == 0L && stats.framesCompleted == 0L &&
                            stats.underruns == 0L && stats.transferErrors == 0L && !stats.disconnected && !output.volumeVerified()) {
                            "Unexpected empty drain evidence: $label $stats"
                        }
                        observations += "empty drain verified; inputEnded=true; queued/submitted/completed=0"
                    }
                    check(output.stop()) { "Stop did not quiesce callbacks" }
                    check(!output.drain()) { "Stopped stream accepted drain" }
                    check(!output.clockConfigured() && !output.feedbackReady() && !output.volumeVerified())
                    observations += "cycle=${cycle + 1}, $label, deviceRate=${stream.deviceRate}, sync=${stream.format.syncType}"
                }
                return "${modes.size} native silence cycles passed: " + observations.joinToString() +
                    "; no gain/mute command or PCM write; not calibration or fidelity proof"
            } finally {
                // A failed close keeps the wrapper reachable; do not report a clean release.
                output.close()
                owned = null
            }
        } catch (failure: Exception) {
            throw IllegalStateException(failure.message.orEmpty() +
                if (owned != null) "; cleanup incomplete, owner retained in test process" else "", failure)
        }
    }
}
