package com.evanna.player.webmusic

import android.content.Context
import android.util.Base64
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.sources.SourceSecrets
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class SoulseekFile(val path: String, val size: Long)
internal data class SoulseekRelease(val peer: String, val folder: String, val files: List<SoulseekFile>)
internal data class SoulseekTransfer(
    val peer: String, val id: String, val filename: String, val status: String, val bytes: Long, val size: Long
)

internal class SoulseekCompanion(address: String, private val key: String, localHttp: Boolean) {
    private val base = address.toHttpUrlOrNull()?.takeIf {
        it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty() && it.query == null && it.fragment == null
    } ?: throw IOException("Enter your slskd server address.")
    private val http = HttpAccess(NetworkPolicy(base.toString(), allowLanHttp = localHttp))

    init {
        require(key.length in 16..255 && key.all { it.code in 33..126 }) { "Enter a valid slskd API key." }
        require(base.isHttps || localHttp) { "Use HTTPS or allow HTTP on your local network." }
    }

    fun search(text: String, cancel: CancelScope): List<SoulseekRelease> {
        val search = UUID.randomUUID().toString()
        request("searches", cancel = cancel, method = "POST", body = JSONObject()
            .put("id", search).put("searchText", text).put("searchTimeout", 5000)
            .put("fileLimit", 250).put("responseLimit", 30).toString())
        var releases = emptyList<SoulseekRelease>()
        for (attempt in 0..9) {
            cancel.checkActive()
            val state = JSONObject(request("searches", search, cancel = cancel, includeResponses = true))
            releases = parseSearch(state.optJSONArray("responses") ?: JSONArray())
            if (state.optBoolean("isComplete") || state.optString("state").contains("Completed", ignoreCase = true)) break
            if (attempt < 9) TimeUnit.MILLISECONDS.sleep(900)
        }
        return releases
    }

    fun enqueue(release: SoulseekRelease, selected: List<SoulseekFile>, cancel: CancelScope) {
        require(selected.isNotEmpty() && selected.all { it in release.files })
        val request = JSONArray(selected.map { JSONObject().put("filename", it.path).put("size", it.size) })
        val response = request("transfers", "downloads", release.peer, cancel = cancel, method = "POST", body = request.toString())
        if (response.startsWith("{")) {
            val result = JSONObject(response)
            if ((result.optJSONArray("failed")?.length() ?: 0) > 0)
                throw IOException("slskd could not queue every selected file. Check Downloads for accepted files.")
        }
    }

    fun downloads(cancel: CancelScope): List<SoulseekTransfer> {
        val rows = JSONArray(request("transfers", "downloads", cancel = cancel))
        return buildList {
            for (index in 0 until minOf(rows.length(), 100)) {
                val row = rows.optJSONObject(index) ?: continue
                val peer = row.providerText("username", 100)?.takeIf(::validSoulseekPeer) ?: continue
                val dirs = row.optJSONArray("directories") ?: continue
                for (dir in 0 until minOf(dirs.length(), 80)) {
                    val files = dirs.optJSONObject(dir)?.optJSONArray("files") ?: continue
                    for (i in 0 until minOf(files.length(), 250)) {
                        val file = files.optJSONObject(i) ?: continue
                        val path = file.providerText("filename", 1024) ?: continue
                        val id = file.providerText("id", 100) ?: continue
                        if (!peerAudio(path)) continue
                        add(SoulseekTransfer(peer, id, path.substringAfterLast('/').substringAfterLast('\\'),
                            file.providerText("state", 100) ?: "Queued",
                            file.optLong("bytesTransferred"), file.optLong("size")))
                        if (size == 150) return@buildList
                    }
                }
            }
        }
    }

    fun cancel(transfer: SoulseekTransfer, scope: CancelScope) {
        request("transfers", "downloads", transfer.peer, transfer.id, cancel = scope, method = "DELETE")
    }

    private fun request(vararg segments: String, cancel: CancelScope, method: String = "GET", body: String? = null,
                        includeResponses: Boolean = false): String {
        val url = base.newBuilder().apply {
            if (base.encodedPath.endsWith('/') && base.encodedPath != "/") removePathSegment(base.pathSize - 1)
            addPathSegments("api/v0")
            segments.forEach(::addPathSegment)
            if (includeResponses) addQueryParameter("includeResponses", "true")
        }.build()
        val response = http.fetch(url.toString(), maxBytes = 2 * 1024 * 1024,
            headers = mapOf("X-API-Key" to key, "Accept" to "application/json"), method = method, body = body, scope = cancel)
        if (response.code !in 200..299) throw IOException(when (response.code) {
            401, 403 -> "Check your slskd API key and permissions."
            409 -> "Connect slskd to Soulseek, then try again."
            429 -> "slskd is busy. Try again shortly."
            else -> "slskd returned HTTP ${response.code}."
        })
        return response.bytes.toString(Charsets.UTF_8).trim()
    }

    companion object {
        internal fun parseSearch(rows: JSONArray): List<SoulseekRelease> {
            val releases = linkedMapOf<Pair<String, String>, MutableList<SoulseekFile>>()
            for (index in 0 until minOf(rows.length(), 100)) {
                val row = rows.optJSONObject(index) ?: continue
                val peer = row.providerText("username", 100)?.takeIf(::validSoulseekPeer) ?: continue
                val files = row.optJSONArray("files") ?: continue
                for (i in 0 until minOf(files.length(), 250)) {
                    val file = files.optJSONObject(i) ?: continue
                    val path = file.providerText("filename", 1024)?.takeIf { safePeerPath(it) && peerAudio(it) } ?: continue
                    val size = file.optLong("size").takeIf { it > 0 } ?: continue
                    val folder = path.replace('\\', '/').substringBeforeLast('/', "")
                    val name = peer to folder
                    if (name !in releases && releases.size >= 30) continue
                    releases.getOrPut(name) { mutableListOf() }.add(SoulseekFile(path, size))
                }
            }
            return releases.map { (key, files) -> SoulseekRelease(key.first, key.second, files.distinct()) }
        }
    }
}

private fun validSoulseekPeer(peer: String) = peer != "." && peer != ".." && peer.none { it in "/\\?#%" }

internal data class SoulseekSettings(val address: String, val key: String, val localHttp: Boolean) {
    fun client() = SoulseekCompanion(address, key, localHttp)
}

internal object SoulseekConnection {
    private const val AAD = "evanna:slskd"
    private fun preferences(context: Context) = context.getSharedPreferences("soulseek-companion", Context.MODE_PRIVATE)

    fun load(context: Context): SoulseekSettings? = preferences(context).getString("connection", null)?.let {
        val bytes = SourceSecrets().decrypt(AAD, Base64.decode(it, Base64.NO_WRAP))
        val row = JSONObject(bytes.toString(Charsets.UTF_8))
        SoulseekSettings(row.getString("address"), row.getString("key"), row.optBoolean("localHttp"))
    }

    fun save(context: Context, settings: SoulseekSettings?) {
        val editor = preferences(context).edit()
        if (settings == null) editor.remove("connection") else {
            settings.client()
            val json = JSONObject().put("address", settings.address).put("key", settings.key).put("localHttp", settings.localHttp)
            val bytes = SourceSecrets().encrypt(AAD, json.toString().encodeToByteArray())
            editor.putString("connection", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        check(editor.commit())
    }
}
