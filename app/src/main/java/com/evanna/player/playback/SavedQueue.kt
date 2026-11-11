package com.evanna.player.playback

import com.evanna.player.decoder.MusicTags

internal data class SavedQueueEntry(val id: String, val libraryId: String?, val fileUri: String?, val title: String,
    val artist: String?, val album: String?, val durationUs: Long?,val musicTags:MusicTags=MusicTags()) {
    init {
        require(id.startsWith("evanna:local:queue:") && id.length <= 100)
        require(libraryId == null || libraryId.matches(Regex("[0-9a-f]{64}")))
        require(fileUri == null || (fileUri.startsWith("content://") && fileUri.length <= 8192))
        require(libraryId == null || fileUri == null)
        require(title.length in 1..512 && (artist?.length ?: 0) <= 512 && (album?.length ?: 0) <= 512)
        require(durationUs == null || durationUs >= 0)
        require(listOf(musicTags.albumArtist,musicTags.genre,musicTags.composer).all {(it?.length ?: 0)<=512})
        require(listOf(musicTags.trackNumber,musicTags.totalTracks,musicTags.discNumber,musicTags.totalDiscs).all {it==null || it in 1..999})
        require(musicTags.year==null || musicTags.year in 1000..2999)
        require(musicTags.trackNumber==null || musicTags.totalTracks==null || musicTags.totalTracks>=musicTags.trackNumber)
        require(musicTags.discNumber==null || musicTags.totalDiscs==null || musicTags.totalDiscs>=musicTags.discNumber)
    }
}
internal data class SavedQueue(val items: List<SavedQueueEntry> = emptyList(), val index: Int = -1, val positionUs: Long = 0,
    val shuffleEnabled:Boolean=false, val repeat:QueueRepeat=QueueRepeat.OFF, val playOrder:List<String> = items.map{it.id}) {
    init {
        require(items.size <= 1000 && items.map { it.id }.toSet().size == items.size)
        require(if (items.isEmpty()) index == -1 && positionUs == 0L else index in items.indices)
        require(positionUs >= 0 && positionUs <= Long.MAX_VALUE / 1000)
        require(playOrder.size==items.size && playOrder.toSet()==items.map{it.id}.toSet())
        require(shuffleEnabled || playOrder==items.map{it.id})
    }
}
internal interface QueuePersistence {
    fun load(): SavedQueue?
    fun save(state: SavedQueue)
}
