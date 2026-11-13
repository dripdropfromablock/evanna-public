package com.evanna.player.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.PlayerIcons
import com.evanna.player.playbackTime
import com.evanna.player.ui.*
import com.evanna.player.user.UserData
import com.evanna.player.user.isFavorite
import com.evanna.player.user.setFavorite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

private val SongMenuActionShape = RoundedCornerShape(20.dp)

@Composable
internal fun LibrarySongRow(
    song: LibrarySong, enabled: Boolean, scope: CoroutineScope, onPlay: () -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit, onPlayNext: (String, (Boolean) -> Unit) -> Unit,
    onPlaylistSong: (String) -> Unit, onTrackInfo: () -> Unit, onOpenAlbum: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val canAct = song.available && enabled
    val context = LocalContext.current
    val personal = remember(context) { UserData.database(context) }
    var menu by remember(song.id) { mutableStateOf(false) }
    var favorite by remember(song.id) { mutableStateOf<Boolean?>(null) }
    fun openMenu() {
        favorite = null
        menu = true
    }
    LaunchedEffect(menu, canAct, song.id) {
        if (!canAct) menu = false
        else if (menu) personal.changes.collectLatest {
            favorite = try {
                withContext(Dispatchers.IO) { personal.isFavorite(song.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    val addToQueue = {
        onAdd(song.id) { success ->
            LocalLibrary.status = if (success) "Added ${song.title} to the queue."
            else "The song could not be added. Check folder access and the queue limit."
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(EvannaDesign.shapes.medium)
            .combinedClickable(enabled = canAct, onLongClickLabel = "More options", onLongClick = ::openMenu, onClick = onPlay)
            .alpha(if (song.available) 1f else 0.5f).padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
        IconButton(onClick = addToQueue, enabled = canAct,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Add ${song.title} to queue" }) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
        Box {
            IconButton(onClick = ::openMenu, enabled = canAct, modifier = Modifier.size(48.dp)) {
                Icon(PlayerIcons.More, "More options for ${song.title}")
            }
            LibrarySongMenu(expanded = menu && canAct, onDismiss = { menu = false }, favorite = favorite,
                canOpenAlbum = onOpenAlbum != null,
                onPlayNext = {
                    menu = false
                    onPlayNext(song.id) { success ->
                        LocalLibrary.status = if (success) "${song.title} will play next."
                        else "The song could not be added. Check folder access and the queue limit."
                    }
                },
                onQueue = { menu = false; addToQueue() },
                onFavorite = {
                    val selected = favorite != true
                    menu = false
                    scope.launch {
                        val success = withContext(NonCancellable + Dispatchers.IO) { personal.setFavorite(song.id, selected) }
                        LocalLibrary.status = if (success) "Favorites updated." else "The favorite could not be changed."
                    }
                },
                onPlaylist = { menu = false; onPlaylistSong(song.id) },
                onAlbum = { menu = false; onOpenAlbum?.invoke() },
                onTrackInfo = { menu = false; onTrackInfo() },
            )
        }
    }
}

@Composable
private fun LibrarySongMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    favorite: Boolean?,
    canOpenAlbum: Boolean,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onFavorite: () -> Unit,
    onPlaylist: () -> Unit,
    onAlbum: () -> Unit,
    onTrackInfo: () -> Unit,
) {
    // iOS context menu: the three quick actions side by side on top, then rows with their icons at the end.
    DropdownMenu(expanded, onDismiss, Modifier.width(272.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SongMenuAction("Play next", PlayerIcons.Next, onPlayNext, Modifier.weight(1f))
            SongMenuAction("Add to queue", PlayerIcons.Queue, onQueue, Modifier.weight(1f))
            SongMenuAction("Favorite", if (favorite == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                onFavorite, Modifier.weight(1f), enabled = favorite != null, highlighted = favorite == true,
                actionLabel = if (favorite == true) "Remove from favorites" else "Add to favorites")
        }
        MenuDivider()
        DropdownMenuItem(text = { Text("Add to playlist") }, onClick = onPlaylist, icon = PlayerIcons.Plus)
        DropdownMenuItem(text = { Text("Go to album") }, onClick = onAlbum, enabled = canOpenAlbum, icon = PlayerIcons.Album)
        DropdownMenuItem(text = { Text("Track info") }, onClick = onTrackInfo, icon = PlayerIcons.Info)
    }
}

@Composable
private fun SongMenuAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    actionLabel: String? = null,
) {
    val ink = EvannaDesign.colorScheme.onSurface
    val tint = ink.copy(alpha = if (enabled) 1f else 0.38f)
    Column(
        modifier.heightIn(min = 68.dp).clip(SongMenuActionShape)
            .background(if (highlighted) ink.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(label, color = tint, style = EvannaDesign.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun LibraryTrackInfo(song: LibrarySong, onDismiss: () -> Unit) {
    val details = listOf(
        "Artist" to song.artist,
        "Album" to song.album,
        "Duration" to song.durationUs?.let { playbackTime(it / 1_000) },
        "Format" to song.codec,
        "Sample rate" to song.rate?.let { "$it Hz" },
    ).mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { "$label · $it" } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Track info") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(song.title, style = EvannaDesign.typography.titleMedium)
            if (details.isEmpty()) Text("No additional track details available.")
            for (detail in details) Text(detail)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
