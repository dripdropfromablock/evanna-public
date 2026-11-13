package com.evanna.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import android.os.SystemClock
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import com.evanna.player.library.ArtworkImage
import com.evanna.player.library.Lyrics
import com.evanna.player.ui.glass.GlassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun routeLabel(route: String) = when (route) { "ANDROID" -> "Android audio"; "DIRECT_USB" -> "Direct USB"; else -> "Choose output" }

/** Title row for pushed screens: a Back arrow and a heading (Apple look: an accent chevron and a centred title). */
@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit) {
    if (LocalLookStyle.current.look.apple) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).semantics { contentDescription = "Back" }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = EvannaDesign.colorScheme.primary,
                    modifier = Modifier.size(32.dp))
            }
            Text(title, style = EvannaDesign.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).semantics { heading() })
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }
        Text(title, style = EvannaDesign.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() })
    }
}

/** Evanna dark uses outlined chips. The other looks use filled capsules. */
@Composable
internal fun LookChip(selected: Boolean, onClick: () -> Unit, label: String, enabled: Boolean = true) {
    val style = LocalLookStyle.current
    val shape = style.chipShape
    // The outlined look marks selection with a check.
    if (shape == null) FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null)
    else FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) }, shape = shape, border = null,
        colors = FilterChipDefaults.filterChipColors(containerColor = if (style.look.dark) EvannaDesign.colorScheme.surfaceContainerHigh
            else EvannaDesign.colorScheme.surfaceContainerHighest, labelColor = EvannaDesign.colorScheme.onSurface))
}

/** A Settings row with a title, a short note and a switch; the whole row toggles it. */
@Composable
internal fun SwitchRow(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val muted = EvannaDesign.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = EvannaDesign.typography.titleSmall, color = if (enabled) Color.Unspecified else muted)
            Text(note, style = EvannaDesign.typography.bodySmall, color = muted)
        }
        Box(Modifier.padding(start = 12.dp).clearAndSetSemantics {}) { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) }
    }
}

