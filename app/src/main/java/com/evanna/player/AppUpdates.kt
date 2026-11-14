package com.evanna.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.evanna.player.ui.ActivityIndicator
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

/** A newer release for this phone: its APK and, when the release has one, its SHA256SUMS.txt. */
internal data class AppUpdate(val versionCode: Long, val versionName: String, val downloadUrl: String, val checksumsUrl: String? = null)

private const val CHECKSUMS_NAME = "SHA256SUMS.txt"
private val UPDATE_HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

/** The SHA-256 a sha256sum line ("<64 hex>  <name>" or "<64 hex> *<name>") gives for [name], in lower case. */
internal fun checksumFor(sums: String, name: String): String? = sums.lineSequence().firstNotNullOfOrNull { line ->
    Regex("^([0-9a-fA-F]{64}) [ *](.+)$").find(line.trim())?.takeIf { it.groupValues[2] == name }?.groupValues?.get(1)?.lowercase()
}

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

/**
 * Opens [url] over HTTPS and returns the connection once it answers 200; the caller disconnects it. Redirects are
 * followed by hand, three at most, and only to GitHub's own hosts. A 404 gives null when [missingIsEmpty].
 */
internal fun openHttps(url: String, timeoutMs: Int, missingIsEmpty: Boolean = false): HttpsURLConnection? {
    var target = URL(url)
    repeat(4) {
        val uri = target.toURI()
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.host !in UPDATE_HOSTS) {
            throw IOException("Unexpected update host")
        }
        val connection = target.openConnection() as HttpsURLConnection
        var answered = false
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            when (connection.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    target = URI(target.toString()).resolve(location).toURL()
                }
                404 -> if (missingIsEmpty) return null else throw IOException("Update asset missing")
                200 -> { answered = true; return connection }
                else -> throw IOException("Update request failed")
            }
        } finally {
            if (!answered) connection.disconnect()
        }
    }
    throw IOException("Too many redirects")
}

internal fun readHttps(url: String, limit: Int, missingIsEmpty: Boolean = false): String? {
    val connection = openHttps(url, 4_000, missingIsEmpty) ?: return null
    try {
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
    } finally {
        connection.disconnect()
    }
}

internal suspend fun latestUpdate(installed: Long): AppUpdate? = withContext(Dispatchers.IO) {
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
    val checksums = urlFor(CHECKSUMS_NAME)
    compatibleUpdate(installed, Build.SUPPORTED_ABIS.toList()) { abi ->
        val entry = apks.optJSONObject(abi) ?: return@compatibleUpdate null
        val code = entry.opt("versionCode")
        if (code !is Int && code !is Long) return@compatibleUpdate null
        val versionCode = (code as Number).toLong()
        val name = entry.optString("assetName")
        val url = urlFor(name)?.takeIf { name.endsWith(".apk") } ?: return@compatibleUpdate null
        if (versionCode > 0) AppUpdate(versionCode, version, url, checksums) else null
    }
}

/** Saves what a check found, so the daily prompt and the Settings row read the same answer. */
internal fun SharedPreferences.saveUpdateCheck(result: AppUpdate?) {
    edit().putLong("update-checked", System.currentTimeMillis())
        .putLong("update-code", result?.versionCode ?: 0)
        .putString("update-version", result?.versionName)
        .putString("update-url", result?.downloadUrl)
        .putString("update-sums-url", result?.checksumsUrl).apply()
}

