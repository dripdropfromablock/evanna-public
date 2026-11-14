package com.evanna.player.library

import android.app.Instrumentation
import com.evanna.player.decoder.MusicTags

/** Ordered SQLite results and album identities; isolated from the saved library and queue. */
internal object AlbumLibraryProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val name = "album-library-probe.db"
        context.deleteDatabase(name)
        fun id(n: Int) = n.toString(16).padStart(64, '0')
        fun doc(n: Int) = LibraryDocument("$n", id(n), "content://test/$n", "$n.flac", "audio/flac", 20, 30)
        fun audio(title: String, album: String?, tags: MusicTags, artist: String = "Shared") =
            LibraryAudio(title, artist, album, 1000, "FLAC", 44100, 24, 2, tags)
        val album = AlbumIdentity("Greatest Hits", "Artist A")
        val filter = LibraryFilter(kind = LibraryGroupKind.ALBUM, value = album.title, albumArtist = album.albumArtist)
        try {
            LibraryDatabase(context, name).use { db ->
                db.addRoot("a", "Music", true); db.addRoot("b", "Paused", false); db.begin("a", "seed")
                for (disc in 1..2) for (track in 1..7) db.record("a", "seed", doc(disc*100+track),
                    audio("Song ${8-track}", album.title, MusicTags("Artist A", track, 7, disc, 2, if (disc == 1) 2019 else 2020)))
                db.record("a", "seed", doc(300), audio("Other album", album.title, MusicTags("Artist B", 1, year = 2018)))
                db.record("a", "seed", doc(350), audio("No album artist", album.title, MusicTags()))
                db.record("a", "seed", doc(400), audio("Missing title", null, MusicTags()))
                db.record("a", "seed", doc(410), audio("Literal title", "Unknown album", MusicTags()))
                db.record("a", "seed", doc(500), audio("First mix", "Mix", MusicTags("Various", 1, discNumber = 1, compilation = true), "Track artist A"))
                db.record("a", "seed", doc(501), audio("Second mix", "Mix", MusicTags("Various", 2, discNumber = 1, compilation = true), "Track artist B"))
                db.finish("a", "seed")
                val expected = (1..2).flatMap { d -> (1..7).map { t -> id(d*100+t) } }
                check(db.page(filter = filter).songs.map { it.id } == expected) { "Album is not in disc/track order" }
                check((0..13 step 7).flatMap { db.page(7, it, filter).songs.map { song -> song.id } } == expected)
                check(db.page(filter = filter.copy(search = "song")).songs.map { it.id } == expected)
                check(db.page(filter = filter.copy(albumArtist = "Artist B")).songs.single().id == id(300))
                check(db.page(filter = filter.copy(albumArtist = null)).songs.single().id == id(350))
                check(db.groups(LibraryGroupKind.ALBUM).groups.filter { it.value == album.title }.map { it.albumArtist }.toSet() == setOf("Artist A", "Artist B", null))
                check(db.groups(LibraryGroupKind.ALBUM).groups.single { it.value == "Mix" }.let { it.count == 2 && it.artist == "Various" })
                val listed = db.groups(LibraryGroupKind.ALBUM).groups
                check(listed.indices.all { db.albumIndex(AlbumIdentity(listed[it].value, listed[it].albumArtist)) == it }) {
                    "Album index disagrees with the album list"
                }
                check(db.albumIndex(AlbumIdentity("No such album", null)) == null)
                check(db.albumSummary(album) == AlbumSummary("Artist A", null, true))
                check(db.albumSummary(AlbumIdentity("Mix", "Various")) == AlbumSummary("Various", null, false))
                check(db.albumArtworkSongs(album).all { it in expected } && db.albumArtworkSongs(album).size == 4)
                check(db.albumArtworkSongs(AlbumIdentity(album.title, "Artist B")) == listOf(id(300)))
                check(db.page(filter = LibraryFilter(kind = LibraryGroupKind.ALBUM)).songs.single().id == id(400))
                val artist = db.page(filter = LibraryFilter(kind = LibraryGroupKind.ARTIST, value = "Shared")).songs.map { it.id }
                check(artist.indexOf(id(300)) < artist.indexOf(expected.first()))
                check(artist.subList(artist.indexOf(expected.first()), artist.indexOf(expected.first()) + expected.size) == expected)
                check(db.rootsNeedingTagRefresh().isEmpty())
                db.writableDatabase.execSQL("UPDATE tracks SET tags_version=1 WHERE album_artist='Artist A'")
                check(db.rootsNeedingTagRefresh().map { it.uri } == listOf("a"))
                db.begin("a", "refresh"); db.record("a", "refresh", doc(101), null)
                check(db.song(id(101))?.musicTags?.trackNumber == 1)
                check(db.cached("a", id(101))?.let { it.audio == null && it.tagsVersion == 1 } == true)
                db.interrupt("a", "refresh", LibraryRootState.PAUSED)
                check(db.rootsNeedingTagRefresh().isEmpty())
            }
            return "Album identity, compilation grouping, full/paged disc order, summaries, cover candidates, artist album grouping and failed-refresh metadata retention pass; saved library untouched."
        } finally { context.deleteDatabase(name) }
    }
}
