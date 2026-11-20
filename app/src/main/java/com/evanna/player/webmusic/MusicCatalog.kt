package com.evanna.player.webmusic

import com.evanna.player.EVANNA_REPOSITORY
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class CatalogRecording(
    val id: String,
    val title: String,
    val artist: String,
    val version: String?,
    val year: String?,
    val lengthMillis: Long?,
    val release: String? = null
) {
    val details: String get() = listOfNotNull(artist.ifBlank { null }, version, release, year, lengthMillis?.let {
        val seconds = it / 1_000
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }).joinToString(" · ")
}

internal data class CatalogSearchResult(val provider: String, val recordings: List<CatalogRecording>)
private val searchEndpoint = "https://musicbrainz.org/ws/2/recording/".toHttpUrl()
private val appleEndpoint = "https://itunes.apple.com/search".toHttpUrl()
private val catalogWhitespace = Regex("\\s+")

internal fun musicBrainzRecordingSearch(query: String): HttpUrl? {
    val terms = query.split(catalogWhitespace).filter { term -> term.any(Char::isLetterOrDigit) }
    if (terms.isEmpty()) return null
    val filter = terms.joinToString(" AND ") { term ->
        val phrase = "\"${term.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        "(recording:$phrase OR artist:$phrase)"
    }
    return searchEndpoint.newBuilder()
        .addQueryParameter("query", filter)
        .addQueryParameter("fmt", "json")
        .addQueryParameter("limit", "24")
        .build()
}

internal fun appleRecordingSearch(query: String, market: String): HttpUrl = appleEndpoint.newBuilder()
    .addQueryParameter("term", query)
    .addQueryParameter("country", market)
    .addQueryParameter("entity", "song")
    .addQueryParameter("limit", "24")
    .build()

internal enum class CatalogSite(val label: String) {
    ARCHIVE("Internet Archive"), CCMIXTER("ccMixter");

    fun searchUrl(recording: CatalogRecording): HttpUrl = when (this) {
        ARCHIVE -> {
            fun literal(term: String) = "\"${term.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            val words = listOf(recording.title, recording.artist).filter(String::isNotBlank).map(::literal)
            "https://archive.org/search.php".toHttpUrl().newBuilder()
                .addQueryParameter("query", (words + "mediatype:audio").joinToString(" AND "))
                .build()
        }
        CCMIXTER -> "https://dig.ccmixter.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("searchp", listOf(recording.title, recording.artist).filter(String::isNotBlank).joinToString(" "))
            .build()
    }
}

internal object MusicCatalog {
    private val access = HttpAccess(NetworkPolicy(searchEndpoint.toString(), requirePublic = true))
    private val appleAccess = HttpAccess(NetworkPolicy(appleEndpoint.toString(), requirePublic = true))
    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private var nextRequestNanos = System.nanoTime()
    private var nextAppleRequestNanos = System.nanoTime()
    private val second = TimeUnit.SECONDS.toNanos(1)
    private val cacheDuration = TimeUnit.MINUTES.toNanos(10)
    private val recordingId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val year = Regex("[0-9]{4}")

    private data class Cached(val time: Long, val result: CatalogSearchResult)

    /** Run on the I/O dispatcher. The catalog allows at most one request per second. */
    @Synchronized
    fun search(query: String, cancel: CancelScope): CatalogSearchResult {
        val normalized = query.trim().replace(catalogWhitespace, " ")
        require(normalized.length in 2..180)
        cancel.checkActive()
        val key = normalized.lowercase(Locale.ROOT)
        cache[key]?.takeIf { System.nanoTime() - it.time < cacheDuration }?.let { return it.result }
        val recordings = try {
            searchMusicBrainz(normalized, cancel)
        } catch (_: IOException) {
            cancel.checkActive()
            emptyList()
        }
        val result = if (recordings.isNotEmpty()) {
            CatalogSearchResult("MusicBrainz", recordings)
        } else {
            CatalogSearchResult("Apple catalog", searchApple(normalized, cancel))
        }
        cache[key] = Cached(System.nanoTime(), result)
        if (cache.size > 16) cache.remove(cache.keys.first())
        return result
    }

    private fun waitUntil(whenNanos: Long, cancel: CancelScope) {
        val delay = whenNanos - System.nanoTime()
        if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay)
        cancel.checkActive()
    }

    private fun searchMusicBrainz(query: String, cancel: CancelScope): List<CatalogRecording> {
        val url = musicBrainzRecordingSearch(query) ?: return emptyList()
        waitUntil(nextRequestNanos, cancel)
        nextRequestNanos = System.nanoTime() + second
        val answer = access.fetch(url.toString(), maxBytes = 1024 * 1024,
            headers = mapOf("User-Agent" to "Evanna/1.5 ($EVANNA_REPOSITORY)", "Accept" to "application/json"),
            scope = cancel)
        if (answer.code != 200) throw IOException("Catalog search failed")
        val rows = JSONObject(answer.bytes.toString(Charsets.UTF_8)).getJSONArray("recordings")
        return buildList {
            val seen = HashSet<String>()
            for (index in 0 until minOf(rows.length(), 24)) {
                cancel.checkActive()
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id")
                if (!recordingId.matches(id) || !seen.add(id)) continue
                val title = row.text("title") ?: continue
                val artist = credits(row.optJSONArray("artist-credit"))
                val version = row.text("disambiguation")
                val firstYear = row.optString("first-release-date").substringBefore('-').takeIf(year::matches)
                val duration = row.optLong("length", 0L).takeIf { it in 1..604_800_000 }
                add(CatalogRecording(id, title, artist, version, firstYear, duration))
            }
        }
    }

    private fun searchApple(query: String, cancel: CancelScope): List<CatalogRecording> {
        waitUntil(nextAppleRequestNanos, cancel)
        nextAppleRequestNanos = System.nanoTime() + 3 * second
        val market = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val answer = appleAccess.fetch(appleRecordingSearch(query, market).toString(), maxBytes = 1024 * 1024,
            headers = mapOf("Accept" to "application/json"), scope = cancel)
        if (answer.code == 429 || answer.code == 503) {
            throw CatalogUnavailable("The catalogs are busy. Try again shortly.")
        }
        if (answer.code != 200) throw IOException("Catalog search failed")
        val rows = JSONObject(answer.bytes.toString(Charsets.UTF_8)).getJSONArray("results")
        return buildList {
            val seen = HashSet<Long>()
            for (index in 0 until minOf(rows.length(), 24)) {
                cancel.checkActive()
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optLong("trackId")
                if (id <= 0 || !seen.add(id)) continue
                val title = row.text("trackName") ?: continue
                val date = row.optString("releaseDate").substringBefore('-').takeIf(year::matches)
                val duration = row.optLong("trackTimeMillis").takeIf { it in 1..604_800_000 }
                add(CatalogRecording("apple:$id", title, row.text("artistName").orEmpty(), null, date,
                    duration, row.text("collectionName")))
            }
        }
    }

    private fun credits(artists: JSONArray?): String = buildString {
        if (artists == null) return@buildString
        for (index in 0 until minOf(artists.length(), 12)) {
            val credit = artists.optJSONObject(index) ?: continue
            append(credit.text("name") ?: credit.optJSONObject("artist")?.text("name").orEmpty())
            append(credit.optString("joinphrase").filterNot(Char::isISOControl).take(16))
        }
    }.trim().take(240)

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).filterNot(Char::isISOControl).trim().take(240).ifBlank { null }
}

internal class CatalogUnavailable(message: String) : IOException(message)
