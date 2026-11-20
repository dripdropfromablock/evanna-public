package com.evanna.player.sources.smb

import com.evanna.player.network.CancelScope
import java.io.IOException

internal data class SmbFileStamp(
    val length: Long,
    val index: Long,
    val created: Long,
    val modified: Long,
    val changed: Long,
)

internal class SmbReader(
    private val cancel: CancelScope,
    private val expected: SmbFileStamp,
    private val stamp: () -> SmbFileStamp,
    private val readAt: (ByteArray, Long, Int, Int) -> Int,
) {
    val length: Long = expected.length

    init {
        if (length <= 0) throw IOException("SOURCE_AUDIO_REQUIRED")
    }

    fun read(offset: Long, size: Int, output: ByteArray): Int {
        try {
            cancel.checkActive()
            if (offset < 0 || size !in 0..output.size) throw IOException("INVALID_SOURCE_READ")
            if (stamp() != expected) throw IOException("SOURCE_REPRESENTATION_CHANGED")
            val total = minOf(size.toLong(), (length - offset).coerceAtLeast(0)).toInt()
            var copied = 0
            while (copied < total) {
                cancel.checkActive()
                val count = minOf(total - copied, SMB_BLOCK_BYTES)
                if (readAt(output, offset + copied, copied, count) != count) throw IOException("SOURCE_SHORT_READ")
                copied += count
            }
            if (total != 0 && stamp() != expected) throw IOException("SOURCE_REPRESENTATION_CHANGED")
            cancel.checkActive()
            return total
        } catch (failure: Exception) {
            val error = when {
                cancel.cancelled -> IOException("SOURCE_CANCELLED")
                failure is IOException -> failure
                else -> IOException("SOURCE_READ_FAILED")
            }
            cancel.cancel()
            throw error
        }
    }
}

internal const val SMB_BLOCK_BYTES = 64 * 1024
