package com.evanna.player.webmusic

import com.evanna.player.library.AlbumIdentity
import com.evanna.player.library.LibraryDatabase
import com.evanna.player.library.LibraryFilter
import com.evanna.player.library.LibraryGroup
import com.evanna.player.library.LibraryGroupKind
import com.evanna.player.library.LibrarySong
import com.evanna.player.network.CancelScope

internal const val LIBRARY_SEARCH_ID = "library"

internal class LibraryMusicSearch(private val db: LibraryDatabase) : MusicSearchProvider {
    override val id = LIBRARY_SEARCH_ID
    override val label = "Library"

    override fun search(query: String, cancel: CancelScope): SourceMatches {
        val tracks = db.page(80, filter = LibraryFilter(search = query)).songs.map(::track)
        cancel.checkActive()
        val artists = db.groups(LibraryGroupKind.ARTIST, query, 40).groups.mapNotNull { row ->
            row.value?.let { SearchArtist(id, it, it, "") }
        }
        cancel.checkActive()
        val releases = db.groups(LibraryGroupKind.ALBUM, query, 40).groups.mapNotNull(::release)
        return SourceMatches(artists, releases, tracks)
    }

    override fun artist(artist: SearchArtist, cancel: CancelScope): SourceMatches {
        val releases = db.albumsForArtist(artist.name, limit = 1000).groups.mapNotNull(::release)
        cancel.checkActive()
        val filter = LibraryFilter(kind = LibraryGroupKind.ARTIST, value = artist.name)
        return SourceMatches(releases = releases, tracks = db.page(1000, filter = filter).songs.map(::track))
    }

    override fun tracks(release: SearchRelease, cancel: CancelScope): List<SearchTrack> {
        val albumArtist = release.artist.takeUnless { release.id == AlbumIdentity(release.title, null).key }
        val filter = LibraryFilter(kind = LibraryGroupKind.ALBUM, value = release.title, albumArtist = albumArtist)
        cancel.checkActive()
        return db.page(1000, filter = filter).songs.map(::track)
    }

    fun matchingRelease(remote: SearchRelease): SearchRelease? =
        db.groups(LibraryGroupKind.ALBUM, remote.title.take(180), 1000).groups.mapNotNull(::release).singleOrNull {
            normalizedTag(it.title) == normalizedTag(remote.title) && normalizedTag(it.artist) == normalizedTag(remote.artist)
        }

    private fun release(row: LibraryGroup): SearchRelease? = row.value?.let { title ->
        SearchRelease(id, AlbumIdentity(title, row.albumArtist).key, title, row.albumArtist ?: row.artist.orEmpty(), "")
    }

    private fun track(song: LibrarySong): SearchTrack {
        val release = song.album?.let { title ->
            SearchRelease(id, AlbumIdentity(title, song.musicTags.albumArtist).key, title,
                song.musicTags.albumArtist ?: song.artist.orEmpty(), "")
        }
        return SearchTrack(id, song.id, song.title, song.artist.orEmpty(), song.album, "",
            format = song.codec?.uppercase(), quality = song.rate?.let { "${it / 1000.0} kHz" },
            disc = song.musicTags.discNumber, number = song.musicTags.trackNumber, durationMs = song.durationUs?.div(1000),
            release = release, libraryId = song.id.takeIf { song.available })
    }
}
