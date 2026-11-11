package com.evanna.player.library

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.evanna.player.decoder.NativeDecoder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlinx.coroutines.*

internal object ArtworkProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "artwork-native-probe").apply { mkdirs() }
        fun asset(name: String) = instrumentation.context.assets.open("artwork/$name").use { it.readBytes() }
        fun decode(file: File, inspect: (NativeDecoder) -> Unit) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            NativeDecoder.open(it.fd).use(inspect)
        }
        val front = asset("front.png")
        val back = asset("back.jpg")
        try {
            for ((name, expected) in listOf("cover.flac" to front, "covers.mp3" to front, "cover.m4a" to back, "cover.ogg" to front)) {
                val file = File(directory, name).apply { writeBytes(asset(name)) }
                decode(file) { decoder ->
                    check(decoder.artwork()?.contentEquals(expected) == true) { "Embedded cover differs in $name" }
                    check(decoder.stats().positionFrames == 0L)
                    val bitmap = checkNotNull(decodeArtwork(expected))
                    check(bitmap.width in 1..256 && bitmap.height in 1..256)
                    if (expected === front) checkCover(bitmap)
                    bitmap.recycle()
                    if (name == "cover.flac") {
                        val buffer = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)
                        check(decoder.readPcm(buffer, 4) == 4)
                        val expectedPcm = intArrayOf(Int.MIN_VALUE, 2147483392, -256, 256, 0, 0, 768, -1280)
                        check((0..7).map { buffer.getInt(it * 4) } == expectedPcm.toList())
                        check(decoder.readPcm(buffer, 4) == 0)
                    }
                }
            }
            val plain = instrumentation.context.assets.open("decoder-extended/tone.mp3").use { it.readBytes() }
            val file = File(directory, "edge.mp3")
            file.writeBytes(plain); decode(file) { check(it.artwork() == null) }
            val largeCover = ByteArray(6 * 1024 * 1024).also { front.copyInto(it) }
            file.writeBytes(mp3Picture(plain, largeCover))
            decode(file) { decoder ->
                val extracted = checkNotNull(decoder.artwork()) { "A valid 6 MiB cover was dropped by the native reader" }
                check(extracted.contentEquals(largeCover))
                val bitmap = checkNotNull(decodeArtwork(extracted)) { "A valid 6 MiB cover was dropped before bitmap decode" }
                try { checkCover(bitmap) } finally { bitmap.recycle() }
                check(decoder.stats().positionFrames == 0L)
            }
            val tooLarge = ByteArray(8 * 1024 * 1024 + 1).also { front.copyInto(it) }
            check(decodeArtwork(tooLarge) == null)
            file.writeBytes(mp3Picture(plain, tooLarge))
            try { decode(file) { check(it.artwork() == null) { "Oversized native artwork was copied" } } }
            catch (failure: IOException) { if (failure.message != "DECODER_LIMIT_EXCEEDED") throw failure }
            file.writeBytes(mp3Picture(plain, "invalid image bytes".toByteArray()))
            decode(file) { check(it.artwork()?.let(::decodeArtwork) == null) }
            val bomb = front.copyOf()
            ByteBuffer.wrap(bomb).order(ByteOrder.BIG_ENDIAN).putInt(16, 20000)
            val crc = CRC32().apply { update(bomb, 12, 17) }.value.toInt()
            ByteBuffer.wrap(bomb).order(ByteOrder.BIG_ENDIAN).putInt(29, crc)
            check(decodeArtwork(bomb) == null)
            decode(file) { decoder -> decoder.requestCancel(); check(runCatching { decoder.artwork() }.isFailure) }
            checkSourceVersions(instrumentation)
            return "Exact FLAC/MP3(front over back)/M4A JPEG/Ogg PNG extraction, 6 MiB cover decode, unchanged literal PCM, sampled colors, missing/malformed/oversized art, dimension bomb and cancelled decoder checks passed; no audio output."
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    fun library(instrumentation: Instrumentation): String = runBlocking {
        val context = instrumentation.targetContext
        val root = LocalLibrary.database(context).roots().singleOrNull { it.name == "Evanna-library-check" }
            ?: error("Select the generated Evanna-library-check folder first")
        val source = SafLibrarySource(context, Uri.parse(root.uri))
        val document = source.children(source.root().id).singleOrNull { it.name == "Evanna-embedded-cover-check.flac" }
            ?: error("Generated embedded-cover fixture is not visible in the selected test folder")
        val audio = source.inspect(document)
        val name = "artwork-probe.db"
        val directory = File(context.cacheDir, "artwork-cache-probe")
        context.deleteDatabase(name)
        directory.mkdirs()
        val grants = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        try {
            LibraryDatabase(context, name).use { db ->
                db.addRoot(root.uri, root.name, false)
                scanLibrary(root.uri, db, source)
                val plainSong = db.page(filter = LibraryFilter(search = "plain.flac")).songs.singleOrNull { it.title == "plain.flac" }
                    ?: error("The existing plain.flac no-cover test fixture is missing")
                val plain = checkNotNull(db.cached(root.uri, plainSong.id)).document
                fun record(token: String, item: LibraryDocument = document) {
                    db.begin(root.uri, token); db.record(root.uri, token, item, audio); db.finish(root.uri, token)
                }
                record("first")
                val first = checkNotNull(db.artworkSource(document.key))
                File(directory, "${first.key}.art").writeBytes(byteArrayOf()) // Old reader's cached miss.
                val cache = EmbeddedArtwork(context, db, directory)
                val small = checkNotNull(cache.song(document.key))
                checkCover(small)
                check(small.width == 256) { "List cover was not scaled to 256 px: ${small.width}" }
                checkCover(checkNotNull(cache.album(AlbumIdentity(audio.album, audio.musicTags.albumArtist))))
                check(File(directory, "${first.key}.art").length() in 1..512 * 1024L)
                // The 320 px fixture is smaller than LARGE, so it keeps its own size instead of being enlarged.
                val large = checkNotNull(cache.song(document.key, ArtworkSize.LARGE))
                checkCover(large)
                check(large.width == 320) { "Large cover changed size: ${large.width}" }
                check(File(directory, "${artworkSizeKey(first.key, ArtworkSize.LARGE)}.art").length() > 0)
                db.interrupt(root.uri, "first", LibraryRootState.UNAVAILABLE)
                check(!checkNotNull(db.artworkSource(document.key)).available)
                checkCover(checkNotNull(EmbeddedArtwork(context, db, directory).song(document.key)))
                // An offline folder falls back to the cached list cover when MEDIUM was never made.
                checkNotNull(EmbeddedArtwork(context, db, directory).song(document.key, ArtworkSize.MEDIUM)).let { checkCover(it); check(it.width == 256) }

                record("rescan")
                val updated = checkNotNull(db.artworkSource(document.key))
                check(updated.key != first.key)
                checkCover(checkNotNull(cache.song(document.key)))
                check(File(directory, "${updated.key}.art").isFile)
                val missing = document.copy(key = "f".repeat(64), uri = document.uri + "-not-present")
                record("missing", missing)
                check(cache.song(missing.key) == null)
                val missingKey = checkNotNull(db.artworkSource(missing.key)).key
                check(!File(directory, "${artworkMissKey(missingKey)}.art").exists()) { "Transient failure was negatively cached" }

                // Existing nested plain.flac is an original test fixture with no cover.
                record("plain", plain)
                check(cache.song(plain.key) == null)
                val plainKey = checkNotNull(db.artworkSource(plain.key)).key
                check(File(directory, "${artworkMissKey(plainKey)}.art").let { it.isFile && it.length() == 0L })
                check(cache.song(plain.key, ArtworkSize.LARGE) == null)
                check(!File(directory, "${artworkSizeKey(plainKey, ArtworkSize.LARGE)}.art").exists()) { "No-cover marker was not shared by sizes" }

                record("cancel")
                val before = File("/proc/self/fd").list()!!.size
                coroutineScope {
                    val loads = List(32) { launch(Dispatchers.IO) { cache.song(document.key) } }
                    loads.forEach { it.cancel() }; loads.joinAll()
                }
                checkCover(checkNotNull(withTimeout(10000) { cache.song(document.key) }))
                check(File("/proc/self/fd").list()!!.size <= before + 2) { "Cancelled covers retained file descriptors" }

                repeat(ARTWORK_CACHE_FILES + 2) { index -> File(directory, "%064x.art".format(index)).apply { writeBytes(byteArrayOf()); setLastModified(1) } }
                val big = 20L * 1024 * 1024
                repeat((ARTWORK_CACHE_BYTES / big + 1).toInt()) { index ->
                    RandomAccessFile(File(directory, "%064x.art".format(index + 100000)), "rw").use { it.setLength(big) }
                }
                checkCover(checkNotNull(EmbeddedArtwork(context, db, directory).song(document.key)))
                val entries = directory.listFiles()!!.filter { it.extension == "art" }
                check(entries.size <= ARTWORK_CACHE_FILES && entries.sumOf { it.length() } <= ARTWORK_CACHE_BYTES)
                check(context.contentResolver.persistedUriPermissions.map { it.uri }.toSet() == grants)
            }
            scanLibrary(root.uri, LocalLibrary.database(context), source)
            return@runBlocking "Real tree-backed cover/cache/reopen, list/large cover sizes without enlarging, unavailable-media cache hit and list-size fallback, same-stamp rescan invalidation, album lookup, shared missing-art marker, transient retry, queued cancellation/mutex recovery/FD bound and $ARTWORK_CACHE_FILES-entry/${ARTWORK_CACHE_BYTES shr 20}MiB eviction passed. Generated silent cover indexed; grants unchanged; no output opened."
        } finally {
            context.deleteDatabase(name)
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }

    /** The front cover's two colours. Disk copies are JPEG, so a few levels of rounding are allowed. */
    private fun checkCover(bitmap: Bitmap) {
        fun near(pixel: Int, red: Int, green: Int, blue: Int) = abs(Color.red(pixel) - red) <= 4 &&
            abs(Color.green(pixel) - green) <= 4 && abs(Color.blue(pixel) - blue) <= 4
        check(near(bitmap.getPixel(bitmap.width / 4, bitmap.height / 2), 131, 198, 181)) { "Mint half of the cover is wrong" }
        check(near(bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2), 112, 69, 45)) { "Brown half of the cover is wrong" }
    }

    private fun checkSourceVersions(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val name = "artwork-version-probe.db"
        context.deleteDatabase(name)
        try {
            LibraryDatabase(context, name).use { db ->
                val document = LibraryDocument("shared", "1".repeat(64), "content://test/shared", "shared.flac", "audio/flac", 100, 100)
                val audio = LibraryAudio("Title", null, null, 1000, "FLAC", 44100, 24, 2)
                for (root in listOf("a", "b")) {
                    db.addRoot(root, root, true); db.begin(root, "initial"); db.record(root, "initial", document, audio); db.finish(root, "initial")
                }
                val before = checkNotNull(db.artworkSource(document.key)).key
                db.begin("b", "rescan"); db.record("b", "rescan", document, audio); db.finish("b", "rescan")
                check(checkNotNull(db.artworkSource(document.key)).key != before) { "Rescanning overlapping root B kept root A's stale artwork identity" }
                val after = checkNotNull(db.artworkSource(document.key)).key
                check(after == checkNotNull(db.artworkSource(document.key)).key)
                db.interrupt("b", "rescan", LibraryRootState.UNAVAILABLE)
                check(checkNotNull(db.artworkSource(document.key)).available)
                check(db.artworkSource("invalid") == null)
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun mp3Picture(mp3: ByteArray, picture: ByteArray): ByteArray {
        var skip = 0
        if (mp3.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) {
            skip = 10 + (mp3[6].toInt() and 127 shl 21) + (mp3[7].toInt() and 127 shl 14) +
                (mp3[8].toInt() and 127 shl 7) + (mp3[9].toInt() and 127)
        }
        val payload = byteArrayOf(0) + "image/png\u0000".toByteArray() + byteArrayOf(3, 0) + picture
        val frame = "APIC".toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + byteArrayOf(0, 0) + payload
        val header = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + byteArrayOf(
            (frame.size shr 21 and 127).toByte(), (frame.size shr 14 and 127).toByte(),
            (frame.size shr 7 and 127).toByte(), (frame.size and 127).toByte())
        return header + frame + mp3.copyOfRange(skip, mp3.size)
    }
}
