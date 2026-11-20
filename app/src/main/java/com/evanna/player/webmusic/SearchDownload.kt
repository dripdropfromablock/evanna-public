package com.evanna.player.webmusic

import com.evanna.player.sources.SourceReference

internal val searchDownloadFormats = setOf("flac", "mp3")

internal data class SearchDownloadFile(val format: String, val ref: SourceReference) {
    init { require(format in searchDownloadFormats) }
}

internal data class SearchDownloadCandidate(val provider: String, val file: SearchDownloadFile)

internal data class SearchDownload(
    val key: String,
    val name: String,
    val candidates: List<SearchDownloadCandidate>,
    val expected: RecordingEvidence? = null,
    val album: String? = null,
)

internal data class SearchDownloadChoice(val provider: String, val format: String)

internal data class SearchDownloadOffer(val choice: SearchDownloadChoice, val tracks: Int)

internal fun searchDownloadOffers(recordings: List<List<SearchTrack>>): List<SearchDownloadOffer> = recordings
    .flatMap { copies ->
        copies.flatMap { track -> track.downloads.map { SearchDownloadChoice(track.provider, it.format) } }.distinct()
    }
    .groupingBy { it }.eachCount().map { (choice, count) -> SearchDownloadOffer(choice, count) }
    .sortedWith(compareByDescending<SearchDownloadOffer> { it.tracks }.thenBy { it.choice.format })

internal fun planSearchDownloads(
    recordings: List<List<SearchTrack>>,
    choice: SearchDownloadChoice,
    fallback: Boolean,
    label: (String) -> String,
    album: String? = null,
): List<SearchDownload> = recordings.mapNotNull { copies ->
    val compatible = copies.mapNotNull { track ->
        track.downloads.firstOrNull { it.format == choice.format }?.let { track to it }
    }
    val selected = compatible.firstOrNull { it.first.provider == choice.provider } ?: return@mapNotNull null
    val (track, file) = selected
    val candidates = if (fallback) listOf(selected) + compatible.filter { it.first.provider != track.provider }.take(7)
        else listOf(selected)
    // Keep the receipt identity for an existing server download.
    val key = "${track.provider}:${track.id}" + if (track.downloads.first() == file) "" else ":${file.format}"
    val name = importedDownloadName("${track.title.replace('/', '-').replace('\\', '-')}.${file.format}", file.format)
    SearchDownload(key, name, candidates.map { (copy, option) -> SearchDownloadCandidate(label(copy.provider), option) },
        RecordingEvidence(track.title, track.artist, track.durationMs), album)
}
