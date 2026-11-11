package com.evanna.player.playback

import android.hardware.usb.UsbManager
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import com.evanna.player.usb.dacIdentity

/** Call on the controller's IO lane with permission already granted. No calibration is supplied by default. */
internal fun openUsbPlaybackOutput(manager: UsbManager, deviceId: Int): NativeUsbPlaybackOutput {
    val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId }
        ?: throw UsbAudioException("The USB connection is no longer available")
    if (!manager.hasPermission(device) || !UsbAudioOutput.isUsbAudioOutput(device))
        throw UsbAudioException("USB playback permission or capability is unavailable")
    val output = UsbAudioOutput.open(manager, device)
    try {
        val hash = MessageDigest.getInstance("SHA-256").digest(output.rawDescriptors)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val identity = UsbDacIdentity(UUID.randomUUID().toString(), deviceId, device.vendorId, device.productId, hash)
        val profileKey=runCatching {manager.deviceList.values.firstOrNull {it.deviceId==deviceId}?.dacIdentity()?.key}.getOrNull().orEmpty()
        return NativeUsbPlaybackOutput(identity, AndroidUsbDac(output),profileKey)
    } catch (failure: Exception) {
        output.close()
        throw failure
    }
}

private class AndroidUsbDac(private val output: UsbAudioOutput) : UsbDacConnection {
    override val description get() = output.description
    override val formats get() = output.formats
    override val outputModes get() = output.outputModes
    override val volumeRange get() = output.volumeRange
    override fun setEqualizer(bands: List<EqBand>, preampDb: Float) = output.setEqualizer(bands, preampDb)
    override fun setCrossfeed(cutHz:Int,feedDb:Float) = output.setCrossfeed(cutHz,feedDb)
    override fun plan(source: PcmFormat, mode: OutputMode?) =
        output.plan(source.sampleRate, source.significantBits, source.channels, mode)
    // BEST measured 0.2-1.9x real time on this phone's small cores and underran; MEDIUM measured 4.7x or faster.
    override fun start(source: PcmFormat, mode: OutputMode) =
        output.start(source.sampleRate, source.significantBits, source.channels, ResampleQuality.MEDIUM, mode)
    override fun clockConfigured() = output.clockConfigured()
    override fun feedbackReady() = output.feedbackReady()
    override fun volumeVerified() = output.volumeVerified()
    override fun setVolumeDb(db: Float) = output.setVolumeDb(db)
    override fun stats() = output.stats()
    override fun write(buffer: ByteBuffer, frames: Int) = output.write(buffer, frames)
    override fun drain() = output.drain()
    override fun stop() = output.stop()
    override fun close() = output.close()
}
