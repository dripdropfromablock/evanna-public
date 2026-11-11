package com.evanna.player.library

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val CUE_SHEET_MAX_BYTES = 256 * 1024

internal data class CueTrack(val number: Int, val title: String?, val performer: String?, val startCdFrame: Long)
internal data class CueFile(val name: String, val tracks: List<CueTrack>)
internal data class CueSheet(val title: String?, val performer: String?, val files: List<CueFile>) {
    // CUE numbers retain gaps and skipped data-track slots, so a count can be smaller than a song's number.
    val totalTracks: Int? get() = files.flatMap { it.tracks }.maxOfOrNull { it.number }
}
/** Exact source frames [startFrame, endFrame); a null end plays to the end of the file. */
internal data class CueRange(val track: CueTrack, val startFrame: Long, val endFrame: Long?)

/** Settings only: FILE names are matched to sibling documents by the caller and are never opened as paths. */
internal fun parseCueSheet(bytes: ByteArray): CueSheet {
    require(bytes.size <= CUE_SHEET_MAX_BYTES) { "CUE_TOO_LARGE" }
    var title: String? = null
    var performer: String? = null
    val files = linkedMapOf<String, MutableList<CueTrack>>()
    var file: String? = null
    var number: Int? = null
    var audio = false
    var trackTitle: String? = null
    var trackPerformer: String? = null
    var start: Pair<String, Long>? = null
    var count = 0
    var last = 0
    fun finishTrack() {
        val current = number ?: return
        if (audio) {
            val (owner, frame) = start ?: throw IllegalArgumentException("CUE_TRACK_WITHOUT_INDEX")
            val tracks = files.getValue(owner)
            require(tracks.isEmpty() || frame > tracks.last().startCdFrame) { "CUE_TRACKS_OUT_OF_ORDER" }
            tracks += CueTrack(current, trackTitle, trackPerformer, frame)
        }
        number = null; audio = false; trackTitle = null; trackPerformer = null; start = null
    }
    for (raw in decodeCueText(bytes).lineSequence()) {
        val line = raw.trim()
        require(line.length <= 4096) { "CUE_LINE_TOO_LONG" }
        if (line.isEmpty()) continue
        val command = line.substringBefore(' ').uppercase(java.util.Locale.ROOT)
        val rest = line.substringAfter(' ', "").trim()
        when (command) {
            "FILE" -> {
                val name = if (rest.startsWith('"')) rest.substring(1).substringBefore('"') else rest.substringBefore(' ')
                require(name.isNotEmpty() && name.length <= 1024 && name.none(Char::isISOControl)) { "CUE_INVALID_FILE" }
                file = name
                files.getOrPut(name) { mutableListOf() }
            }
            "TRACK" -> {
                finishTrack()
                val parts = rest.split(Regex("\\s+"))
                val value = parts[0].toIntOrNull()
                require(value != null && value in 1..99 && value > last && ++count <= 99) { "CUE_INVALID_TRACK" }
                number = value; last = value
                audio = parts.getOrNull(1).equals("AUDIO", ignoreCase = true)
            }
            "INDEX" -> {
                val parts = rest.split(Regex("\\s+"))
                require(parts.size == 2 && parts[0].toIntOrNull() in 0..99) { "CUE_INVALID_INDEX" }
                val time = parts[1].split(':').map { it.toLongOrNull() }
                require(time.size == 3 && time.all { it != null && it >= 0 } && time[0]!! <= 9999 && time[1]!! < 60 && time[2]!! < 75) { "CUE_INVALID_TIME" }
                // INDEX 00 starts a pregap, which stays at the end of the previous track.
                if (parts[0].toInt() == 1 && number != null) {
                    require(start == null) { "CUE_DUPLICATE_INDEX" }
                    start = (file ?: throw IllegalArgumentException("CUE_INDEX_WITHOUT_FILE")) to ((time[0]!! * 60 + time[1]!!) * 75 + time[2]!!)
                }
            }
            "TITLE" -> cueText(rest).let { if (number != null) trackTitle = it else title = it }
            "PERFORMER" -> cueText(rest).let { if (number != null) trackPerformer = it else performer = it }
            else -> Unit // REM, ISRC, FLAGS, PREGAP, CATALOG and other commands do not change the audio ranges.
        }
    }
    finishTrack()
    return CueSheet(title, performer, files.filterValues { it.isNotEmpty() }.map { (name, tracks) -> CueFile(name, tracks) })
}

/** Exact frames at the file's own rate; adjacent tracks share each boundary, so no sample repeats or goes missing. */
internal fun cueRanges(file: CueFile, rate: Int, durationUs: Long?): List<CueRange>? {
    require(rate > 0)
    val starts = file.tracks.map { it.startCdFrame * rate / 75 }
    if (durationUs != null && starts.last() * 1_000_000 / rate >= durationUs) return null
    return file.tracks.mapIndexed { index, track -> CueRange(track, starts[index], starts.getOrNull(index + 1)) }
}

private fun cueText(value: String): String? {
    val text = if (value.startsWith('"')) value.substring(1).substringBefore('"') else value
    return text.filterNot(Char::isISOControl).trim().take(512).ifEmpty { null }
}

/** UTF-8 with or without a BOM; anything that is not valid UTF-8 is read as Windows-1252. */
private fun decodeCueText(bytes: ByteArray): String {
    val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val body = if (bom) ByteBuffer.wrap(bytes, 3, bytes.size - 3) else ByteBuffer.wrap(bytes)
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(body.duplicate()).toString()
    } catch (_: CharacterCodingException) {
        require(!bom) { "CUE_INVALID_UTF8" }
        String(bytes, Charset.forName("windows-1252"))
    }
}
