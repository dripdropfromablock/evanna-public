package com.evanna.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal fun audioBufferSize(minimum: Int, sampleRate: Int, channels: Int, bytesPerSample: Int): Int {
    val frameSize = channels * bytesPerSample
    val requested = maxOf(minimum, sampleRate / 10 * frameSize)
    return (requested + frameSize - 1) / frameSize * frameSize
}

internal fun openAndroidAudioOutput(context: Context, bitPerfect: Boolean = false): AndroidAudioOutput {
    val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    check(!bitPerfect || androidBitPerfectAvailable(context)) { "Android bit-perfect output is unavailable" }
    return AndroidAudioOutput({ source, pinned -> PlatformAudioTrack(manager, source, pinned, bitPerfect) }, bitPerfect = bitPerfect)
}

/** No system volume, track gain, effects or native USB commands are issued here. The bit-perfect mode (Android 14+)
 * writes the unchanged integers through Android's bit-perfect mixer, which it sets, reads back and clears on release. */
@android.annotation.SuppressLint("WrongConstant") // setEncoding gets ENCODING_PCM_FLOAT or the encoding Android's mixer reported.
private class PlatformAudioTrack(private val manager: AudioManager, source: PcmFormat, pinned: Int?, override val bitPerfect: Boolean) : AndroidTrackSink {
    private val track: AudioTrack
    private var mixerDevice: android.media.AudioDeviceInfo? = null
    override val encodingBits = if (bitPerfect) source.significantBits else 0
    private val bytes = ByteArray(if (bitPerfect) 4096 * 2 * 4 else 0)
    @Volatile private var playing = false
    @Volatile private var changed = false
    @Volatile private var selectedDevice = 0
    private var selectedType = 0
    private var frozenHead = 0
    private var primed = 0
    override val sampleRate get() = track.sampleRate
    override val channels get() = track.channelCount
    override val primingFrames get() = primed
    override val deviceId get() = selectedDevice
    override val deviceType get() = selectedType
    override val routeChanged get() = changed || (playing && selectedDevice != 0 && track.routedDevice?.id != selectedDevice)
    override val playbackHead get() = if (playing) track.playbackHeadPosition else frozenHead
    override val underruns get() = track.underrunCount
    private val routing = AudioRouting.OnRoutingChangedListener {
        if (playing && selectedDevice != 0 && track.routedDevice?.id != selectedDevice) {
            changed = true
            // Stop consumption promptly; the controller still owns flush/join/release.
            try { track.pause() } catch (_: IllegalStateException) { }
        }
    }

    init {
        if (pinned != null) check(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.id == pinned }) {
            "Select Android output again after a device change"
        }
        val mask = if (source.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        var attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        var encoding = AudioFormat.ENCODING_PCM_FLOAT
        if (bitPerfect) {
            if (Build.VERSION.SDK_INT >= 34) {
                val (device, mixer) = AndroidMixer.apply(manager, source)
                mixerDevice = device; attributes = AndroidMixer.attributes; encoding = AndroidMixer.encoding(mixer)
            } else throw IllegalStateException("Android bit-perfect output needs Android 14")
        }
        try {
            val minimum = AudioTrack.getMinBufferSize(source.sampleRate, mask, encoding)
            check(minimum > 0) { "Android does not support this PCM configuration" }
            track = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(source.sampleRate).setChannelMask(mask).setEncoding(encoding).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(audioBufferSize(minimum, source.sampleRate, source.channels, if (bitPerfect) encodingBits / 8 else 4))
                .build()
        } catch (failure: Exception) { clearMixer(); throw failure }
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED && track.sampleRate == source.sampleRate && track.channelCount == source.channels)
            mixerDevice?.let { check(track.setPreferredDevice(it)) }
            // Observe/guard Android's chosen device; changing preference can invalidate buffered tracks.
            track.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        } catch (failure: Exception) { track.release(); clearMixer(); throw failure }
    }

    private fun clearMixer() {
        if (Build.VERSION.SDK_INT >= 34) mixerDevice?.let { AndroidMixer.clear(manager, it) }
        mixerDevice = null
    }

    override fun play() {
        val width = if (bitPerfect) encodingBits / 8 else 1
        val silenceFloats = FloatArray(if (bitPerfect) 0 else track.bufferSizeInFrames * channels)
        val silenceBytes = ByteArray(if (bitPerfect) track.bufferSizeInFrames * channels * width else 0)
        val size = if (bitPerfect) silenceBytes.size else silenceFloats.size
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + 1000
        while (offset < size) {
            check(SystemClock.elapsedRealtime() < deadline) { "Android silence priming timed out" }
            val count = if (bitPerfect) track.write(silenceBytes, offset, size - offset, AudioTrack.WRITE_NON_BLOCKING)
                else track.write(silenceFloats, offset, size - offset, AudioTrack.WRITE_NON_BLOCKING)
            check(count >= 0 && count % (channels * width) == 0)
            if (count == 0) SystemClock.sleep(5) else offset += count
        }
        primed = offset / (channels * width)
        track.play(); playing = true
        while (track.routedDevice == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5)
        val routed = checkNotNull(track.routedDevice) { "Android output device unavailable" }
        selectedDevice = routed.id; selectedType = routed.type
        check(ready())
    }

    override fun ready(): Boolean = playing && !changed && track.playState == AudioTrack.PLAYSTATE_PLAYING && track.routedDevice?.id == selectedDevice
    override fun write(data: FloatArray, offset: Int, count: Int) = track.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
    override fun writePcm(data: java.nio.ByteBuffer, offset: Int, count: Int): Int {
        val width = encodingBits / 8
        val written = track.write(bytes, 0, packPcm(data, offset, count, encodingBits, bytes), AudioTrack.WRITE_NON_BLOCKING)
        return if (written < 0 || written % width != 0) -1 else written / width
    }
    override fun pauseAndFlush() {
        playing = false
        track.pause()
        frozenHead = track.playbackHeadPosition
        track.flush()
    }
    override fun release() {
        playing = false
        track.removeOnRoutingChangedListener(routing)
        track.release()
        clearMixer()
    }
}
