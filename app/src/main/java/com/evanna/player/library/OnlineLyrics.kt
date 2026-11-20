package com.evanna.player.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What Evanna sends to LRCLIB to find a song's lyrics: the title and, when known, the artist, album and length. */
internal data class LyricsQuery(val title: String, val artist: String?, val album: String?, val durationMs: Long?)

/** One LRCLIB lyrics record, as far as Evanna needs it. */
internal data class LrcLibRecord(val trackName: String, val durationS: Double?, val instrumental: Boolean, val wordSync: Boolean,
    val synced: String?, val plain: String?, val lyricsfile: String?, val artistName: String? = null, val albumName: String? = null)

/** Where lyrics found online were kept. */
internal enum class LyricsPlace { BESIDE_SONG, IN_APP }

internal sealed interface FoundLyrics {
    /** [askFolder] names the music folder Evanna may not write in, when allowing it would put the .lrc beside the song. */
    data class Found(val lyrics: Lyrics, val place: LyricsPlace, val askFolder: String?) : FoundLyrics
    data object NotFound : FoundLyrics
    data object Instrumental : FoundLyrics
}

/** LRCLIB answered 429 or 503: it wants a pause before the next request. */
internal class LrcLibBusy(val retryAfterMs: Long = 30_000) : IOException("LRCLIB_BUSY")

internal sealed interface LyricsLookup {
    data class Match(val text: String, val lyrics: Lyrics) : LyricsLookup
    data object NotFound : LyricsLookup
    data object Instrumental : LyricsLookup
}

internal fun lrcLibClient(version: String): String = "Evanna $version (${com.evanna.player.EVANNA_REPOSITORY})"

/** Word timings beat line timings, which beat plain text; 0 has nothing to show. */
internal fun lrcLibTier(record: LrcLibRecord): Int = when {
    record.instrumental -> 0
    record.wordSync && !record.lyricsfile.isNullOrBlank() -> 3
    !record.synced.isNullOrBlank() -> 2
    !record.plain.isNullOrBlank() -> 1
    else -> 0
}

/**
 * The record to use for [query]. Search results must be within 2 s of the song's length (LRCLIB's own rule), or have the
 * same title when the length is unknown. Then the richest timing wins; [exact], LRCLIB's own match, wins a tie. When
 * nothing has words but a fitting record is instrumental, that record is returned so the song can say so.
 */
internal fun bestLrcLibRecord(query: LyricsQuery, exact: LrcLibRecord?, candidates: List<LrcLibRecord>): LrcLibRecord? {
    val seconds = query.durationMs?.takeIf { it > 0 }?.let { it / 1000.0 }
    fun fits(record: LrcLibRecord) = if (seconds != null) record.durationS?.let { abs(it - seconds) <= 2.0 } == true
        else record.trackName.trim().equals(query.title.trim(), ignoreCase = true)
    val pool = listOfNotNull(exact) + candidates.filter(::fits)
    return pool.maxByOrNull(::lrcLibTier)?.takeIf { lrcLibTier(it) > 0 } ?: pool.firstOrNull { it.instrumental }
}

/** Require identifying metadata before applying a match without a per-track review. */
internal fun bulkLrcLibRecord(query: LyricsQuery, candidates: List<LrcLibRecord>): LrcLibRecord? {
    val artist = query.artist?.takeIf { it.isNotBlank() } ?: return null
    val album = query.album?.takeIf { it.isNotBlank() }
    val seconds = query.durationMs?.takeIf { it > 0 }?.div(1000.0)
    if (album == null && seconds == null) return null
    val matches = candidates.filter { record ->
        record.trackName.trim().equals(query.title.trim(), ignoreCase = true) &&
            record.artistName?.trim().equals(artist.trim(), ignoreCase = true) &&
            (album == null || record.albumName?.trim().equals(album.trim(), ignoreCase = true)) &&
            (seconds == null || record.durationS?.let { abs(it - seconds) <= 2.0 } == true)
    }
    if (seconds == null && matches.map { it.durationS }.distinct().size > 1) return null
    return bestLrcLibRecord(query, null, matches)
}

