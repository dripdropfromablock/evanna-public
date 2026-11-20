package com.evanna.player.webmusic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.SettingsLink
import com.evanna.player.network.CancelScope
import com.evanna.player.ui.Button
import com.evanna.player.ui.OutlinedTextField
import com.evanna.player.ui.Switch
import com.evanna.player.ui.Text
import com.evanna.player.ui.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SoulseekSearch(query: String) {
    val app = LocalContext.current.applicationContext
    var config by remember { mutableStateOf<SoulseekSettings?>(null) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var lan by remember { mutableStateOf(false) }
    var results by remember(query) { mutableStateOf(emptyList<SoulseekRelease>()) }
    var release by remember { mutableStateOf<SoulseekRelease?>(null) }
    var selected by remember(release) { mutableStateOf(release?.files?.toSet().orEmpty()) }
    var page by remember(release) { mutableIntStateOf(0) }
    var operation by remember { mutableStateOf<CancelScope?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { operation?.cancel() } }
    LaunchedEffect(app) {
        config = withContext(Dispatchers.IO) { runCatching { SoulseekConnection.load(app) }.getOrNull() }
    }

    fun run(work: suspend (CancelScope) -> Unit) {
        if (operation != null) return
        val cancel = CancelScope()
        operation = cancel
        message = null
        scope.launch {
            try {
                work(cancel)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (!cancel.cancelled) message = failure.message?.take(160) ?: "Could not reach slskd."
            } finally {
                if (operation === cancel) operation = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Soulseek", style = EvannaDesign.typography.titleLarge)
        Text("Requires your own slskd companion. slskd saves the files on its server.")
        val current = config
        if (editing) {
            OutlinedTextField(address, { address = it.take(2048) }, "slskd server address", Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it.take(255) }, "slskd API key", Modifier.fillMaxWidth(), secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow HTTP on local network", Modifier.weight(1f))
                Switch(lan, { lan = it })
            }
            Row {
                Button(enabled = operation == null && address.isNotBlank() && key.isNotBlank(), onClick = {
                    val newConfig = SoulseekSettings(address.trim(), key.trim(), lan)
                    run {
                        withContext(Dispatchers.IO) { SoulseekConnection.save(app, newConfig) }
                        config = newConfig
                        results = emptyList()
                        release = null
                        editing = false
                        key = ""
                    }
                }) { Text("Save companion") }
                TextButton(onClick = { editing = false; key = "" }) { Text("Cancel") }
            }
        } else {
            SettingsLink(if (current == null) "Connect slskd companion" else "Edit slskd companion") {
                address = current?.address.orEmpty()
                lan = current?.localHttp ?: false
                editing = true
            }
            if (current != null && query.length >= 2) TextButton(enabled = operation == null, onClick = {
                run { cancel ->
                    release = null
                    results = withContext(Dispatchers.IO) { current.client().search(query, cancel) }
                    if (results.isEmpty()) message = "No matching shared MP3 or FLAC files."
                }
            }) { Text("Search Soulseek via slskd") }
        }
        if (operation != null) Text("Working…")
        message?.let { Text(it) }
        val item = release
        if (item == null) {
            results.forEach { match ->
                val name = match.folder.substringAfterLast('/').ifBlank { match.files.first().path }
                SettingsLink(name, "${match.peer} · ${match.files.size} files") { release = match }
            }
        } else {
            Text(item.folder.ifBlank { item.peer }, style = EvannaDesign.typography.titleMedium)
            Row {
                TextButton(onClick = { release = null }) { Text("Back to releases") }
                TextButton(onClick = { selected = item.files.toSet() }) { Text("Select all") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
            }
            item.files.drop(page * 20).take(20).forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.path.replace('\\', '/').substringAfterLast('/'), Modifier.weight(1f))
                    Switch(file in selected, { selected = if (it) selected + file else selected - file })
                }
            }
            if (item.files.size > 20) Row {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous files") }
                TextButton(onClick = { page++ }, enabled = (page + 1) * 20 < item.files.size) { Text("More files") }
            }
            Button(enabled = current != null && operation == null && selected.isNotEmpty(), onClick = {
                val files = item.files.filter { it in selected }
                run { cancel ->
                    withContext(Dispatchers.IO) { checkNotNull(current).client().enqueue(item, files, cancel) }
                    message = "Queued on slskd. See Downloads."
                    release = null
                }
            }) { Text("Download ${selected.size} via slskd") }
        }
    }
}

@Composable
internal fun SoulseekDownloads() {
    val app = LocalContext.current.applicationContext
    val cancel = remember { CancelScope() }
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<SoulseekCompanion?>(null) }
    var transfers by remember { mutableStateOf(emptyList<SoulseekTransfer>()) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(cancel) { onDispose { cancel.cancel() } }
    LaunchedEffect(app) {
        client = withContext(Dispatchers.IO) { runCatching { SoulseekConnection.load(app)?.client() }.getOrNull() }
        val connected = client ?: return@LaunchedEffect
        while (true) {
            val response = withContext(Dispatchers.IO) { runCatching { connected.downloads(cancel) } }
            response.onSuccess { transfers = it; message = null }
                .onFailure { message = "Could not refresh transfers from slskd." }
            delay(3000)
        }
    }
    if (client != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Soulseek via slskd", style = EvannaDesign.typography.titleLarge)
        if (transfers.isEmpty() && message == null) Text("No Soulseek transfers on this companion.")
        transfers.forEach { transfer ->
            Text(transfer.filename, style = EvannaDesign.typography.bodyLarge)
            val percent = if (transfer.size > 0) " · ${(100.0 * transfer.bytes / transfer.size).toInt().coerceIn(0, 100)}%" else ""
            Text(transfer.status + percent)
            if (transfer.bytes < transfer.size && !transfer.status.contains("Completed", ignoreCase = true)) TextButton(onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { client?.cancel(transfer, cancel) } }
                    if (result.isFailure) message = "Could not cancel the transfer on slskd."
                }
            }) { Text("Cancel on slskd") }
        }
        message?.let { Text(it) }
    }
}
