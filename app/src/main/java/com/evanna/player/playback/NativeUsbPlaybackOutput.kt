package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import com.evanna.player.equalizer.Crossfeed
import com.evanna.player.loudness.leveledUsbGain

internal data class UsbDacIdentity(
    val sessionId: String, val deviceId: Int, val vendorId: Int, val productId: Int, val descriptorSha256: String,
)

/** Trusted measurement of the current physical chain and full-scale analysis fixture, using suitable equipment.
 * No default calibration/approval workflow exists. Reconnect or changing the physical load requires new evidence. */
internal data class UsbVolumeCalibration(
    val identity: UsbDacIdentity, val sampleRate: Int, val significantBits: Int, val channels: Int,
    val mode: OutputMode, val volumeDb: Float,
)

/** Android/native IO boundary; production forwards to the separately licensed USB module. */
internal interface UsbDacConnection {
    val description: String
    val formats: List<DacFormat>
    val outputModes: List<OutputMode>
    val volumeRange: VolumeRange? get() = null
    fun setEqualizer(bands: List<EqBand>, preampDb: Float)
    /** [cutHz] 0 turns it off. Fakes may ignore it: planned processing then disagrees and playback fails closed. */
    fun setCrossfeed(cutHz:Int,feedDb:Float) {}
    fun plan(source: PcmFormat, mode: OutputMode?): PlaybackPlan?
    fun start(source: PcmFormat, mode: OutputMode): StreamInfo
    fun clockConfigured(): Boolean
    fun feedbackReady(): Boolean
    fun volumeVerified(): Boolean
    fun setVolumeDb(db: Float): Boolean
    fun stats(): StreamStats
    fun write(buffer: ByteBuffer, frames: Int): Int
    fun drain(): Boolean = false
    fun stop(): Boolean
    fun close()
}

