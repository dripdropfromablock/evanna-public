package com.evanna.player.decoder

internal data class MusicTags(val albumArtist: String? = null, val trackNumber: Int? = null,
    val totalTracks: Int? = null, val discNumber: Int? = null, val totalDiscs: Int? = null,
    val year: Int? = null, val genre: String? = null, val composer: String? = null,
    val compilation: Boolean = false)

private fun tagNumber(text: String?) = text?.trim()?.toIntOrNull()?.takeIf { it in 1..999 }

internal fun parseNumberPair(text: String?): Pair<Int?, Int?> {
    val match = Regex("([0-9]+)(?:\\s*/\\s*([0-9]+))?").matchEntire(text?.trim() ?: "") ?: return null to null
    val number = tagNumber(match.groupValues[1])
    return number to tagNumber(match.groupValues[2]).takeIf { number == null || it == null || it >= number }
}

internal fun parseYear(text: String?): Int? = Regex("([0-9]{4})(?:[-/].*)?").matchEntire(text?.trim() ?: "")
    ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1000..2999 }

internal fun parseFlag(text: String?): Boolean = text?.trim()?.lowercase(java.util.Locale.ROOT) in listOf("1", "true", "yes")

internal fun musicTagsFromBytes(tags: Array<ByteArray>): MusicTags = parseMusicTags(tags.mapIndexed { index, bytes ->
    // A structured value at the native cap may have lost a suffix; never infer a number from its prefix.
    if ((index in 4..12 || index == 15) && bytes.size >= 4096) null else bytes.toString(Charsets.UTF_8)
})

/** Slot order is shared with nativeTags; absent/invalid fields stay unknown. */
internal fun parseMusicTags(tags: List<String?>): MusicTags {
    require(tags.size == 16) { "DECODER_TAGS_MISMATCH" }
    val track = parseNumberPair(tags[4]); val disc = parseNumberPair(tags[7])
    fun total(pair: Pair<Int?, Int?>, first: String?, second: String?) =
        listOf(pair.second, tagNumber(first), tagNumber(second)).firstOrNull { it != null && (pair.first == null || it >= pair.first!!) }
    fun text(index: Int) = tags[index]?.let { metadataText(it.toByteArray(Charsets.UTF_8)) }
    return MusicTags(text(3), track.first, total(track, tags[5], tags[6]), disc.first, total(disc, tags[8], tags[9]),
        listOf(tags[10], tags[11], tags[12]).firstNotNullOfOrNull(::parseYear), text(13), text(14), parseFlag(tags[15]))
}
