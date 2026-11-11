package com.evanna.player.library

import java.security.MessageDigest

/** Longest decoded side for each place a cover is shown: lists SMALL, grids MEDIUM, Now Playing LARGE. */
internal enum class ArtworkSize(val pixels: Int) { SMALL(256), MEDIUM(512), LARGE(1024) }

/**
 * Power-of-two decode step that keeps the longest side at or above [target] (or the whole source when it is smaller).
 * The decoder then scales down exactly, so covers stay sharp while the decoded image stays under twice the target.
 */
internal fun artworkSampleSize(width: Int, height: Int, target: Int = ArtworkSize.SMALL.pixels): Int? {
    if (width !in 1..16384 || height !in 1..16384 || width.toLong() * height > 64_000_000) return null
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= target) sample *= 2
    return sample
}

/** SMALL keeps the original key, so list covers cached before sizes existed stay valid. */
internal fun artworkSizeKey(key: String, size: ArtworkSize): String =
    if (size == ArtworkSize.SMALL) key else artworkKey(key, size.name, null, null, "cover-size")

/** Version misses separately so a reader upgrade can retry old misses and keep cached bitmaps. */
internal fun artworkMissKey(key: String): String = artworkKey(key, "", null, null, "cover-miss-v2")

/** A directly chosen file, not from a scanned folder; covers and lyrics share it. Its key changes only when it is chosen again. */
internal fun directSource(id: String, uri: String): ArtworkSource {
    require(id.length in 1..100 && uri.length in 1..8192)
    return ArtworkSource(uri, artworkKey(id, uri, null, null, "direct"), true)
}

internal fun artworkKey(id: String, uri: String, size: Long?, modified: Long?, token: String): String =
    MessageDigest.getInstance("SHA-256").digest(listOf(id, uri, size, modified, token).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