/** Commands/calibration share a monitor; writer/readiness never hold it while native write blocks. */
internal class NativeUsbPlaybackOutput(
    val identity: UsbDacIdentity, private val dac: UsbDacConnection,
    override val profileIdentityKey:String?=null,
) : UsbPlaybackOutput {
    override val description = dac.description
    val modes = dac.outputModes.toList()
    private var calibration: UsbVolumeCalibration? = null
    private var plannedSource: PcmFormat? = null
    private var plannedMode: OutputMode? = null
    private var planned: PlaybackPlan? = null
    private var prepared = false
    private var restartBlocked = false
    @Volatile private var active = false
    @Volatile private var volumeAccepted = false
    @Volatile private var closed = false
    @Volatile override var usbVolume: VolumeRange? = null
        private set
    @Volatile override var startVolumeDb: Float = USB_START_VOLUME_DEFAULT_DB
    @Volatile override var volumeLimitDb: Float = USB_VOLUME_LIMIT_DEFAULT_DB
    private var musicRange: VolumeRange? = null
    private var lastMusicGain: Float? = null
    private var levelingOffsetDb=0f
    @Volatile override var appliedGainDb:Float?=null
        private set

    @Synchronized override fun setLevelingOffsetDb(db:Float):Boolean {
        if(active || closed || restartBlocked || !db.isFinite() || db !in -60f..30f || calibration!=null && db!=0f)return false
        levelingOffsetDb=db;return true
    }

    /** Trusted measured evidence only. Applying/replacing it requires an inactive PCM pipeline. */
    @Synchronized fun setCalibration(value: UsbVolumeCalibration?): Boolean {
        check(!active && !closed) { "Stop playback before changing calibration" }
        calibration = null
        lastMusicGain = null
        clearPlan()
        if (restartBlocked || levelingOffsetDb!=0f || value == null || value.identity != identity ||
            value.sampleRate <= 0 || value.significantBits !in listOf(16, 24, 32) || value.channels !in 1..2 ||
            !value.volumeDb.isFinite() || value.volumeDb !in -128f..0f || value.volumeDb.toDouble() * 256 % 1 != 0.0)
            return false
        val mode = value.mode
        if (mode.sampleRate <= 0 || mode.bitsPerSample !in 1..32 || mode.subslotBytes !in 1..4) return false
        val format = dac.formats.getOrNull(mode.formatIndex) ?: return false
        val supportedRate = format.sampleRates?.contains(mode.sampleRate)
            ?: (mode.sampleRate in format.minRate..format.maxRate)
        if (mode !in modes || mode.channels != value.channels || format.channels != value.channels ||
            mode.bitsPerSample != format.bitResolution || mode.subslotBytes != format.subslotBytes || !supportedRate)
            return false
        calibration = value
        return true
    }

    @Synchronized override fun setEqualizer(bands: List<EqBand>, preampDb: Float) {
        check(!closed && !restartBlocked)
        dac.setEqualizer(bands, preampDb)
    }

    @Synchronized override fun setCrossfeed(level:Crossfeed) {
        check(!closed && !restartBlocked)
        dac.setCrossfeed(level.cutHz,level.feedDb)
    }

    @Synchronized override fun plan(source: PcmFormat): PlaybackPlan? = plan(source, null as OutputMode?)

    /** Prefers an exact divisor of the song rate (88.2 kHz -> 44.1 kHz, 96 kHz -> 48 kHz), which converts cheaply. */
    @Synchronized override fun plan(source: PcmFormat, maxRate: Int): PlaybackPlan? {
        if (source !is TrackPcmFormat) return plan(source)
        if(maxRate<=0){clearPlan();return null}
        val fitting = modes.filter { it.sampleRate <= maxRate && validMode(it, source.channels) }
        if(fitting.isEmpty()){clearPlan();return null}
        if(source.sampleRate<=maxRate) {
            val normal=plan(source)
            if(normal!=null && normal.outputRate<=maxRate)return normal
        }
        val rate = fitting.map { it.sampleRate }.filter { source.sampleRate % it == 0 }.maxOrNull()
            ?: fitting.maxOfOrNull { it.sampleRate }
        val atRate = fitting.filter { it.sampleRate == rate }
        val result=plan(source, atRate.filter { it.bitsPerSample >= source.significantBits }.minByOrNull { it.subslotBytes }
            ?: atRate.maxByOrNull { it.bitsPerSample })
        if(result!=null && result.outputRate>maxRate){clearPlan();return null}
        return result
    }

    private fun plan(source: PcmFormat, capped: OutputMode?): PlaybackPlan? {
        clearPlan()
        if (closed || restartBlocked || active) return null
        var mode = calibration?.takeIf { matches(it, source) }?.mode ?: capped
        val result = dac.plan(source, mode)
        if(mode==null && source is TrackPcmFormat && result!=null) {
            mode=modes.firstOrNull{it.sampleRate==result.outputRate && it.bitsPerSample==result.dacBits && validMode(it,source.channels)}
        }
        plannedSource = source
        plannedMode = mode
        planned = result
        return result
    }

    @Synchronized override fun prepareVolume(): Boolean {
        prepared = false
        val source = plannedSource ?: return false
        val mode = plannedMode ?: return false
        val plan = planned ?: return false
        val authorized = calibration?.let{matches(it,source) && levelingOffsetDb==0f} ?: (source is TrackPcmFormat)
        prepared = !closed && !restartBlocked && !active && authorized &&
            (calibration?.let {it.volumeDb<=volumeLimitDb} ?: true) &&
            plan.outputRate == mode.sampleRate && plan.dacBits == mode.bitsPerSample
        return prepared
    }

    @Synchronized override fun start(source: PcmFormat): StreamInfo {
        val cal = calibration
        val mode = checkNotNull(plannedMode)
        val plan = checkNotNull(planned)
        check(prepared && !closed && !restartBlocked && !active && source==plannedSource &&
            (cal?.let{matches(it,source) && it.volumeDb<=volumeLimitDb} ?: (source is TrackPcmFormat)))
        prepared = false
        volumeAccepted = false
        usbVolume = null; musicRange = null;appliedGainDb=null
        val stream = dac.start(source, mode)
        check(stream.sampleRate == source.sampleRate && stream.bitsPerSample == source.significantBits &&
            stream.channels == source.channels && stream.format == dac.formats[mode.formatIndex] &&
            stream.outputRate == mode.sampleRate && stream.resampled == plan.resampled &&
            stream.bitPerfect == plan.bitPerfect && stream.processed==plan.processed && (stream.deviceRate == 0 || stream.deviceRate == mode.sampleRate)) {
            "Actual USB stream does not match the selected mode"
        }
        active = true
        if (dac.clockConfigured() && dac.feedbackReady()) {
            if(cal!=null) volumeAccepted = dac.setVolumeDb(cal.volumeDb) && dac.volumeVerified()
            else {
                musicRange=dac.volumeRange
                usbMusicVolume(musicRange,lastMusicGain,startVolumeDb,volumeLimitDb)?.let { gain ->
                val target=leveledUsbGain(gain.currentDb,levelingOffsetDb,gain)
                volumeAccepted = target!=null && dac.setVolumeDb(target) && dac.volumeVerified()
                if(volumeAccepted){usbVolume=gain;lastMusicGain=gain.currentDb;appliedGainDb=target}
                }
            }
        }
        return stream
    }

    @Synchronized override fun adjustVolume(direction:Int):Boolean {
        if(direction !in listOf(-1,1))return false
        val gain=usbVolume ?: return false
        return setVolume((gain.currentDb+direction*gain.stepDb).coerceIn(gain.minDb,gain.maxDb))
    }

    @Synchronized override fun setVolume(db: Float): Boolean {
        if(!active || closed || !volumeAccepted || !dac.volumeVerified())return false
        val gain=usbMusicVolume(musicRange,lastMusicGain,startVolumeDb,volumeLimitDb) ?: run {
            volumeAccepted=false;usbVolume=null;return false
        }
        val target=gain.snapDown(db) ?: return false
        val applied=leveledUsbGain(target,levelingOffsetDb,gain) ?: return false
        if(target==lastMusicGain && applied==appliedGainDb){usbVolume=gain.copy(currentDb=target);return true}
        volumeAccepted=false
        appliedGainDb=null
        // Native setVolume validates the current ranges and reads every changed control back.
        volumeAccepted=dac.setVolumeDb(applied) && dac.volumeVerified()
        if(volumeAccepted){usbVolume=gain.copy(currentDb=target);lastMusicGain=target;appliedGainDb=applied}
        return volumeAccepted
    }

    override fun readiness(): UsbPlaybackReadiness {
        if (closed || !active) return UsbPlaybackReadiness()
        return UsbPlaybackReadiness(dac.clockConfigured(), dac.feedbackReady(), volumeAccepted && dac.volumeVerified())
    }

    override fun stats() = dac.stats()

    override fun drain(): Boolean {
        val before = readiness()
        if (!before.clockConfigured || !before.feedbackReady || !before.volumeVerified) return false
        val drained = dac.drain()
        val after = readiness()
        return drained && after.clockConfigured && after.feedbackReady && after.volumeVerified
    }

    override fun write(buffer: ByteBuffer, frames: Int): Int {
        val ready = readiness()
        if (!ready.clockConfigured || !ready.feedbackReady || !ready.volumeVerified) return -1
        return dac.write(buffer, frames)
    }

    @Synchronized override fun stop(): Boolean {
        active = false
        volumeAccepted = false
        clearPlan()
        if (closed) return true
        val quiet = try { dac.stop() } catch (failure: Exception) { restartBlocked = true; throw failure }
        if (!quiet) restartBlocked = true
        return quiet
    }

    @Synchronized override fun close() {
        if (closed) return
        active = false
        volumeAccepted = false
        clearPlan()
        try { dac.close() } catch (failure: Exception) { restartBlocked = true; throw failure }
        closed = true
        calibration = null
        musicRange = null
        lastMusicGain = null
    }

    private fun clearPlan() {
        prepared = false
        plannedSource = null
        plannedMode = null
        planned = null
        usbVolume = null
        appliedGainDb=null
    }

    private fun validMode(mode:OutputMode,channels:Int):Boolean {
        val format=dac.formats.getOrNull(mode.formatIndex) ?: return false
        val rate=format.sampleRates?.contains(mode.sampleRate) ?: (mode.sampleRate in format.minRate..format.maxRate)
        return mode in modes && mode.channels==channels && format.channels==channels && mode.sampleRate>0 &&
            mode.bitsPerSample==format.bitResolution && mode.subslotBytes==format.subslotBytes &&
            mode.subslotBytes in 1..4 && mode.bitsPerSample in 1..(mode.subslotBytes*8) && rate
    }

    private fun matches(cal: UsbVolumeCalibration, source: PcmFormat) = cal.identity == identity &&
        cal.sampleRate == source.sampleRate && cal.significantBits == source.significantBits && cal.channels == source.channels
}
