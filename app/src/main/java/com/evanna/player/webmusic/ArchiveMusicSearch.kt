package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

internal object ArchiveMusicSearch : MusicSearchProvider {
    override val id = "archive"
    override val label = "Internet Archive"
    private val root = "https://archive.org/".toHttpUrl()
    private val identifier = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")
    private val audioExtensions = setOf("flac", "mp3", "aif", "aiff", "alac", "ape", "aac", "m4a", "ogg", "opus", "shn", "wav", "wma")
    private val mirror = Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.archive\\.org")

    override fun search(query: String, cancel: CancelScope): SourceMatches {
        val words = query.trim().split(Regex("\\s+")).filter { it.any(Char::isLetterOrDigit) }
        if (words.isEmpty()) return SourceMatches()
        val expression = words.joinToString(" AND ") {
            val term = literal(it)
            "(title:$term OR creator:$term OR description:$term)"
        }
        val hits = find(expression, cancel)
        return hits.copy(tracks = firstTracks(hits, cancel).filter { track ->
            val text = listOfNotNull(track.title, track.artist, track.album, track.release?.title).joinToString(" ")
            words.all { text.contains(it, ignoreCase = true) }
        })
    }

    override fun artist(artist: SearchArtist, cancel: CancelScope): SourceMatches {
        require(artist.provider == id)
        val hits = find("creator:${literal(artist.id)}", cancel)
        return hits.copy(tracks = firstTracks(hits, cancel))
    }

    private fun firstTracks(hits: SourceMatches, cancel: CancelScope): List<SearchTrack> {
        // Archive indexes releases. Resolve the leading hit to expose recordings without fetching every album as the user types.
        val first = hits.releases.firstOrNull() ?: return emptyList()
        return try { tracks(first, cancel) } catch (_: Exception) {
            cancel.checkActive()
            emptyList()
        }
    }

    private fun find(expression: String, cancel: CancelScope): SourceMatches {
        val uri = root.newBuilder().addPathSegment("advancedsearch.php")
            .addQueryParameter("q", "mediatype:audio AND ($expression)")
            .addQueryParameter("output", "json").addQueryParameter("rows", "36")
        listOf("identifier", "title", "creator").forEach { uri.addQueryParameter("fl[]", it) }
        val rows = JSONObject(providerGet(uri.build(), cancel, 512 * 1024)).getJSONObject("response").getJSONArray("docs")
        val releases = mutableListOf<SearchRelease>()
        val artists = linkedMapOf<String, SearchArtist>()
        for (i in 0 until minOf(rows.length(), 36)) {
            val row = rows.optJSONObject(i) ?: continue
            val key = row.providerText("identifier", 100)?.takeIf(identifier::matches) ?: continue
            val title = row.providerText("title") ?: continue
            val creators = creators(row)
            releases += SearchRelease(id, key, title, creators.joinToString(", "), itemUrl(key))
            creators.forEach { name ->
                val page = root.newBuilder().addPathSegment("search").addQueryParameter("query", "mediatype:audio AND creator:${literal(name)}").build()
                artists.putIfAbsent(name.lowercase(Locale.ROOT), SearchArtist(id, name, name, page.toString()))
            }
        }
        return SourceMatches(artists.values.toList(), releases.distinctBy { it.id })
    }

