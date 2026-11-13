package com.evanna.player.equalizer

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** AutoEq (github.com/jaakkopasanen/AutoEq, MIT) lists its profiles in results/INDEX.md. */
private const val AUTOEQ_RESULTS = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
private const val INDEX_LIMIT = 4 * 1024 * 1024
private const val INDEX_MAX_AGE_MS = 7L * 24 * 3_600_000

/** One AutoEq profile: the headphone, its folder under results/ (URL-encoded), and who measured it. */
internal data class AutoEqEntry(val name: String, val path: String, val source: String)

private val SAFE_SEGMENT = Regex("[^/?#\\\\\\s]{1,200}")

/** Reads lines like `- [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990`. */
internal fun parseAutoEqIndex(text: String): List<AutoEqEntry> = text.lineSequence().mapNotNull { line ->
    if (!line.startsWith("- [")) return@mapNotNull null
    val open = line.indexOf("](./"); val by = line.lastIndexOf(") by ")
    if (open < 3 || by < open + 4) return@mapNotNull null
    val path = line.substring(open + 4, by)
    val segments = path.split('/')
    if (segments.size !in 2..5 || segments.any { !SAFE_SEGMENT.matches(it) || it == "." || it == ".." }) return@mapNotNull null
    AutoEqEntry(line.substring(3, open).trim().take(120), path, line.substring(by + 5).trim().take(120))
}.distinctBy { it.path }.toList()

/** Every word must appear in the name or source; names that start with the query come first. At most [limit] results. */
internal fun searchAutoEq(entries: List<AutoEqEntry>, query: String, limit: Int = 60): List<AutoEqEntry> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val start = query.trim().lowercase()
    return entries.asSequence()
        .filter { e -> val hay = "${e.name} ${e.source}".lowercase(); words.all { it in hay } }
        .sortedWith(compareBy({ !it.name.lowercase().startsWith(start) }, { it.name.length }, { it.name }))
        .take(limit).toList()
}

/** The profile's ParametricEQ.txt: the folder's last segment plus " ParametricEQ.txt". */
internal fun autoEqProfileUrl(entry: AutoEqEntry): String = "$AUTOEQ_RESULTS${entry.path}/${entry.path.substringAfterLast('/')}%20ParametricEQ.txt"

/** Fetches the index (cached for a week, used stale when offline) and single profiles. Only reached from the search dialog. */
internal object AutoEqLibrary {
    @Volatile private var loaded: List<AutoEqEntry>? = null

    suspend fun index(context: Context): List<AutoEqEntry> = loaded ?: withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "autoeq-index.md")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < INDEX_MAX_AGE_MS
        val text = if (fresh) cache.readText() else try {
            readRaw("${AUTOEQ_RESULTS}INDEX.md", INDEX_LIMIT).also { cache.writeText(it) }
        } catch (e: IOException) {
            if (cache.isFile) cache.readText() else throw e
        }
        parseAutoEqIndex(text).also { if (it.isEmpty()) throw IOException("Empty AutoEq index") else loaded = it }
    }

    suspend fun profile(entry: AutoEqEntry): String = withContext(Dispatchers.IO) { readRaw(autoEqProfileUrl(entry), 65536) }

    private fun readRaw(url: String, limit: Int): String {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            if (connection.url.host != "raw.githubusercontent.com") throw IOException("Unexpected host")
            if (connection.responseCode != 200) throw IOException("AutoEq returned ${connection.responseCode}")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > limit) throw IOException("AutoEq file too large")
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
