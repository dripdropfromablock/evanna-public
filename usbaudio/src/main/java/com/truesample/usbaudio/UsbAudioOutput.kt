// Modified for Evanna (2026-11-02): checked writes, native readiness, bounded drain and connection cleanup.
// Modified for Evanna (2026-11-04): separate processing evidence and clipped-float statistics.
// Modified for Evanna (2026-11-05): validated stereo crossfeed setter.
// Modified for Evanna (2026-11-07): per-band equalizer channels.
package com.truesample.usbaudio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/** One playback format a DAC advertises. */
data class DacFormat(
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    val syncType: SyncType,
    val maxPacketBytes: Int,
    /** Discrete sample rates, or null when the DAC accepts the continuous [minRate]..[maxRate] range. */
    val sampleRates: List<Int>?,
    val minRate: Int,
    val maxRate: Int,
) {
    enum class SyncType { NONE, ASYNC, ADAPTIVE, SYNC }
}

data class StreamInfo(
    /** The source's sample rate. */
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    val format: DacFormat,
    /** The rate the DAC runs at; differs from [sampleRate] when [resampled]. */
    val outputRate: Int,
    /** Rate the DAC reports after switching, or 0 if it cannot report one. */
    val deviceRate: Int,
    /** True when the DAC receives the source samples unchanged. */
    val bitPerfect: Boolean,
    /** True when the DAC lacks [sampleRate] and audio is converted to [outputRate]. */
    val resampled: Boolean,
    /** Enabled sample effects; rate/precision conversion is reported separately. */
    val processed:Boolean=false,
)

/** One way the DAC can run, from [UsbAudioOutput.outputModes]. */
data class OutputMode(
    /** Index into [UsbAudioOutput.formats]. */
    val formatIndex: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val subslotBytes: Int,
    val channels: Int,
)

/** How a source format would play on a DAC, from [UsbAudioOutput.plan]. */
data class PlaybackPlan(
    val outputRate: Int,
    val dacBits: Int,
    val resampled: Boolean,
    val bitPerfect: Boolean,
    val processed:Boolean=false,
)

/**
 * One equalizer filter. Frequencies in Hz, gain in dB; [q] sets the width (higher is narrower).
 * [channels]: 0 both, 1 left only, 2 right only.
 */
data class EqBand(val type: Type, val frequencyHz: Float, val gainDb: Float, val q: Float, val channels: Int = 0) {
    /**
     * The native order. LOW_PASS, HIGH_PASS and BAND_PASS boost or cut a range: their output, scaled by
     * 10^(dB/20) - 1, is added to the dry signal before the other types run as a chain.
     */
    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, BAND_PASS }
}

/** libsamplerate sinc converter used when the DAC lacks the source rate. */
enum class ResampleQuality { BEST, MEDIUM, FAST }

data class VolumeRange(val minDb: Float, val maxDb: Float, val stepDb: Float, val currentDb: Float)

/** Output-frame counters are concurrent snapshots, not a DAC presentation clock. */
data class StreamStats(val framesSent: Long, val underruns: Long, val transferErrors: Long, val disconnected: Boolean,
    val framesQueued: Long = 0, val framesCompleted: Long = 0, val inputEnded: Boolean = false,
    val clippedSamples: Long = 0)

/**
 * Plays PCM on a USB Audio Class DAC directly over USB, bypassing Android's
 * audio mixer and resampler. No root needed: the app only needs USB permission
 * for the device.
 *
 * PCM passed to [write] is always interleaved signed 32-bit little-endian,
 * left-justified (a 24-bit sample shifted left by 8, a 16-bit sample by 16).
 *
 * Threading: call [write] and [drain] from one playback thread. [stop] may be
 * called from any thread and makes a blocked [write] return -1. Only call
 * [start] or [close] once the playback thread has finished.
 */
