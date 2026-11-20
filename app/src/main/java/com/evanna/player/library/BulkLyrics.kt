package com.evanna.player.library

import android.content.Context
import com.evanna.player.metadata.MusicTagSource
import com.evanna.player.metadata.TagFileStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class BulkLyricsOutcome(val label: String) {
    BESIDE_SONG("Saved beside songs"),
    IN_APP("Saved in Evanna"),
    EXISTING("Already had lyrics"),
    NO_MATCH("No confident match"),
    INSTRUMENTAL("Instrumental"),
    UNAVAILABLE("Could not check"),
    FAILED("Lookup or save failed"),
}

internal suspend fun lookupMissingLyrics(app: Context, song: MusicTagSource, client: String,
    folders: LyricsFiles.FolderIndex): BulkLyricsOutcome =
    withContext(Dispatchers.IO) {
        val lyrics = LocalLibrary.lyrics(app)
        fillMissingLyrics(
            song,
            hasLyrics = { lyrics.hasLibraryLyrics(app, song.id, folders) },
            lookup = { query -> LrcLib.lookup(query, client, bulk = true) },
            save = { match -> lyrics.keepMissing(app, song.id, match, folders)?.place },
        )
    }

internal suspend fun fillMissingLyrics(
    song: MusicTagSource,
    hasLyrics: suspend () -> Boolean,
    lookup: suspend (LyricsQuery) -> LyricsLookup,
    save: suspend (LyricsLookup.Match) -> LyricsPlace?,
): BulkLyricsOutcome {
    if (song.status != TagFileStatus.READABLE) return BulkLyricsOutcome.UNAVAILABLE
    val existing = try {
        hasLyrics()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return BulkLyricsOutcome.UNAVAILABLE
    }
    if (existing) return BulkLyricsOutcome.EXISTING
    if (song.title.isBlank()) return BulkLyricsOutcome.NO_MATCH
    val query = LyricsQuery(song.title, song.artist, song.album, song.durationUs?.div(1_000))
    return try {
        when (val result = lookup(query)) {
            LyricsLookup.NotFound -> BulkLyricsOutcome.NO_MATCH
            LyricsLookup.Instrumental -> BulkLyricsOutcome.INSTRUMENTAL
            is LyricsLookup.Match -> when (save(result)) {
                LyricsPlace.BESIDE_SONG -> BulkLyricsOutcome.BESIDE_SONG
                LyricsPlace.IN_APP -> BulkLyricsOutcome.IN_APP
                null -> BulkLyricsOutcome.EXISTING
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (busy: LrcLibBusy) {
        throw busy
    } catch (_: Exception) {
        BulkLyricsOutcome.FAILED
    }
}
