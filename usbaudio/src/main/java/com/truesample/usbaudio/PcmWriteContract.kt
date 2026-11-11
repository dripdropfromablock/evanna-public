// Added for Evanna (2026-11-01): explicit canonical PCM buffer contract.
package com.truesample.usbaudio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun validatePcmBuffer(pcm: ByteBuffer, frames: Int) {
    require(frames >= 0) { "frames must not be negative" }
    require(pcm.isDirect) { "pcm must be a direct ByteBuffer" }
    require(pcm.order() == ByteOrder.LITTLE_ENDIAN) { "pcm must be little-endian" }
    // JNI reads the base address and capacity, not Java's position/limit.
    require(pcm.position() == 0 && pcm.limit() == pcm.capacity()) {
        "pcm must have position zero and limit equal to capacity; use a bounded slice"
    }
}
