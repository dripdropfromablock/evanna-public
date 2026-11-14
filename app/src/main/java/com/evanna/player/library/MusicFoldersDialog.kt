package com.evanna.player.library

import android.net.Uri
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.evanna.player.PlayerIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MusicFoldersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val addFolder = rememberAddMusicFolder()
    val folderVolumes = rememberFolderVolumeNames()
    val busy = LocalLibrary.scanning || LocalLibrary.managingFolders
    val revision = LocalLibrary.scansFinished
    var roots by remember { mutableStateOf<List<LibraryRoot>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<LibraryRoot?>(null) }
    var subfolders by remember { mutableStateOf<LibraryRoot?>(null) }
    LaunchedEffect(revision, LocalLibrary.activeScanRoot) {
        loadFailed = false
        try {
            roots = withContext(Dispatchers.IO) { LocalLibrary.database(context).roots() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music folders") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loadFailed -> item { Text("Couldn't load music folders.") }
                    roots == null -> item { ActivityIndicator(Modifier.fillMaxWidth()) }
                    roots.orEmpty().isEmpty() -> item { Text("Add a folder from your phone or SD card.") }
                }
                items(roots.orEmpty(), key = { it.uri }) { root ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(root.name, style = EvannaDesign.typography.titleSmall)
                            FolderSourceDetails(root, folderVolumes)
                        }
                        FolderSourceActions(root, enabled = !busy, onSubfolders = { subfolders = root }, onRemove = { remove = root })
                    }
                }
                if (roots.orEmpty().isNotEmpty()) item {
                    TextButton(onClick = { LocalLibrary.scan(context) }, enabled = !busy) { Text("Rescan all folders") }
                }
                if (!LocalLibrary.statusIsIdle) item { Text(LocalLibrary.status, style = EvannaDesign.typography.bodySmall) }
                if (LocalLibrary.scanning && !LocalLibrary.managingFolders) item {
                    TextButton(onClick = LocalLibrary::cancelScan) { Text("Cancel scan") }
                }
            }
        },
        confirmButton = { TextButton(onClick = addFolder, enabled = !busy) { Text("Add folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    remove?.let { root -> RemoveMusicFolderDialog(root, busy) { remove = null } }
    subfolders?.let { root -> FolderExclusionsDialog(root) { subfolders = null } }
}

@Composable
internal fun rememberFolderVolumeNames(): Map<String, String> {
    val context = LocalContext.current.applicationContext
    val names by produceState<Map<String, String>>(emptyMap(), context, LocalLibrary.scansFinished) {
        value = withContext(Dispatchers.IO) {
            try {
                context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty().mapNotNull { volume ->
                    volume.uuid?.let { it.uppercase() to volume.getDescription(context) }
                }.toMap()
            } catch (_: SecurityException) {
                emptyMap()
            }
        }
    }
    return names
}

@Composable
internal fun FolderSourceDetails(root: LibraryRoot, volumes: Map<String, String>) {
    val state = if (LocalLibrary.activeScanRoot == root.uri) LibraryRootState.SCANNING else root.state
    val style = EvannaDesign.typography.bodySmall
    val color = EvannaDesign.colorScheme.onSurfaceVariant
    Text(folderLocation(Uri.parse(root.uri), volumes), maxLines = 2, overflow = TextOverflow.Ellipsis, style = style, color = color)
    val status = when (state) {
        LibraryRootState.READY -> "Ready"
        LibraryRootState.SCANNING -> "Scanning"
        LibraryRootState.PAUSED -> "Paused"
        LibraryRootState.UNAVAILABLE -> "Unavailable"
    }
    val songs = if (root.songCount == 1) "1 song" else "${root.songCount} songs"
    Text("$songs · $status", style = style, color = color)
}

@Composable
internal fun FolderSourceActions(root: LibraryRoot, enabled: Boolean, onSubfolders: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    var menu by remember(root.uri) { mutableStateOf(false) }
    IconButton(onClick = { LocalLibrary.scan(context, Uri.parse(root.uri)) }, enabled = enabled) {
        Icon(PlayerIcons.Refresh, "Rescan ${root.name}")
    }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) { Icon(PlayerIcons.More, "Manage ${root.name}") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Manage subfolders") }, onClick = { menu = false; onSubfolders() })
            DropdownMenuItem(text = { Text("Remove folder") }, onClick = { menu = false; onRemove() }, destructive = true)
        }
    }
}

@Composable
internal fun RemoveMusicFolderDialog(root: LibraryRoot, busy: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${root.name}?") },
        text = { Text("Music files stay on storage. Saved playlists keep any entries that become unavailable.") },
        confirmButton = {
            TextButton(onClick = { LocalLibrary.removeFolder(context, root); onDismiss() }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        destructive = true,
    )
}