class UsbAudioOutput private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private var handle: Long,
    val rawDescriptors: ByteArray,
) : Closeable {

    private val lock = UsbWriteGuard()

    /** Human-readable summary of the DAC's descriptors. */
    val description: String = NativeBridge.nativeDescribe(handle)

    val formats: List<DacFormat> = parseFormats(NativeBridge.nativeFormats(handle))

    /**
     * Every mode the DAC can stream, highest quality first: each playback format at each
     * sample rate it supports, limited to what fits the USB link.
     */
    val outputModes: List<OutputMode> = NativeBridge.nativeModes(handle).toList().chunked(5)
        .map { OutputMode(formatIndex = it[0], sampleRate = it[1], bitsPerSample = it[2], subslotBytes = it[3], channels = it[4]) }
        .distinct()
        .sortedWith(compareByDescending<OutputMode> { it.sampleRate }.thenByDescending { it.bitsPerSample })

    /** Master range (channel gains must be unity), or common channel range; null if unknown or unsupported. */
    val volumeRange: VolumeRange?
        get() = synchronized(lock) {
            checkOpen()
            val r = NativeBridge.nativeVolumeRange(handle)
            if (r[0] == 0) null else VolumeRange(r[1] / 256f, r[2] / 256f, r[3] / 256f, r[4] / 256f)
        }

    /**
     * Selects the matching DAC mode and starts streaming silence until [write] supplies audio.
     * With [mode] null the DAC runs at the source rate when it can (bit-perfect); otherwise
     * audio is converted to [mode], or to the best rate the DAC has. Conversion resamples with
     * [quality], and reducing bit depth uses TPDF dither; [StreamInfo.bitPerfect] is then false.
     * Throws [UsbAudioException] if the DAC (or [mode]) has no output with [channels] channels.
     * After silent start, explicitly set a calibrated volume and check [volumeVerified] before writing.
     * Opening/starting never changes volume or mute. Volume calls block; use a control thread.
     */
    fun start(
        sampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        quality: ResampleQuality = ResampleQuality.BEST,
        mode: OutputMode? = null,
    ): StreamInfo = synchronized(lock) {
        checkOpen()
        lock.checkIdle()
        val r = NativeBridge.nativeStart(
            handle, sampleRate, bitsPerSample, channels, quality.ordinal, mode?.formatIndex ?: -1, mode?.sampleRate ?: 0,
        )
        check(r.size==8){"USB stream report version mismatch"}
        StreamInfo(
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            format = formats[r[0]],
            outputRate = r[5],
            deviceRate = r[1],
            bitPerfect = r[4] == 1,
            resampled = r[6] == 1,
            processed=r[7]==1,
        )
    }

    /**
     * Works out how a source format would be played on this DAC without starting
     * anything, or returns null if the DAC cannot play it at all (e.g. wrong channel count).
     */
    fun plan(
        sampleRate: Int,
        bitsPerSample: Int,
        channels: Int,
        mode: OutputMode? = null,
    ): PlaybackPlan? = synchronized(lock) {
        checkOpen()
        val r = NativeBridge.nativePlan(
            handle, sampleRate, bitsPerSample, channels, mode?.formatIndex ?: -1, mode?.sampleRate ?: 0,
        ) ?: return null
        check(r.size==5){"USB plan report version mismatch"}
        PlaybackPlan(outputRate = r[0], dacBits = r[1], resampled = r[2] == 1, bitPerfect = r[3] == 1,processed=r[4]==1)
    }

    /**
     * Queues [frames] frames from a direct [pcm] buffer, blocking while the
     * internal buffer is full. Returns frames queued, or -1 once stopped or unplugged.
     * The little-endian buffer must have position zero and limit equal to capacity.
     * Use an aligned slice for a subrange. Zero frames does not start or access the DAC.
     */
    fun write(pcm: ByteBuffer, frames: Int): Int {
        validatePcmBuffer(pcm, frames)
        return lock.withWriter({ checkOpen(); handle }) { NativeBridge.nativeWrite(it, pcm, frames) }
    }

    /** Seals this stream and verifies queued PCM USB completions within 1500 ms, including SRC tail.
     * False means stopped/failed/unverified/timed out. Restart before further writes; this is not acoustic timing. */
    fun drain(): Boolean = lock.withWriter({ checkOpen(); handle }) { NativeBridge.nativeDrain(it) }

    /** Selected-mode clock configuration completed; unavailable rate readback still stays unknown. */
    fun clockConfigured(): Boolean = synchronized(lock) {
        handle != 0L && NativeBridge.nativeClockConfigured(handle)
    }

    /** Current transport/feedback evidence only; this does not verify hardware volume or physical fidelity. */
    fun feedbackReady(): Boolean = synchronized(lock) {
        handle != 0L && NativeBridge.nativeFeedbackReady(handle)
    }

    /** True only after native callbacks have finished; false retains ownership for cleanup retry. */
    fun stop(): Boolean = synchronized(lock) {
        handle == 0L || NativeBridge.nativeStop(handle)
    }

    /**
     * Applies an equalizer after any resampling (an empty [bands] list with 0 dB preamp turns it off).
     * Takes effect within ~10 ms, mid-song included. While it is on, audio is processed, so playback
     * is not bit-perfect. Settings persist across [start] calls until changed.
     */
    fun setEqualizer(bands: List<EqBand>, preampDb: Float) = synchronized(lock) {
        checkOpen()
        val flat = FloatArray(bands.size * 5)
        bands.forEachIndexed { i, b ->
            flat[i * 5] = b.type.ordinal.toFloat()
            flat[i * 5 + 1] = b.frequencyHz
            flat[i * 5 + 2] = b.gainDb
            flat[i * 5 + 3] = b.q
            flat[i * 5 + 4] = b.channels.toFloat()
        }
        NativeBridge.nativeSetEqualizer(handle, flat, preampDb)
    }

    /**
     * Stereo crossfeed after the equalizer: bs2b lowpass cut 300-2000 Hz and feed level 1-15 dB
     * (a higher level crosses less sound). [cutHz] 0 turns it off. Mono and multichannel streams
     * bypass it. Same timing and processing status rules as [setEqualizer].
     */
    fun setCrossfeed(cutHz: Int, feedDb: Float) = synchronized(lock) {
        require(cutHz == 0 || (cutHz in 300..2000 && feedDb.isFinite() && feedDb in 1f..15f)) { "Invalid crossfeed setting" }
        checkOpen()
        NativeBridge.nativeSetCrossfeed(handle, cutHz, feedDb)
    }

    /** True after verified set/readback on every required volume control; not an acoustic calibration. */
    fun volumeVerified(): Boolean = synchronized(lock) {
        handle != 0L && NativeBridge.nativeVolumeVerified(handle)
    }

    /** Sets master gain with unity channels, or every channel gain; requires silent start first. Does not unmute. */
    fun setVolumeDb(db: Float): Boolean = synchronized(lock) {
        checkOpen()
        NativeBridge.nativeSetVolume(handle, hardwareVolume256(db))
    }

    fun stats(): StreamStats = synchronized(lock) {
        checkOpen()
        val s = NativeBridge.nativeStats(handle)
        check(s.size == 8) { "Invalid USB statistics" }
        StreamStats(s[0], s[1], s[2], s[3] != 0L, s[4], s[5], s[6] != 0L, s[7])
    }

    /** Releases the DAC; failed cancellation or volume restoration throws and retains ownership for retry. */
    override fun close() = synchronized(lock) {
        if (handle == 0L) return
        lock.checkIdle()
        NativeBridge.nativeClose(handle)
        handle = 0L
        try { claimed.forEach { connection.releaseInterface(it) } } finally { connection.close() }
    }

    private fun checkOpen() = check(handle != 0L) { "UsbAudioOutput is closed" }

    companion object {
        /**
         * Takes exclusive control of [device]. The caller must already hold USB
         * permission for it (see [UsbManager.requestPermission]).
         */
        @Throws(UsbAudioException::class)
        fun open(usbManager: UsbManager, device: UsbDevice): UsbAudioOutput {
            val connection = usbManager.openDevice(device)
                ?: throw UsbAudioException("Android refused to open the DAC. Is USB permission granted?")
            val claimed = mutableListOf<UsbInterface>()
            var handle = 0L
            try {
                for (intf in playbackInterfaces(device)) {
                    // force = true detaches Android's own USB audio driver from the interface.
                    if (!connection.claimInterface(intf, true)) {
                        throw UsbAudioException("Could not take over interface ${intf.id} of the DAC.")
                    }
                    claimed += intf
                }
                if (claimed.isEmpty()) throw UsbAudioException("${device.productName ?: "This device"} is not a USB audio DAC.")
                val raw = connection.rawDescriptors
                    ?: throw UsbAudioException("Could not read the DAC's descriptors.")
                handle = NativeBridge.nativeOpen(connection.fileDescriptor, raw)
                return UsbAudioOutput(device, connection, claimed, handle, raw)
            } catch (e: Exception) {
                // Construction has not started transfers or changed gain; close native ownership before its FD.
                if (handle != 0L) NativeBridge.nativeClose(handle)
                try { claimed.forEach { connection.releaseInterface(it) } } finally { connection.close() }
                throw e
            }
        }

        /** Also append driver logs to [file], or stop when null. Useful on phones that hide app logcat output. */
        fun setLogFile(file: File?) = NativeBridge.nativeSetLogFile(file?.absolutePath)

        /** True when [device] exposes a USB Audio playback interface. */
        fun isUsbAudioOutput(device: UsbDevice): Boolean = playbackInterfaces(device).any {
            it.interfaceSubclass == SUBCLASS_STREAMING
        }

        private const val SUBCLASS_CONTROL = 1
        private const val SUBCLASS_STREAMING = 2

        /** The AudioControl interface plus every streaming interface with an isochronous OUT endpoint. */
        private fun playbackInterfaces(device: UsbDevice): List<UsbInterface> {
            val all = (0 until device.interfaceCount).map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            val streamingOut = all.filter { intf ->
                intf.interfaceSubclass == SUBCLASS_STREAMING &&
                    (0 until intf.endpointCount).map { intf.getEndpoint(it) }.any {
                        it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_OUT
                    }
            }.map { it.id }.toSet()
            // Each alternate setting is listed separately; claim each interface number once.
            return all.filter { it.interfaceSubclass == SUBCLASS_CONTROL || it.id in streamingOut }
                .distinctBy { it.id }
        }

        private fun parseFormats(flat: IntArray): List<DacFormat> {
            val formats = mutableListOf<DacFormat>()
            var i = 0
            while (i < flat.size) {
                val rateCount = flat[i + 11]
                val continuous = flat[i + 8] == 1
                formats += DacFormat(
                    interfaceNumber = flat[i],
                    altSetting = flat[i + 1],
                    endpointAddress = flat[i + 2],
                    channels = flat[i + 3],
                    subslotBytes = flat[i + 4],
                    bitResolution = flat[i + 5],
                    syncType = DacFormat.SyncType.entries[flat[i + 6]],
                    maxPacketBytes = flat[i + 7],
                    sampleRates = if (continuous) null else flat.copyOfRange(i + 12, i + 12 + rateCount).toList(),
                    minRate = flat[i + 9],
                    maxRate = flat[i + 10],
                )
                i += 12 + rateCount
            }
            return formats
        }
    }
}
