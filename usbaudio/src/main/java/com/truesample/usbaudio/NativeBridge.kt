// Modified for Evanna (2026-11-02): expose clock/transport/volume/completion evidence and safe cleanup.
// Modified for Evanna (2026-11-05): crossfeed setter.
package com.truesample.usbaudio

import java.nio.ByteBuffer

internal object NativeBridge {
    init {
        System.loadLibrary("usbaudio")
    }

    external fun nativeOpen(fd: Int, rawDescriptors: ByteArray): Long
    external fun nativeDescribe(handle: Long): String
    external fun nativeFormats(handle: Long): IntArray
    external fun nativeStart(
        handle: Long, sampleRate: Int, bitsPerSample: Int, channels: Int, quality: Int, modeIndex: Int, modeRate: Int,
    ): IntArray
    external fun nativeWrite(handle: Long, pcm: ByteBuffer, frames: Int): Int
    external fun nativeDrain(handle: Long): Boolean
    external fun nativeFeedbackReady(handle: Long): Boolean
    external fun nativeVolumeVerified(handle: Long): Boolean
    external fun nativeClockConfigured(handle: Long): Boolean
    external fun nativeStop(handle: Long): Boolean
    external fun nativeClose(handle: Long)
    external fun nativeVolumeRange(handle: Long): IntArray
    external fun nativeSetVolume(handle: Long, value256: Int): Boolean
    external fun nativeStats(handle: Long): LongArray
    external fun nativeSetLogFile(path: String?)
    external fun nativePlan(
        handle: Long, sampleRate: Int, bitsPerSample: Int, channels: Int, modeIndex: Int, modeRate: Int,
    ): IntArray?
    external fun nativeModes(handle: Long): IntArray
    external fun nativeSetEqualizer(handle: Long, bands: FloatArray, preampDb: Float)
    external fun nativeSetCrossfeed(handle: Long, cutHz: Int, feedDb: Float)
}