    private fun literal(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun itemUrl(key: String) = root.newBuilder().addPathSegment("details").addPathSegment(key).build().toString()

    private fun creators(row: JSONObject): List<String> {
        val values = row.optJSONArray("creator") ?: return listOfNotNull(row.providerText("creator"))
        return (0 until minOf(values.length(), 16)).mapNotNull { (values.opt(it) as? String)?.trim()?.takeIf { name ->
            name.isNotEmpty() && name.length <= 400 && name.none(Char::isISOControl)
        } }
    }

    override fun tracks(release: SearchRelease, cancel: CancelScope): List<SearchTrack> {
        require(release.provider == id && identifier.matches(release.id))
        val url = root.newBuilder().addPathSegment("metadata").addPathSegment(release.id).build()
        return albumTracks(release, JSONObject(providerGet(url, cancel, 4 * 1024 * 1024)))
    }

    internal fun albumTracks(release: SearchRelease, item: JSONObject): List<SearchTrack> {
        val metadata = item.getJSONObject("metadata")
        if (metadata.providerText("identifier") != release.id || metadata.providerText("mediatype") != "audio")
            throw IOException("The source could not identify this release.")
        val files = item.getJSONArray("files")
        if (files.length() > 6000) throw IOException("This release is too large. Open it at Internet Archive.")
        val restricted = item.flag("is_dark") || metadata.flag("access-restricted-item") || metadata.flag("nodownload") ||
            metadata.flag("stream_only") || metadata.flag("stream-only") || metadata.flag("private")
        val folder = if (restricted) null else storageFolder(item, release.id)
        val groups = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val name = file.providerText("name", 1024) ?: continue
            if (extension(name) !in audioExtensions) continue
            val original = file.providerText("original", 1024)?.takeIf { extension(it) in audioExtensions } ?: name
            groups.getOrPut(original) { mutableListOf() }.add(file)
        }
        return groups.map { (original, variants) ->
            val info = variants.find { it.providerText("name", 1024) == original } ?: variants.first()
            val suitable = variants.filter { row ->
                val size = row.optLong("size", -1)
                !row.flag("private") && size in 1..WEB_AUDIO_MAX_BYTES && extension(row.getString("name")) in setOf("flac", "mp3") &&
                    safePath(row.getString("name"))
            }
            val choice = suitable.maxWithOrNull(compareBy<JSONObject> { extension(it.getString("name")) == "flac" }
                .thenBy { it.optLong("size") })
            val audio = choice?.getString("name")?.let { name ->
                folder?.newBuilder()?.apply { name.split('/').forEach(::addPathSegment) }?.build()?.toString()
            }
            val title = info.providerText("title") ?: choice?.providerText("title")
                ?: original.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
            val selected = choice ?: info
            SearchTrack(id, "${release.id}/$original", title,
                info.providerText("artist") ?: choice?.providerText("artist") ?: release.artist,
                info.providerText("album") ?: release.title, release.pageUrl, streamUrl = audio,
                format = extension(selected.getString("name")).uppercase(Locale.ROOT), quality = selected.providerText("format"),
                disc = ordinal(info, "disc") ?: ordinal(info, "discnumber"), number = ordinal(info, "track") ?: ordinal(info, "tracknumber"),
                durationMs = durationMillis(info.optString("length")) ?: durationMillis(selected.optString("length")), release = release)
        }.sortedWith(compareBy<SearchTrack> { it.disc ?: 1 }.thenBy { it.number ?: Int.MAX_VALUE })
    }

    private fun storageFolder(item: JSONObject, key: String): HttpUrl? {
        val host = listOf("server", "d1", "d2").firstNotNullOfOrNull { item.providerText(it)?.takeIf(mirror::matches) } ?: return null
        val dir = item.providerText("dir") ?: return null
        val parts = dir.removePrefix("/").split('/')
        if (!dir.startsWith('/') || parts.size != 3 || parts[0].toIntOrNull() == null || parts[1] != "items" || parts[2] != key) return null
        return HttpUrl.Builder().scheme("https").host(host).apply { parts.forEach(::addPathSegment) }.build()
    }

    private fun JSONObject.flag(key: String) = optString(key).lowercase(Locale.ROOT) in setOf("true", "1", "yes")
    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun safePath(name: String) = '\\' !in name && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    private fun ordinal(row: JSONObject, key: String) = row.optString(key).substringBefore('/').trim().toIntOrNull()?.takeIf { it in 1..9999 }
}

internal fun durationMillis(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 1..3) return null
    val seconds = parts.foldIndexed(0.0) { index, total, part ->
        val amount = part.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        if (index > 0 && amount >= 60 || index < parts.lastIndex && amount % 1 != 0.0) return null
        total * 60 + amount
    }
    return (seconds * 1000).toLong().takeIf { it in 1..604_800_000 }
}
