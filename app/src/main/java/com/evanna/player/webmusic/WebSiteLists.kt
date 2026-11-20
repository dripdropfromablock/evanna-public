package com.evanna.player.webmusic

import android.content.Context
import android.content.Intent
import android.util.JsonReader
import android.util.JsonToken
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import okhttp3.HttpUrl
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale

internal const val WEB_SITE_URL_LIMIT = 2048
private const val LIST_BYTES = 64 * 1024
private const val MAX_SITES = 24
private const val QUERY_TOKEN = "{query}"
private val listFileLock = Any()

internal class WebSiteListError(message: String) : IOException(message)

internal data class WebSiteRecipe(val name: String, val address: HttpUrl, val queryKey: String?) {
    fun searchUrl(recording: CatalogRecording): HttpUrl? = queryKey?.let { key ->
        val terms = listOf(recording.title, recording.artist).filter(String::isNotBlank).joinToString(" ")
        address.newBuilder().setQueryParameter(key, terms).build().takeIf { it.toString().length <= WEB_SITE_URL_LIMIT }
    }
}

internal fun webSiteRecipe(name: String, template: String, accepts: String): WebSiteRecipe {
    if (name.isBlank() || name.length > 64 || name.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
        throw WebSiteListError("Use a site name of up to 64 characters.")
    }
    val parts = template.split(QUERY_TOKEN)
    val address = listAddress(template)
    val queryKey = when (accepts) {
        "name" -> {
            val slot = (0 until address.querySize).singleOrNull { address.queryParameterValue(it) == QUERY_TOKEN }
            if (parts.size != 2 || slot == null) throw WebSiteListError("Put {query} in one complete search parameter value.")
            val key = address.queryParameterName(slot)
            if (key.isEmpty() || address.queryParameterValues(key).size != 1) {
                throw WebSiteListError("Use a unique parameter for {query}.")
            }
            key
        }
        "link" -> {
            if (parts.size != 1) throw WebSiteListError("Link sites need a fixed HTTPS page.")
            null
        }
        else -> throw WebSiteListError("A site must accept either name or link.")
    }
    if (parts.any { '{' in it || '}' in it }) throw WebSiteListError("Use {query} as the only template placeholder.")
    return WebSiteRecipe(name.trim(), address, queryKey)
}

private fun listAddress(value: String): HttpUrl {
    fun invalid(): Nothing = throw WebSiteListError("Use a public HTTPS address without a login or fragment.")
    if (value.length > WEB_SITE_URL_LIMIT || !value.startsWith("https://", ignoreCase = true) ||
        value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) invalid()
    val url = httpsWebAddress(value) ?: invalid()
    val host = url.host.trimEnd('.')
    if (url.toString().length > WEB_SITE_URL_LIMIT || url.fragment != null ||
        host.endsWith(".local") || host.endsWith(".localhost") || '.' !in host && ':' !in host) invalid()
    try {
        NetworkPolicy(url.toString(), requirePublic = true)
    } catch (_: IOException) {
        invalid()
    }
    return url
}

private fun parseSiteList(text: String): List<WebSiteRecipe> = try {
    JsonReader(StringReader(text)).use { reader ->
        var version: Int? = null
        var sites: List<WebSiteRecipe>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "version" -> {
                    check(version == null && reader.peek() == JsonToken.NUMBER)
                    version = reader.nextInt()
                    if (version != 1) throw WebSiteListError("This source list needs a supported version.")
                }
                "sites" -> {
                    check(sites == null)
                    reader.beginArray()
                    sites = buildList {
                        while (reader.hasNext()) {
                            if (size >= MAX_SITES) throw WebSiteListError("A list can contain up to $MAX_SITES sites.")
                            add(reader.readSite())
                        }
                    }
                    reader.endArray()
                }
                else -> throw WebSiteListError("Source lists use only version and sites at the top level.")
            }
        }
        reader.endObject()
        check(reader.peek() == JsonToken.END_DOCUMENT && version == 1)
        val result = checkNotNull(sites)
        if (result.isEmpty() || result.distinctBy { it.name.lowercase(Locale.ROOT) }.size != result.size) {
            throw WebSiteListError("Add at least one site and give each site a different name.")
        }
        result
    }
} catch (failure: Exception) {
    throw failure as? WebSiteListError ?: WebSiteListError("This is not a valid version 1 source list.")
}

private fun JsonReader.readSite(): WebSiteRecipe {
    val fields = mutableMapOf<String, String>()
    var formats: Set<String>? = null
    beginObject()
    while (hasNext()) {
        val field = nextName()
        when (field) {
            "name", "search", "accepts" -> {
                check(field !in fields && peek() == JsonToken.STRING)
                fields[field] = nextString()
            }
            "files" -> {
                check(formats == null)
                beginArray()
                formats = buildSet {
                    while (hasNext()) {
                        check(peek() == JsonToken.STRING)
                        val format = nextString()
                        check(format in setOf("mp3", "flac", "zip") && add(format))
                    }
                }
                endArray()
                check(formats.isNotEmpty())
            }
            else -> throw WebSiteListError("A site can only specify its name, search, accepts, and files.")
        }
    }
    endObject()
    check(formats != null)
    return webSiteRecipe(fields.getValue("name"), fields.getValue("search"), fields.getValue("accepts"))
}

internal data class WebSiteList(val address: HttpUrl, val sites: List<WebSiteRecipe>)

internal class WebSiteListStore(private val file: File,
                                private val fetch: (HttpUrl, CancelScope) -> ByteArray = ::fetchSiteList) {
    fun current(): WebSiteList? = synchronized(listFileLock) {
        if (!file.isFile) return@synchronized null
        if (file.length() > LIST_BYTES + WEB_SITE_URL_LIMIT + 1) throw WebSiteListError("The saved list is too large.")
        file.bufferedReader(Charsets.UTF_8).use { input ->
            val address = listAddress(input.readLine().orEmpty())
            WebSiteList(address, parseSiteList(input.readText()))
        }
    }

    fun update(address: String, cancel: CancelScope): WebSiteList {
        val url = listAddress(address.trim())
        cancel.checkActive()
        val bytes = fetch(url, cancel)
        if (bytes.size > LIST_BYTES) throw WebSiteListError("Use a source list of up to 64 KiB.")
        val manifest = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val list = WebSiteList(url, parseSiteList(manifest))
        synchronized(listFileLock) {
            cancel.checkActive()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write("$url\n".toByteArray(Charsets.UTF_8))
                    output.write(bytes)
                    output.fd.sync()
                }
                cancel.checkActive()
                Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
        return list
    }

    fun remove() { synchronized(listFileLock) { Files.deleteIfExists(file.toPath()) } }
}

private fun fetchSiteList(url: HttpUrl, cancel: CancelScope): ByteArray {
    val address = url.toString()
    val result = HttpAccess(NetworkPolicy(address, requirePublic = true)).fetch(address, maxBytes = LIST_BYTES,
        headers = mapOf("Accept" to "application/json"), scope = cancel)
    if (result.code != 200) throw WebSiteListError("Could not load the list (HTTP ${result.code}). Use its final HTTPS URL.")
    return result.bytes
}

internal fun webSiteListStore(context: Context) = WebSiteListStore(File(context.noBackupFilesDir, "web-site-list"))

internal fun openWebMusicSite(context: Context, address: HttpUrl) {
    val intent = Intent(context, WebMusicActivity::class.java).putExtra(WEB_SEARCH_PAGE, address.toString())
    context.startActivity(intent)
}
