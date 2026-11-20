package com.evanna.player.metadata

import com.evanna.player.EVANNA_REPOSITORY
import com.evanna.player.metadata.MusicTagField.*
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.webmusic.appleRecordingSearch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class OnlineTagProvider(val label: String, val endpoint: String, val canSave: Boolean) {
    MUSICBRAINZ("MusicBrainz", "https://musicbrainz.org/ws/2/", true),
    DISCOGS("Discogs", "https://api.discogs.com/", false),
    APPLE("iTunes Search", "https://itunes.apple.com/", false),
    WIKIDATA("Wikidata", "https://www.wikidata.org/w/api.php", true);

    fun validId(id: String): Boolean = when (this) {
        MUSICBRAINZ -> mbid.matches(id)
        DISCOGS, APPLE -> numericId.matches(id)
        WIKIDATA -> qid.matches(id)
    }

    fun page(id: String): String = when (this) {
        MUSICBRAINZ -> "https://musicbrainz.org/release/$id"
        DISCOGS -> "https://www.discogs.com/release/$id"
        APPLE -> "https://music.apple.com/song/$id"
        WIKIDATA -> "https://www.wikidata.org/wiki/$id"
    }
}

internal data class OnlineTagResult(
    val origin: TagOrigin,
    val title: String,
    val detail: String,
    val loaded: OnlineTagMatch? = null,
)

internal data class OnlineTagTrack(val tags: Map<MusicTagField, String>, val durationMs: Long? = null, val position: String? = null)
internal data class OnlineTagMatch(
    val origin: TagOrigin,
    val tags: Map<MusicTagField, String>,
    val tracks: List<OnlineTagTrack>,
    val detail: String = "",
    val artistNames: List<String> = emptyList(),
    val reference: String = origin.provider.page(origin.id),
    val artistEntity: Boolean = false,
)

private val mbid = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
private val numericId = Regex("[1-9][0-9]{0,17}")
private val barcode = Regex("[0-9]{8,14}")
private val qid = Regex("Q[1-9][0-9]{0,12}")
private const val RESULTS = 20
private const val MAX_TRACKS = 600
private const val AGENT = "Evanna/1.6"
internal val defaultItunesMarket: String get() = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
private val storefronts = Locale.getISOCountries().toSet()

internal fun discogsReleaseId(input: String): String? {
    val direct = input.trim().removePrefix("[r").removeSuffix("]").removePrefix("r")
    if (numericId.matches(direct)) return direct
    val url = input.trim().toHttpUrlOrNull() ?: return null
    if (url.host !in setOf("www.discogs.com", "discogs.com")) return null
    val index = url.pathSegments.indexOf("release")
    if (index < 0) return null
    return url.pathSegments.getOrNull(index + 1)?.substringBefore('-')?.takeIf(numericId::matches)
}

internal object OnlineMusicTags {
    private val access = OnlineTagProvider.entries.associateWith { HttpAccess(NetworkPolicy(it.endpoint, requirePublic = true)) }
    private val requestLock = OnlineTagProvider.entries.associateWith { Any() }
    private val nextRequest = LongArray(OnlineTagProvider.entries.size)
    private val jsonHeaders = mapOf("Accept" to "application/json", "User-Agent" to "$AGENT ($EVANNA_REPOSITORY)")

    fun search(provider: OnlineTagProvider, text: String, token: String, market: String, byTrack: Boolean,
        cancel: CancelScope): List<OnlineTagResult> {
        val query = text.trim()
        require(query.length in 2..240 && query.none(Char::isISOControl))
        return when (provider) {
            OnlineTagProvider.MUSICBRAINZ -> musicBrainzSearch(query, byTrack, cancel)
            OnlineTagProvider.DISCOGS -> discogsSearch(query, token, cancel)
            OnlineTagProvider.APPLE -> appleSearch(query, market, cancel)
            OnlineTagProvider.WIKIDATA -> wikidataSearch(query, cancel)
        }
    }

