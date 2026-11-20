package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import com.evanna.player.sources.SourceCatalog
import com.evanna.player.sources.SourceItem

internal class JellyfinMusicSearch(private val catalog: SourceCatalog, private val connectionId: String,
                                   override val label: String) : MusicSearchProvider {
    override val id = "jellyfin:$connectionId"

    override fun search(query: String, cancel: CancelScope): SourceMatches {
        val items = catalog.searchJellyfin(connectionId, query, cancel).items
        val albums = items.filter { it.folder }.map { SearchRelease(id, it.details.getString("id"), it.title, it.artist.orEmpty(), "") }
        val albumsById = albums.associateBy { it.id }
        val tracks = items.filterNot { it.folder }.map { item ->
            val release = albumsById[item.details.optString("albumId")] ?: item.album?.let { title ->
                item.details.providerText("albumId", 2048)?.let { albumId ->
                    val artist = item.details.providerText("albumArtist") ?: item.artist.orEmpty()
                    SearchRelease(id, albumId, title, artist, "")
                }
            }
            track(item, release)
        }
        return SourceMatches(releases = albums, tracks = tracks)
    }

    override fun artist(artist: SearchArtist, cancel: CancelScope) = SourceMatches()

    override fun tracks(release: SearchRelease, cancel: CancelScope): List<SearchTrack> {
        require(release.provider == id)
        return buildList {
            var offset: Int? = 0
            while (offset != null && size < 1000) {
                val page = catalog.jellyfinAlbumTracks(connectionId, release.id, offset, cancel)
                addAll(page.items.asSequence().filterNot { it.folder }.take(1000 - size).map { track(it, release) }.toList())
                offset = page.nextOffset
            }
        }
    }

    private fun track(item: SourceItem, release: SearchRelease?): SearchTrack {
        val format = item.details.optString("container").lowercase().takeIf { it in searchDownloadFormats }
        val downloads = format?.let { listOf(SearchDownloadFile(it, item.ref)) }.orEmpty()
        return SearchTrack(id, item.ref.encoded, item.title, item.artist.orEmpty(), item.album, "",
            downloads = downloads, format = format?.uppercase(), disc = item.details.optInt("disc").takeIf { it > 0 },
            number = item.details.optInt("track").takeIf { it > 0 }, durationMs = item.durationUs?.div(1000),
            release = release, sourceRef = item.ref)
    }
}
