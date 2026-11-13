package com.evanna.player.playback

import com.evanna.player.equalizer.Crossfeed
import com.evanna.player.loudness.LevelingMode
import java.util.Locale
import kotlin.math.abs

/** What a stop does to the sound. [END] is where it is heard. */
internal enum class StopState { SAME, CHANGED, PROBLEM, END }
internal enum class StopKind { FILE, EVANNA, ANDROID, USB, DAC, BLUETOOTH, SPEAKER, HEADPHONES }

/** One stop on the sound's way to the listener, in plain words. [details] holds the numbers behind it. */
internal data class SoundStop(val kind: StopKind, val title: String, val value: String, val state: StopState = StopState.SAME,
    val details: List<Pair<String, String>> = emptyList())

/**
 * The Audio quality sheet, from the same evidence as [signalStages]: a verdict, why, and each stop from the file down.
 * [offerOtherOutput]: Direct USB lost packets this song, so the sheet offers another output.
 */
internal data class SoundPath(val verdict: String, val why: String, val playing: Boolean, val stops: List<SoundStop>,
    val offerOtherOutput: Boolean = false)

internal fun soundPath(snapshot: UsbPlaybackSnapshot): SoundPath {
    val playing = snapshot.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)
    val file = snapshot.source?.let(::fileStop)
    return when (snapshot.route) {
        null -> SoundPath("No output picked yet", "Press Play to hear this song through Android audio.", false,
            listOfNotNull(file, SoundStop(StopKind.HEADPHONES, "You", "Where you hear it", StopState.END)))
        PlaybackRoute.ANDROID -> androidPath(snapshot, file, playing)
        PlaybackRoute.DIRECT_USB -> usbPath(snapshot, file, playing)
    }
}

private fun fileStop(source: PcmFormat): SoundStop {
    val bits = source.sourceBits
    val kind = if (source.strictEligible) "Lossless" else "Compressed"
    return SoundStop(StopKind.FILE, "Song file", listOfNotNull(kind, bits?.let { "$it-bit" }, khz(source.sampleRate)).joinToString(" · "),
        details = listOfNotNull("Format" to "${source.codec}, ${kind.lowercase()}", bits?.let { "Bits" to "$it" },
            "Rate" to hz(source.sampleRate), "Channels" to "${source.channels}"))
}

private fun UsbPlaybackSnapshot.eqDetails() = listOf("EQ" to if (eqProcessed) "On, preamp ${db(eqPreampDb)}" else "Off",
    "Crossfeed" to when {
        crossfeed == Crossfeed.OFF -> "Off"
        crossfeedProcessed -> crossfeed.label
        else -> "${crossfeed.label}, stereo songs only"
    })

