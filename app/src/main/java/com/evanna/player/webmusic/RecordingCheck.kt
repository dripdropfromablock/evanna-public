package com.evanna.player.webmusic

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class RecordingEvidence(val title: String?, val artist: String?, val durationMillis: Long?)

internal enum class RecordingCheck(val warning: String? = null) {
    UNKNOWN,
    CONSISTENT,
    DURATION_MISMATCH("The download’s duration differs from the recording you chose."),
    TAG_MISMATCH("The download’s title or artist differs from the recording you chose."),
}

internal fun checkRecording(expected: RecordingEvidence?, actual: RecordingEvidence): RecordingCheck {
    if (expected == null) return RecordingCheck.UNKNOWN

    val expectedDuration = expected.durationMillis?.takeIf { it > 0 }
    val actualDuration = actual.durationMillis?.takeIf { it > 0 }
    val hasDurations = expectedDuration != null && actualDuration != null
    if (hasDurations && abs(expectedDuration - actualDuration) > 2_000) {
        return RecordingCheck.DURATION_MISMATCH
    }

    var hasMatchingTags = false
    for ((selected, downloaded) in listOf(expected.title to actual.title, expected.artist to actual.artist)) {
        val expectedTag = normalizedTag(selected) ?: continue
        val actualTag = normalizedTag(downloaded) ?: continue
        if (expectedTag != actualTag) return RecordingCheck.TAG_MISMATCH
        hasMatchingTags = true
    }
    return if (hasDurations || hasMatchingTags) RecordingCheck.CONSISTENT else RecordingCheck.UNKNOWN
}

private val tagSeparators = Regex("""[^\p{L}\p{M}\p{N}]+""")

internal fun normalizedTag(value: String?): String? = value?.let {
    Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        .replace(tagSeparators, " ").trim().ifEmpty { null }
}
