package com.evanna.player.loudness

import com.evanna.player.playback.snapDown
import com.evanna.player.playback.keyVolumeScale
import com.truesample.usbaudio.VolumeRange
import kotlin.math.log10
import kotlin.math.pow

internal const val LOUDNESS_MAX_DURATION_US=604_800_000_000L
/** Null LUFS means the integrated meter had no qualifying block, including short or quiet audio. */
internal data class LoudnessMeasurement(val integratedLufs:Double?,val samplePeak:Double,val durationUs:Long) {
    init {
        require(integratedLufs==null || integratedLufs.isFinite() && integratedLufs in -100.0..30.0)
        require(samplePeak.isFinite() && samplePeak in 0.0..1.0 && durationUs in 1..LOUDNESS_MAX_DURATION_US)
    }
}
internal data class LoudnessTags(val trackDb:Float?=null,val albumDb:Float?=null,val trackPeak:Double?=null,
    val albumPeak:Double?=null,val soundCheckPresent:Boolean=false) {
    init {
        require(listOfNotNull(trackDb,albumDb).all {it.isFinite() && it in -60f..30f})
        require(listOfNotNull(trackPeak,albumPeak).all {it.isFinite() && it in 0.0..16.0})
    }
}

/** ReplayGain gain/peak, R128 gain, then Sound Check presence. Sound Check's reference is not assumed. */
internal fun loudnessTagsFromText(values:List<String?>):LoudnessTags {
    require(values.size==7)
    fun text(index:Int)=values[index]?.takeIf {it.length<=128 && it.none(Char::isISOControl)}?.trim()
    fun gain(index:Int):Float? {
        val raw=text(index) ?: return null
        if(!Regex("[+-]?[0-9]+(?:\\.[0-9]+)?\\s*(?:dB)?",RegexOption.IGNORE_CASE).matches(raw))return null
        return raw.replace(Regex("\\s*dB$",RegexOption.IGNORE_CASE),"").toFloatOrNull()?.takeIf {it.isFinite() && it in -60f..30f}
    }
    fun r128(index:Int)=values[index]?.takeIf {it.length in 1..6 && Regex("[+-]?[0-9]+").matches(it)}?.toIntOrNull()?.takeIf {it in -32768..32767}
        ?.let {it/256f+5f}?.takeIf {it in -60f..30f}
    fun peak(index:Int)=text(index)?.toDoubleOrNull()?.takeIf {it.isFinite() && it in 0.0..16.0}
    return LoudnessTags(gain(0) ?: r128(4),gain(1) ?: r128(5),peak(2),peak(3),!text(6).isNullOrEmpty())
}

/** Duration-weighted energy approximation, not a fresh gated analysis of the concatenated album. */
internal fun albumIntegratedLufs(tracks:List<LoudnessMeasurement>):Double? {
    require(tracks.size<=100000)
    if(tracks.isEmpty())return null
    if(tracks.any {it.integratedLufs==null && it.samplePeak>0})return null
    val duration=tracks.sumOf {it.durationUs.toDouble()}
    val energy=tracks.sumOf {it.durationUs*(it.integratedLufs?.let {lufs->10.0.pow(lufs/10)} ?: 0.0)}
    return if(energy>0)10*log10(energy/duration) else null
}
internal fun levelingOffset(lufs:Double?):Float? = lufs?.takeIf {it.isFinite() && it in -100.0..30.0}
    ?.let {(-18.0-it).coerceIn(-60.0,30.0).toFloat()}

/** The already-limited hardware range remains the base slider range; only the returned target changes. */
internal fun leveledUsbGain(base:Float,offset:Float,range:VolumeRange):Float? {
    if(!offset.isFinite() || offset !in -60f..30f)return null
    if(keyVolumeScale(range)==null || range.snapDown(base)!=base)return null
    return range.snapDown(base+offset)
}

internal data class LevelingDecision(val mode:LevelingMode=LevelingMode.OFF,val offsetDb:Float=0f,val evidence:String="Off") {
    init {require(offsetDb.isFinite() && offsetDb in -60f..30f && (mode!=LevelingMode.OFF || offsetDb==0f) && evidence.length<=200 && evidence.none(Char::isISOControl))}
}
internal fun levelingDecision(mode:LevelingMode,tags:LoudnessTags,track:LoudnessMeasurement?=null,
    album:List<LoudnessMeasurement?>?=null):LevelingDecision {
    if(mode==LevelingMode.OFF)return LevelingDecision()
    val tag=if(mode==LevelingMode.TRACK)tags.trackDb else tags.albumDb
    if(tag!=null)return LevelingDecision(mode,tag,"${if(mode==LevelingMode.TRACK)"Track" else "Album"} gain tag")
    val lufs=if(mode==LevelingMode.TRACK)track?.integratedLufs
        else album?.takeIf {it.isNotEmpty() && it.none {value->value==null}}?.let {albumIntegratedLufs(it.filterNotNull())}
    val offset=levelingOffset(lufs) ?: return LevelingDecision(mode,0f,"No current loudness result; no offset")
    return LevelingDecision(mode,offset,if(mode==LevelingMode.TRACK)"Measured track · target −18 LUFS" else "Measured album energy approximation · target −18 LUFS")
}