/** A rounded Settings section with a heading. */
@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val studio = LocalLookStyle.current.hairlineCards
    Surface(Modifier.fillMaxWidth(), shape = EvannaDesign.shapes.large, color = EvannaDesign.colorScheme.surfaceContainer,
        border = if (studio) evannaHairline(0.55f) else null) {
        Column(Modifier.padding(horizontal = if (studio) 16.dp else 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = if (studio) EvannaType.technical.copy(fontSize = 13.sp) else EvannaDesign.typography.titleMedium,
                color = if (studio) EvannaDesign.colorScheme.primary else EvannaDesign.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** A full-width row that opens another screen. */
@Composable
internal fun SettingsLink(label: String, subtitle: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(EvannaDesign.shapes.small).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = EvannaDesign.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, style = EvannaDesign.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = EvannaDesign.typography.bodySmall, color = EvannaDesign.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = EvannaDesign.colorScheme.onSurfaceVariant)
    }
}

/**
 * The floating mini player: at least 48 dp tall, 30 dp cover, one-line title, Play/Pause and Next.
 * A separate floating capsule above the navigation row. Tap or swipe up to open Now Playing.
 */
@Composable
internal fun MiniPlayer(state: PlayerPresentation, cover: Bitmap?, onOpen: () -> Unit, onPlayPause: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onOutput: () -> Unit,
    motion: PlayerSheetMotion, flying: Boolean, onOpenChange: (Boolean) -> Unit,
) {
    if (!state.hasSong) return
    val look = LocalLookStyle.current.look
    val colors = EvannaDesign.colorScheme
    val shape = RoundedCornerShape(50)
    val border = evannaHairline(if (look.dark) 0.62f else 0.70f)
    val openPress = remember { MutableInteractionSource() }
    val canPrevious = state.connected && state.canPrevious
    val canNext = state.connected && state.canNext
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    GlassSurface(Modifier.fillMaxWidth(), shape,
        solidColor = if (look.dark) colors.surfaceContainerLow.copy(alpha = 0.94f) else colors.surfaceContainerHigh,
        border = border, shadowElevation = 6.dp) {
        val rowHeight = 56.dp
        Row(Modifier.heightIn(min = rowHeight).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = rowHeight).playerPull(motion, false, state.id, onOpenChange)
                .pointerInput(state.id, canPrevious, canNext) {
                    if (!canPrevious && !canNext) return@pointerInput
                    var distance = 0f
                    val threshold = 40.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { distance = 0f },
                        onHorizontalDrag = { _, delta -> distance += delta },
                        onDragEnd = {
                            if (distance <= -threshold && canNext) next()
                            if (distance >= threshold && canPrevious) previous()
                        },
                    )
                }
                .controlFocus(openPress, shape)
                .clickable(openPress, indication = null, onClick = onOpen).semantics {
                    contentDescription = "Open Now Playing"
                    customActions = buildList {
                        if (canPrevious) add(CustomAccessibilityAction("Previous track") { onPrevious(); true })
                        if (canNext) add(CustomAccessibilityAction("Next track") { onNext(); true })
                    }
                }
                .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(cover, Modifier.size(38.dp).dockingCover(motion, flying, mini = true)
                    .shadow(2.dp, RoundedCornerShape(8.dp)), RoundedCornerShape(8.dp))
                Column(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp).pressScale(openPress)) {
                    Text(state.title.ifBlank { "Local audio file" }, style = EvannaDesign.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        fontWeight = if (look.apple) FontWeight.Medium else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!state.artist.isNullOrBlank()) Text(state.artist,
                        style = EvannaDesign.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.route == "NONE" && !state.canPlayPause) TextButton(onClick = onOutput, enabled = state.connected, modifier = Modifier.heightIn(min = 48.dp)) { Text("Output") }
            else {
                IconButton(onClick = onPlayPause, enabled = state.connected && state.canPlayPause,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = if (state.playing) "Pause playback" else "Play playback" }) {
                    Icon(if (state.playing) PlayerIcons.Pause else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = onNext, enabled = canNext,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Next" }) {
                    Icon(PlayerIcons.Next, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable
internal fun PlayerDock(selected: PlayerScreen, onSelect: (PlayerScreen) -> Unit,
    tabs: List<Triple<PlayerScreen, String, ImageVector>>, searchIcon: ImageVector, hasSong: Boolean,
    hideNavigation: Boolean, modifier: Modifier = Modifier, miniPlayer: @Composable () -> Unit,
) {
    val colors = EvannaDesign.colorScheme
    val surface = if (LocalLookStyle.current.look.dark) colors.surfaceContainerLow.copy(alpha = 0.94f) else colors.surfaceContainer
    val border = evannaHairline(0.62f)
    Column(modifier.fillMaxWidth()) {
        if (hasSong) miniPlayer()
        // The dock is bottom-aligned, so collapsing this space lowers the mini player with the navigation row.
        AnimatedVisibility(visible = !hideNavigation,
            enter = expandVertically(tween(EvannaMotion.enter.durationMillis, easing = EvannaMotion.enter.easing), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(EvannaMotion.exit.durationMillis, easing = EvannaMotion.exit.easing), shrinkTowards = Alignment.Top),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = if (hasSong) 8.dp else 0.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassSurface(Modifier.weight(1f), CircleShape, solidColor = surface, border = border, shadowElevation = 6.dp) {
                    DockTabs(selected, onSelect, tabs)
                }
                GlassSurface(Modifier.size(56.dp), CircleShape, solidColor = surface, border = border, shadowElevation = 6.dp) {
                    val searchSelected = selected == PlayerScreen.SEARCH
                    val interaction = remember { MutableInteractionSource() }
                    val searchOpacity by animateFloatAsState(if (searchSelected) 1f else 0f, EvannaMotion.enter, label = "search selection opacity")
                    Box(Modifier.fillMaxSize().drawBehind { drawCircle(colors.onSurface.copy(alpha = SELECTION_PILL_ALPHA * searchOpacity)) }
                        .controlFocus(interaction, CircleShape)
                        .selectable(selected = searchSelected, interactionSource = interaction, indication = null,
                            role = Role.Tab, onClick = { onSelect(PlayerScreen.SEARCH) })
                        .semantics { text = AnnotatedString("Search") }, contentAlignment = Alignment.Center) {
                        Icon(searchIcon, contentDescription = null, modifier = Modifier.pressScale(interaction),
                            tint = if (searchSelected) colors.primary else colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTabs(selected: PlayerScreen, onSelect: (PlayerScreen) -> Unit,
    tabs: List<Triple<PlayerScreen, String, ImageVector>>,
) {
    val colors = EvannaDesign.colorScheme
    val look = LocalLookStyle.current.look
    val tabShape = RoundedCornerShape(50)
    val selectedIndex = tabs.indexOfFirst { it.first == selected }
    val searchSelected = selected == PlayerScreen.SEARCH
    var draggedMarker by remember(selected) { mutableStateOf<Float?>(null) }
    val select by rememberUpdatedState(onSelect)
    val layout = LocalLayoutDirection.current
    val markerPosition by animateFloatAsState(draggedMarker ?: selectedIndex.takeIf { it >= 0 }?.toFloat() ?: tabs.lastIndex.toFloat(),
        if (draggedMarker != null) snap() else EvannaMotion.enter, label = "dock selection position")
    val markerOpacity by animateFloatAsState(if (searchSelected && draggedMarker == null) 0f else 1f,
        EvannaMotion.exit, label = "dock selection opacity")
    Row(Modifier.fillMaxWidth().padding(4.dp).pointerInput(selectedIndex, tabs, layout) {
        val direction = if (layout == LayoutDirection.Ltr) 1 else -1
        var start = 0
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { touch ->
                val logicalX = if (direction > 0) touch.x else size.width - touch.x
                val slot = (logicalX / size.width * tabs.size).toInt().coerceIn(tabs.indices)
                start = selectedIndex.takeIf { it >= 0 } ?: slot
                distance = 0f
            },
            onDragEnd = {
                val step = when {
                    distance >= 36.dp.toPx() -> 1
                    distance <= -36.dp.toPx() -> -1
                    else -> 0
                }
                val target = (start + step).coerceIn(tabs.indices)
                draggedMarker = null
                if (step != 0 && target != selectedIndex) select(tabs[target].first)
            },
            onDragCancel = { draggedMarker = null },
            onHorizontalDrag = { _, delta ->
                distance += delta * direction
                val slotWidth = (size.width.toFloat() / tabs.size).coerceAtLeast(1f)
                draggedMarker = (start + distance / slotWidth).coerceIn(0f, tabs.lastIndex.toFloat())
            },
        )
    }.slidingSelectionPill(tabs.size, { markerPosition }, { colors.onSurface.copy(alpha = SELECTION_PILL_ALPHA * markerOpacity) })) {
        for ((tab, label, icon) in tabs) {
            val on = tab == selected
            val tint = if (on && !look.apple) colors.primary else if (on) colors.onSurface else colors.onSurfaceVariant
            val interaction = remember(tab) { MutableInteractionSource() }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(tabShape)
                .controlFocus(interaction, tabShape)
                .selectable(selected = on, interactionSource = interaction, indication = null,
                    role = Role.Tab, onClick = { onSelect(tab) }).padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, tint = if (on) colors.primary else tint, modifier = Modifier.size(24.dp).pressScale(interaction))
                Spacer(Modifier.height(3.dp))
                Text(label, style = EvannaDesign.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                    fontWeight = FontWeight.Medium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun BoxScope.UsbVolumeIndicator(db: Float?) {
    if (db == null) return
    Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }, shape = EvannaDesign.shapes.large, color = EvannaDesign.colorScheme.surfaceContainerHigh) {
        Text("USB volume ${db.toString().removeSuffix(".0")} dB", style = EvannaType.technical,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