    fun details(result: OnlineTagResult, token: String, cancel: CancelScope): OnlineTagMatch {
        result.loaded?.let { return it }
        val origin = result.origin
        require(origin.provider.validId(origin.id))
        return when (origin.provider) {
            OnlineTagProvider.MUSICBRAINZ -> musicBrainzRelease(origin, cancel)
            OnlineTagProvider.DISCOGS -> discogsRelease(origin, token, cancel)
            OnlineTagProvider.WIKIDATA -> wikidataRelease(origin, cancel)
            OnlineTagProvider.APPLE -> error("APPLE_RESULT_INCOMPLETE")
        }
    }

    private fun endpoint(provider: OnlineTagProvider, vararg segments: String): HttpUrl.Builder =
        provider.endpoint.toHttpUrl().newBuilder().apply { segments.forEach { addPathSegment(it) } }

    private fun fetch(provider: OnlineTagProvider, url: HttpUrl.Builder, cancel: CancelScope, token: String = ""): JSONObject {
        val interval = when (provider) {
            OnlineTagProvider.MUSICBRAINZ -> 1_100L
            OnlineTagProvider.APPLE -> 3_100L
            else -> 1_100L
        }
        synchronized(requestLock.getValue(provider)) {
            var remaining = nextRequest[provider.ordinal] - System.nanoTime()
            while (remaining > 0) {
                cancel.checkActive()
                TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)))
                remaining = nextRequest[provider.ordinal] - System.nanoTime()
            }
            cancel.checkActive()
            nextRequest[provider.ordinal] = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval)
        }
        val headers = if (token.isEmpty()) jsonHeaders else {
            if (provider != OnlineTagProvider.DISCOGS || token.length > 128 ||
                token.any { it !in '0'..'9' && it !in 'A'..'Z' && it !in 'a'..'z' }) {
                throw OnlineTagLookupError("Enter a valid Discogs personal access token.")
            }
            jsonHeaders + ("Authorization" to "Discogs token=$token")
        }
        val response = access.getValue(provider).fetch(url.build().toString(), maxBytes = 4 * 1024 * 1024,
            headers = headers, scope = cancel)
        when (response.code) {
            200 -> Unit
            401, 403 -> throw OnlineTagLookupError(if (provider == OnlineTagProvider.DISCOGS)
                "Discogs denied access. Check your personal access token." else "${provider.label} denied access.")
            429, 503 -> throw OnlineTagLookupError("${provider.label} is busy. Try again shortly.")
            404 -> throw OnlineTagLookupError("That release is no longer available on ${provider.label}.")
            else -> throw IOException("TAG_LOOKUP_FAILED")
        }
        val json = JSONObject(response.bytes.decodeToString())
        if (json.has("error")) throw IOException("TAG_LOOKUP_FAILED")
        return json
    }

    private fun musicBrainzSearch(query: String, byTrack: Boolean, cancel: CancelScope): List<OnlineTagResult> {
        val provider = OnlineTagProvider.MUSICBRAINZ
        val id = query.toHttpUrlOrNull()?.takeIf { it.host == "musicbrainz.org" && it.pathSegments.firstOrNull() == "release" }
            ?.pathSegments?.getOrNull(1) ?: query
        if (provider.validId(id)) return listOf(musicBrainzRelease(TagOrigin(provider, id), cancel).asResult())
        val phrase = "\"${query.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val exactBarcode = barcode.matches(query)
        val recordingSearch = byTrack && !exactBarcode
        val filter = when {
            exactBarcode -> "barcode:$query"
            recordingSearch -> "recording:$phrase"
            else -> "release:$phrase OR artist:$phrase"
        }
        val url = endpoint(provider, if (recordingSearch) "recording" else "release").addQueryParameter("query", filter)
            .addQueryParameter("limit", RESULTS.toString()).addQueryParameter("fmt", "json")
        val results = fetch(provider, url, cancel)
        val releases = if (recordingSearch) results.rows("recordings").flatMap { it.rows("releases") }
            else results.rows("releases")
        return releases.mapNotNull { row ->
            val release = row.string("id")?.takeIf(provider::validId) ?: return@mapNotNull null
            val title = row.string("title") ?: return@mapNotNull null
            OnlineTagResult(TagOrigin(provider, release), title, details(row.credits(), row.string("date"),
                row.string("country"), row.rows("media").mapNotNull { it.string("format") }.distinct().joinToString("/"),
                row.string("disambiguation"), row.string("barcode")))
        }.distinctBy { it.origin.id }.take(RESULTS)
    }

    private fun musicBrainzRelease(origin: TagOrigin, cancel: CancelScope): OnlineTagMatch {
        val url = endpoint(origin.provider, "release", origin.id).addQueryParameter("fmt", "json")
            .addQueryParameter("inc", "recordings+artist-credits")
        val row = fetch(origin.provider, url, cancel)
        val media = row.rows("media", MAX_TRACKS)
        val artist = row.credits()
        val tags = tagsOf(ALBUM to row.string("title"), ALBUM_ARTIST to artist, YEAR to row.string("date"),
            DISC_TOTAL to media.size.toString())
        val tracks = media.asSequence().flatMap { disc ->
            disc.rows("tracks", MAX_TRACKS).asSequence().map { track ->
                val recording = track.optJSONObject("recording")
                OnlineTagTrack(tagsOf(TITLE to (track.string("title") ?: recording?.string("title")),
                    ARTIST to (track.credits() ?: recording?.credits() ?: artist),
                    TRACK_NUMBER to track.string("position"), TRACK_TOTAL to disc.string("track-count"),
                    DISC_NUMBER to disc.string("position")),
                    durationMs = track.positiveLong("length") ?: recording?.positiveLong("length"))
            }
        }.take(MAX_TRACKS).toList()
        return OnlineTagMatch(origin, tags, tracks, details(row.string("date"), row.string("country"),
            media.mapNotNull { it.string("format") }.distinct().joinToString("/"), row.string("disambiguation"),
            row.string("barcode")), artistNames = row.creditNames())
    }

    private fun discogsSearch(query: String, token: String, cancel: CancelScope): List<OnlineTagResult> {
        val provider = OnlineTagProvider.DISCOGS
        discogsReleaseId(query)?.let { id -> return listOf(discogsRelease(TagOrigin(provider, id), token, cancel).asResult()) }
        if (token.isBlank()) throw OnlineTagLookupError("Enter a Discogs release link or ID, or add a personal access token to search by name.")
        val url = endpoint(provider, "database", "search").addQueryParameter("q", query)
            .addQueryParameter("type", "release").addQueryParameter("per_page", RESULTS.toString())
        return fetch(provider, url, cancel, token).rows("results").mapNotNull { row ->
            val id = row.string("id")?.takeIf(provider::validId) ?: return@mapNotNull null
            val title = row.string("title") ?: return@mapNotNull null
            OnlineTagResult(TagOrigin(provider, id), title, details(row.string("year"), row.string("country"),
                row.strings("format"), row.string("catno")))
        }
    }

    private fun discogsRelease(origin: TagOrigin, token: String, cancel: CancelScope): OnlineTagMatch {
        val row = fetch(origin.provider, endpoint(origin.provider, "releases", origin.id), cancel, token)
        val artist = row.rows("artists").joinToString(" / ") { it.string("name").orEmpty() }
        val tags = tagsOf(ALBUM to row.string("title"), ALBUM_ARTIST to artist,
            YEAR to (row.string("released")?.takeIf(YEAR::accepts) ?: row.string("year")),
            GENRE to row.strings("genres"))
        val tracks = row.rows("tracklist", MAX_TRACKS).filter { it.string("type_") == "track" }.map { track ->
            val trackArtist = track.rows("artists").mapNotNull { it.string("name") }.joinToString(" / ").ifBlank { artist }
            val position = track.string("position")
            OnlineTagTrack(tagsOf(TITLE to track.string("title"), ARTIST to trackArtist, TRACK_NUMBER to position),
                position = position)
        }
        return OnlineTagMatch(origin, tags, tracks, details(row.string("country"),
            row.rows("formats").joinToString("/") { it.string("name").orEmpty() },
            row.rows("labels").joinToString("/") { it.string("catno").orEmpty() }))
    }

    private fun appleSearch(query: String, market: String, cancel: CancelScope): List<OnlineTagResult> {
        val provider = OnlineTagProvider.APPLE
        if (market !in storefronts) throw OnlineTagLookupError("Enter a two-letter country code such as IN, GB, or US.")
        val url = appleRecordingSearch(query, market).newBuilder().setQueryParameter("limit", RESULTS.toString())
        return fetch(provider, url, cancel).rows("results").mapNotNull { row ->
            val id = row.string("trackId")?.takeIf(provider::validId) ?: return@mapNotNull null
            val title = row.string("trackName") ?: return@mapNotNull null
            val origin = TagOrigin(provider, id)
            val link = row.string("trackViewUrl")?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() && it.host in setOf("music.apple.com", "itunes.apple.com") }
            val release = tagsOf(ALBUM to row.string("collectionName"), GENRE to row.string("primaryGenreName"),
                YEAR to row.string("releaseDate")?.substringBefore('T'), DISC_TOTAL to row.string("discCount"))
            val track = OnlineTagTrack(tagsOf(TITLE to title, ARTIST to row.string("artistName"),
                TRACK_NUMBER to row.string("trackNumber"), TRACK_TOTAL to row.string("trackCount"),
                DISC_NUMBER to row.string("discNumber")), row.positiveLong("trackTimeMillis"))
            val detail = details(track.tags[ARTIST], release[ALBUM], release[YEAR], market)
            OnlineTagResult(origin, title, detail,
                OnlineTagMatch(origin, release, listOf(track), market, reference = link?.toString() ?: origin.provider.page(id)))
        }.distinctBy { it.origin.id }
    }

    private fun wikidataSearch(query: String, cancel: CancelScope): List<OnlineTagResult> {
        val provider = OnlineTagProvider.WIKIDATA
        val id = query.toHttpUrlOrNull()?.takeIf { it.host == "www.wikidata.org" }
            ?.pathSegments?.lastOrNull() ?: query
        if (provider.validId(id)) return listOf(wikidataRelease(TagOrigin(provider, id), cancel).asResult())
        val url = endpoint(provider).addQueryParameter("action", "wbsearchentities").addQueryParameter("search", query)
            .addQueryParameter("language", "en").addQueryParameter("format", "json").addQueryParameter("limit", RESULTS.toString())
        return fetch(provider, url, cancel).rows("search").mapNotNull { row ->
            val item = row.string("id")?.takeIf(provider::validId) ?: return@mapNotNull null
            val name = row.string("label") ?: return@mapNotNull null
            OnlineTagResult(TagOrigin(provider, item), name, details(row.string("description"), item))
        }
    }

    private fun wikidataRelease(origin: TagOrigin, cancel: CancelScope): OnlineTagMatch {
        val row = wikidataEntities(origin.provider, listOf(origin.id), "labels|aliases|descriptions|claims", cancel)
            .optJSONObject(origin.id) ?: throw IOException("MISSING_WIKIDATA_ITEM")
        val claims = row.optJSONObject("claims") ?: JSONObject()
        val types = claims.claimIds("P31")
        val isRelease = types.any { it in setOf("Q482994", "Q169930", "Q134556", "Q108346082", "Q222910", "Q208569", "Q209939") }
        val isArtist = types.any { it in setOf("Q5", "Q215380", "Q2088357") }
        if (!isRelease && !isArtist) {
            throw OnlineTagLookupError("Choose a Wikidata item for a release or performer.")
        }
        val performers = if (isRelease) claims.claimIds("P175") else emptyList()
        val genres = claims.claimIds("P136")
        val ids = (performers + genres).distinct().take(36)
        val labels = if (ids.isNotEmpty()) wikidataEntities(origin.provider, ids, "labels", cancel) else JSONObject()
        fun names(keys: List<String>) = keys.mapNotNull { labels.optJSONObject(it)?.wikidataText("labels") }
        val artistNames = names(performers)
        val description = details(row.wikidataText("descriptions"), origin.id)
        val genre = names(genres).joinToString(" / ")
        if (!isRelease) {
            val aliases = row.optJSONObject("aliases")?.rows("en").orEmpty().mapNotNull { it.string("value") }
            return OnlineTagMatch(origin, tagsOf(GENRE to genre), emptyList(), description,
                listOfNotNull(row.wikidataText("labels")) + aliases, artistEntity = true)
        }
        val date = claims.claimValues("P577").mapNotNull { value ->
            val time = value as? JSONObject ?: return@mapNotNull null
            if (time.string("calendarmodel")?.endsWith("/Q1985727") != true) return@mapNotNull null
            val length = when (time.optInt("precision")) { 9 -> 4; 10 -> 7; 11 -> 10; else -> return@mapNotNull null }
            time.string("time")?.takeIf { it.startsWith('+') }?.drop(1)?.take(length)?.takeIf(YEAR::accepts)
        }.distinct().singleOrNull()
        val tags = tagsOf(ALBUM to row.wikidataText("labels"), ALBUM_ARTIST to artistNames.joinToString(" / "),
            YEAR to date, GENRE to genre)
        return OnlineTagMatch(origin, tags, emptyList(), description, artistNames)
    }

    private fun wikidataEntities(provider: OnlineTagProvider, ids: List<String>, props: String, cancel: CancelScope): JSONObject {
        val url = endpoint(provider).addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", ids.joinToString("|")).addQueryParameter("props", props)
            .addQueryParameter("languages", "en").addQueryParameter("format", "json")
        return fetch(provider, url, cancel).getJSONObject("entities")
    }
}