private fun usbPath(snapshot: UsbPlaybackSnapshot, file: SoundStop?, playing: Boolean): SoundPath = with(snapshot) {
    val source = snapshot.source
    val rate = stream?.outputRate ?: plan?.outputRate
    val bits = stream?.format?.bitResolution ?: plan?.dacBits
    val known = source != null && rate != null && bits != null
    val resampled = known && rate != source!!.sampleRate
    val reduced = known && bits!! < source!!.significantBits
    val stats = snapshot.stats
    val clipped = stats?.clippedSamples ?: 0
    val lost = stats?.droppedPackets ?: 0
    val ranShort = stats?.underruns ?: 0

    // What Evanna changes, as reasons for the verdict and as words for its stop.
    val reasons = buildList {
        if (resampled) add(maxUsbRate?.takeIf { source!!.sampleRate > it }
            ?.let { "Your DAC is set to ${khz(it)} at most, so Evanna converts this ${khz(source!!.sampleRate)} song." }
            ?: "Your DAC can't play ${khz(source!!.sampleRate)}, so Evanna converts it to ${khz(rate!!)}.")
        if (reduced) add("Your DAC takes $bits-bit at most, so Evanna rounds this ${source!!.significantBits}-bit song.")
        if (eqProcessed) add("The EQ is on, so Evanna changes the sound on purpose.")
        if (crossfeedProcessed) add("Crossfeed is on, so Evanna blends the left and right sides.")
    }
    val changes = buildList {
        if (resampled) add("turned into ${khz(rate!!)}, 2 dB quieter so loud parts don't crack")
        if (reduced) add("rounded to $bits-bit")
        if (eqProcessed) add("EQ on")
        if (crossfeedProcessed) add("crossfeed on")
    }
    val evanna = SoundStop(StopKind.EVANNA, "Evanna",
        when {
            !known -> "Waiting for playback. EQ is ${if (eqProcessed) "on" else "off"}."
            changes.isEmpty() -> "Left untouched. EQ and crossfeed are off."
            else -> changes.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        } + if (clipped > 0) " ${clipped} loud peaks were cut off." else "",
        when {
            clipped > 0 || failure == UsbPlaybackFailure.CONVERSION_REQUIRED -> StopState.PROBLEM
            changes.isNotEmpty() -> StopState.CHANGED
            else -> StopState.SAME
        },
        buildList {
            if (known) add("Rate" to if (resampled) "${hz(source!!.sampleRate)} → ${hz(rate!!)}" else "${hz(rate!!)}, no change")
            if (resampled) add("Headroom" to "−2 dB")
            if (known) add("Bits" to "${source!!.significantBits} → $bits")
            addAll(eqDetails())
            add("Cut-off peaks" to "$clipped")
            if (source?.strictEligible == false) add("Decoding" to "Compressed file, decoded with rounding")
        })

    val transportFailure = failure?.takeIf { it !in listOf(UsbPlaybackFailure.CONVERSION_REQUIRED, UsbPlaybackFailure.VOLUME_UNVERIFIED) &&
        !it.name.startsWith("SOURCE_") }
    val usbProblem = when {
        transportFailure != null -> transportFailure.plain() + "."
        stats?.disconnected == true -> "The DAC was unplugged."
        ranShort > 0 -> "Ran out of sound $ranShort ${if (ranShort == 1L) "time" else "times"}. You may hear gaps."
        lost > 0 -> "Lost $lost tiny ${if (lost == 1L) "bit" else "bits"} of sound. The phone's USB drops them, not the file."
        else -> null
    }
    val format = stream?.format
    val usb = SoundStop(StopKind.USB, "USB cable",
        usbProblem ?: if (stream == null) "Waiting for playback" else "Straight to your DAC, skipping Android",
        if (usbProblem != null) StopState.PROBLEM else StopState.SAME,
        listOfNotNull("Route" to "Direct USB",
            stats?.let { "Lost packets" to "${it.droppedPackets} this song, ${it.droppedSinceOpen} since you plugged in" },
            stats?.let { "Ran short" to "${it.underruns} times" },
            "Timing" to if (readiness.clockConfigured && readiness.feedbackReady) "Checked" else "Not checked yet",
            format?.let { "Connection" to "Interface ${it.interfaceNumber} · alt ${it.altSetting} · endpoint ${it.endpointAddress} · ${it.syncType.name.lowercase()}" }))

    val volume = appliedGainDb ?: usbVolume?.currentDb
    val volumeText = if (readiness.volumeVerified && volume != null) "${db(volume)} volume" else "volume not checked yet"
    val levelingOn = levelingMode != LevelingMode.OFF && snapshot.leveling.mode == levelingMode
    val dac = SoundStop(StopKind.DAC, dacProfileName ?: "USB DAC",
        when {
            failure == UsbPlaybackFailure.VOLUME_UNVERIFIED -> "Its volume couldn't be checked, so Evanna won't play."
            stream != null -> "Plays $bits-bit · ${khz(rate!!)} at $volumeText"
            plan != null -> "Will play $bits-bit · ${khz(rate!!)}"
            else -> "Waiting for playback"
        },
        if (failure == UsbPlaybackFailure.VOLUME_UNVERIFIED) StopState.PROBLEM else StopState.SAME,
        listOfNotNull(if (known) "Plays" to "$bits-bit · ${hz(rate!!)}" else null,
            volume?.let { "Volume" to "${db(it)}, set on the DAC" },
            "Volume check" to if (readiness.volumeVerified) "Confirmed" else "Not yet",
            "Leveling" to if (levelingOn) "${levelingMode.name.lowercase().replaceFirstChar { it.uppercase() }}, ${db(snapshot.leveling.offsetDb)} on the DAC" else "Off"))

    val stops = listOfNotNull(file?.let { if (failure?.name?.startsWith("SOURCE_") == true) it.copy(state = StopState.PROBLEM) else it },
        evanna, usb, dac, SoundStop(StopKind.HEADPHONES, "Your headphones", "Where you hear it", StopState.END))
    val problems = buildList {
        if (lost > 0) add("lost sound" to "This phone's USB dropped $lost tiny ${if (lost == 1L) "bit" else "bits"} of sound. You may hear a faint crackle.")
        if (clipped > 0) add("cut-off peaks" to "$clipped loud peaks were cut off. You may hear a little distortion.")
        if (ranShort > 0) add("sound ran short" to "The DAC ran out of sound $ranShort ${if (ranShort == 1L) "time" else "times"}. You may hear gaps.")
    }
    val (verdict, why) = when {
        failure != null -> "Playback stopped" to "${failure.plain()}."
        problems.size == 1 -> "1 problem: ${problems[0].first}" to problems[0].second
        problems.size > 1 -> "${problems.size} problems found" to problems.joinToString(" ") { it.second }
        stream == null && state == UsbPlaybackState.PREPARING -> "Getting ready" to "Evanna is setting up your DAC."
        stream == null -> "Paused" to "Press Play to see this song's path."
        usbSamplePathEligible() -> "An exact copy of your file" to "Every bit of this song reaches your DAC unchanged."
        reasons.isNotEmpty() -> (if (reasons.size == 1) "Changed once on the way" else "Changed ${reasons.size} times on the way") to reasons.joinToString(" ")
        source?.strictEligible == false -> "Nothing changed on the way" to "This file is compressed, so an exact copy isn't possible. Evanna adds no changes."
        else -> "Checking the path" to "Evanna is still confirming your DAC's clock and volume."
    }
    SoundPath(verdict, why, playing, stops, offerOtherOutput = lost > 0)
}

