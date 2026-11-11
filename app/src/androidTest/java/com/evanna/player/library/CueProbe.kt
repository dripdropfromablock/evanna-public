package com.evanna.player.library

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.evanna.player.decoder.*
import com.evanna.player.playback.RangePcmTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Original CUE fixture and a disposable index only; never touches the owner's library, grants or output. */
internal object CueProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "cue-probe").apply { check(isDirectory || mkdirs()) }
        val name = "cue-probe.db"
        val queueName = "cue-queue-probe.db"
        val backup = File(context.noBackupFilesDir, "library-migrations/cue-probe-v5-backup.db")
        context.deleteDatabase(name); context.deleteDatabase(queueName); backup.delete()
        try {
            val audio = File(directory, "album.flac")
            instrumentation.context.assets.open("cue/album.flac").use { input -> audio.outputStream().use(input::copyTo) }
            ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val metadata = NativeDecoder.inspect(descriptor.fd, CancellationSignal())
                NativeDecoder.open(descriptor.fd).use { decoder ->
                    check(metadata == decoder.info.copy(gaplessEligible = false, loudnessTags = com.evanna.player.loudness.LoudnessTags()))
                }
                val cancelled = CancellationSignal().apply { cancel() }
                check(runCatching { NativeDecoder.inspect(descriptor.fd, cancelled) }.exceptionOrNull() is java.io.IOException)
            }
            val sheet = parseCueSheet(instrumentation.context.assets.open("cue/album.cue").use { it.readBytes() })
            val file = DecodedAudioTrack.prepare({ ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDecoder.open(it.fd) } })
            val rate = file.format.sampleRate
            val ranges = checkNotNull(cueRanges(sheet.files.single(), rate, file.format.durationUs))
            check(ranges.map { it.startFrame } == listOf(0L, 47_040L, 95_844L)) { "CUE frame math differs: $ranges" }
            // Left holds n & 0xFFFF and right holds n >> 16 in the high 16 bits of each S32 sample.
            fun frameOf(buffer: ByteBuffer, index: Int) = (buffer.getInt(index * 8) ushr 16) or ((buffer.getInt(index * 8 + 4) shr 16) shl 16)
            var expected = 0
            for (range in ranges) RangePcmTrack(file, range.startFrame, range.endFrame).open().use { reader ->
                val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    val count = reader.read(buffer, 4096)
                    if (count == 0) break
                    repeat(count) { check(frameOf(buffer, it) == expected++) { "CUE sample missing or repeated near frame $expected" } }
                }
                check(expected.toLong() == (range.endFrame ?: 132_300L)) { "CUE track ${range.track.number} ended at $expected" }
            }
            check(expected == 132_300)
            RangePcmTrack(file, 47_040, 95_844).open().use { reader ->
                reader.seekTo(500_000) // 22,050 frames into track two
                val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
                check(reader.read(buffer, 1) == 1 && frameOf(buffer, 0) == 47_040 + 22_050) { "Seek left the CUE range" }
                check(runCatching { reader.seekTo(2_000_000) }.isFailure)
            }
            file.close()

            val root = "content://probe/tree/root"
            val old = object : SQLiteOpenHelper(context, name, null, 5) {
                override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
                override fun onCreate(db: SQLiteDatabase) { librarySchema(5).forEach(db::execSQL) }
                override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) = error("Unexpected upgrade")
            }
            old.writableDatabase.apply {
                execSQL("INSERT INTO roots VALUES(?,'Probe','READY',NULL,1)", arrayOf(root))
                execSQL("INSERT INTO excluded_folders VALUES(?,'skip','Skip')", arrayOf(root))
                execSQL("INSERT INTO pending_grant_releases VALUES('content://probe/tree/old')")
            }
            old.close()
            LibraryDatabase(context, name).use { db ->
                check(db.readableDatabase.version == LIBRARY_DB_VERSION)
                SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                    check(copy.version == 5)
                    for (table in listOf("roots", "excluded_folders", "pending_grant_releases"))
                        check(copy.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) } == 1) { "Backup lost $table" }
                }
                check(db.excludedFolders(root).single().id == "skip" && db.pendingGrantReleases() == listOf("content://probe/tree/old"))
                val key = "c".repeat(64)
                val document = LibraryDocument("doc", key, "$root/document/doc", "album.flac", "audio/flac", audio.length(), 1)
                val info = LibraryAudio("Whole file", "Artist", "Album", file.format.durationUs, "FLAC", rate, 16, 2)
                fun scan(withSheet: Boolean) {
                    val token = java.util.UUID.randomUUID().toString()
                    db.begin(root, token); db.record(root, token, document, info)
                    if (withSheet) db.recordCue(root, token, document, info, sheet, ranges)
                    db.finish(root, token)
                }
                scan(true); scan(true)
                val listed = db.page().songs.sortedBy { it.startFrame }
                check(listed.map { it.title } == listOf("One", "Two", "Three")) { "CUE songs differ: ${listed.map { it.title }}" }
                check(listed.map { it.startFrame to it.endFrame } == listOf(0L to 47_040L, 47_040L to 95_844L, 95_844L to null))
                check(listed.all { it.available && it.album == "CUE fixture" && it.artist == "Evanna" && it.uri == document.uri && it.musicTags.totalTracks == 3 })
                check(db.song(listed[1].id)?.endFrame == 95_844L && db.song(key)?.startFrame == null)
                scan(false)
                val after = db.page().songs
                check(after.single().title == "Whole file") { "Removing the sheet must show the whole file again" }

                val gapped = parseCueSheet(("TITLE \"Gapped album\"\nFILE \"album.flac\" WAVE\n" +
                    "TRACK 07 AUDIO\nTITLE Seven\nINDEX 01 00:00:00\n" +
                    "TRACK 09 AUDIO\nTITLE Nine\nINDEX 01 00:01:00\n" +
                    "TRACK 10 MODE1/2352\nINDEX 01 00:02:00").toByteArray())
                val gappedFile = document.copy(key = "d".repeat(64), id = "gapped", uri = "$root/document/gapped")
                db.begin(root, "gapped"); db.record(root, "gapped", gappedFile, info)
                db.recordCue(root, "gapped", gappedFile, info, gapped, checkNotNull(cueRanges(gapped.files.single(), rate, info.durationUs)))
                db.finish(root, "gapped")
                val songs = db.page(filter = LibraryFilter(kind = LibraryGroupKind.ALBUM, value = "Gapped album")).songs.sortedBy { it.musicTags.trackNumber }
                check(songs.map { it.musicTags.trackNumber } == listOf(7, 9))
                check(songs.all { it.musicTags.totalTracks == 9 })
                fun saved(song: LibrarySong) = com.evanna.player.playback.SavedQueueEntry("evanna:local:queue:${song.id}",
                    song.id, null, song.title, song.artist, song.album, song.durationUs, song.musicTags)
                com.evanna.player.playback.PlaybackQueueStore(context, queueName).use { queue ->
                    val entries = songs.map(::saved)
                    queue.save(com.evanna.player.playback.SavedQueue(entries, 0, 0))
                    check(queue.load()?.items == entries)
                    // Legacy rows must remain queueable immediately, even before their next rescan repairs the total.
                    db.writableDatabase.execSQL("UPDATE tracks SET track_total=2 WHERE id=?", arrayOf(songs.last().id))
                    val legacy = checkNotNull(db.song(songs.last().id))
                    check(legacy.musicTags.trackNumber == 9 && legacy.musicTags.totalTracks == null)
                    queue.save(com.evanna.player.playback.SavedQueue(listOf(saved(legacy)), 0, 0))
                    check(queue.load()?.items?.single()?.musicTags?.totalTracks == null)
                }
            }
            return "Three CUE tracks of an original FLAC played every frame exactly once across boundaries and seeks stayed inside a track; disposable v5 index migrated with a complete backup; covered whole file hidden, ranges stored, rescan without the sheet restored it; gapped mixed-mode CUE totals, saved queue roundtrip and legacy invalid-total fallback pass. No owner library, grant or output touched."
        } finally {
            context.deleteDatabase(name); context.deleteDatabase(queueName); backup.delete()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
}
