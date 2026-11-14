package com.evanna.player

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.evanna.player.ui.AlertDialog
import com.evanna.player.ui.Text
import com.evanna.player.ui.TextButton
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal const val EVANNA_REPOSITORY = "https://github.com/dripdropfromablock/evanna-public"
private const val RELEASE_API = "https://api.github.com/repos/dripdropfromablock/evanna-public/releases/latest"
private const val MANIFEST_NAME = "evanna-update.json"
private const val HOUR_MS = 3_600_000L
private const val CHECK_INTERVAL_MS = 24 * HOUR_MS
private const val RETRY_MS = HOUR_MS / 4

internal data class AppUpdate(val versionCode: Long, val versionName: String, val downloadUrl: String)

internal fun releaseAssetUrl(tag: String, name: String): String? {
    val safeSegment = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
    if (!safeSegment.matches(tag) || !safeSegment.matches(name)) return null
    return "$EVANNA_REPOSITORY/releases/download/$tag/$name"
}

internal fun shouldPromptUpdate(
    candidate: Long, installed: Long, snoozed: Long, expiresAt: Long, now: Long,
): Boolean = candidate > installed && (candidate != snoozed || now >= expiresAt)

internal fun compatibleUpdate(
    installed: Long, supportedAbis: List<String>, candidateForAbi: (String) -> AppUpdate?,
): AppUpdate? = supportedAbis.firstNotNullOfOrNull(candidateForAbi)?.takeIf { it.versionCode > installed }

private fun readHttps(url: String, limit: Int, missingIsEmpty: Boolean = false): String? {
    var target = URL(url)
    repeat(4) {
        val uri = target.toURI()
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
            uri.host !in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
            throw IOException("Unexpected update host")
        }
        val connection = target.openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            when (connection.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    target = URI(target.toString()).resolve(location).toURL()
                }
                404 -> if (missingIsEmpty) return null else throw IOException("Update asset missing")
                200 -> {
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (bytes.size() + count > limit) throw IOException("Update metadata too large")
                            bytes.write(buffer, 0, count)
                        }
                    }
                    return bytes.toString(Charsets.UTF_8.name())
                }
                else -> throw IOException("Update request failed")
            }
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("Too many redirects")
}

private suspend fun latestUpdate(installed: Long): AppUpdate? = withContext(Dispatchers.IO) {
    val response = readHttps(RELEASE_API, 512 * 1024, missingIsEmpty = true) ?: return@withContext null
    val release = JSONObject(response)
    if (release.getBoolean("draft") || release.getBoolean("prerelease")) return@withContext null
    val tag = release.getString("tag_name")
    val entries = release.getJSONArray("assets")
    val assets = (0 until entries.length()).associate { index ->
        val asset = entries.getJSONObject(index)
        asset.getString("name") to asset.getString("browser_download_url")
    }
    fun urlFor(name: String): String? = releaseAssetUrl(tag, name)?.takeIf { it == assets[name] }
    val metadataUrl = urlFor(MANIFEST_NAME) ?: return@withContext null
    val metadata = JSONObject(checkNotNull(readHttps(metadataUrl, 16 * 1024)))
    val version = metadata.getString("versionName").trim().takeIf { it.isNotEmpty() && it.length <= 80 }
        ?: return@withContext null
    val apks = metadata.getJSONObject("apks")
    compatibleUpdate(installed, Build.SUPPORTED_ABIS.toList()) { abi ->
        val entry = apks.optJSONObject(abi) ?: return@compatibleUpdate null
        val code = entry.opt("versionCode")
        if (code !is Int && code !is Long) return@compatibleUpdate null
        val versionCode = (code as Number).toLong()
        val name = entry.optString("assetName")
        val url = urlFor(name)?.takeIf { name.endsWith(".apk") } ?: return@compatibleUpdate null
        if (versionCode > 0) AppUpdate(versionCode, version, url) else null
    }
}

@Composable
internal fun AppUpdatePrompt(
    preferences: SharedPreferences, installed: Long, foreground: Boolean, allowPrompt: Boolean,
    openDownload: (String) -> Boolean,
) {
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var wake by remember { mutableIntStateOf(0) }
    var retryAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(foreground, installed, wake) {
        if (!foreground) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val last = preferences.getLong("update-checked", 0L).coerceAtMost(now)
            if ((last == 0L || now - last >= CHECK_INTERVAL_MS) && now >= retryAt) {
                try {
                    val result = latestUpdate(installed)
                    preferences.edit().putLong("update-checked", System.currentTimeMillis())
                        .putLong("update-code", result?.versionCode ?: 0)
                        .putString("update-version", result?.versionName)
                        .putString("update-url", result?.downloadUrl).apply()
                    retryAt = 0L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    retryAt = System.currentTimeMillis() + RETRY_MS
                }
            }
            val code = preferences.getLong("update-code", 0L)
            val version = preferences.getString("update-version", null)
            val url = preferences.getString("update-url", null)
            val expiry = preferences.getLong("update-snooze-until", 0L)
            val snoozed = preferences.getLong("update-snooze-code", 0L)
            val checkedAt = preferences.getLong("update-checked", 0L)
            val clock = System.currentTimeMillis()
            update = if (version != null && url != null && shouldPromptUpdate(code, installed, snoozed, expiry, clock))
                AppUpdate(code, version, url) else null
            val nextCheck = if (retryAt > clock) retryAt else checkedAt.coerceAtMost(clock) + CHECK_INTERVAL_MS
            val nextPrompt = if (snoozed == code && expiry > clock) expiry else Long.MAX_VALUE
            delay((minOf(nextCheck, nextPrompt) - clock).coerceAtLeast(1000L))
        }
    }

    val candidate = update ?: return
    if (!foreground || !allowPrompt) return
    var choosingDelay by rememberSaveable(candidate.versionCode) { mutableStateOf(false) }
    fun snooze(hours: Long) {
        preferences.edit().putLong("update-snooze-code", candidate.versionCode)
            .putLong("update-snooze-until", System.currentTimeMillis() + hours * HOUR_MS).apply()
        choosingDelay = false
        update = null
        wake++
    }
    if (choosingDelay) {
        AlertDialog(
            onDismissRequest = { snooze(1) },
            title = { Text("Remind me in") },
            text = {},
            confirmButton = {
                TextButton(onClick = { snooze(1) }) { Text("1 hour") }
                TextButton(onClick = { snooze(24) }) { Text("1 day") }
                TextButton(onClick = { snooze(168) }) { Text("1 week") }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = { snooze(1) },
            title = { Text("Update available") },
            text = { Text("Evanna ${candidate.versionName} is available to download.") },
            confirmButton = {
                TextButton(onClick = { if (openDownload(candidate.downloadUrl)) snooze(1) }) { Text("Update now") }
            },
            dismissButton = { TextButton(onClick = { choosingDelay = true }) { Text("Update later") } },
        )
    }
}