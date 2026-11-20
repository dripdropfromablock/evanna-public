package com.evanna.player.library

import android.content.Context
import android.util.LruCache
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads a song's lyrics on demand: its embedded tags (through the cover reader's local-only path, never the playback
 * decoder), the .lrc beside it, then lyrics found online and kept in Evanna.
 */
internal class EmbeddedLyrics(private val database: LibraryDatabase) {
    private val none = Lyrics(emptyList())
    // Keyed by the same source version as covers, so a rescan that changes the file reads it again.
    private val memory = LruCache<String, Lyrics>(32)
    private val writes = Mutex()

    suspend fun song(context: Context, id: String): Lyrics? = load(context, id, { database.songPlace(id) }) { database.artworkSource(id) }

    suspend fun file(context: Context, id: String, uri: String): Lyrics? = load(context, id, { null }) { directSource(id, uri) }

    /** Null means the song has no lyrics. A file that cannot be read throws; a folder that cannot be listed only skips its .lrc. */
    private suspend fun load(context: Context, id: String, place: () -> SongPlace?, source: () -> ArtworkSource?): Lyrics? =
        withContext(Dispatchers.IO) {
            val found = source() ?: return@withContext null
            memory.get(found.key)?.let { return@withContext it.takeIf { cached -> cached !== none } }
            if (!found.available) throw IOException("LYRICS_SOURCE_UNAVAILABLE")
            val app = context.applicationContext
            val embedded = readLocalDecoder(app, found.uri) { it.lyrics() }
            // A .lrc beside the song counts like its own tags (timed beats plain); lyrics kept in Evanna come last.
            val beside = place()?.let { at ->
                try { LyricsFiles.readBeside(app, at) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            val lyrics = bestLyrics(embedded + listOfNotNull(beside)) ?: LyricsFiles.readSaved(app, id)?.let(::parseLyrics)
            memory.put(found.key, lyrics ?: none)
            lyrics
        }

    /** Where lyrics found online went; [askRoot] is the music folder that would take the .lrc if Evanna could write in it. */
    data class Kept(val place: LyricsPlace, val askRoot: String?)

    /** Checks the current sources without trusting a cached absence of lyrics. */
    suspend fun hasLibraryLyrics(app: Context, id: String, folders: LyricsFiles.FolderIndex): Boolean = withContext(Dispatchers.IO) {
        val source = database.artworkSource(id)?.takeIf { it.available } ?: throw IOException("LYRICS_SOURCE_UNAVAILABLE")
        val place = database.songPlace(id) ?: throw IOException("LYRICS_SOURCE_UNAVAILABLE")
        hasLyrics(app, id, source, place, folders)
    }

    private suspend fun hasLyrics(app: Context, id: String, source: ArtworkSource, place: SongPlace,
        folders: LyricsFiles.FolderIndex): Boolean =
        LyricsFiles.readSaved(app, id)?.let(::parseLyrics) != null ||
            LyricsFiles.readBeside(app, place, folders)?.let(::parseLyrics) != null ||
            bestLyrics(readLocalDecoder(app, source.uri) { it.lyrics() }) != null

    suspend fun keepMissing(app: Context, id: String, match: LyricsLookup.Match, folders: LyricsFiles.FolderIndex): Kept? =
        withContext(Dispatchers.IO) {
            writes.withLock {
                val source = database.artworkSource(id)?.takeIf { it.available } ?: throw IOException("LYRICS_SOURCE_UNAVAILABLE")
                val place = database.songPlace(id) ?: throw IOException("LYRICS_SOURCE_UNAVAILABLE")
                if (hasLyrics(app, id, source, place, folders)) return@withLock null
                val beside = LyricsFiles.writeBeside(app, place, match.text, folders)
                if (!beside && LyricsFiles.readBeside(app, place, folders)?.let(::parseLyrics) != null) return@withLock null
                store(app, id, source, place, match.text, match.lyrics, beside)
            }
        }

    /**
     * Keeps lyrics found online for a library song ([uri] null) or a single opened file: as a .lrc beside the song when its
     * folder lets Evanna write, else in Evanna. Either way the song shows them at once.
     */
    suspend fun keep(context: Context, id: String, uri: String?, text: String, lyrics: Lyrics): Kept = withContext(Dispatchers.IO) {
        writes.withLock {
            val app = context.applicationContext
            val place = if (uri == null) database.songPlace(id) else null
            val beside = place != null && LyricsFiles.writeBeside(app, place, text)
            val source = if (uri == null) database.artworkSource(id) else directSource(id, uri)
            store(app, id, source, place, text, lyrics, beside)
        }
    }

    private fun store(app: Context, id: String, source: ArtworkSource?, place: SongPlace?, text: String,
        lyrics: Lyrics, beside: Boolean): Kept {
        if (beside) LyricsFiles.deleteSaved(app, id) else LyricsFiles.writeSaved(app, id, text)
        source?.let { memory.put(it.key, lyrics) }
        return if (beside) Kept(LyricsPlace.BESIDE_SONG, null)
        else Kept(LyricsPlace.IN_APP, place?.root?.takeUnless { LyricsFiles.canWrite(app, it) })
    }

    /** Once the folder lets Evanna write: moves the library song's lyrics kept in Evanna to a .lrc beside it. */
    suspend fun moveBeside(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            val app = context.applicationContext
            val place = database.songPlace(id) ?: return@withLock false
            val text = LyricsFiles.readSaved(app, id) ?: return@withLock false
            LyricsFiles.writeBeside(app, place, text).also { if (it) LyricsFiles.deleteSaved(app, id) }
        }
    }
}
