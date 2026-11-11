package com.evanna.player.library

/** A sung word or syllable from an enhanced-LRC `<mm:ss.xx>` stamp; [text] keeps its trailing space, so pieces join back into the line. */
internal data class LyricWord(val timeMs: Long, val text: String)

/** One lyric line; [timeMs] is null in plain (untimed) lyrics. An empty timed line marks a break. [words] is empty without word stamps. */
internal data class LyricLine(val timeMs: Long?, val text: String, val words: List<LyricWord> = emptyList())

internal data class Lyrics(val lines: List<LyricLine>) {
    val timed get() = lines.firstOrNull()?.timeMs != null

    /** Index of the line being sung at [positionMs]; -1 before the first line or for plain lyrics. */
    fun lineAt(positionMs: Long): Int {
        if (!timed) return -1
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs!! <= positionMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found
    }
}

private val timeStamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
// Only standard LRC header names: plain lyrics can contain lines such as "[Chorus: Drake]".
private val headerTag = Regex("""^\[(ar|al|ti|au|by|length|offset|re|ve|tool|la|id|#)\s*:(.*)]$""", RegexOption.IGNORE_CASE)
private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
private val spaces = Regex("\\s+")
private const val MAX_LINES = 2000
// Real lines repeat a few stamps; each strip copies the rest of the line, so a broken tag would cost quadratic time.
private const val MAX_STAMPS_PER_LINE = 64

/**
 * Embedded lyrics as LRC or plain text. Lines with `[mm:ss.xx]` stamps become timed lines, sorted, with `[offset:ms]`
 * applied (a positive offset shows lines sooner). Header tags such as `[ar:...]` and word stamps `<mm:ss.xx>` are
 * dropped. Text with fewer timed than plain lines stays plain. Returns null when nothing is left to show.
 */
internal fun parseLyrics(text: String): Lyrics? {
    var offsetMs = 0L
    val timed = mutableListOf<LyricLine>()
    val texts = mutableListOf<String>() // every row's words in file order, for plain display
    var stampedRows = 0
    var plainRows = 0
    for (raw in text.removePrefix("\uFEFF").split(Regex("\r\n|\r|\n"))) {
        var row = raw.trim()
        val header = headerTag.matchEntire(row)
        if (header != null) {
            if (header.groupValues[1].equals("offset", ignoreCase = true)) header.groupValues[2].trim().toLongOrNull()?.let { offsetMs = it }
            continue
        }
        val stamps = mutableListOf<Long>()
        while (stamps.size < MAX_STAMPS_PER_LINE) {
            val stamp = timeStamp.find(row) ?: break
            val (minutes, seconds, fraction) = stamp.destructured
            stamps += stampMs(minutes, seconds, fraction)
            row = row.substring(stamp.range.last + 1).trimStart()
        }
        val words = row.replace(wordStamp, "").replace(spaces, " ").trim()
        if (stamps.isEmpty()) { if (words.isNotEmpty()) plainRows++ } else {
            stampedRows++
            // Word stamps are written against the line's first stamp; a repeated line keeps the same word spacing in time.
            val pieces = wordPieces(row)
            val first = stamps.min()
            val valid = pieces != null && pieces.all { (it.first ?: first) >= first } &&
                pieces.zipWithNext().all { (a, b) -> (a.first ?: first) <= (b.first ?: first) }
            stamps.forEach { start ->
                timed += LyricLine(start, words, if (valid) pieces!!.map { LyricWord(start + (it.first ?: first) - first, it.second) } else emptyList())
            }
        }
        texts += words
    }
    if (timed.isNotEmpty() && stampedRows >= plainRows) {
        fun shift(ms: Long) = (ms - offsetMs).coerceAtLeast(0)
        val lines = timed.map { line -> line.copy(timeMs = shift(line.timeMs!!), words = line.words.map { it.copy(timeMs = shift(it.timeMs)) }) }
            .sortedBy { it.timeMs }.take(MAX_LINES)
        return Lyrics(lines).takeIf { lines.any { it.text.isNotEmpty() } }
    }
    // Plain text keeps one blank line between verses and drops the rest.
    val trimmed = texts.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    val lines = trimmed.filterIndexed { index, line -> line.isNotEmpty() || trimmed[index - 1].isNotEmpty() }
        .take(MAX_LINES).map { LyricLine(null, it) }
    return Lyrics(lines).takeIf { lines.isNotEmpty() }
}

private fun stampMs(minutes: String, seconds: String, fraction: String): Long {
    val fractionMs = when (fraction.length) { 0 -> 0; 1 -> fraction.toInt() * 100; 2 -> fraction.toInt() * 10; else -> fraction.toInt() }
    return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + fractionMs
}

/**
 * Splits a row (line stamps already removed) at its word stamps into (start, text) pieces; text before the first stamp has
 * no start of its own. Spacing matches the plain line text. Null without word stamps or with more than the per-line bound.
 */
private fun wordPieces(row: String): List<Pair<Long?, String>>? {
    val stamps = wordStamp.findAll(row).take(MAX_STAMPS_PER_LINE + 1).toList()
    if (stamps.isEmpty() || stamps.size > MAX_STAMPS_PER_LINE) return null
    val pieces = mutableListOf<Pair<Long?, String>>()
    var time: Long? = null
    var from = 0
    fun add(text: String) {
        var clean = text.replace(spaces, " ")
        if (pieces.isEmpty() || pieces.last().second.endsWith(' ')) clean = clean.trimStart()
        if (clean.isNotEmpty()) pieces += time to clean
    }
    for (stamp in stamps) {
        add(row.substring(from, stamp.range.first))
        val (minutes, seconds, fraction) = stamp.destructured
        time = stampMs(minutes, seconds, fraction); from = stamp.range.last + 1
    }
    add(row.substring(from))
    if (pieces.isEmpty()) return null
    pieces[pieces.lastIndex] = pieces.last().let { it.first to it.second.trimEnd() }
    return pieces.filter { it.second.isNotEmpty() }.takeIf { it.isNotEmpty() }
}

/** The best of a file's lyric tags: timed beats plain, then more lines win. */
internal fun bestLyrics(texts: List<String>): Lyrics? =
    texts.distinct().mapNotNull(::parseLyrics).maxWithOrNull(compareBy<Lyrics>({ it.timed }, { it.lines.size }))
