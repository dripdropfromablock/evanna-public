package com.evanna.player.playback

import com.truesample.usbaudio.VolumeRange
import kotlin.math.floor
import kotlin.math.roundToInt

/** Gain for the first music stream of a new USB connection, unless the user saves another (Settings). */
internal const val USB_START_VOLUME_DEFAULT_DB = -5f
/** Lowest saved starting volume; any DAC whose range ends higher starts at its own minimum instead. */
internal const val USB_START_VOLUME_MIN_DB = -80f
internal const val USB_VOLUME_LIMIT_DEFAULT_DB = 0f
internal const val USB_KEY_STEP_DEFAULT_DB = 1f

private fun units(value: Float): Int? {
    val scaled = value.toDouble() * 256
    return if (scaled.isFinite() && scaled in -32768.0..32767.0 && scaled % 1 == 0.0) scaled.toInt() else null
}

/**
 * USB gain readback is control evidence, not a measurement of headphone output. The first stream of a connection
 * starts at [startDb], rounded down within the DAC's range and [limitDb]. Later streams retain [previous] unless
 * the ceiling requires a reduction; a changed range never raises gain.
 */
internal fun usbMusicVolume(range: VolumeRange?, previous: Float? = null, startDb: Float = USB_START_VOLUME_DEFAULT_DB,
    limitDb: Float = USB_VOLUME_LIMIT_DEFAULT_DB): VolumeRange? {
    range ?: return null
    val minimum = units(range.minDb) ?: return null
    val maximum = units(range.maxDb) ?: return null
    val step = units(range.stepDb)?.takeIf { it > 0 } ?: return null
    val current = units(range.currentDb) ?: return null
    if (minimum >= 0 || maximum <= minimum || current !in minimum..maximum || (current - minimum) % step != 0) return null
    if (!limitDb.isFinite() || limitDb !in -128f..0f) return null
    val limit = floor(limitDb.toDouble()*256).toInt()
    if (limit < minimum) return null
    val ceiling = minimum + (minOf(maximum, limit) - minimum) / step * step
    val old = previous?.let { units(it) ?: return null }
    val target = when {
        old == null -> snapDown(startDb, minimum, ceiling, step)
        old in minimum..maximum && (old - minimum) % step == 0 -> minOf(old,ceiling)
        else -> minimum
    }
    if (old != null && target > old) return null
    return VolumeRange(minimum / 256f, ceiling / 256f, step / 256f, target / 256f)
}

/** The grid value at or below [db] inside this (already checked) music range; null for a non-finite request. */
internal fun VolumeRange.snapDown(db: Float): Float? {
    if (!db.isFinite()) return null
    val minimum = units(minDb) ?: return null
    val ceiling = units(maxDb) ?: return null
    val step = units(stepDb)?.takeIf { it > 0 } ?: return null
    return snapDown(db, minimum, ceiling, step) / 256f
}

/** A savable starting volume: whole decibels from [USB_START_VOLUME_MIN_DB] to 0 dB; null otherwise. */
internal fun validStartVolume(db: Float): Float? = db.takeIf { it.isFinite() && it in USB_START_VOLUME_MIN_DB..0f && it % 1f == 0f }
internal fun validVolumeLimit(db: Float): Float? = validStartVolume(db)
internal fun validUsbKeyStep(db: Float): Float? = db.takeIf {it in listOf(0.5f,1f,2f,3f)}

/** Button steps use whole hardware increments; a coarser DAC still moves by at least one increment. */
internal fun VolumeRange.adjusted(direction:Int,keyStepDb:Float):Float? {
    if(direction !in listOf(-1,1) || validUsbKeyStep(keyStepDb)==null)return null
    val hardware=units(stepDb)?.takeIf {it>0} ?: return null
    val requested=units(keyStepDb) ?: return null
    val delta=maxOf(hardware,requested/hardware*hardware)/256f
    return snapDown(currentDb+direction*delta)
}

/** Rounds to the nearest grid value for display while a slider moves; the output still rounds down on commit. */
internal fun VolumeRange.nearest(db: Float): Float =
    (minDb + ((db - minDb) / stepDb).roundToInt() * stepDb).coerceIn(minDb, maxDb)

private fun snapDown(db: Float, minimum: Int, ceiling: Int, step: Int): Int {
    if (!db.isFinite()) return minimum
    val requested = floor(db.toDouble() * 256).coerceIn(minimum.toDouble(), ceiling.toDouble()).toInt()
    return minimum + (requested - minimum) / step * step
}

/** Android's absolute slider represents the hardware grid; relative keys still use the saved key step. */
internal data class KeyVolumeScale(val minDb:Float,val stepDb:Float,val positions:Int) {
    fun dbAt(index:Int):Float=minDb+index.coerceIn(0,positions-1)*stepDb
    fun indexFor(db:Float):Int=if(!db.isFinite())0 else floor((db.toDouble()-minDb)/stepDb).toInt().coerceIn(0,positions-1)
}
internal fun keyVolumeScale(range:VolumeRange):KeyVolumeScale? {
    val minimum=units(range.minDb) ?: return null
    val maximum=units(range.maxDb) ?: return null
    val step=units(range.stepDb)?.takeIf {it>0} ?: return null
    val current=units(range.currentDb) ?: return null
    if(minimum>=0 || maximum>0 || maximum<minimum || (maximum-minimum)%step!=0 ||
        current !in minimum..maximum || (current-minimum)%step!=0)return null
    return KeyVolumeScale(range.minDb,range.stepDb,(maximum-minimum)/step+1)
}
internal data class UsbDeviceVolume(val scale:KeyVolumeScale,val index:Int)
internal fun usbDeviceVolume(snapshot:UsbPlaybackSnapshot):UsbDeviceVolume? {
    if(snapshot.route!=PlaybackRoute.DIRECT_USB || snapshot.state !in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING) ||
        snapshot.failure!=null || !snapshot.readiness.clockConfigured || !snapshot.readiness.feedbackReady ||
        !snapshot.readiness.volumeVerified || snapshot.generation<0 || validVolumeLimit(snapshot.volumeLimitDb)==null ||
        validUsbKeyStep(snapshot.usbKeyStepDb)==null)return null
    val gain=snapshot.usbVolume ?: return null
    if(gain.maxDb>snapshot.volumeLimitDb)return null
    val scale=keyVolumeScale(gain) ?: return null
    return UsbDeviceVolume(scale,scale.indexFor(gain.currentDb))
}
