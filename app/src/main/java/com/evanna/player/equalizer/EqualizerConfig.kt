package com.evanna.player.equalizer

import com.truesample.usbaudio.EqBand
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Two tone bands plus a 32-band ladder; parametric presets use the same limit. */
internal const val MAX_EQ_BANDS=34

/** [graphic] marks a preset made in the graphic editor, so the editor reopens it there. */
internal data class EqualizerConfig(val bands:List<EqBand> = emptyList(),val preampDb:Float=0f,val graphic:Boolean=false) {
    init {
        require(bands.size<=MAX_EQ_BANDS && preampDb.isFinite() && preampDb in -20f..0f)
        // 10 Hz, not 20: the 32-band ladder starts at 16 Hz.
        require(bands.all {it.frequencyHz.isFinite() && it.frequencyHz in 10f..24000f && it.gainDb.isFinite() && it.gainDb in -15f..15f &&
            it.q.isFinite() && it.q in 0.1f..12f && it.channels in 0..2})
    }
}
internal data class AutoEqImport(val config:EqualizerConfig,val warnings:List<String>)

internal fun equalizerValues(config:EqualizerConfig)=config.bands.flatMap {
    listOf(it.type.ordinal.toFloat(),it.frequencyHz,it.gainDb,it.q,it.channels.toFloat())
}.toFloatArray()

internal fun equalizerConfigFromValues(values:FloatArray,preamp:Float,graphic:Boolean):EqualizerConfig {
    require(values.size<=MAX_EQ_BANDS*5 && values.size%5==0)
    return EqualizerConfig(values.toList().chunked(5).map {v ->
        val type=EqBand.Type.entries.firstOrNull {it.ordinal.toFloat()==v[0]} ?: error("Unknown EQ type")
        val channels=(0..2).firstOrNull {it.toFloat()==v[4]} ?: error("Unknown EQ channels")
        EqBand(type,v[1],v[2],v[3],channels)
    },preamp,graphic)
}

/** Parse settings only. Include/command directives are never followed or executed. */
internal fun parseAutoEq(bytes:ByteArray):AutoEqImport {
    require(bytes.size<=65536){"The EQ file must be at most 64 KiB."}
    val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    val warnings=mutableListOf<String>();val bands=mutableListOf<EqBand>();val numbers=hashSetOf<Int>()
    var preamp:Float?=null;var filters=0
    fun number(raw:String):Float=raw.replace(',','.').toFloatOrNull()?.takeIf {it.isFinite()} ?: throw IllegalArgumentException("The EQ file contains an invalid number.")
    fun bounded(raw:String,min:Float,max:Float,label:String):Float {
        val value=number(raw);val clamped=value.coerceIn(min,max)
        if(value!=clamped)warnings+="$label was limited to $clamped."
        return clamped
    }
    for(raw in text.lineSequence()) {
        val line=raw.substringBefore('#').trim()
        if(line.isEmpty())continue
        require(line.length<=4096){"An EQ line is too long."}
        val gain=Regex("^Preamp\\s*:\\s*(\\S+)\\s+dB$",RegexOption.IGNORE_CASE).matchEntire(line)
        if(gain!=null) {
            require(preamp==null){"The EQ file contains more than one preamp."}
            preamp=bounded(gain.groupValues[1],-20f,0f,"Preamp");continue
        }
        val filter=Regex("^Filter\\s+(\\d+)\\s*:\\s*(ON|OFF)\\s+(\\S+)(.*)$",RegexOption.IGNORE_CASE).matchEntire(line)
        if(filter==null) {
            require(!line.startsWith("Preamp",true) && !line.startsWith("Filter",true)){"An EQ setting is malformed."}
            if(warnings.size<40)warnings+="Ignored an unsupported settings line."
            continue
        }
        require(++filters<=64){"At most 64 filter lines can be read."}
        val index=filter.groupValues[1].toIntOrNull()?.takeIf {it in 1..1000} ?: throw IllegalArgumentException("Invalid filter number.")
        require(numbers.add(index)){"The EQ file repeats a filter number."}
        if(filter.groupValues[2].equals("OFF",true))continue
        val type=when(filter.groupValues[3].uppercase(java.util.Locale.ROOT)) {
            "PK" -> EqBand.Type.PEAK
            "LS","LSC" -> EqBand.Type.LOW_SHELF
            "HS","HSC" -> EqBand.Type.HIGH_SHELF
            else -> {warnings+="Skipped unsupported filter type ${filter.groupValues[3].take(40)}.";continue}
        }
        val values=Regex("^\\s+Fc\\s+(\\S+)\\s+Hz\\s+Gain\\s+(\\S+)\\s+dB\\s+Q\\s+(\\S+)\\s*$",RegexOption.IGNORE_CASE)
            .matchEntire(filter.groupValues[4]) ?: throw IllegalArgumentException("A supported filter is missing frequency, gain or Q.")
        require(bands.size<MAX_EQ_BANDS){"Evanna supports at most $MAX_EQ_BANDS enabled filters. Turn off extra filters before importing."}
        bands+=EqBand(type,bounded(values.groupValues[1],20f,20000f,"Frequency"),bounded(values.groupValues[2],-15f,15f,"Gain"),bounded(values.groupValues[3],0.1f,10f,"Q"))
    }
    require(preamp!=null || filters>0){"No EQ settings were found."}
    val initial=preamp ?: -maxOf(0f,bands.maxOfOrNull {it.gainDb} ?: 0f)
    return AutoEqImport(EqualizerConfig(bands.toList(),initial),warnings.take(40))
}
