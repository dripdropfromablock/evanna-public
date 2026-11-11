package com.evanna.player.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.LruCache
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.decoder.isLocalDocument
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Disk cache bounds; sized covers make several files per song (about 2,400 files, 45 MiB for 2,164 songs). */
internal const val ARTWORK_CACHE_FILES = 4096
internal const val ARTWORK_CACHE_BYTES = 64L * 1024 * 1024
private const val ARTWORK_FILE_LIMIT = 2 * 1024 * 1024
private const val ARTWORK_INPUT_LIMIT = 8 * 1024 * 1024 // Match nativeArtwork's compressed byte limit.
// Cleanup lists and sorts the whole directory, so it runs on first use and then once per this many new files.
private const val ARTWORK_PRUNE_EVERY = 32
private val temporaryArtworkName = Regex("[0-9a-f]{64}\\.art\\.(new|bak)")
private val artworkName = Regex("[0-9a-f]{64}\\.art")

/** Disposable covers only; independent readers never share the playback decoder or own grants. */
internal class EmbeddedArtwork(context: Context, private val database: LibraryDatabase,
    private val directory: File = File(context.cacheDir, "embedded-artwork-v1")) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var initialized = false
    private var written = 0
    // An eighth of the heap (24 MiB on the M30s's 192 MB heap): a screen of grid covers plus the large Now Playing cover.
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceIn(8L shl 20, 48L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Back to the old 8 MiB while no screen is shown; covers reload quickly from the disk cache. */
    fun trimMemory() = memory.trimToSize(8 shl 20)

    suspend fun song(id: String, size: ArtworkSize = ArtworkSize.SMALL): Bitmap? = cached(size) { database.artworkSource(id) }

    // shortcut: Direct-file covers refresh on reselection; folder rescans use catalog membership versions.
    suspend fun file(id: String, uri: String, size: ArtworkSize = ArtworkSize.SMALL): Bitmap? = cached(size) { directSource(id, uri) }

    private suspend fun cached(size: ArtworkSize, source: () -> ArtworkSource?): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { source()?.let { load(it, size) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { null }
            catch (_: SecurityException) { null }
            catch (_: IllegalArgumentException) { null }
            catch (_: OutOfMemoryError) { null }
        }
    }

    suspend fun album(album: AlbumIdentity, size: ArtworkSize = ArtworkSize.SMALL): Bitmap? {
        // shortcut: Try four accessible songs per album; expand when libraries with more missing covers justify it.
        val candidates = withContext(Dispatchers.IO) { database.albumArtworkSongs(album) }
        for (id in candidates) song(id, size)?.let { return it }
        return null
    }

    private suspend fun load(source: ArtworkSource, size: ArtworkSize): Bitmap? {
        currentCoroutineContext().ensureActive()
        if (!initialized) { prune(); initialized = true }
        val key = artworkSizeKey(source.key, size)
        val file = File(directory, "$key.art")
        memory.get(key)?.let { file.setLastModified(System.currentTimeMillis()); return it }
        // A single versioned miss answers all sizes without invalidating a previously cached bitmap.
        val marker = File(directory, "${artworkMissKey(source.key)}.art")
        if (marker.isFile && marker.length() == 0L) { marker.setLastModified(System.currentTimeMillis()); return null }
        if (file.isFile) {
            if (file.length() <= ARTWORK_FILE_LIMIT) {
                val cached = decodeArtwork(file.readBytes(), size.pixels)
                currentCoroutineContext().ensureActive()
                if (cached != null) {
                    memory.put(key, cached); file.setLastModified(System.currentTimeMillis())
                    return cached
                }
            }
            file.delete()
        }
        // An offline folder can still show the list-size cover when a bigger one was never cached.
        if (!source.available) return if (size == ArtworkSize.SMALL) null else load(source, ArtworkSize.SMALL)
        val bytes = readEmbedded(source.uri)
        currentCoroutineContext().ensureActive()
        val bitmap = bytes?.let { decodeArtwork(it, size.pixels) }
        currentCoroutineContext().ensureActive()
        if (bitmap != null) memory.put(key, bitmap)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("ARTWORK_CACHE_UNAVAILABLE")
            val atomic = AtomicFile(if (bitmap != null) file else marker)
            val output = atomic.startWrite()
            try {
                // JPEG keeps big opaque covers small on disk; PNG keeps transparency.
                val format = if (bitmap?.hasAlpha() == true) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (bitmap != null && !bitmap.compress(format, 90, output)) throw IOException("ARTWORK_CACHE_WRITE_FAILED")
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            if (++written % ARTWORK_PRUNE_EVERY == 0) prune()
        } catch (_: IOException) { } // A full disposable cache must not hide a successfully decoded cover.
        catch (_: SecurityException) { }
        return bitmap
    }

    private fun prune() {
        directory.listFiles()?.filter { it.name.matches(temporaryArtworkName) }?.forEach { it.delete() }
        // Each date and size is read once; a sort comparing lastModified() would ask the disk again on every comparison.
        val files = directory.listFiles()?.filter { it.name.matches(artworkName) }
            ?.map { Triple(it, it.lastModified(), it.length()) }
            ?.sortedWith(compareBy({ it.second }, { it.first.name })) ?: return
        var count = files.size
        var bytes = files.sumOf { it.third }
        for ((file, _, size) in files) {
            if (count <= ARTWORK_CACHE_FILES && bytes <= ARTWORK_CACHE_BYTES) break
            if (file.delete()) { count--; bytes -= size }
        }
    }

    private suspend fun readEmbedded(location: String): ByteArray? = readLocalDecoder(app, location) { it.artwork() }
}

