package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

internal val SearchTrack.playable get() = libraryId != null || streamUrl != null || sourceRef != null

private fun sameRecording(left: SearchTrack, right: SearchTrack): Boolean {
    if (left.provider == right.provider && left.id == right.id) return true
    val artist = normalizedTag(left.artist)
    if (artist == null || artist != normalizedTag(right.artist) || left.preview != right.preview) return false
    val leftAlbum = normalizedTag(left.album)
    val rightAlbum = normalizedTag(right.album)
    if (leftAlbum != null && rightAlbum != null && leftAlbum != rightAlbum) return false
    if (left.durationMs != null && right.durationMs != null && abs(left.durationMs - right.durationMs) > 2000) return false
    if (left.disc != null && right.disc != null && left.disc != right.disc) return false
    if (left.number != null && right.number != null && left.number != right.number) return false
    return leftAlbum != null && leftAlbum == rightAlbum || left.durationMs != null && right.durationMs != null
}

/** Keep edition words and recording lengths. A missing tag is insufficient to merge two unrelated performances. */
internal fun groupSearchRecordings(tracks: List<SearchTrack>): List<List<SearchTrack>> {
    val byTitle = mutableMapOf<String?, MutableList<MutableList<SearchTrack>>>()
    val groups = mutableListOf<MutableList<SearchTrack>>()
    for (track in tracks.distinctBy { it.provider to it.id }) {
        val candidates = byTitle.getOrPut(normalizedTag(track.title)) { mutableListOf() }
        val group = candidates.firstOrNull { copies -> copies.all { sameRecording(it, track) } }
        if (group != null) group.add(track) else {
            val copy = mutableListOf(track)
            candidates.add(copy)
            groups.add(copy)
        }
    }
    return groups.map { copies -> copies.sortedWith(compareBy({ it.libraryId == null }, { !it.playable }, { it.downloads.isEmpty() })) }
}

internal data class ProviderAnswer(val provider: String, val matches: SourceMatches?, val problem: String? = null)

/** Each completion is delivered independently. Cancelling a query also closes each provider's active request. */
internal fun searchEachProvider(
    providers: List<MusicSearchProvider>, debounceMs: Long = 350, timeoutMs: Long = 25_000,
    load: (MusicSearchProvider, CancelScope) -> SourceMatches
) = channelFlow {
    for (provider in providers) launch {
        if (provider.id != LIBRARY_SEARCH_ID) delay(debounceMs)
        val scope = CancelScope()
        val result = try {
            val matches = coroutineScope {
                val task = async(Dispatchers.IO) { runCatching { load(provider, scope) } }
                try { withTimeout(timeoutMs) { task.await().getOrThrow() } } finally { scope.cancel() }
            }
            ProviderAnswer(provider.id, matches)
        } catch (_: TimeoutCancellationException) {
            ProviderAnswer(provider.id, null, "${provider.label} timed out.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProviderAnswer(provider.id, null, "${provider.label} could not be reached.")
        }
        send(result)
    }
}
