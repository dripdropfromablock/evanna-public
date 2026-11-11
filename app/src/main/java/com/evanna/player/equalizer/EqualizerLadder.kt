package com.evanna.player.equalizer

import com.truesample.usbaudio.EqBand
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Band counts the graphic editor offers (Poweramp's list); 10, 15 and 31 are the ISO ladders. */
internal val GRAPHIC_BAND_COUNTS=listOf(5,10,12,15,16,24,31,32)

/** Poweramp's ladder width: Q 1.083 at 5 bands rising to 3.33 at 32 (about 1.5 at 10). */
internal fun graphicQ(count:Int):Float=1.083f+(3.33f-1.083f)*(count.coerceIn(5,32)-5)/27f

/** Log-spaced band centres between the count's first and last frequency, rounded to whole hertz. */
internal fun graphicFrequencies(count:Int):List<Float> {
    require(count in GRAPHIC_BAND_COUNTS)
    val (first,last)=when {
        count<10 -> 60.0 to 14000.0
        count<12 -> 31.0 to 16000.0
        count<15 -> 29.5 to 16000.0
        count<24 -> 25.0 to 16000.0
        count<32 -> 25.0 to 20000.0
        else -> 15.874 to 20000.0
    }
    return List(count) {i -> 10.0.pow(log10(first)+(log10(last)-log10(first))*i/(count-1)).roundToInt().toFloat()}
}

/** The band-count chooser's names: "10 (ISO)", "15 (ISO)" and "31 (ISO)" for the ISO ladders, the plain count otherwise. */
internal fun graphicCountLabel(count:Int)=if(count==10 || count==15 || count==31)"$count (ISO)" else "$count"

/** The label under a graphic slider: whole hertz below 100, the nearest 5 Hz below 950, then kHz with at most one decimal. */
internal fun graphicLabel(hz:Float):String=when {
    hz<100f -> hz.roundToInt().toString()
    hz<950f -> ((hz/5f).roundToInt()*5).toString()
    else -> (hz/100f).roundToInt().let {tenths -> if(tenths%10==0)"${tenths/10}k" else "${tenths/10}.${tenths%10}k"}
}

/** Bass and Treble are a low pass at 90 Hz and a high pass at 10 kHz that only boost (0 to +15 dB). */
internal fun bassBand(gainDb:Float)=EqBand(EqBand.Type.LOW_PASS,90f,gainDb.coerceIn(0f,15f),0.8f)
internal fun trebleBand(gainDb:Float)=EqBand(EqBand.Type.HIGH_PASS,10000f,gainDb.coerceIn(0f,15f),0.6f)

/** A flat graphic preset: Bass, Treble, then [count] band-pass sliders. Changing the band count starts from this. */
internal fun flatGraphic(count:Int):EqualizerConfig {
    val q=graphicQ(count)
    return EqualizerConfig(listOf(bassBand(0f),trebleBand(0f))+graphicFrequencies(count).map {EqBand(EqBand.Type.BAND_PASS,it,0f,q)},graphic=true)
}
