package com.evanna.player.decoder

/** Private ordering hints; display tags and public session metadata keep the original names. */
internal data class SortTags(val title:String?=null,val artist:String?=null,val album:String?=null,val albumArtist:String?=null) {
    init {require(listOf(title,artist,album,albumArtist).all {it==null || it.length<=512 && it.none(Char::isISOControl)})}
}
internal fun sortTagsFromBytes(tags:Array<ByteArray>):SortTags {
    require(tags.size==4){"DECODER_SORT_TAGS_MISMATCH"}
    return SortTags(metadataText(tags[0]),metadataText(tags[1]),metadataText(tags[2]),metadataText(tags[3]))
}
