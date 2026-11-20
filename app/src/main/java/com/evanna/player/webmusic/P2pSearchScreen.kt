package com.evanna.player.webmusic

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.network.CancelScope
import com.evanna.player.ui.Button
import com.evanna.player.ui.OutlinedTextField
import com.evanna.player.ui.Switch
import com.evanna.player.ui.Text
import com.evanna.player.ui.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun P2pSearch(query: String) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { P2pTransfers.prefs(context) }
    var mobile by remember { mutableStateOf(prefs.getBoolean("mobile", false)) }
    var seed by remember { mutableStateOf(prefs.getBoolean("seed", false)) }
    var magnet by rememberSaveable { mutableStateOf("") }
    var operation by remember { mutableStateOf<CancelScope?>(null) }
    var release by remember { mutableStateOf<TorrentRelease?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { operation?.cancel() } }
    LaunchedEffect(context) { withContext(Dispatchers.IO) { runCatching { P2pTransfers.restore(context) } } }

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
                if (!cancel.cancelled) message = failure.message?.take(180) ?: "Could not access the transfer."
            } finally {
                if (operation === cancel) operation = null
            }
        }
    }

    fun enqueue(folder: Uri, item: TorrentRelease, files: Set<Int>) {
        run { _ ->
            withContext(Dispatchers.IO) { P2pTransfers.enqueue(context, item, files, folder) }
            release = null
            message = "Added to Downloads."
        }
    }

    fun showRelease(item: TorrentRelease) {
        selected = item.files.mapTo(mutableSetOf()) { it.index }
        page = 0
        release = item
    }

    val folderPicker = rememberSearchFolderPicker { folder ->
        val item = release
        if (folder != null && item != null && selected.isNotEmpty()) enqueue(folder, item, selected)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { cancel ->
            val parsed = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use(P2pTorrent::fromDocument)
                    ?: throw IOException("Could not read the .torrent file.")
            }
            cancel.checkActive()
            showRelease(parsed)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BitTorrent", style = EvannaDesign.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow mobile and metered data", Modifier.weight(1f))
            Switch(mobile, {
                mobile = it
                prefs.edit().putBoolean("mobile", it).apply()
                scope.launch(Dispatchers.IO) { runCatching { P2pTransfers.restore(context, reschedule = true) } }
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Seed after download", Modifier.weight(1f))
            Switch(seed, { seed = it; prefs.edit().putBoolean("seed", it).apply() })
        }
        TextButton(enabled = operation == null, onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Open .torrent file") }
        OutlinedTextField(magnet, { magnet = it.take(8192) }, "Magnet link", Modifier.fillMaxWidth())
        TextButton(enabled = operation == null && magnet.isNotBlank(), onClick = {
            run { cancel ->
                val input = magnet.trim()
                showRelease(withContext(Dispatchers.IO) {
                    if (!P2pTransfers.allowedNetwork(context)) throw IOException("Connect to Wi-Fi or allow mobile data.")
                    P2pTorrent.fromMagnet(input, context.cacheDir, cancel)
                })
            }
        }) { Text("Inspect magnet") }
        operation?.let { cancel ->
            Text("Working.")
            TextButton(onClick = { cancel.cancel() }) { Text("Cancel lookup") }
        }
        release?.let { item ->
            val selection = selected
            Text(item.name, style = EvannaDesign.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = item.files.map { it.index }.toSet() }) { Text("Select all") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
            }
            for (file in item.files.drop(page * 20).take(20)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.path, Modifier.weight(1f))
                    Switch(file.index in selection, {
                        selected = if (it) selected + file.index else selected - file.index
                    }, modifier = Modifier.semantics { contentDescription = "Select ${file.path}" })
                }
            }
            if (item.files.size > 20) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous files") }
                TextButton(onClick = { page++ }, enabled = (page + 1) * 20 < item.files.size) { Text("More files") }
            }
            Button(enabled = operation == null && selection.isNotEmpty(), onClick = {
                val folder = searchMusicFolder(context)
                if (folder == null) folderPicker() else enqueue(folder, item, selection)
            }) { Text("Download ${selection.size} selected") }
        }
        message?.let { Text(it) }
        SoulseekSearch(query)
    }
}

@Composable
internal fun P2pDownloads() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val revision by P2pTransfers.changes.collectAsState()
    var rows by remember { mutableStateOf(emptyList<TorrentTask>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context, revision) {
        rows = withContext(Dispatchers.IO) { P2pTransfers.tasks(context) }
    }
    LaunchedEffect(context) { withContext(Dispatchers.IO) { runCatching { P2pTransfers.restore(context) } } }
    fun change(action: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            notice = result.exceptionOrNull()?.message?.take(180)
        }
    }
    if (rows.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BitTorrent", style = EvannaDesign.typography.titleLarge)
        for (row in rows) {
            Text(row.name, style = EvannaDesign.typography.bodyLarge)
            Text(P2pTransfers.progress[row.hash] ?: row.state)
            Text("${row.saved.size} of ${row.selected.size} tracks saved")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (row.state) {
                    "Queued", "Seeding" -> TextButton(onClick = { change { P2pTransfers.pause(context, row) } }) { Text("Pause") }
                    "Saved to Library" -> Unit
                    else -> TextButton(onClick = { change { P2pTransfers.resume(context, row) } }) { Text("Resume") }
                }
                if (row.state != "Queued" && row.state != "Seeding")
                    TextButton(onClick = { change { P2pTransfers.forget(context, row) } }) { Text("Remove transfer") }
            }
        }
        notice?.let { Text(it) }
    }
    SoulseekDownloads()
}
