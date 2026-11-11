package com.evanna.player.playback

internal const val SESSION_ARTWORK_LIMIT=300*1024

/** Main-thread cover ownership; a late load never attaches to another queue occurrence. */
internal class SessionArtwork {
    private var current:String?=null
    private var bytes:ByteArray?=null
    fun select(id:String?) {if(current!=id){current=id;bytes=null}}
    fun update(id:String,value:ByteArray?):Boolean {
        if(id!=current)return false
        if(value!=null && value.size !in 1..SESSION_ARTWORK_LIMIT){bytes=null;return false}
        bytes=value?.copyOf();return true
    }
    fun forItem(id:String):ByteArray?=bytes.takeIf {id==current}
}
