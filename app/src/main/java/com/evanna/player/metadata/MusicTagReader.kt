package com.evanna.player.metadata

import android.content.Context
import android.net.Uri
import com.evanna.player.decoder.DecoderInfo
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.inspectLibraryDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal suspend fun auditMusicTags(context: Context, progress: suspend (checked: Int, total: Int) -> Unit): List<TagAuditEntry> =
    withContext(Dispatchers.IO) {
        val sources = LocalLibrary.database(context).musicTagSources()
        val folders = TagSidecarFolders(context)
        progress(0, sources.size)
        sources.mapIndexed { index, source ->
            coroutineContext.ensureActive()
            val entry = readMusicTags(context, folders, source)
            val checked = index + 1
            if (checked % 24 == 0 || checked == sources.size) progress(checked, sources.size)
            entry
        }
    }

private suspend fun readMusicTags(context: Context, folders: TagSidecarFolders, source: MusicTagSource): TagAuditEntry {
    if (source.status != TagFileStatus.READABLE) return TagAuditEntry(source, null)
    return try {
        val info = inspectLibraryDocument(context.contentResolver, Uri.parse(source.uri))
        val embedded = info.tagValues()
        try {
            TagAuditEntry(source, folders.read(source)?.applyTo(embedded) ?: embedded, embedded)
        } catch (_: IOException) {
            TagAuditEntry(source, embedded, embedded, sidecarIssue = true)
        } catch (_: SecurityException) {
            TagAuditEntry(source, embedded, embedded, sidecarIssue = true)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecurityException) {
        TagAuditEntry(source.copy(status = TagFileStatus.UNAVAILABLE), null)
    } catch (_: IOException) {
        TagAuditEntry(source.copy(status = TagFileStatus.UNREADABLE), null)
    }
}

internal fun DecoderInfo.tagValues(): Map<MusicTagField, String> = buildMap {
    title?.let { put(MusicTagField.TITLE, it) }
    artist?.let { put(MusicTagField.ARTIST, it) }
    album?.let { put(MusicTagField.ALBUM, it) }
    val tags = musicTags
    tags.albumArtist?.let { put(MusicTagField.ALBUM_ARTIST, it) }
    tags.trackNumber?.let { put(MusicTagField.TRACK_NUMBER, it.toString()) }
    tags.totalTracks?.let { put(MusicTagField.TRACK_TOTAL, it.toString()) }
    tags.discNumber?.let { put(MusicTagField.DISC_NUMBER, it.toString()) }
    tags.totalDiscs?.let { put(MusicTagField.DISC_TOTAL, it.toString()) }
    releaseDate?.let { put(MusicTagField.YEAR, it) }
    tags.genre?.let { put(MusicTagField.GENRE, it) }
    tags.composer?.let { put(MusicTagField.COMPOSER, it) }
    if (hasEmbeddedArtwork) put(MusicTagField.ARTWORK, "Embedded cover")
}
