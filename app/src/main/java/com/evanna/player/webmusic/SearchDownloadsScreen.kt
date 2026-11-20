package com.evanna.player.webmusic

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.evanna.player.EvannaDesign
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsLink
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.isLocalTree
import com.evanna.player.playback.PlaybackService
import com.evanna.player.ui.Text
import com.evanna.player.ui.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val folderAccess = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
private fun destinationPrefs(context: Context) = context.getSharedPreferences("search-downloads", Context.MODE_PRIVATE)

internal fun searchMusicFolder(context: Context): Uri? =
    destinationPrefs(context).getString("music-folder", null)?.let(Uri::parse)?.takeIf { selected ->
        context.contentResolver.persistedUriPermissions.any {
            it.uri == selected && it.isReadPermission && it.isWritePermission
        }
    }

@Composable
internal fun rememberSearchFolderPicker(onPicked: (Uri?) -> Unit): () -> Unit {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            onPicked(null)
        } else scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                runCatching {
                    if (!isLocalTree(app, uri)) throw WebImportError("Choose a local music folder on this device or SD card.")
                    app.contentResolver.takePersistableUriPermission(uri, folderAccess)
                    if (!destinationPrefs(app).edit().putString("music-folder", uri.toString()).commit())
                        throw WebImportError("Could not remember your music folder.")
                }
            }
            accepted.onFailure { LocalLibrary.showNotice((it as? WebImportError)?.message ?: "Allow read and write access to the music folder.") }
            onPicked(uri.takeIf { accepted.isSuccess })
        }
    }
    return {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(folderAccess or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).apply {
            searchMusicFolder(app)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        })
    }
}

internal class PendingSearchDownload : ViewModel() {
    var selection: Pair<List<SearchDownload>, Boolean>? = null
}

@Composable
internal fun rememberSearchDownloader(): (List<SearchDownload>, Boolean) -> Unit {
    val app = LocalContext.current.applicationContext
    val activity = LocalActivity.current as ComponentActivity
    val pending = remember(activity) { ViewModelProvider(activity)[PendingSearchDownload::class.java] }
    val scope = rememberCoroutineScope()
    fun submit(folder: Uri, tracks: List<SearchDownload>, play: Boolean) {
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { WebDownloadQueue.get(app).enqueueSearch(folder.toString(), tracks, play) }
            }
            LocalLibrary.showNotice(added.fold({ count -> when (count) {
                0 -> "Already saved or in Downloads."
                1 -> "Added to Downloads."
                else -> "Added $count tracks to Downloads."
            } }, { (it as? WebImportError)?.message ?: "Could not queue this download. Check your music folder." }))
        }
    }
    val choose = rememberSearchFolderPicker { folder ->
        val selection = pending.selection
        pending.selection = null
        if (folder != null && selection != null) submit(folder, selection.first, selection.second)
        else if (folder != null) LocalLibrary.showNotice("Music folder selected. Choose Download again.")
    }
    return { tracks, play ->
        if (tracks.isNotEmpty()) {
            val folder = searchMusicFolder(app)
            if (folder == null) {
                pending.selection = tracks to play
                choose()
            } else submit(folder, tracks, play)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun playSavedDownload(context: Context, uri: String) {
    val app = context.applicationContext
    val connection = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    fun finish(success: Boolean) {
        MediaController.releaseFuture(connection)
        if (!success) LocalLibrary.showNotice("Could not start playback. Your saved track is in Downloads.")
    }
    connection.addListener({
        val controller = runCatching { connection.get() }.getOrNull()
        if (controller == null) finish(false) else {
            val args = Bundle().apply { putString(PlaybackService.FILE_URI, uri) }
            val select = runCatching {
                controller.sendCustomCommand(SessionCommand(PlaybackService.SELECT_FILE, Bundle.EMPTY), args)
            }.getOrNull()
            if (select == null) finish(false) else select.addListener({
                val playing = runCatching {
                    check(select.get().resultCode == SessionResult.RESULT_SUCCESS)
                    controller.play()
                }.isSuccess
                finish(playing)
            }, app.mainExecutor)
        }
    }, app.mainExecutor)
}

@Composable
internal fun SearchDownloadsScreen(onBack: () -> Unit, bottomInset: Dp) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<WebDownloadQueue?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf(searchMusicFolder(app)) }
    val choose = rememberSearchFolderPicker { if (it != null) folder = it }
    LaunchedEffect(app) {
        val result = withContext(Dispatchers.IO) { runCatching { WebDownloadQueue.get(app) } }
        queue = result.getOrNull()
        if (result.isFailure) error = "Could not read Downloads."
    }
    val entries = queue?.entries?.collectAsState()?.value.orEmpty()
    fun change(action: (WebDownloadQueue) -> Unit) {
        val active = queue ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action(active) } }
            error = result.exceptionOrNull()?.let { (it as? WebImportError)?.message ?: "Could not update Downloads." }
        }
    }
    ScreenBackHandler(true, onBack)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Downloads", onBack) }
        item { SettingsLink("Music folder", folder?.let { DocumentsContract.getTreeDocumentId(it) } ?: "Choose folder", onClick = choose) }
        error?.let { item { Text(it) } }
        if (entries.isEmpty() && queue != null) item { Text("No music downloads yet.") }
        for ((album, tracks) in entries.groupBy { it.album }) {
            if (album != null) item {
                Column {
                    Text(album, style = EvannaDesign.typography.titleLarge)
                    val failed = tracks.filter { it.canRetry && it.status in setOf(WebDownloadStatus.INTERRUPTED, WebDownloadStatus.FAILED) }
                    if (failed.isNotEmpty()) TextButton(onClick = { change { store ->
                        failed.forEach { store.retry(it.id) }
                    } }) { Text("Retry album (${failed.size})") }
                }
            }
            items(tracks, key = { it.id }) { entry ->
                Column {
                    Text(entry.name, style = EvannaDesign.typography.bodyLarge)
                    entry.provider?.let { provider ->
                        Text(listOfNotNull(provider, entry.audioFormat?.uppercase()).joinToString(" · "),
                            style = EvannaDesign.typography.bodyMedium)
                    }
                    Text(entry.detail, style = EvannaDesign.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (entry.status) {
                            WebDownloadStatus.WAITING, WebDownloadStatus.RUNNING, WebDownloadStatus.SAVING ->
                                TextButton(onClick = { change { it.cancel(entry.id) } }) { Text("Cancel") }
                            WebDownloadStatus.SAVED -> entry.savedUri?.let { uri ->
                                TextButton(onClick = { playSavedDownload(app, uri) }) { Text("Play") }
                            }
                            WebDownloadStatus.FAILED, WebDownloadStatus.INTERRUPTED -> if (entry.canRetry) {
                                TextButton(onClick = { change { it.retry(entry.id) } }) { Text("Retry") }
                            }
                        }
                        if (entry.status !in setOf(WebDownloadStatus.RUNNING, WebDownloadStatus.SAVING, WebDownloadStatus.WAITING)) {
                            TextButton(onClick = { change { it.remove(entry.id) } }) { Text("Remove entry") }
                        }
                    }
                }
            }
        }
        item { P2pDownloads() }
    }
}
