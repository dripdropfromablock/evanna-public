package com.evanna.player.diagnostics

import com.evanna.player.playback.UsbPlaybackSnapshot
import com.evanna.player.playback.PlaybackRoute
import com.evanna.player.playback.usbSamplePathEligible

/** Only typed signal evidence crosses this boundary; source locations and track IDs are excluded. */
internal fun playbackSignalFields(snapshot: UsbPlaybackSnapshot?): Map<ReportField, String> = buildMap {
    val android = snapshot?.route == PlaybackRoute.ANDROID
    put(ReportField.ROUTE, snapshot?.route?.name ?: "NONE")
    put(ReportField.POLICY, if (android) "ANDROID_SYSTEM" else snapshot?.usbPolicy?.name ?: "STRICT")
    put(ReportField.CODEC, snapshot?.source?.codec ?: "UNKNOWN")
    put(ReportField.SOURCE_STRICT_ELIGIBLE, (snapshot?.source?.strictEligible == true).toString())
    put(ReportField.CONVERSION_AUTHORIZED, (snapshot?.conversionAllowed == true).toString())
    put(ReportField.BIT_PERFECT_ELIGIBLE, (snapshot?.usbSamplePathEligible() == true).toString())
    snapshot?.let {
        it.source?.let { source -> put(ReportField.RATE, source.sampleRate.toString()); put(ReportField.BITS, source.sourceBits?.toString() ?: "UNKNOWN"); put(ReportField.CHANNELS, source.channels.toString()) }
        put(ReportField.SOURCE_FRAMES_ACCEPTED, it.framesAccepted.toString())
        it.plan?.let { plan -> put(ReportField.PLANNED_RATE, plan.outputRate.toString()); put(ReportField.PLANNED_BITS, plan.dacBits.toString()) }
        it.stream?.let { stream ->
            put(ReportField.OUTPUT_RATE, stream.outputRate.toString()); put(ReportField.OUTPUT_BITS, stream.format.bitResolution.toString())
            put(ReportField.DEVICE_RATE, if (stream.deviceRate > 0) stream.deviceRate.toString() else "UNKNOWN")
            put(ReportField.RESAMPLED, stream.resampled.toString())
        }
        it.androidStream?.let { stream ->
            put(ReportField.OUTPUT_RATE, stream.sampleRate.toString()); put(ReportField.OUTPUT_ENCODING, "PCM_FLOAT")
            put(ReportField.OUTPUT_BITS, "UNKNOWN"); put(ReportField.DEVICE_RATE, "UNKNOWN"); put(ReportField.RESAMPLED, "UNKNOWN")
            put(ReportField.ANDROID_DEVICE_TYPE, stream.deviceType.toString())
        }
        if (android) put(ReportField.VOLUME_CONTROL, "ANDROID_SYSTEM") else {
            if (it.route == PlaybackRoute.DIRECT_USB) put(ReportField.VOLUME_CONTROL, "USB_HARDWARE")
            put(ReportField.CLOCK_CONFIGURED, it.readiness.clockConfigured.toString()); put(ReportField.FEEDBACK_READY, it.readiness.feedbackReady.toString())
            put(ReportField.VOLUME_VERIFIED, it.readiness.volumeVerified.toString())
        }
        it.stats?.let { stats ->
            put(ReportField.FRAMES_QUEUED, stats.framesQueued.toString()); put(ReportField.FRAMES_SUBMITTED, stats.framesSent.toString())
            put(ReportField.FRAMES_COMPLETED, stats.framesCompleted.toString())
            if (!android) put(ReportField.USB_INPUT_ENDED, stats.inputEnded.toString())
            put(ReportField.UNDERRUNS, stats.underruns.toString()); put(ReportField.TRANSFER_ERRORS, stats.transferErrors.toString())
        }
        it.failure?.let { failure -> put(ReportField.FAILURE, failure.name) }
    }
}
