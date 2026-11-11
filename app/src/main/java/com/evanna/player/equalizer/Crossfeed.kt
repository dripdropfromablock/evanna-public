package com.evanna.player.equalizer

/** bs2b presets (bs2b.sourceforge.net). The feed level is how much quieter the crossed sound is, so a higher dB crosses less. */
internal enum class Crossfeed(val label:String,val cutHz:Int,val feedDb:Float) {
    OFF("Off",0,0f),
    LIGHT("Light",650,9.5f),  // Jan Meier
    MEDIUM("Medium",700,6f),  // Chu Moy
    STRONG("Strong",700,4.5f), // bs2b default
}

internal fun storedCrossfeed(value:String?):Crossfeed=Crossfeed.entries.firstOrNull {it.name==value} ?: Crossfeed.OFF