private fun androidPath(snapshot: UsbPlaybackSnapshot, file: SoundStop?, playing: Boolean): SoundPath = with(snapshot) {
    val out = androidStream
    val exact = out?.bitPerfect == true
    val evanna = SoundStop(StopKind.EVANNA, "Evanna", if (eqProcessed) "EQ on, so the sound changes on purpose." else "Left untouched. EQ is off.",
        if (eqProcessed) StopState.CHANGED else StopState.SAME, eqDetails())
    val android = when {
        out == null -> SoundStop(StopKind.ANDROID, "Android", "Waiting for playback")
        exact -> SoundStop(StopKind.ANDROID, "Android", "Passed on unchanged, Android says. Evanna can't check it.", StopState.SAME,
            listOfNotNull("Mode" to "Bit-perfect mixer", out.bits?.let { "Gets" to "$it-bit · ${hz(out.sampleRate)}" }, "Volume" to "Not changed by Android"))
        else -> SoundStop(StopKind.ANDROID, "Android", "Mixed by Android, which may change the rate. Uses the phone's volume.", StopState.CHANGED,
            listOf("Gets" to "${hz(out.sampleRate)} · float", "Volume" to "Phone volume", "Exact copy" to "Not promised"))
    }
    val device = when (out?.deviceType) {
        TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY -> listOf(SoundStop(StopKind.DAC, "USB DAC", "Plays what Android sends"),
            SoundStop(StopKind.HEADPHONES, "Your headphones", "Where you hear it", StopState.END))
        TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> listOf(
            SoundStop(StopKind.BLUETOOTH, "Bluetooth", "Sends a compressed copy over the air", StopState.CHANGED),
            SoundStop(StopKind.HEADPHONES, "Your headphones", "Where you hear it", StopState.END))
        TYPE_BUILTIN_SPEAKER -> listOf(SoundStop(StopKind.SPEAKER, "Phone speaker", "Where you hear it", StopState.END))
        else -> listOf(SoundStop(StopKind.HEADPHONES, "Your headphones", "Where you hear it", StopState.END))
    }
    val bluetooth = device.any { it.kind == StopKind.BLUETOOTH }
    val (verdict, why) = when {
        failure != null -> "Playback stopped" to "${failure.plain()}."
        out == null -> "Paused" to "Press Play to see this song's path."
        exact && !eqProcessed -> "An exact copy, Android says" to "Android reports it passes this song on unchanged. Evanna can't check that itself."
        bluetooth -> "Changed for Bluetooth" to "Bluetooth always sends a compressed copy, and Android mixes it first."
        else -> "Android may change the sound" to "Android mixes it with other sounds and may change its rate. It uses the phone's volume."
    }
    SoundPath(verdict, why, playing, listOfNotNull(file, evanna, android) + device)
}

