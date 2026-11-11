package com.evanna.player.playback

import com.truesample.usbaudio.StreamInfo
import com.truesample.usbaudio.StreamStats
import java.nio.ByteBuffer

internal enum class PlaybackRoute { DIRECT_USB, ANDROID }
/** [bitPerfect]: Android confirmed its bit-perfect mixer for this stream; [bits] is the integer precision written. */
internal data class AndroidOutputInfo(val sampleRate: Int, val channels: Int, val deviceType: Int, val bitPerfect: Boolean = false, val bits: Int? = null)
internal sealed interface StartedOutput {
    data class Usb(val info: StreamInfo) : StartedOutput
    data class Android(val info: AndroidOutputInfo) : StartedOutput
}

/** One writer; stop interrupts it, and close/start follow its join. No backend silently changes route. */
internal interface PlaybackOutput {
    val route: PlaybackRoute
    val description: String
    fun startOutput(source: PcmFormat): StartedOutput
    fun ready(): Boolean
    fun stats(): StreamStats
    fun write(buffer: ByteBuffer, frames: Int): Int
    fun drain(): Boolean
    fun stop(): Boolean
    fun close()
}