/**
 * Opens a local document with its own decoder, only to read tags or a picture. Folder songs need the folder's read grant.
 * Cancellation reaches both the provider and native reads.
 */
internal suspend fun <T> readLocalDecoder(app: Context, location: String, read: (NativeDecoder) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val acquired = AtomicReference<NativeDecoder?>()
        continuation.invokeOnCancellation { signal.cancel(); acquired.get()?.requestCancel() }
        try {
            val uri = Uri.parse(location)
            if (!isLocalDocument(app, uri)) throw IOException("LOCAL_PROVIDER_REQUIRED")
            if (DocumentsContract.isTreeUri(uri)) {
                val tree = DocumentsContract.buildTreeDocumentUri(uri.authority!!, DocumentsContract.getTreeDocumentId(uri))
                if (!hasTreeReadGrant(app, tree)) throw IOException("FOLDER_ACCESS_REQUIRED")
            }
            val descriptor = app.contentResolver.openFileDescriptor(uri, "r", signal)
                ?: throw IOException("ARTWORK_SOURCE_UNAVAILABLE")
            val value = descriptor.use {
                NativeDecoder.open(descriptor.fd).use { decoder ->
                    acquired.set(decoder)
                    if (!continuation.isActive) throw CancellationException()
                    read(decoder)
                }
            }
            continuation.resume(value)
        } catch (failure: Exception) { continuation.resumeWithException(failure) }
        finally { acquired.set(null) }
    }

/** Decodes to at most [target] pixels on the longest side; smaller covers are never enlarged. */
internal fun decodeArtwork(bytes: ByteArray, target: Int = ArtworkSize.SMALL.pixels): Bitmap? {
    if (bytes.isEmpty() || bytes.size > ARTWORK_INPUT_LIMIT) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType !in listOf("image/jpeg", "image/png", "image/webp")) return null
    val sample = artworkSampleSize(bounds.outWidth, bounds.outHeight, target) ?: return null
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: return null
    val scale = target.toFloat() / maxOf(decoded.width, decoded.height)
    val bitmap = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(decoded,
        (decoded.width * scale).roundToInt().coerceIn(1, target), (decoded.height * scale).roundToInt().coerceIn(1, target), true)
    if (bitmap !== decoded) decoded.recycle()
    if (bitmap.width > target || bitmap.height > target) { bitmap.recycle(); return null }
    return bitmap
}