// AudioDeviceInfo types, copied so JVM tests need no Android classes.
private const val TYPE_BUILTIN_SPEAKER = 2
private const val TYPE_BLUETOOTH_A2DP = 8
private const val TYPE_USB_DEVICE = 11
private const val TYPE_USB_ACCESSORY = 12
private const val TYPE_USB_HEADSET = 22
private const val TYPE_BLE_HEADSET = 26
private const val TYPE_BLE_SPEAKER = 27

internal fun UsbPlaybackFailure.plain(): String = when (this) {
    UsbPlaybackFailure.NOT_OPEN -> "No output is open"
    UsbPlaybackFailure.UNSUPPORTED_FORMAT -> "This output can't play this song's format"
    UsbPlaybackFailure.CONVERSION_REQUIRED -> "Strict USB won't convert this song. Pick Compatibility USB to play it"
    UsbPlaybackFailure.STREAM_MISMATCH, UsbPlaybackFailure.CLOCK_MISMATCH -> "The DAC didn't switch to the right format"
    UsbPlaybackFailure.CLOCK_UNVERIFIED, UsbPlaybackFailure.FEEDBACK_UNVERIFIED -> "The DAC's timing couldn't be checked"
    UsbPlaybackFailure.VOLUME_UNVERIFIED -> "The DAC's volume couldn't be checked"
    UsbPlaybackFailure.DISCONNECTED -> "The DAC was unplugged"
    UsbPlaybackFailure.TRANSFER_FAILED -> "USB stopped sending sound"
    UsbPlaybackFailure.WRITE_FAILED, UsbPlaybackFailure.OUTPUT_FAILED -> "The output stopped taking sound"
    UsbPlaybackFailure.STOP_FAILED, UsbPlaybackFailure.CLOSE_FAILED -> "The output didn't stop cleanly"
    UsbPlaybackFailure.SOURCE_OPEN_FAILED, UsbPlaybackFailure.SOURCE_READ_FAILED,
    UsbPlaybackFailure.SOURCE_CLOSE_FAILED, UsbPlaybackFailure.SOURCE_SEEK_FAILED -> "The song file couldn't be read"
    UsbPlaybackFailure.DRAIN_UNVERIFIED -> "The end of the song couldn't be confirmed"
    UsbPlaybackFailure.ROUTE_CHANGED -> "The audio output changed. Pick it again"
}

/** 44100 → "44.1 kHz", 96000 → "96 kHz". */
internal fun khz(rate: Int): String =
    (if (rate % 1000 == 0) "${rate / 1000}" else String.format(Locale.US, "%.1f", rate / 1000.0)) + " kHz"
private fun hz(rate: Int) = String.format(Locale.US, "%,d Hz", rate)
/** "−1 dB", "−6.3 dB", "+2 dB"; a true minus sign, as the volume readouts use. */
private fun db(value: Float): String {
    if (abs(value) < 0.05f) return "0 dB"
    val text = String.format(Locale.US, "%.1f", abs(value)).removeSuffix(".0")
    return (if (value < 0) "−" else "+") + text + " dB"
}

// The sheet's stops travel in the session extras as one line each: kind, state, title, value, then key/value pairs.
private const val FIELD = '\u001E'
private const val PAIR = '\u001D'
private const val KEY = '\u001F'

internal fun encodeStop(stop: SoundStop): String = listOf(stop.kind.name, stop.state.name, stop.title, stop.value,
    stop.details.joinToString(PAIR.toString()) { (k, v) -> "$k$KEY$v" }).joinToString(FIELD.toString())

internal fun decodeStop(line: String): SoundStop? {
    if (line.length > 4096) return null
    val parts = line.split(FIELD)
    if (parts.size != 5) return null
    val kind = StopKind.entries.firstOrNull { it.name == parts[0] } ?: return null
    val state = StopState.entries.firstOrNull { it.name == parts[1] } ?: return null
    val details = if (parts[4].isEmpty()) emptyList() else parts[4].split(PAIR).map {
        val kv = it.split(KEY)
        if (kv.size != 2) return null
        kv[0] to kv[1]
    }
    if (details.size > 16) return null
    return SoundStop(kind, parts[2], parts[3], state, details)
}
