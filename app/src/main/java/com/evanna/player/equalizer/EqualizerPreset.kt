package com.evanna.player.equalizer

internal fun validEqualizerName(name:String):String?=name.trim().takeIf {it.length in 1..200 && it.none(Char::isISOControl)}
internal data class EqualizerPreset(val id:Long,val name:String,val config:EqualizerConfig,val revision:Long) {
    init {
        require(id>=0 && revision>=1 && validEqualizerName(name)==name)
        if(id==0L)require(name=="Flat" && config==EqualizerConfig() && revision==1L)
    }
}
