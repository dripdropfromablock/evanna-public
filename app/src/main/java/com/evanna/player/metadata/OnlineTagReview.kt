package com.evanna.player.metadata

import com.evanna.player.metadata.MusicTagField.*
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal fun onlineTagSuggestions(entries: List<TagAuditEntry>, match: OnlineTagMatch): List<Pair<TagAuditEntry, Map<MusicTagField, String>>> {
    if (!match.origin.provider.canSave) return emptyList()
    val tracksByTitle = match.tracks.groupBy { normalizeTag(it.tags[TITLE]) }
    return entries.mapNotNull { entry ->
        val actual = entry.values ?: return@mapNotNull null
        if (entry.sidecarIssue || entry.source.status != TagFileStatus.READABLE) return@mapNotNull null
        if (match.artistEntity) {
            val artist = actual[ARTIST] ?: actual[ALBUM_ARTIST]
            return@mapNotNull if (match.tags.isNotEmpty() && match.artistNames.any { sameTag(artist, it) }) {
                entry to match.tags
            } else null
        }
        val album = actual[ALBUM]
        val release = match.tags[ALBUM]
        if (album != null && release != null && !sameTag(album, release)) return@mapNotNull null
        val sameAlbum = sameTag(album, release)
        if (match.origin.provider == OnlineTagProvider.WIKIDATA) {
            val artist = actual[ALBUM_ARTIST] ?: actual[ARTIST]
            val sameArtist = sameTag(artist, match.tags[ALBUM_ARTIST]) || match.artistNames.any { sameTag(artist, it) }
            if (sameAlbum && sameArtist) entry to match.tags else null
        } else {
            val localTitle = actual[TITLE] ?: entry.source.filename.substringBeforeLast('.')
            val localArtist = actual[ARTIST]
            val localAlbumArtist = actual[ALBUM_ARTIST]
            val duration = entry.source.durationUs?.div(1_000)
            val trackNo = actual[TRACK_NUMBER]?.toIntOrNull()
            val discNo = actual[DISC_NUMBER]?.toIntOrNull()
            val titleKey = normalizeTag(localTitle) ?: return@mapNotNull null
            val track = tracksByTitle[titleKey].orEmpty().filter { candidate ->
                val tags = candidate.tags
                val sameTrackArtist = sameTag(localArtist, tags[ARTIST])
                val sameAlbumArtist = sameTag(localAlbumArtist, match.tags[ALBUM_ARTIST])
                val comparableDuration = duration != null && candidate.durationMs != null
                val closeDuration = comparableDuration && abs(duration - candidate.durationMs) <= 5_000
                val candidateTrackNo = tags[TRACK_NUMBER]?.toIntOrNull()
                val candidateDiscNo = tags[DISC_NUMBER]?.toIntOrNull()
                (sameTrackArtist || sameAlbum && (sameAlbumArtist || localArtist == null && closeDuration)) &&
                    (!comparableDuration || closeDuration) &&
                    (trackNo == null || candidateTrackNo == null || trackNo == candidateTrackNo) &&
                    (discNo == null || candidateDiscNo == null || discNo == candidateDiscNo)
            }.singleOrNull()
            track?.let { entry to (match.tags + it.tags) }
        }
    }
}

internal fun previewOnlineTagEdits(
    suggestions: List<Pair<TagAuditEntry, Map<MusicTagField, String>>>,
    fields: Set<MusicTagField>,
    fillMissing: Boolean,
): List<TagFilePreview> = suggestions.flatMap { (entry, tags) ->
    val edits = tags.filterKeys { it in fields }.mapValues { TagChange.Set(it.value) }
    previewTagEdits(listOf(entry), edits, fillMissing)
}

private fun sameTag(first: String?, second: String?): Boolean {
    val normalized = normalizeTag(first) ?: return false
    return normalized == normalizeTag(second)
}

private fun normalizeTag(value: String?): String? = value?.let {
    Normalizer.normalize(it, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).replace(whitespace, " ").ifBlank { null }
}

private val whitespace = Regex("(?U)\\s+")
