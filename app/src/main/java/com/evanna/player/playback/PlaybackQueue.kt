package com.evanna.player.playback

import com.evanna.player.decoder.MusicTags

internal data class QueueSource(val libraryId: String?, val title: String, val artist: String?, val album: String?,
    val durationUs: Long?, val fileUri: String? = null, val musicTags:MusicTags=MusicTags(),val create: () -> PcmTrack)
internal data class QueueItem(val id: String, val title: String, val artist: String?, val album: String?, val durationUs: Long?,
    val musicTags:MusicTags=MusicTags())
internal enum class QueueRepeat { OFF, SONG, QUEUE }
internal data class PlaybackQueue(val items: List<QueueItem> = emptyList(), val index: Int = -1,
    val saveFailed: Boolean = false, val restoreFailed: Boolean = false,
    val shuffleEnabled:Boolean=false, val repeat:QueueRepeat=QueueRepeat.OFF,
    val playOrder:List<String> = items.map{it.id},val historySaveFailed:Boolean=false) {
    val current get() = items.getOrNull(index)

    fun navigationIndex(direction:Int,automatic:Boolean=false):Int {
        val selected=current ?: return -1
        if(direction!=1 && direction!= -1)return -1
        if(automatic && repeat==QueueRepeat.SONG)return index
        val order=if(shuffleEnabled)playOrder else items.map{it.id}
        val from=order.indexOf(selected.id)
        if(from<0)return -1
        val next=from+direction
        val target=if(next in order.indices)next else if(repeat==QueueRepeat.QUEUE)(next+order.size)%order.size else return -1
        return items.indexOfFirst{it.id==order[target]}
    }
}
internal class OwnedQueueEntry(val source: QueueSource, val item: QueueItem, var track: PcmTrack? = null)
internal data class ContextQueue(val sources: List<QueueSource>, val startIndex: Int, val shuffle: Boolean = false)
