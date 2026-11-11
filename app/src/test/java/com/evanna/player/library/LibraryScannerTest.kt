package com.evanna.player.library

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LibraryScannerTest {
    @Test fun startupSkipsNomediaTreesAndPrunesTheirPreviouslyIndexedSongs() = runBlocking {
        val index = Index()
        var hidden = false
        val source = object : LibraryScanSource by Source(emptyList()) {
            override suspend fun children(id: String): List<LibraryDocument> = when (id) {
                "root" -> listOf(doc("album", DIRECTORY_MIME), doc("visible"))
                "album" -> if (hidden) listOf(doc("secret"), doc("deep", DIRECTORY_MIME),
                    doc("marker", "application/octet-stream").copy(name = ".nomedia")) else listOf(doc("secret"))
                else -> error("The ignored subtree was traversed")
            }
        }
        scanLibrary("root", index, source)
        hidden = true
        assertTrue(scanLibrary("root", index, source, silent = true).changed)
        assertEquals(setOf("visible"), index.rows.keys)
        assertFalse(scanLibrary("root", index, source, silent = true).changed)
    }

    @Test fun foregroundScanCancelsStartupBeforeAcquiringTheIndex() = runBlocking {
        val queue = LibraryScanQueue()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val index = Index()
        val source = Source(listOf(doc("song")))
        scanLibrary("root", index, source)
        val waitingSource = object : LibraryScanSource by source {
            override suspend fun children(id: String): List<LibraryDocument> {
                try { entered.complete(Unit); awaitCancellation() }
                finally { events += "startup cancelled" }
            }
        }
        val startup = queue.startup(this) {
            scanLibrary("root", index, waitingSource, silent = true)
        }
        entered.await()
        withTimeout(1000) { queue.userAction { events += "foreground scan" } }
        startup.join()
        assertTrue(startup.isCancelled)
        assertEquals(listOf("startup cancelled", "foreground scan"), events)
        assertEquals(LibraryRootState.READY, index.state)
        assertEquals(setOf("song"), index.rows.keys)
    }

    @Test fun silentSyncAcrossRootsReportsOnlyCommittedChanges() = runBlocking {
        val index = Index()
        val phone = Source(listOf(doc("keep"), doc("delete")))
        val card = Source(listOf(doc("old")))
        val roots = listOf("phone", "card")
        fun source(root: String) = if (root == "phone") phone else card
        scanLibraryBatch(roots, index, ::source, silent = true)
        assertFalse(scanLibraryBatch(roots, index, ::source, silent = true))
        assertEquals(2, phone.inspections)
        assertEquals(1, card.inspections)

        phone.files = listOf(doc("keep"), doc("new"))
        card.files = listOf(doc("old", modified = 42))
        var committed = false
        assertTrue(scanLibraryBatch(roots, index, ::source, silent = true, onChanged = { committed = true }))
        assertTrue(committed)
        assertEquals(setOf("keep", "new"), index.files("phone").keys)
        assertEquals(setOf("old"), index.files("card").keys)
        assertEquals(3, phone.inspections)
        assertEquals(2, card.inspections)
        assertFalse(scanLibraryBatch(roots, index, ::source, silent = true))

        card.failDirectory = "root"
        assertTrue(scanLibraryBatch(roots, index, ::source, silent = true))
        assertEquals(LibraryRootState.UNAVAILABLE, index.state)
        assertEquals(setOf("old"), index.files("card").keys)
        assertFalse(scanLibraryBatch(roots, index, ::source, silent = true))
    }

    @Test fun folderOverlapUsesWholePathSegmentsAndIncludesBothDirections() {
        val music = "/storage/emulated/0/Music"
        assertTrue(folderPathsOverlap(music, "$music/"))
        assertTrue(folderPathsOverlap(music, "$music/Albums"))
        assertTrue(folderPathsOverlap("$music/Albums", music))
        assertFalse(folderPathsOverlap(music, "${music}Backup"))
        assertFalse(folderPathsOverlap(music, "/storage/1A2B-3C4D/Music"))
    }

    @Test fun excludedDirectoryIsNeitherEnumeratedNorDecodedAndSiblingStillIndexes() = runBlocking {
        val index=Index();val visits=mutableListOf<String>()
        val source=object:LibraryScanSource {
            override val excludedDirectories=setOf("skip")
            override suspend fun root()=doc("root",DIRECTORY_MIME)
            override suspend fun children(id:String):List<LibraryDocument> {
                visits+=id
                check(id!="skip")
                return if(id=="root")listOf(doc("skip",DIRECTORY_MIME),doc("keep",DIRECTORY_MIME)) else listOf(doc("song"))
            }
            override suspend fun inspect(document:LibraryDocument)=audio
        }
        assertEquals(1,scanLibrary("tree",index,source).checked)
        assertEquals(listOf("root","keep"),visits)
        assertEquals(setOf("song"),index.rows.keys)
        assertEquals(LibraryRootState.READY,index.state)
    }
    private fun doc(id: String, mime: String = "audio/flac", size: Long? = 10, modified: Long? = 20) =
        LibraryDocument(id, id, "content:/$id", "$id.flac", mime, size, modified)
    private val audio = LibraryAudio("Title", "Artist", "Album", 1000000, "FLAC", 44100, 24, 2)
    private class Index : LibraryScanIndex {
        data class Row(val document: LibraryDocument, val audio: LibraryAudio?, val token: String, var present: Boolean = true,
            val tagsVersion: Int = LIBRARY_TAGS_VERSION, val readable: Boolean = audio != null)
        private val filesByRoot = mutableMapOf<String, MutableMap<String, Row>>()
        private val states = mutableMapOf<String, LibraryRootState>()
        private var activeRoot = "root"
        fun files(root: String) = filesByRoot.getOrPut(root) { linkedMapOf() }
        val rows get() = files(activeRoot)
        var token = ""
        val state get() = states[activeRoot] ?: LibraryRootState.PAUSED
        var writes = 0
        val batchSizes = mutableListOf<Int>()
        override fun begin(root: String, token: String) {
            beginSilent(root, token)
            states[root] = LibraryRootState.SCANNING
        }
        override fun beginSilent(root: String, token: String) { activeRoot = root; this.token = token }
        override fun cached(root: String, key: String) = rows[key]?.let { LibraryCached(it.document, it.audio.takeIf { _ -> it.readable }, it.tagsVersion) }
        override fun record(root: String, token: String, document: LibraryDocument, audio: LibraryAudio?) {
            if (this.token == token) {
                writes++
                val old = rows[document.key]
                rows[document.key] = Row(document, audio ?: old?.audio, token,
                    tagsVersion = if (audio != null) LIBRARY_TAGS_VERSION else old?.tagsVersion ?: 1, readable = audio != null)
            }
        }
        override fun recordBatch(root: String, token: String, rows: List<Pair<LibraryDocument, LibraryAudio?>>) {
            batchSizes += rows.size
            super.recordBatch(root, token, rows)
        }
        override fun markSeen(root: String, token: String, keys: List<String>, reveal: List<String>): Boolean {
            if (this.token != token) return false
            for (key in keys) rows[key] = rows.getValue(key).copy(token = token)
            return false
        }
        override fun finish(root: String, token: String, checkActive: () -> Unit): Boolean {
            checkActive()
            if (this.token != token) return false
            val pruned = rows.values.removeAll { it.token != token }
            return interrupt(root, token, LibraryRootState.READY) || pruned
        }
        override fun interrupt(root: String, token: String, state: LibraryRootState): Boolean {
            if (this.token != token || this.state == state) return false
            states[root] = state
            return true
        }
    }
    private inner class Source(var files: List<LibraryDocument>) : LibraryScanSource {
        var inspections = 0
        var failDirectory: String? = null
        var failFile: String? = null
        override suspend fun root() = doc("root", DIRECTORY_MIME)
        override suspend fun children(id: String): List<LibraryDocument> {
            if (id == failDirectory) throw IOException()
            return if (id == "root") files else emptyList()
        }
        override suspend fun inspect(document: LibraryDocument): LibraryAudio {
            inspections++
            if (document.id == failFile) throw IOException()
            return audio
        }
    }
    @Test fun extraLosslessExtensionsIndexAndWavPackCorrectionFilesAreSkipped() = runBlocking {
        fun file(name: String, mime: String) = LibraryDocument(name, name, "content:/$name", name, mime, 10, 20)
        val index = Index()
        val source = Source(listOf(file("a.wv", "application/octet-stream"), file("b.ape", "application/octet-stream"),
            file("c.TTA", "application/octet-stream"), file("a.wvc", "audio/x-wavpack-correction"), file("d.bin", "application/octet-stream")))
        scanLibrary("root", index, source)
        assertEquals(setOf("a.wv", "b.ape", "c.TTA"), index.rows.keys)
        assertEquals(3, source.inspections)
    }
    @Test fun cueSheetsCoverOnlyBareSiblingNamesAndAreNeverDecodedAsSongs() = runBlocking {
        fun file(name: String, mime: String) = LibraryDocument(name, name, "content:/$name", name, mime, 10, 20)
        val base = Index(); val covered = mutableListOf<Pair<String, List<Long?>>>(); var decoded = 0
        val index = object : LibraryScanIndex by base {
            override fun recordCue(root: String, token: String, file: LibraryDocument, audio: LibraryAudio, sheet: CueSheet, ranges: List<CueRange>): Boolean {
                covered += file.key to ranges.flatMap { listOf(it.startFrame, it.endFrame) }
                return true
            }
        }
        val sheets = mapOf("album.cue" to "TITLE \"Live\"\nFILE \"album.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 00:00:30",
            "paths.cue" to "FILE \"../other/Album.FLAC\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nFILE \"Album.wav\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00",
            "broken.cue" to "FILE \"Album.FLAC\" WAVE\nTRACK 01 AUDIO")
        val source = object : LibraryScanSource {
            override suspend fun root() = file("root", DIRECTORY_MIME)
            override suspend fun children(id: String) = if (id != "root") emptyList() else listOf(file("album.cue", "application/x-cue"),
                file("Album.FLAC", "audio/flac"), file("paths.cue", "text/plain"), file("broken.cue", "audio/x-cue"))
            override suspend fun inspect(document: LibraryDocument): LibraryAudio { decoded++; return audio }
            override suspend fun readSheet(document: LibraryDocument) = sheets.getValue(document.name).toByteArray()
        }
        scanLibrary("root", index, source)
        assertEquals(listOf("Album.FLAC" to listOf(0L, 17_640L, 17_640L, null)), covered)
        assertEquals(1, decoded); assertEquals(setOf("Album.FLAC"), base.rows.keys)
    }
    @Test fun oldTagVersionForcesInspectionAndFailureKeepsTheOldRowForRetry() = runBlocking {
        val index = Index(); val source = Source(listOf(doc("a")))
        index.rows["a"] = Index.Row(doc("a"), audio.copy(title = "Old title"), "old", tagsVersion = 1)
        source.failFile = "a"
        scanLibrary("root", index, source)
        assertEquals(1, source.inspections)
        assertEquals("Old title", index.rows.getValue("a").audio?.title)
        assertEquals(1, index.rows.getValue("a").tagsVersion)
        assertFalse(index.rows.getValue("a").readable)
        source.failFile = null
        scanLibrary("root", index, source)
        assertEquals(2, source.inspections)
        assertEquals(LIBRARY_TAGS_VERSION, index.rows.getValue("a").tagsVersion)
        scanLibrary("root", index, source)
        assertEquals("Successful untagged inspections are cached too", 2, source.inspections)
    }
    @Test fun rootBatchIsSequentialAndCancellationStopsRemainingRoots() = runBlocking {
        val visits = mutableListOf<String>()
        fun source(root: String) = object : LibraryScanSource by Source(listOf(doc(root))) {
            override suspend fun root(): LibraryDocument { visits += root; return doc("root", DIRECTORY_MIME) }
        }
        scanLibraryBatch(listOf("a", "b"), Index(), ::source)
        assertEquals(listOf("a", "a", "b", "b"), visits)
        visits.clear()
        val job = launch {
            scanLibraryBatch(listOf("a", "b"), Index(), ::source) { throw CancellationException() }
        }
        job.join()
        assertEquals(listOf("a"), visits)
    }
    @Test fun successfulRescanPrunesMissingAndOnlyWritesNewOrChangedFiles() = runBlocking {
        val index = Index(); val source = Source(listOf(doc("a"), doc("b")))
        scanLibrary("root", index, source)
        assertFalse(scanLibrary("root", index, source, silent = true) { error("Silent scan emitted progress") }.changed)
        source.files = listOf(doc("b"), doc("c")); scanLibrary("root", index, source)
        assertEquals(setOf("b", "c"), index.rows.keys)
        assertEquals(3, source.inspections)
        assertEquals(3, index.writes)
        source.files = listOf(doc("b").copy(uri = "content:/moved/b"), doc("c", size = 12))
        scanLibrary("root", index, source)
        assertEquals(5, source.inspections)
        assertEquals(5, index.writes)
        assertEquals(2, index.rows.size)
        assertEquals(LibraryRootState.READY, index.state)
    }
    @Test fun largeDirectoriesWriteAndReportProgressInChunks() = runBlocking {
        val index = Index()
        val source = Source(List(SCAN_BATCH_SIZE * 2 + 1) { doc("song-$it") })
        val progress = mutableListOf<Int>()
        scanLibrary("root", index, source) { progress += it }
        assertEquals(listOf(SCAN_BATCH_SIZE, SCAN_BATCH_SIZE, 1), index.batchSizes)
        assertEquals(listOf(SCAN_BATCH_SIZE, SCAN_BATCH_SIZE * 2, SCAN_BATCH_SIZE * 2 + 1), progress)
    }
    @Test fun failedOrCancelledEnumerationKeepsPreviouslySavedFiles() = runBlocking {
        val index = Index(); val source = Source(listOf(doc("a"), doc("b")))
        scanLibrary("root", index, source)
        source.files = listOf(doc("sub", DIRECTORY_MIME)); source.failDirectory = "sub"
        scanLibrary("root", index, source)
        assertEquals(setOf("a", "b"), index.rows.keys); assertEquals(LibraryRootState.UNAVAILABLE, index.state)
        source.failDirectory = null; source.files = listOf(doc("a", modified = 30))
        val job = launch { scanLibrary("root", index, source) { throw CancellationException() } }
        job.join()
        assertEquals(setOf("a", "b"), index.rows.keys); assertEquals(LibraryRootState.PAUSED, index.state)
    }
    @Test fun changedOrUnknownStampsAndUnreadableFilesRetryButKnownUnchangedMetadataIsCached() = runBlocking {
        val index = Index(); val source = Source(listOf(doc("a")))
        scanLibrary("root", index, source); scanLibrary("root", index, source)
        assertEquals(1, source.inspections)
        source.files = listOf(doc("a", modified = 30)); scanLibrary("root", index, source)
        assertEquals(2, source.inspections)
        source.files = listOf(doc("a", modified = null)); repeat(2) { scanLibrary("root", index, source) }
        assertEquals(4, source.inspections)
        source.files = listOf(doc("bad"), doc("good")); source.failFile = "bad"
        scanLibrary("root", index, source)
        assertNull(index.rows.getValue("bad").audio); assertNotNull(index.rows.getValue("good").audio)
        assertEquals(LibraryRootState.READY, index.state)
        source.failFile = null; scanLibrary("root", index, source)
        assertNotNull(index.rows.getValue("bad").audio)
    }
    @Test fun duplicateDocumentsAndCyclesDoNotDuplicateWorkAndUnrelatedFilesAreSkipped() = runBlocking {
        val index = Index(); val base = Source(emptyList())
        val source = object : LibraryScanSource by base {
            override suspend fun children(id: String) = listOf(doc("root", DIRECTORY_MIME), doc("a"), doc("a"), doc("cover", "image/jpeg").copy(name = "cover.jpg"))
        }
        scanLibrary("root", index, source)
        assertEquals(setOf("a"), index.rows.keys); assertEquals(1, base.inspections)
    }
    @Test fun playlistTypesAndExtensionsNeverReachTheDecoderOrCatalog() = runBlocking {
        val playlists = listOf(
            doc("mime-m3u", "audio/x-mpegurl"), doc("mime-m3u-alias", "audio/mpegurl"),
            doc("mime-pls", "audio/x-scpls"),
            doc("ext-m3u", "audio/flac").copy(name = "mix.M3U"),
            doc("ext-m3u8", "audio/mpeg").copy(name = "mix.m3u8"),
            doc("ext-pls", "audio/aac").copy(name = "mix.PLS"),
            doc("ext-xspf", "audio/unknown").copy(name = "mix.xspf"),
            doc("ext-wpl", "audio/unknown").copy(name = "mix.wpl"),
        )
        val files = playlists + listOf(doc("song"), doc("extensionless").copy(name = "song"),
            doc("fallback", "application/octet-stream").copy(name = "song.FLAC"), doc("broken").copy(name = "broken.mp3"))
        val inspected = mutableListOf<String>()
        val source = object : LibraryScanSource by Source(files) {
            override suspend fun inspect(document: LibraryDocument): LibraryAudio {
                inspected += document.id
                if (document in playlists || document.id == "broken") throw IOException()
                return audio
            }
        }
        val index = Index()
        repeat(2) {
            scanLibrary("root", index, source)
            assertEquals(setOf("song", "extensionless", "fallback", "broken"), index.rows.keys)
            assertTrue(inspected.none { id -> playlists.any { it.id == id } })
            assertNull(index.rows.getValue("broken").audio)
            assertEquals(LibraryRootState.READY, index.state)
        }
        assertEquals(2, inspected.count { it == "broken" })
    }
    @Test fun rescanRemovesOldPlaylistRowsWithoutDiscardingMissingMusic() = runBlocking {
        val index = Index()
        val playlist = doc("playlist", "audio/x-mpegurl").copy(name = "mix.m3u")
        index.begin("root", "old")
        index.record("root", "old", playlist, null)
        index.record("root", "old", doc("missing"), audio)
        index.finish("root", "old")
        scanLibrary("root", index, Source(listOf(playlist, doc("song"))))
        assertEquals(setOf("song"), index.rows.keys)
    }
}
