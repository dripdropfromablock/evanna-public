package com.evanna.player.sources.radio

import java.io.IOException
import java.io.InputStream

/** Keeps the opaque ICY metadata blocks out of the compressed audio stream. */
internal class IcyAudioInput(private val input: InputStream, private val interval: Int) : InputStream() {
    init { require(interval in 1..1024 * 1024) }
    private val single = ByteArray(1)
    private val metadata = ByteArray(4080)
    private var audioLeft = interval

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= target.size - length)
        if (length == 0) return 0
        if (audioLeft == 0) {
            val blocks = input.read()
            if (blocks < 0) return -1
            var remaining = blocks * 16
            while (remaining > 0) {
                val read = input.read(metadata, 0, remaining)
                if (read <= 0) throw IOException("RADIO_TRUNCATED_ICY_METADATA")
                remaining -= read
            }
            audioLeft = interval
        }
        val read = input.read(target, offset, minOf(audioLeft, length))
        if (read > 0) audioLeft -= read
        return read
    }
}