/**
 * The record as the text of a .lrc file: word timings from its Lyricsfile when it has them (enhanced LRC), else its line
 * timings, else plain text. A few header tags name the song and where the words came from.
 */
internal fun lrcLibText(record: LrcLibRecord, query: LyricsQuery): String? {
    val body = record.lyricsfile?.takeIf { record.wordSync }?.let(::lyricsfileLrc)
        ?: record.synced?.takeIf { it.isNotBlank() } ?: record.plain?.takeIf { it.isNotBlank() } ?: return null
    fun tag(name: String, value: String?) = value?.replace(Regex("[\\r\\n\\]]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { "[$name:$it]\n" }
    return listOfNotNull(tag("ti", query.title), tag("ar", query.artist), tag("al", query.album), tag("by", "LRCLIB (lrclib.net)"))
        .joinToString("") + body.trimEnd() + "\n"
}

/** A break this long between lines gets an empty line, so the player shows its waiting dots (as in the LRC LRCLIB sends). */
private const val LYRICSFILE_BREAK_MS = 7_000L

/**
 * A word-timed Lyricsfile (LRCLIB's YAML: `lines` of `text`, `start_ms`, `end_ms` and `words` of `text`, `start_ms`) as
 * enhanced LRC: `[00:27.17]<00:27.20>I've <00:27.59>been`. A line's end becomes a trailing stamp. Word times are kept in
 * order and never before their line. Null when it cannot be read or has no timed line.
 */
internal fun lyricsfileLrc(yaml: String): String? {
    val root = MiniYaml.parse(yaml) as? Map<*, *> ?: return null
    class Line(val start: Long, val end: Long?, val text: String, val words: List<Pair<Long, String>>)
    val lines = (root["lines"] as? List<*>).orEmpty().mapNotNull { item ->
        val line = item as? Map<*, *> ?: return@mapNotNull null
        val start = (line["start_ms"] as? String)?.toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val words = (line["words"] as? List<*>).orEmpty().mapNotNull { word ->
            val piece = word as? Map<*, *> ?: return@mapNotNull null
            val text = (piece["text"] as? String)?.replace(Regex("[\\r\\n]+"), " ")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ((piece["start_ms"] as? String)?.toLongOrNull() ?: return@mapNotNull null) to text
        }
        val text = (line["text"] as? String)?.replace(Regex("[\\r\\n]+"), " ")?.trim()
            ?: words.joinToString("") { it.second }.trim()
        Line(start, (line["end_ms"] as? String)?.toLongOrNull()?.takeIf { it > start }, text, words)
    }.sortedBy { it.start }.take(5_000)
    if (lines.none { it.text.isNotEmpty() }) return null
    return buildString {
        lines.forEachIndexed { index, line ->
            append('[').append(lrcTime(line.start)).append(']')
            if (line.words.isEmpty()) append(line.text) else {
                var last = line.start
                for ((at, text) in line.words) {
                    last = maxOf(last, at)
                    append('<').append(lrcTime(last)).append('>').append(text)
                }
                line.end?.let { append('<').append(lrcTime(maxOf(last, it))).append('>') }
            }
            append('\n')
            val next = lines.getOrNull(index + 1)?.start
            if (line.end != null && (next == null || next - line.end >= LYRICSFILE_BREAK_MS)) append('[').append(lrcTime(line.end)).append("]\n")
        }
    }
}

/** mm:ss.xx, rounded to the nearest hundredth; digits stay ASCII whatever the phone's language. */
internal fun lrcTime(ms: Long): String {
    val hundredths = (ms.coerceAtLeast(0) + 5) / 10
    return String.format(Locale.ROOT, "%02d:%02d.%02d", hundredths / 6000, hundredths / 100 % 60, hundredths % 100)
}

/** LRCLIB (lrclib.net): free lyrics with line and sometimes word timings; no key needed. Only reached when the user asks. */
internal object LrcLib {
    private const val HOST = "lrclib.net"
    private const val RESPONSE_LIMIT = 4 shl 20
    private val requests = Mutex()
    private var lastRequestAt: Long? = null
    private var retryAt: Long? = null

    suspend fun lookup(query: LyricsQuery, client: String, bulk: Boolean = false): LyricsLookup {
        val record = find(query, client, bulk) ?: return LyricsLookup.NotFound
        if (record.instrumental) return LyricsLookup.Instrumental
        val text = lrcLibText(record, query) ?: return LyricsLookup.NotFound
        val lyrics = parseLyrics(text) ?: return LyricsLookup.NotFound
        return LyricsLookup.Match(text, lyrics)
    }

    /**
     * LRCLIB's exact match first (title, artist, album and length). When it is missing or has no word timings, its search
     * by title and artist is checked for a better record of the same length. Null when nothing fits. [client] names Evanna
     * as LRCLIB asks every app to.
     */
    private suspend fun find(query: LyricsQuery, client: String, bulk: Boolean): LrcLibRecord? = withContext(Dispatchers.IO) {
        val title = query.title.trim().take(300)
        require(title.isNotEmpty())
        val artist = query.artist?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
        val album = query.album?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
        val seconds = query.durationMs?.let { (it + 500) / 1000 }?.takeIf { it in 1..3600 }
        // A bulk run searches LRCLIB's catalog without triggering its external-source fallback per song.
        if (bulk && (artist == null || album == null && seconds == null)) return@withContext null
        val exact = artist?.takeUnless { bulk }?.let {
            get("/api/get", listOfNotNull("track_name" to title, "artist_name" to it, album?.let { a -> "album_name" to a },
                seconds?.let { s -> "duration" to s.toString() }), client)?.let { body -> json { record(JSONObject(body)) } }
        }
        if (exact != null && lrcLibTier(exact) == 3) return@withContext exact
        val found = try {
            get("/api/search", if (artist != null) listOf("track_name" to title, "artist_name" to artist) else listOf("q" to title), client)
                ?.let { body ->
                    json {
                        val array = JSONArray(body)
                        (0 until minOf(array.length(), 20)).mapNotNull { array.optJSONObject(it)?.let(::record) }
                    }
                }.orEmpty()
        } catch (failure: IOException) {
            // The exact match still counts when the search for a better one fails.
            if (exact != null && lrcLibTier(exact) > 0) emptyList() else throw failure
        }
        val normalized = query.copy(title = title, artist = artist, album = album)
        if (bulk) bulkLrcLibRecord(normalized, found) else bestLrcLibRecord(normalized, exact, found)
    }

    private inline fun <T> json(read: () -> T): T = try { read() } catch (_: org.json.JSONException) { throw IOException("LRCLIB_BAD_ANSWER") }

    private fun record(json: JSONObject) = LrcLibRecord(
        trackName = json.optString("trackName", json.optString("name")),
        durationS = json.optDouble("duration").takeIf { !it.isNaN() },
        instrumental = json.optBoolean("instrumental"),
        wordSync = json.optBoolean("hasWordSync"),
        synced = json.text("syncedLyrics"), plain = json.text("plainLyrics"), lyricsfile = json.text("lyricsfile"),
        artistName = json.text("artistName"), albumName = json.text("albumName"))

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    /** The body of a 200 answer; null for 404 (no such lyrics). */
    private suspend fun get(path: String, parameters: List<Pair<String, String>>, client: String): String? = requests.withLock {
        val now = System.nanoTime()
        val wait = retryAt?.let { it - now } ?: 0
        if (wait > 0) throw LrcLibBusy(TimeUnit.NANOSECONDS.toMillis(wait) + 1)
        lastRequestAt?.let {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(now - it)
            if (elapsed < 1_000) delay(1_000 - elapsed)
        }
        try {
            fetch(path, parameters, client)
        } finally {
            lastRequestAt = System.nanoTime()
        }
    }

    private fun fetch(path: String, parameters: List<Pair<String, String>>, client: String): String? {
        val query = parameters.joinToString("&") { (name, value) -> "$name=" + URLEncoder.encode(value, "UTF-8") }
        val connection = URL("https://$HOST$path?$query").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", client)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.url.host != HOST) throw IOException("Unexpected host")
            when (val code = connection.responseCode) {
                200 -> Unit
                404 -> return null
                429, 503 -> {
                    val retrySeconds = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                        ?.coerceIn(1, 86_400) ?: 30
                    val delayMs = TimeUnit.SECONDS.toMillis(retrySeconds)
                    val retryAfter = delayMs + Random.nextLong(delayMs / 5 + 1)
                    retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryAfter)
                    throw LrcLibBusy(retryAfter)
                }
                else -> throw IOException("LRCLIB_HTTP_$code")
            }
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > RESPONSE_LIMIT) throw IOException("LRCLIB_TOO_LARGE")
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Just enough YAML for a Lyricsfile: block maps and lists (a list may sit at its key's indent), plain, single- and
 * double-quoted scalars (also over several lines), `|` and `>` block scalars, comments and empty `[]` / `{}`. Maps become
 * [Map], lists [List] and every scalar a [String] (`~` and `null` become null). Anything else (anchors, tags, flow
 * collections with items) gives null, so the caller falls back to LRCLIB's line timings.
 */
internal object MiniYaml {
    private class Unsupported : Exception()

    fun parse(text: String): Any? {
        if (text.length > 4 shl 20) return null
        // Unsupported, or a slip on malformed text (a bad index or code point): either way the caller falls back.
        return try { Parser(text).document() } catch (_: Exception) { null }
    }

    private class Parser(text: String) {
        private val lines = text.removePrefix("\uFEFF").split(Regex("\r\n|\r|\n")).toMutableList()
        private var at = 0
        private var depth = 0

        fun document(): Any? {
            while (at < lines.size && (lines[at].startsWith("%") || skippable(lines[at]))) at++
            val node = block(0)
            skip()
            if (at < lines.size && lines[at].trim() != "...") throw Unsupported()
            return node
        }

        private fun skippable(line: String): Boolean {
            val trimmed = line.trim()
            return trimmed.isEmpty() || trimmed.startsWith("#") || (line.startsWith("---") && line.substring(3).isBlank())
        }

        private fun skip() { while (at < lines.size && skippable(lines[at])) at++ }

        private fun indentOf(line: String): Int = line.length - line.trimStart(' ').length

        private fun isItem(content: String) = content == "-" || content.startsWith("- ")

        private fun block(minIndent: Int): Any? {
            skip()
            if (at >= lines.size) return null
            val indent = indentOf(lines[at])
            if (indent < minIndent) return null
            if (++depth > 32) throw Unsupported()
            try {
                val content = lines[at].substring(indent)
                if (content.startsWith("\t")) throw Unsupported()
                return when {
                    isItem(content) -> list(indent)
                    key(content) != null -> map(indent)
                    else -> { at++; scalar(content, indent) }
                }
            } finally { depth-- }
        }

        private fun list(indent: Int): List<Any?> {
            val items = mutableListOf<Any?>()
            while (true) {
                skip()
                if (at >= lines.size) break
                val line = lines[at]
                val here = indentOf(line)
                if (here < indent) break
                if (here > indent) throw Unsupported()
                val content = line.substring(here)
                if (!isItem(content)) break
                if (content.trim() == "-") { at++; items += block(indent + 1) } else {
                    // "- key: value" is a map whose keys line up after the dash: rewrite the row with spaces there.
                    val rest = content.substring(1)
                    val pad = rest.length - rest.trimStart(' ').length
                    lines[at] = " ".repeat(here + 1 + pad) + rest.trimStart(' ')
                    items += block(here + 1)
                }
            }
            return items
        }

        private fun map(indent: Int): Map<String, Any?> {
            val entries = LinkedHashMap<String, Any?>()
            while (true) {
                skip()
                if (at >= lines.size) break
                val line = lines[at]
                val here = indentOf(line)
                if (here < indent) break
                if (here > indent) throw Unsupported()
                val content = line.substring(here)
                if (isItem(content)) break
                val (name, rest) = key(content) ?: throw Unsupported()
                at++
                entries[name] = value(indent, rest)
            }
            return entries
        }

        /** "name: rest" (name plain or quoted); null when the row is not a key. */
        private fun key(content: String): Pair<String, String>? {
            if (content.startsWith("'") || content.startsWith("\"")) {
                val (name, end) = quotedOnLine(content) ?: return null
                val after = content.substring(end).trimStart(' ')
                if (!after.startsWith(":") || (after.length > 1 && after[1] != ' ')) return null
                return name to after.substring(1)
            }
            if (content.startsWith("? ") || content.startsWith("&") || content.startsWith("*") || content.startsWith("!")) return null
            var index = content.indexOf(':')
            while (index >= 0) {
                if (index == content.length - 1 || content[index + 1] == ' ') {
                    val name = content.substring(0, index).trimEnd()
                    if (name.isEmpty() || name.contains(" #")) return null
                    return name to content.substring(index + 1)
                }
                index = content.indexOf(':', index + 1)
            }
            return null
        }

        private fun value(indent: Int, rest: String): Any? {
            val raw = rest.trimStart(' ')
            val plain = withoutComment(raw).trim()
            return when {
                plain.isEmpty() -> {
                    skip()
                    if (at >= lines.size) return null
                    val here = indentOf(lines[at])
                    val content = lines[at].substring(here)
                    if (here > indent || (here == indent && isItem(content))) block(here) else null
                }
                plain.startsWith("|") || plain.startsWith(">") -> blockScalar(indent, plain)
                plain.startsWith("'") || plain.startsWith("\"") -> quoted(raw)
                plain == "[]" -> emptyList<Any?>()
                plain == "{}" -> emptyMap<String, Any?>()
                plain[0] in "[{&*!%@`" -> throw Unsupported()
                else -> scalar(plain, indent)
            }
        }

        /** A plain scalar; more-indented rows after it continue it, joined by spaces. */
        private fun scalar(first: String, indent: Int): Any? {
            if (first.startsWith("'") || first.startsWith("\"")) return quotedFrom(first, at)
            val parts = mutableListOf(withoutComment(first).trim())
            while (at < lines.size && lines[at].isNotBlank() && indentOf(lines[at]) > indent && !lines[at].trim().startsWith("#")) {
                parts += withoutComment(lines[at].trim()).trim(); at++
            }
            val text = parts.joinToString(" ")
            return if (text == "~" || text == "null" || text == "Null" || text == "NULL") null else text
        }

        private fun withoutComment(text: String): String {
            val hash = Regex("(^|\\s)#").find(text) ?: return text
            return text.substring(0, hash.range.first)
        }

        /** A quoted value starting on the row just read (its rest is [raw]). */
        private fun quoted(raw: String): String = quotedFrom(raw, at)

        /** Reads a quoted scalar from [start], continuing on the rows from [next]; leaves [at] after its last row. */
        private fun quotedFrom(start: String, next: Int): String {
            val quote = start[0]
            val out = StringBuilder()
            var row = start.substring(1)
            var line = next
            var i = 0
            while (true) {
                if (i >= row.length) {
                    // Folded line break: one break is a space, each blank row a newline.
                    while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
                    if (line >= lines.size) throw Unsupported()
                    var blanks = 0
                    while (line < lines.size && lines[line].isBlank()) { blanks++; line++ }
                    if (line >= lines.size) throw Unsupported()
                    if (out.isNotEmpty() && out.last() == '\u0000') out.setLength(out.length - 1) else out.append(if (blanks > 0) "\n".repeat(blanks) else " ")
                    row = lines[line].trimStart(' ', '\t'); line++; i = 0
                    continue
                }
                val c = row[i]
                if (quote == '\'') {
                    if (c == '\'') {
                        if (i + 1 < row.length && row[i + 1] == '\'') { out.append('\''); i += 2; continue }
                        return finish(row.substring(i + 1), line, out)
                    }
                    out.append(c); i++
                } else {
                    when (c) {
                        '"' -> return finish(row.substring(i + 1), line, out)
                        '\\' -> {
                            if (i + 1 >= row.length) { out.append('\u0000'); i++; continue }  // escaped line break: no space
                            val e = row[i + 1]
                            i += 2
                            when (e) {
                                'n' -> out.append('\n'); 't', '\t' -> out.append('\t'); 'r' -> out.append('\r'); '0' -> out.append('\u0000')
                                '"', '\\', '/', ' ', '\'' -> out.append(e); 'N' -> out.append('\u0085'); '_' -> out.append('\u00a0')
                                'a' -> out.append('\u0007'); 'b' -> out.append('\b'); 'e' -> out.append('\u001b'); 'f' -> out.append('\u000c'); 'v' -> out.append('\u000b')
                                'x', 'u', 'U' -> {
                                    val size = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                                    if (i + size > row.length) throw Unsupported()
                                    val code = row.substring(i, i + size).toIntOrNull(16) ?: throw Unsupported()
                                    out.appendCodePoint(code); i += size
                                }
                                else -> throw Unsupported()
                            }
                        }
                        else -> { out.append(c); i++ }
                    }
                }
            }
        }

        private fun finish(after: String, line: Int, out: StringBuilder): String {
            if (withoutComment(after).isNotBlank()) throw Unsupported()
            at = line
            return out.toString()
        }

        /** A quoted key on one row: its text and where it ends. */
        private fun quotedOnLine(content: String): Pair<String, Int>? {
            val quote = content[0]
            val out = StringBuilder()
            var i = 1
            while (i < content.length) {
                val c = content[i]
                if (quote == '\'' && c == '\'') {
                    if (i + 1 < content.length && content[i + 1] == '\'') { out.append('\''); i += 2; continue }
                    return out.toString() to i + 1
                }
                if (quote == '"' && c == '\\' && i + 1 < content.length) { out.append(content[i + 1]); i += 2; continue }
                if (quote == '"' && c == '"') return out.toString() to i + 1
                out.append(c); i++
            }
            return null
        }

        /** `|` keeps line breaks, `>` folds them; `-` strips the last break, `+` keeps every trailing one. */
        private fun blockScalar(indent: Int, header: String): String {
            val literal = header[0] == '|'
            val chomp = header.drop(1).firstOrNull { it == '-' || it == '+' }
            val explicit = header.drop(1).firstOrNull { it.isDigit() }?.digitToInt()
            val rows = mutableListOf<String>()
            var contentIndent = explicit?.let { indent + it }
            while (at < lines.size) {
                val line = lines[at]
                if (line.isBlank()) { rows += ""; at++; continue }
                val here = indentOf(line)
                if (here <= indent) break
                if (contentIndent == null) contentIndent = here
                if (here < contentIndent) break
                rows += line.substring(contentIndent); at++
            }
            val trailing = rows.takeLastWhile { it.isEmpty() }.size
            val body = rows.dropLast(trailing)
            val text = if (literal) body.joinToString("\n") else buildString {
                body.forEachIndexed { index, row ->
                    if (index > 0) append(if (row.isEmpty() || body[index - 1].isEmpty() || row.startsWith(" ")) "\n" else " ")
                    append(row)
                }
            }
            return when (chomp) { '-' -> text; '+' -> text + "\n".repeat(trailing + 1); else -> if (body.isEmpty()) "" else text + "\n" }
        }
    }
}
