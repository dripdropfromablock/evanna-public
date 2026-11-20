package com.evanna.player.library

import com.evanna.player.SHARE_LINER_TRACKS
import com.evanna.player.ShareCard
import com.evanna.player.ShareTrack
import com.evanna.player.playbackTime

internal fun albumShareCard(album: String, summary: AlbumSummary?, page: LibraryPage): ShareCard.AlbumCard {
    val lengthMs = if (page.songs.size >= page.total && page.songs.isNotEmpty() && page.songs.all { it.durationUs != null })
        page.songs.sumOf { it.durationUs ?: 0L } / 1000 else null
    val tracks = page.songs.take(SHARE_LINER_TRACKS).map { ShareTrack(it.title, playbackTime(it.durationUs?.div(1000))) }
    val more = listOfNotNull((page.total - tracks.size).takeIf { it > 0 }?.let { "+$it more" },
        lengthMs?.takeIf { it > 0 }?.let(::albumLength)).joinToString(" · ").takeIf { it.isNotEmpty() }
    return ShareCard.AlbumCard(album.ifBlank { "Unknown album" },
        summary?.displayArtist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
        albumInfoLine(summary?.year, page.total, lengthMs), tracks, more)
}

internal fun albumDiscHeading(songs: List<LibrarySong>, index: Int, summary: AlbumSummary?): String? {
    if (summary?.multipleDiscs != true) return null
    val disc = songs.getOrNull(index)?.musicTags?.discNumber
    if (index !in songs.indices || (index > 0 && songs[index-1].musicTags.discNumber == disc)) return null
    return disc?.let { "Disc $it" } ?: "Disc unknown"
}
