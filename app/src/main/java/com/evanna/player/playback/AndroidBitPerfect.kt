package com.evanna.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/** A platform mixer offer reduced to what the bit-perfect choice compares. */
internal data class MixerOffer(val sampleRate: Int, val channels: Int, val bits: Int, val bitPerfect: Boolean)

/** Only unchanged integer sources can use Android's bit-perfect mixer, at exactly their own rate, precision and channels. */
internal fun bitPerfectOffer(offers: List<MixerOffer>, source: PcmFormat): MixerOffer? =
    if (!source.strictEligible || source.significantBits !in listOf(16, 24, 32)) null
    else offers.firstOrNull { it.bitPerfect && it.sampleRate == source.sampleRate && it.channels == source.channels && it.bits == source.significantBits }

/** Left-justified S32 samples to little-endian 16/24/32-bit PCM; exact for sources of that precision. */
internal fun packPcm(source: ByteBuffer, first: Int, count: Int, bits: Int, target: ByteArray): Int {
    require(bits in listOf(16, 24, 32) && target.size >= count * bits / 8)
    val width = bits / 8
    for (i in 0 until count) {
        val value = source.getInt((first + i) * 4) shr (32 - bits)
        for (b in 0 until width) target[i * width + b] = (value shr (8 * b)).toByte()
    }
    return count * width
}

/** Android 14+ only, and only when a USB output lists a bit-perfect mixer; never chosen automatically. */
internal fun androidBitPerfectAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 34 && runCatching { AndroidMixer.available(context.getSystemService(AudioManager::class.java)) }.getOrDefault(false)

@RequiresApi(34)
internal object AndroidMixer {
    val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val encodings = mapOf(AudioFormat.ENCODING_PCM_16BIT to 16, AudioFormat.ENCODING_PCM_24BIT_PACKED to 24, AudioFormat.ENCODING_PCM_32BIT to 32)
    private fun usb(manager: AudioManager) = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    private fun offers(manager: AudioManager, device: AudioDeviceInfo) = manager.getSupportedMixerAttributes(device).mapNotNull { mixer ->
        val bits = encodings[mixer.format.encoding] ?: return@mapNotNull null
        val channels = when (mixer.format.channelMask) { AudioFormat.CHANNEL_OUT_MONO -> 1; AudioFormat.CHANNEL_OUT_STEREO -> 2; else -> return@mapNotNull null }
        MixerOffer(mixer.format.sampleRate, channels, bits, mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) to mixer
    }

    fun available(manager: AudioManager) = usb(manager).any { device -> offers(manager, device).any { it.first.bitPerfect } }

    /** Sets and reads back the matching bit-perfect mixer; the caller clears it on release. */
    fun apply(manager: AudioManager, source: PcmFormat): Pair<AudioDeviceInfo, AudioMixerAttributes> {
        for (device in usb(manager)) {
            val offers = offers(manager, device)
            val chosen = bitPerfectOffer(offers.map { it.first }, source) ?: continue
            val mixer = offers.first { it.first == chosen }.second
            check(manager.setPreferredMixerAttributes(attributes, device, mixer)) { "Android refused the bit-perfect mixer" }
            if (manager.getPreferredMixerAttributes(attributes, device) != mixer) { clear(manager, device); error("Android did not apply the bit-perfect mixer") }
            return device to mixer
        }
        throw UnsupportedOperationException("BIT_PERFECT_FORMAT_UNSUPPORTED")
    }

    fun clear(manager: AudioManager, device: AudioDeviceInfo) { runCatching { manager.clearPreferredMixerAttributes(attributes, device) } }
    fun encoding(mixer: AudioMixerAttributes) = mixer.format.encoding
}