internal class OnlineTagLookupError(message: String) : IOException(message)

private fun OnlineTagMatch.asResult() =
    OnlineTagResult(origin, tags[ALBUM] ?: artistNames.firstOrNull() ?: origin.id, details(tags[ALBUM_ARTIST], detail), this)

private fun details(vararg parts: String?): String = parts.filterNotNull().filter(String::isNotBlank).joinToString(" · ")

private fun tagsOf(vararg pairs: Pair<MusicTagField, String?>): Map<MusicTagField, String> =
    pairs.mapNotNull { (field, value) -> value?.takeIf(field::accepts)?.let { field to it } }.toMap()

private fun JSONObject.rows(key: String, limit: Int = RESULTS): List<JSONObject> = optJSONArray(key).objects(limit)
private fun JSONArray?.objects(limit: Int): List<JSONObject> =
    if (this == null) emptyList() else (0 until minOf(limit, length())).mapNotNull(::optJSONObject)
private fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key)
    .filterNot(Char::isISOControl).trim().take(512).ifBlank { null }
private fun JSONObject.strings(key: String): String = optJSONArray(key)?.let { array ->
    (0 until minOf(12, array.length())).mapNotNull { index ->
        (array.opt(index) as? String)?.trim()?.take(128)
    }.joinToString(" / ")
}.orEmpty()
private fun JSONObject.positiveLong(key: String) = optLong(key).takeIf { it in 1..604_800_000 }
private fun JSONObject.creditNames() = rows("artist-credit").mapNotNull { it.string("name") ?: it.optJSONObject("artist")?.string("name") }
private fun JSONObject.credits() = rows("artist-credit").joinToString("") {
    (it.string("name") ?: it.optJSONObject("artist")?.string("name")).orEmpty() +
        it.optString("joinphrase").filterNot(Char::isISOControl).take(24)
}.trim().ifBlank { null }
private fun JSONObject.wikidataText(key: String) = optJSONObject(key)?.optJSONObject("en")?.string("value")
private fun JSONObject.claimValues(key: String): List<Any> {
    val rows = rows(key).filter { it.optString("rank") != "deprecated" }
    val preferred = rows.filter { it.optString("rank") == "preferred" }
    return (preferred.ifEmpty { rows }).mapNotNull { it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value") }
}
private fun JSONObject.claimIds(key: String) = claimValues(key).mapNotNull { (it as? JSONObject)?.string("id")?.takeIf(qid::matches) }.distinct()
