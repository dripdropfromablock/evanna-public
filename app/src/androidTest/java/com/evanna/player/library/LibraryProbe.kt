package com.evanna.player.library

import android.app.Instrumentation
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.decoder.*
import com.evanna.player.playback.*
import java.io.File
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import com.truesample.usbaudio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@UnstableApi
internal object LibraryProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val name = "library-probe.db"
        context.deleteDatabase(name)
        fun document(id: String, root: String = "a") = LibraryDocument(id, id, "content://test/$root/$id", "$id.flac", "audio/flac", 50, 100)
        val audio = LibraryAudio("Title", "Artist", "Album", 1000000, "FLAC", 44100, 24, 2)
        try {
            LibraryDatabase(context, name).use { db ->
                db.addRoot("a", "First", true); db.addRoot("b", "Overlap", true)
                db.begin("a", "first"); db.record("a", "first", document("one"), audio); db.record("a", "first", document("two"), audio.copy(title = "Other")); db.finish("a", "first")
                check(db.page().songs.size == 2 && db.page().songs.all { it.available })
                check(db.cached("a", "one")?.audio == audio)
                db.begin("a", "partial"); db.record("a", "partial", document("one"), audio); db.interrupt("a", "partial", LibraryRootState.UNAVAILABLE)
                check(db.page().total == 2 && db.page().songs.none { it.available })
                db.begin("b", "overlap"); db.record("b", "overlap", document("one", "b"), audio); db.finish("b", "overlap")
                check(db.page().total == 2 && db.page().songs.single { it.id == "one" }.uri == "content://test/b/one")
                db.begin("a", "old"); db.begin("a", "new")
                db.record("a", "old", document("stale"), audio); db.finish("a", "old"); db.interrupt("a", "old", LibraryRootState.UNAVAILABLE)
                check(db.page().total == 2 && db.page().roots.single { it.uri == "a" }.state == LibraryRootState.SCANNING)
                db.record("a", "new", document("one"), audio); db.finish("a", "new")
                check(db.page().total == 1 && db.page().songs.none { it.id == "two" })
                check(db.page(1).songs.size == 1 && db.page(1).total == 1)
                check(db.page(1, 1).songs.isEmpty())
                check(db.page(1, 100001).songs.isEmpty() && db.page(1, 100001).total == 1)
                db.begin("b", "interrupted")
            }
            LibraryDatabase(context, name).use { db ->
                check(db.page().total == 1 && db.page().roots.single { it.uri == "b" }.state == LibraryRootState.PAUSED)
                check(db.page().songs.single { it.id == "one" }.available)
                val playlist = document("playlist", "lists").copy(name = "mix.M3U", mime = "audio/flac")
                val mimePlaylist = document("mime-playlist", "lists").copy(mime = "audio/x-scpls")
                val missing = document("missing-music", "lists")
                val song = document("new-music", "lists")
                db.addRoot("lists", "Lists", true); db.addRoot("lists-overlap", "Lists overlap", true)
                db.begin("lists", "old")
                db.record("lists", "old", playlist, null); db.record("lists", "old", mimePlaylist, null)
                db.record("lists", "old", missing, audio); db.finish("lists", "old")
                db.begin("lists-overlap", "overlap"); db.record("lists-overlap", "overlap", playlist, null); db.finish("lists-overlap", "overlap")
                db.begin("lists", "stale"); db.begin("lists", "current")
                db.exclude("lists", "stale", playlist.key)
                check(db.cached("lists", playlist.key) != null)
                var inspected = 0
                runBlocking { scanLibrary("lists", db, object : LibraryScanSource {
                    override suspend fun root() = document("directory").copy(mime = DIRECTORY_MIME)
                    override suspend fun children(id: String) = listOf(playlist, mimePlaylist, song)
                    override suspend fun inspect(document: LibraryDocument): LibraryAudio {
                        check(document == song); inspected++; return audio
                    }
                }) }
                check(inspected == 1 && db.cached("lists", playlist.key) == null)
                check(db.cached("lists-overlap", playlist.key) != null)
                check(db.page().songs.none { it.id == mimePlaylist.key })
                check(db.page().songs.none { it.id == missing.key })
                val unchanged = runBlocking { scanLibrary("lists", db, object : LibraryScanSource {
                    override suspend fun root() = document("directory").copy(mime = DIRECTORY_MIME)
                    override suspend fun children(id: String) = listOf(song)
                    override suspend fun inspect(document: LibraryDocument): LibraryAudio = error("Unchanged song was decoded")
                }, silent = true) }
                check(!unchanged.changed && db.page().songs.any { it.id == song.key && it.available })
                db.begin("lists-overlap", "exclude"); db.exclude("lists-overlap", "exclude", playlist.key)
                check(db.page().total == 2 && db.page().songs.none { it.id == playlist.key })
                check(db.readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks WHERE id=?", arrayOf(playlist.key)).use { it.moveToFirst(); it.getInt(0) } == 0)
            }
            val file = File(context.cacheDir, "library-tag-check.flac")
            try {
                instrumentation.context.assets.open("library/tagged.flac").use { input -> file.outputStream().use(input::copyTo) }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    NativeDecoder.open(descriptor.fd).use { decoder ->
                        check(decoder.info.title == "Evanna café")
                        check(decoder.info.artist == "Local artist" && decoder.info.album == "Offline album")
                        check(decoder.info.significantBits == 24 && decoder.info.sampleRate == 44100)
                    }
                }
            } finally { file.delete() }
            check(!isLocalTree(context, Uri.parse("content://cloud.example/tree/music")))
            check(!isLocalTree(context, Uri.parse("file:///sdcard/Music")))
            return "SQLite saved metadata/reopen, complete-only missing marking, interrupted scans, stale generations, overlap dedup/access choice and paging passed; playlist rescan exclusion/orphan cleanup preserves other roots and missing music; bounded UTF-8 title/artist/album matched; remote/file providers rejected; no audio output opened"
        } finally { context.deleteDatabase(name) }
    }

    /** Run after selecting only the generated Evanna-library-check folder through the real picker. */
    fun runGrant(instrumentation: Instrumentation): String = runBlocking {
        val context = instrumentation.targetContext
        val db = LocalLibrary.database(context)
        val root = db.page().roots.single { it.name == "Evanna-library-check" }
        val tree = Uri.parse(root.uri)
        check(hasTreeReadGrant(context, tree))
        scanLibrary(root.uri, db, SafLibrarySource(context, tree))
        val song = db.page().songs.single { it.title == "Evanna café" && it.available }
        check(song.artist == "Local artist" && song.album == "Offline album")
        val before = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val control = future.get(10, TimeUnit.SECONDS)
        val owner = PlaybackRuntime.owner(context)
        try {
            lateinit var selected: com.google.common.util.concurrent.ListenableFuture<SessionResult>
            main { selected = control.sendCustomCommand(SessionCommand(PlaybackService.SELECT_FILE, Bundle.EMPTY), Bundle().apply { putString(PlaybackService.FILE_URI, song.uri) }) }
            check(selected.get(10, TimeUnit.SECONDS).resultCode == SessionResult.RESULT_SUCCESS)
            fun await(condition: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 10000
                do { var done = false; main { done = condition() }; if (done) return; SystemClock.sleep(20) } while (SystemClock.elapsedRealtime() < end)
                error("Library selection did not settle")
            }
            await { control.mediaItemCount == 1 && !control.playWhenReady }
            check(owner.snapshot.value.route == null && owner.snapshot.value.stream == null && owner.snapshot.value.androidStream == null)
            check(owner.selectedTrack?.format?.codec == "FLAC")
            main { control.stop() }; await { control.mediaItemCount == 0 }
            check(hasTreeReadGrant(context, tree) && db.page().songs.any { it.id == song.id && it.available })
            val output = SyntheticOutput()
            val synthetic = UsbSessionOwner(UsbPlaybackController { output }, object : PlaybackFocus {
                override var held = false
                override fun request(): Boolean { held = true; return true }
                override fun abandon() { held = false }
            })
            try {
                check(synthetic.selectTrack { DecodedAudioTrack.local(context, Uri.parse(song.uri)) })
                check(synthetic.open(7) && synthetic.play())
                val end = withTimeout(10000) { synthetic.snapshot.first { it.state in listOf(UsbPlaybackState.ENDED, UsbPlaybackState.ERROR) } }
                check(end.state == UsbPlaybackState.ENDED && end.framesAccepted == 4L)
                check(output.samples == listOf(Int.MIN_VALUE, 2147483392, -256, 256, 0, 0, 768, -1280))
            } finally { check(synthetic.close()) }
            check(context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet() == before)
            return@runBlocking "Real local tree grant, rescan/tagged catalog, service selection without autoplay, literal PCM through synthetic playback/EOF, Stop retains folder/index and unowned grants passed; no physical output opened"
        } finally { main { control.release() }; check(owner.close()) }
    }

    private class SyntheticOutput : UsbPlaybackOutput {
        override val description = "Synthetic library sink"
        val samples = mutableListOf<Int>()
        private var ended = false
        override fun setEqualizer(bands: List<EqBand>, preampDb: Float) = Unit
        override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, source.significantBits, false, true)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat) = StreamInfo(source.sampleRate, source.significantBits, source.channels,
            DacFormat(1, 1, 1, source.channels, 3, 24, DacFormat.SyncType.SYNC, 512, listOf(source.sampleRate), source.sampleRate, source.sampleRate),
            source.sampleRate, source.sampleRate, true, false)
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats(): StreamStats { val frames = samples.size.toLong() / 2; return StreamStats(frames, 0, 0, false, frames, frames, ended) }
        override fun write(buffer: ByteBuffer, frames: Int): Int { repeat(frames * 2) { samples += buffer.getInt(it * 4) }; return frames }
        override fun drain(): Boolean { ended = true; return true }
        override fun stop() = true
        override fun close() = Unit
    }

    /** Explicitly revoke only the generated test folder, then prove that its catalog survives. */
    fun revokeTestFolder(instrumentation: Instrumentation): String = runBlocking {
        val context = instrumentation.targetContext
        val db = LocalLibrary.database(context)
        val root = db.page().roots.single { it.name == "Evanna-library-check" }
        val tree = Uri.parse(root.uri)
        check(android.provider.DocumentsContract.getTreeDocumentId(tree).endsWith("/Evanna-library-check"))
        val before = db.page().songs.map { it.id }.toSet()
        check(before.isNotEmpty() && hasTreeReadGrant(context, tree))
        val otherGrants = context.contentResolver.persistedUriPermissions.filter { it.uri != tree }.map { it.uri }.toSet()
        context.contentResolver.releasePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scanLibrary(root.uri, db, SafLibrarySource(context, tree))
        check(!hasTreeReadGrant(context, tree) && db.page().roots.single { it.uri == root.uri }.state == LibraryRootState.UNAVAILABLE)
        check(db.page().songs.map { it.id }.toSet() == before && db.page().songs.none { it.available })
        check(context.contentResolver.persistedUriPermissions.map { it.uri }.toSet().containsAll(otherGrants))
        "Explicit test-folder grant revocation marked it unavailable, retained song identities/metadata and preserved other grants; select the test folder again to restore access"
    }
}
