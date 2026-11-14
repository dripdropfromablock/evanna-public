package com.evanna.player.library

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.PlayerIcons
import com.evanna.player.TileOrange
import com.evanna.player.TilePurple
import kotlin.math.abs
import kotlin.math.roundToInt
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
    /** The "+" (Add to queue) button beside "…". */
    queueButton: Boolean = true,
    /** Swipe right for Play next, left for Add to queue. */
    swipeToQueue: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val canAct = song.available && enabled
    val context = LocalContext.current
    val personal = remember(context) { UserData.database(context) }
    // The "…" button opens the menu off itself; a long press lifts the row and hangs the same menu off it.
    var menu by remember(song.id) { mutableStateOf(false) }
    var lifted by remember(song.id) { mutableStateOf<Lifted?>(null) }
    var favorite by remember(song.id) { mutableStateOf<Boolean?>(null) }
    val menuUp = menu || lifted != null
    val picture = rememberGraphicsLayer()
    val row = remember { RowPlace() }
    val captures = rememberCoroutineScope()
    fun openMenu() {
        favorite = null
        menu = true
    }
    fun lift() {
        val coords = row.coords?.takeIf { it.isAttached }
        val at = coords?.localToScreen(Offset.Zero)
        if (coords == null || at == null || !at.isFinite) return openMenu()
        favorite = null
        captures.launch {
            val image = try { picture.toImageBitmap() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (image == null) openMenu() else lifted = Lifted(image, Rect(at, coords.size.toSize()))
        }
    }
    fun close() {
        menu = false
        lifted = null
    }
    LaunchedEffect(menuUp, canAct, song.id) {
        if (!canAct) close()
        else if (menuUp) personal.changes.collectLatest {
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
    val playNext = {
        onPlayNext(song.id) { success ->
            LocalLibrary.status = if (success) "${song.title} will play next."
            else "The song could not be added. Check folder access and the queue limit."
        }
    }
    // Apple Music's swipes: right for Play next, left for Add to queue. The row slides over the action it reveals.
    val swipe = swipeToQueue && canAct
    val haptics = LocalHapticFeedback.current
    val reach = with(LocalDensity.current) { SwipeReach.toPx() }
    var slide by remember(song.id) { mutableFloatStateOf(0f) }
    val swipeState = rememberDraggableState { delta ->
        val before = slide
        slide = (slide + delta).coerceIn(-reach * 1.5f, reach * 1.5f)
        // A tick as the row passes the point where letting go acts.
        if ((abs(before) < reach) != (abs(slide) < reach)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    Box(Modifier.fillMaxWidth().then(if (swipe) Modifier.semantics {
        customActions = listOf(
            CustomAccessibilityAction("Play next") { playNext(); true },
            CustomAccessibilityAction("Add to queue") { addToQueue(); true },
        )
    } else Modifier)) {
    if (slide != 0f) SwipeAction(slide, reach)
    Row(Modifier.fillMaxWidth()
        .then(if (swipe) Modifier.offset { IntOffset(slide.roundToInt(), 0) }
            .draggable(swipeState, Orientation.Horizontal, onDragStopped = {
                val at = slide
                if (at >= reach) playNext() else if (at <= -reach) addToQueue()
                animate(at, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { value, _ -> slide = value }
            }) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        // The row records its own drawing, inside the press dip, so the lifted picture is the row at full size.
        Row(Modifier.weight(1f).clip(EvannaDesign.shapes.medium)
            .combinedClickable(enabled = canAct, onLongClickLabel = "More options", onLongClick = ::lift, onClick = onPlay)
            .onGloballyPositioned { row.coords = it }
            .drawWithContent {
                picture.record { this@drawWithContent.drawContent() }
                drawLayer(picture)
            }
            .alpha(if (song.available) 1f else 0.5f).padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
        if (queueButton) IconButton(onClick = addToQueue, enabled = canAct,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Add ${song.title} to queue" }) {
            Icon(Icons.Filled.Add, contentDescription = null)
        }
        Box {
            IconButton(onClick = ::openMenu, enabled = canAct, modifier = Modifier.size(48.dp)) {
                Icon(PlayerIcons.More, "More options for ${song.title}")
            }
            val items: @Composable ColumnScope.() -> Unit = {
                SongMenuItems(favorite = favorite, canOpenAlbum = onOpenAlbum != null,
                    onPlayNext = { close(); playNext() },
                    onQueue = { close(); addToQueue() },
                    onFavorite = {
                        val selected = favorite != true
                        close()
                        scope.launch {
                            val success = withContext(NonCancellable + Dispatchers.IO) { personal.setFavorite(song.id, selected) }
                            LocalLibrary.status = if (success) "Favorites updated." else "The favorite could not be changed."
                        }
                    },
                    onPlaylist = { close(); onPlaylistSong(song.id) },
                    onAlbum = { close(); onOpenAlbum?.invoke() },
                    onTrackInfo = { close(); onTrackInfo() },
                )
            }
            DropdownMenu(menu && canAct, { menu = false }, Modifier.width(272.dp), content = items)
            LiftMenu(lifted?.takeIf { canAct }, onDismiss = { lifted = null }, onPicture = { close(); onPlay() }, content = items)
        }
    }
    }
}

/** How far a row slides before letting go acts. */
private val SwipeReach = 76.dp

/** What a sliding row reveals at the edge it leaves: Play next (purple) on the left, Add to queue (orange) on the right. */
@Composable
private fun BoxScope.SwipeAction(slide: Float, reach: Float) {
    val next = slide > 0f
    val ready = abs(slide) >= reach
    val width = with(LocalDensity.current) { abs(slide).toDp() }
    // Sized to the row: its own box takes the row's size, so the strip can fill its height.
    Box(Modifier.matchParentSize()) {
        Row(Modifier.align(if (next) Alignment.CenterStart else Alignment.CenterEnd).width(width).fillMaxHeight()
            .padding(vertical = 2.dp).clip(EvannaDesign.shapes.medium).background(if (next) TilePurple else TileOrange)
            .padding(horizontal = 14.dp),
            horizontalArrangement = if (next) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            // The label shows once there is room for it, and firms up past the point where letting go acts.
            if (width > 56.dp) Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = if (ready) 1f else 0.7f }) {
                Icon(if (next) PlayerIcons.Next else PlayerIcons.Queue, null, Modifier.size(20.dp), tint = Color.White)
                Text(if (next) "Play next" else "Queue", color = Color.White, style = EvannaDesign.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** Where a song row was last placed, kept outside composition: it changes on every scroll frame. */
private class RowPlace { var coords: LayoutCoordinates? = null }

/** The song actions: three quick ones side by side on top, as in iOS context menus, then rows with their icons at the end. */
@Composable
private fun SongMenuItems(
    favorite: Boolean?,
    canOpenAlbum: Boolean,
    onPlayNext: () -> Unit,
    onQueue: () -> Unit,
    onFavorite: () -> Unit,
    onPlaylist: () -> Unit,
    onAlbum: () -> Unit,
    onTrackInfo: () -> Unit,
) {
    Column {
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