internal fun SharedPreferences.savedUpdate(installed: Long): AppUpdate? {
    val code = getLong("update-code", 0L)
    val version = getString("update-version", null) ?: return null
    val url = getString("update-url", null) ?: return null
    return AppUpdate(code, version, url, getString("update-sums-url", null)).takeIf { code > installed }
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
                    preferences.saveUpdateCheck(latestUpdate(installed))
                    retryAt = 0L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    retryAt = System.currentTimeMillis() + RETRY_MS
                }
            }
            val saved = preferences.savedUpdate(installed)
            val code = preferences.getLong("update-code", 0L)
            val expiry = preferences.getLong("update-snooze-until", 0L)
            val snoozed = preferences.getLong("update-snooze-code", 0L)
            val checkedAt = preferences.getLong("update-checked", 0L)
            val clock = System.currentTimeMillis()
            // Settings offers the update even while the prompt is snoozed.
            AppUpdater.offer(saved)
            update = saved?.takeIf { shouldPromptUpdate(it.versionCode, installed, snoozed, expiry, clock) }
            val nextCheck = if (retryAt > clock) retryAt else checkedAt.coerceAtMost(clock) + CHECK_INTERVAL_MS
            val nextPrompt = if (snoozed == code && expiry > clock) expiry else Long.MAX_VALUE
            delay((minOf(nextCheck, nextPrompt) - clock).coerceAtLeast(1000L))
        }
    }

    if (!foreground) return
    // A download the user started shows its own alert, from here or from Settings.
    if (UpdateProgressAlert(openDownload)) return
    val candidate = update ?: return
    if (!allowPrompt || AppUpdater.state !is UpdateState.Available) return
    val context = LocalContext.current
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
            text = { Text("Evanna ${candidate.versionName} is ready. It downloads here and installs over this version; your library and settings stay.") },
            confirmButton = {
                TextButton(onClick = { snooze(1); AppUpdater.install(context, candidate) }) { Text("Update now") }
            },
            dismissButton = { TextButton(onClick = { choosingDelay = true }) { Text("Update later") } },
        )
    }
}

/**
 * The alert for an update under way: the download's progress, Android's install permission, or what went wrong.
 * Returns false when there is nothing to show. While Android's own install screen is up, it shows nothing.
 */
@Composable
private fun UpdateProgressAlert(openDownload: (String) -> Boolean): Boolean {
    val context = LocalContext.current
    when (val state = AppUpdater.state) {
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Updating Evanna") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(downloadLine(state.update.versionName, state.fraction))
                    ActivityIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = AppUpdater::cancel) { Text("Cancel") } },
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = AppUpdater::cancel,
            title = { Text("Allow Evanna to install") },
            text = { Text("Android asks once. Turn on Allow from this source, then come back to finish.") },
            confirmButton = { TextButton(onClick = { AppUpdater.openInstallPermission(context) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = AppUpdater::cancel) { Text("Cancel") } },
        )
        is UpdateState.InstallFailed -> AlertDialog(
            onDismissRequest = AppUpdater::cancel,
            title = { Text("Update didn't finish") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = { AppUpdater.cancel(); AppUpdater.install(context, state.update) }) { Text("Try again") }
                TextButton(onClick = { if (openDownload(state.update.downloadUrl)) AppUpdater.cancel() }) { Text("Download in browser") }
            },
            dismissButton = { TextButton(onClick = AppUpdater::cancel) { Text("Close") } },
            prominent = false,
        )
        else -> return false
    }
    return true
}

/** Settings' Updates group: one row that checks, offers or retries, and a note with where things stand. */
@Composable
internal fun UpdateSettingsSection(installed: Long, versionName: String) {
    val context = LocalContext.current
    // Shows what the last daily check found before anything has run this launch.
    LaunchedEffect(installed) {
        AppUpdater.offer(context.getSharedPreferences(UI_SETTINGS, Context.MODE_PRIVATE).savedUpdate(installed))
    }
    val state = AppUpdater.state
    val note = when (state) {
        UpdateState.Idle -> "You have Evanna $versionName. Evanna checks GitHub once a day."
        UpdateState.Checking -> "Checking GitHub…"
        UpdateState.UpToDate -> "Evanna $versionName is the latest version."
        is UpdateState.CheckFailed -> state.message
        is UpdateState.Available -> "Evanna ${state.update.versionName} downloads here and installs over $versionName. Your library and settings stay."
        is UpdateState.Downloading -> downloadLine(state.update.versionName, state.fraction)
        is UpdateState.NeedsPermission -> "Waiting for Android's permission to install."
        is UpdateState.Installing -> "Android is installing the update. Evanna closes and updates."
        is UpdateState.InstallFailed -> state.message
    }
    SettingsSection("Updates", footer = note) {
        row {
            when (state) {
                is UpdateState.Available -> ActionRow("Update to ${state.update.versionName}") { AppUpdater.install(context, state.update) }
                is UpdateState.InstallFailed -> ActionRow("Try again") { AppUpdater.cancel(); AppUpdater.install(context, state.update) }
                else -> ActionRow(if (state == UpdateState.Checking) "Checking…" else "Check for updates",
                    enabled = state == UpdateState.Idle || state == UpdateState.UpToDate || state is UpdateState.CheckFailed) {
                    AppUpdater.check(context, installed)
                }
            }
        }
    }
}
