package com.evanna.player.playback

internal enum class SleepMode { OFF, MINUTES, SONG, ALBUM }
internal data class SleepTimerState(val mode:SleepMode=SleepMode.OFF,val deadlineMs:Long?=null,
    val remainingSeconds:Long=0,val targetId:String?=null,val albumKey:String?=null) {
    fun remaining(nowMs:Long)=deadlineMs?.let {((it-nowMs).coerceAtLeast(0)+999)/1000} ?: 0
    fun blocks(queue:PlaybackQueue,id:String):Boolean {
        if(mode !in listOf(SleepMode.SONG,SleepMode.ALBUM))return false
        if(mode==SleepMode.SONG || albumKey==null)return id==targetId
        val index=queue.items.indexOfFirst {it.id==id}
        val current=queue.items.getOrNull(index) ?: return false
        if(sleepAlbumKey(current)!=albumKey)return false
        val next=queue.copy(index=index).navigationIndex(1,automatic=true)
        val following=queue.items.getOrNull(next) ?: return true
        val order=if(queue.shuffleEnabled)queue.playOrder else queue.items.map {it.id}
        return sleepAlbumKey(following)!=albumKey || order.indexOf(following.id)<=order.indexOf(id)
    }
    val label:String get()=when(mode) {
        SleepMode.OFF -> ""
        SleepMode.MINUTES -> "Sleep in ${remainingSeconds/60}:${(remainingSeconds%60).toString().padStart(2,'0')}"
        SleepMode.SONG -> "Sleep after this song"
        SleepMode.ALBUM -> if(albumKey==null)"Sleep after this song (album unknown)" else "Sleep after this album"
    }
}
internal fun sleepAlbumKey(item:QueueItem):String? {
    val album=item.album?.takeIf {it.isNotBlank()} ?: return null
    val artist=item.musicTags.albumArtist ?: item.artist.orEmpty()
    return "${album.length}:$album:${artist.length}:$artist"
}
