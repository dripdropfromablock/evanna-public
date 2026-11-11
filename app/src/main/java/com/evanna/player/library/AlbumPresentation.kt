package com.evanna.player.library

internal fun albumDiscHeading(songs: List<LibrarySong>, index: Int, summary: AlbumSummary?): String? {
    if (summary?.multipleDiscs != true) return null
    val disc = songs.getOrNull(index)?.musicTags?.discNumber
    if (index !in songs.indices || (index > 0 && songs[index-1].musicTags.discNumber == disc)) return null
    return disc?.let { "Disc $it" } ?: "Disc unknown"
}
