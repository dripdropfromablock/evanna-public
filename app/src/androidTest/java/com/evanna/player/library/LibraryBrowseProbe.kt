package com.evanna.player.library

import android.app.Instrumentation

/** Real SQLite coverage, isolated from the user's saved index, grants and playback. */
internal object LibraryBrowseProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val name = "library-browse-probe.db"
        context.deleteDatabase(name)
        fun id(number: Int) = number.toString(16).padStart(64, '0')
        fun document(number: Int, root: String = "a", file: String = "$number.flac") =
            LibraryDocument("$number", id(number), "content://test/$root/$number", file, "audio/flac", 50, 100)
        val audio = LibraryAudio("Silver Song", "Alpha Artist", "Night Album", 1000000, "FLAC", 44100, 24, 2)
        fun checkIds(db: LibraryDatabase, filter: LibraryFilter, vararg numbers: Int) {
            val page = db.page(filter = filter)
            check(page.total == numbers.size && page.songs.map { it.id }.toSet() == numbers.map(::id).toSet()) {
                "Filtered catalog differs: $filter"
            }
        }
        try {
            LibraryDatabase(context, name).use { db ->
                db.addRoot("a", "Music", true); db.addRoot("b", "Overlap", true); db.addRoot("empty", "Empty", true)
                db.begin("a", "seed")
                db.record("a", "seed", document(1, file = "Marker-file.flac"), audio)
                db.record("a", "seed", document(2), audio.copy(title = "100%_\\' literal", artist = null, album = null))
                db.record("a", "seed", document(3), audio.copy(title = "Other Song", artist = "Unknown artist", album = "Unknown album"))
                db.record("a", "seed", document(4), audio.copy(title = "Absent Song"))
                db.record("a", "seed", document(5), audio.copy(title = "Other", artist = null))
                db.finish("a", "seed")
                db.begin("b", "overlap"); db.record("b", "overlap", document(1, "b"), audio); db.finish("b", "overlap")

                checkIds(db, LibraryFilter(search = "sILVer"), 1)
                checkIds(db, LibraryFilter(search = "alpha ARTIST"), 1, 4)
                checkIds(db, LibraryFilter(search = "night album"), 1, 4, 5)
                checkIds(db, LibraryFilter(search = "marker-FILE"), 1)
                for (literal in listOf("%", "_", "\\", "'")) checkIds(db, LibraryFilter(search = literal), 2)
                checkIds(db, LibraryFilter(search = "' OR 1=1 --"))
                checkIds(db, LibraryFilter(kind = LibraryGroupKind.ARTIST, value = "' OR 1=1 --"))
                checkIds(db, LibraryFilter(search = "  Silver  "), 1)
                checkIds(db, LibraryFilter(kind = LibraryGroupKind.ARTIST, value = null), 2, 5)
                checkIds(db, LibraryFilter(kind = LibraryGroupKind.ARTIST, value = "Unknown artist"), 3)
                checkIds(db, LibraryFilter(kind = LibraryGroupKind.ALBUM, value = null), 2)
                checkIds(db, LibraryFilter(kind = LibraryGroupKind.FOLDER, value = "b", search = "night"), 1)
                checkIds(db, LibraryFilter(kind = LibraryGroupKind.FOLDER, value = "empty"))
                check(db.groups(LibraryGroupKind.ARTIST).groups.associate { it.value to it.count } ==
                    mapOf(null to 2, "Alpha Artist" to 2, "Unknown artist" to 1))
                check(db.groups(LibraryGroupKind.ALBUM).groups.associate { it.value to it.count } ==
                    mapOf(null to 1, "Night Album" to 3, "Unknown album" to 1))
                check(db.groups(LibraryGroupKind.ALBUM).groups.associate { it.value to it.artist } ==
                    mapOf(null to null, "Night Album" to "Alpha Artist", "Unknown album" to "Unknown artist"))
                check(db.groups(LibraryGroupKind.ARTIST).groups.all { it.artist == null })
                check(db.groups(LibraryGroupKind.ARTIST, search = "night").groups.sumOf { it.count } == 3)

                // A completed scan removes missing files while other memberships remain available.
                db.begin("a", "missing")
                for (number in listOf(1, 2, 3, 5)) {
                    val cached = checkNotNull(db.cached("a", id(number)))
                    db.record("a", "missing", cached.document, cached.audio)
                }
                db.finish("a", "missing")
                check(db.page(filter = LibraryFilter(search = "Absent")).songs.isEmpty())
                db.interrupt("a", "missing", LibraryRootState.UNAVAILABLE)
                check(db.page(filter = LibraryFilter(search = "Silver")).songs.single().uri == "content://test/b/1")
                check(db.groups(LibraryGroupKind.ARTIST).groups.sumOf { it.count } == 4)

                db.begin("b", "paged")
                db.record("b", "paged", document(1, "b"), audio)
                repeat(105) { index ->
                    val tag = "Paged %03d".format(index)
                    db.record("b", "paged", document(index + 100, "b"), audio.copy(title = tag, artist = tag, album = tag))
                }
                db.finish("b", "paged")
                val allSongs = (0..105 step 7).flatMap { db.page(7, it, LibraryFilter(search = "Paged")).songs }
                check(allSongs.size == 105 && allSongs.map { it.id }.distinct().size == 105)
                check(allSongs.map { it.title } == (0..104).map { "Paged %03d".format(it) })
                for (kind in listOf(LibraryGroupKind.ALBUM, LibraryGroupKind.ARTIST)) {
                    val allGroups = (0..105 step 7).flatMap { db.groups(kind, "Paged", 7, it).groups }
                    check(allGroups.size == 105 && allGroups.map { it.value }.distinct().size == 105)
                    check(allGroups.all { it.count == 1 })
                    check(db.groups(kind, "Paged", 7, 1000).let { it.total == 105 && it.groups.isEmpty() })
                }
                check(db.page(7, 1000, LibraryFilter(search = "Paged")).let { it.total == 105 && it.songs.isEmpty() })
                db.begin("b", "unicode")
                db.record("b", "unicode", document(300, "b"), audio.copy(title = "Café 東京", artist = "Äther", album = "नमस्ते"))
                db.finish("b", "unicode")
                checkIds(db, LibraryFilter(search = "東京"), 300)
                checkIds(db, LibraryFilter(search = "नमस्ते"), 300)
                checkIds(db, LibraryFilter(search = "CAFé"), 300)
                val folded = db.readableDatabase.rawQuery("SELECT 'Äther' LIKE '%äther%'", null).use { it.moveToFirst(); it.getInt(0) != 0 }
                if (folded) checkIds(db, LibraryFilter(search = "äther"), 300)
                else checkIds(db, LibraryFilter(search = "äther"))
                // Album cards name the most common track artist, not the alphabetically first one.
                db.begin("b", "artists")
                for ((number, artist) in listOf(400 to "Main Artist", 401 to "Main Artist", 402 to "Aardvark & Main Artist", 403 to null))
                    db.record("b", "artists", document(number, "b"), audio.copy(title = "Card $number", artist = artist, album = "Card Album"))
                db.finish("b", "artists")
                check(db.groups(LibraryGroupKind.ALBUM, "Card").groups.single().let { it.value == "Card Album" && it.count == 4 && it.artist == "Main Artist" })
                // A mix where every artist has one song names nobody instead of the first name.
                db.begin("b", "mix")
                for ((number, artist) in listOf(410 to "First Singer", 411 to "Second Singer"))
                    db.record("b", "mix", document(number, "b"), audio.copy(title = "Mix $number", artist = artist, album = "Mixed Album"))
                db.finish("b", "mix")
                check(db.groups(LibraryGroupKind.ALBUM, "Mixed").groups.single().let { it.value == "Mixed Album" && it.count == 2 && it.artist == null }) {
                    "An all-single mix named one artist"
                }
                check(runCatching { db.page(filter = LibraryFilter(search = "x".repeat(201))) }.isFailure)
                check(runCatching { db.groups(LibraryGroupKind.FOLDER) }.isFailure)
            }
            LibraryDatabase(context, name).use { db ->
                check(db.readableDatabase.version == LIBRARY_DB_VERSION)
                checkIds(db, LibraryFilter(search = "Marker-file"), 1)
                check(db.groups(LibraryGroupKind.ALBUM, "Paged").total == 105)
                check(db.roots().size == 3)
            }
            return "Literal/bound title, artist, album and file-name search; unknown tags; overlap counts/access; folder/search filters; most-common album artist and none for all-single mixes; unavailable recovery; 105-row song/group paging and v2 reopen passed. Isolated index; no audio or grant changes."
        } finally { context.deleteDatabase(name) }
    }
}
