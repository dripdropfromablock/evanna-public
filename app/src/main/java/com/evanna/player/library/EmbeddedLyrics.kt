package com.evanna.player.library

import android.content.Context
import android.util.LruCache
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads embedded lyrics on demand through the cover reader's local-only path; never the playback decoder. */
internal class EmbeddedLyrics(private val database: LibraryDatabase) {
    private val none = Lyrics(emptyList())
    // Keyed by the same source version as covers, so a rescan that changes the file reads it again.
    private val memory = LruCache<String, Lyrics>(32)

    suspend fun song(context: Context, id: String): Lyrics? = load(context) { database.artworkSource(id) }

    suspend fun file(context: Context, id: String, uri: String): Lyrics? = load(context) { directSource(id, uri) }

    /** Null means the song has no lyrics. A file that cannot be read throws. */
    private suspend fun load(context: Context, source: () -> ArtworkSource?): Lyrics? = withContext(Dispatchers.IO) {
        val found = source() ?: return@withContext null
        memory.get(found.key)?.let { return@withContext it.takeIf { cached -> cached !== none } }
        if (!found.available) throw IOException("LYRICS_SOURCE_UNAVAILABLE")
        val lyrics = bestLyrics(readLocalDecoder(context.applicationContext, found.uri) { it.lyrics() })
        memory.put(found.key, lyrics ?: none)
        lyrics
    }
}
