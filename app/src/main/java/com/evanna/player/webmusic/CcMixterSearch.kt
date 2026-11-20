package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

internal object CcMixterSearch : MusicSearchProvider {
    override val id = "ccmixter"
    override val label = "ccMixter"
    private val root = "https://ccmixter.org/".toHttpUrl()
    private val endpoint = "https://ccmixter.org/api/query/api".toHttpUrl()
    private val textEndpoint = "https://ccmixter.org/api/query".toHttpUrl()
    private val handle = Regex("[A-Za-z0-9_@.-]{1,100}")

    override fun search(query: String, cancel: CancelScope): SourceMatches {
        val url = textEndpoint.newBuilder().addQueryParameter("t", "search_uploads").addQueryParameter("s", query)
            .addQueryParameter("search_type", "all").addQueryParameter("f", "json").addQueryParameter("limit", "15").build()
        return parse(JSONArray(providerGet(url, cancel, 384 * 1024)))
    }

    override fun artist(artist: SearchArtist, cancel: CancelScope): SourceMatches {
        require(artist.provider == id && handle.matches(artist.id))
        return load(endpoint.newBuilder().addQueryParameter("user", artist.id), cancel)
    }

    // ccMixter publishes individual uploads and remixes.
    override fun tracks(release: SearchRelease, cancel: CancelScope) = emptyList<SearchTrack>()

    private fun load(request: HttpUrl.Builder, cancel: CancelScope): SourceMatches {
        val url = request.addQueryParameter("f", "json").addQueryParameter("dataview", "info")
            .addQueryParameter("limit", "15").build()
        return parse(JSONArray(providerGet(url, cancel, 768 * 1024)))
    }

    internal fun parse(rows: JSONArray): SourceMatches {
        val artists = LinkedHashMap<String, SearchArtist>()
        val tracks = buildList {
            for (index in 0 until minOf(rows.length(), 15)) {
                val row = rows.optJSONObject(index) ?: continue
                val upload = row.optLong("upload_id").takeIf { it > 0 } ?: continue
                val user = (row.providerText("user_name") ?: row.providerText("artist_page_url")
                    ?.let(::httpsWebAddress)?.takeIf { it.host == root.host && it.pathSegments.size == 2 && it.pathSegments[0] == "people" }
                    ?.pathSegments?.last())?.takeIf(handle::matches) ?: continue
                if (user == "." || user == "..") continue
                val name = row.providerText("user_real_name") ?: user
                val title = row.providerText("upload_name") ?: continue
                val page = root.newBuilder().addPathSegment("files").addPathSegment(user).addPathSegment(upload.toString()).build()
                val person = root.newBuilder().addPathSegment("people").addPathSegment(user).build()
                artists.putIfAbsent(user, SearchArtist(id, user, name, person.toString()))
                val audio = audioInfo(row.optJSONArray("files"))
                add(SearchTrack(id, upload.toString(), title, name, null, page.toString(),
                    format = audio?.providerText("default-ext")?.uppercase(),
                    quality = audio?.providerText("br"), durationMs = audio?.providerText("ps")?.let(::durationMillis)))
            }
        }
        return SourceMatches(artists.values.toList(), tracks = tracks.distinctBy { it.id })
    }

    private fun audioInfo(files: JSONArray?): JSONObject? {
        if (files == null) return null
        for (index in 0 until minOf(files.length(), 20)) {
            val info = files.optJSONObject(index)?.optJSONObject("file_format_info") ?: continue
            if (info.providerText("media-type") == "audio") return info
        }
        return null
    }
}
