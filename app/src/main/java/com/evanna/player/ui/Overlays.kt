package com.evanna.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.evanna.player.EvannaDesign
import com.evanna.player.EvannaMotion
import com.evanna.player.LocalLookStyle
import com.evanna.player.PlayerIcons
import com.evanna.player.blockTouches
import com.evanna.player.evannaHairline
import com.evanna.player.ui.glass.rimColors

@Composable
internal fun Surface(modifier: Modifier = Modifier, shape: Shape = RectangleShape,
    color: Color = EvannaDesign.colorScheme.surface, border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp, content: @Composable () -> Unit,
) = SurfaceBox(modifier, shape, color, border, shadowElevation, Modifier, content)

@Composable
internal fun Surface(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RectangleShape, color: Color = EvannaDesign.colorScheme.surface, content: @Composable () -> Unit,
) = SurfaceBox(modifier, shape, color, null, 0.dp, Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick), content)

@Composable
private fun SurfaceBox(modifier: Modifier, shape: Shape, color: Color, border: BorderStroke?, elevation: Dp,
    interaction: Modifier, content: @Composable () -> Unit,
) {
    val palette = EvannaDesign.colorScheme
    val ink = when (color) {
        palette.background -> palette.onBackground
        palette.primary -> palette.onPrimary
        palette.primaryContainer -> palette.onPrimaryContainer
        palette.secondaryContainer -> palette.onSecondaryContainer
        else -> LocalContentColor.current
    }
    // A clickable surface's press feedback wraps its fill and border, so the whole surface sinks (PressIndication).
    Box(modifier.shadow(elevation, shape).then(interaction).clip(shape).background(color)
        .then(if (border == null) Modifier else Modifier.border(border, shape)), propagateMinConstraints = true) {
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}

@Composable
internal fun HorizontalDivider(modifier: Modifier = Modifier, color: Color = EvannaDesign.colorScheme.outlineVariant) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

private class MenuPosition(
    private val chevronInset: Int,
    private val onOrigin: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val y = if (anchorBounds.bottom <= maxY || anchorBounds.top < popupContentSize.height) anchorBounds.bottom
            else anchorBounds.top - popupContentSize.height
        val position = IntOffset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
        // Wide triggers have a chevron at the trailing edge. Icon buttons use their center.
        val originX = when {
            anchorBounds.width <= popupContentSize.width -> anchorBounds.center.x
            layoutDirection == LayoutDirection.Ltr -> anchorBounds.right - chevronInset
            else -> anchorBounds.left + chevronInset
        }
        onOrigin(TransformOrigin(
            (originX - position.x).toFloat() / popupContentSize.width.coerceAtLeast(1),
            (anchorBounds.center.y - position.y).toFloat() / popupContentSize.height.coerceAtLeast(1),
        ))
        return position
    }
}

/** Menus are opaque: a popup window can't blur the page behind it, and even 3% see-through lets list text ghost through. */
internal const val MENU_SURFACE_ALPHA = 1f
private val MenuItemInset = 6.dp
private val MenuShape = RoundedCornerShape(24.dp).continuous()
private val MenuRowShape = RoundedCornerShape(16.dp).continuous()
private val MenuCheckWidth = 28.dp
/** iOS menu text: 17 sp with slightly tight tracking. */
private val MenuText = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)

/** True in a menu that has choices: every row keeps the leading checkmark column, so all the text lines up. */
private val LocalMenuChecks = staticCompositionLocalOf { false }

/** The rim light along a menu's edge, as on the frosted glass dock. */
@Composable
internal fun menuRim(): BorderStroke {
    val dark = LocalLookStyle.current.look.dark
    return remember(dark) { rimColors(dark).let { (top, bottom) -> BorderStroke(1.dp, Brush.verticalGradient(listOf(top, bottom))) } }
}

/**
 * An iOS pull-down menu: a frosted, round-cornered panel that grows out of the button that opened it. Pass [checkmarks]
 * when it holds [DropdownChoices], so the chosen row's leading checkmark has a column every row shares.
 */
@Composable
internal fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    shape: Shape = MenuShape, border: BorderStroke = menuRim(), checkmarks: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val transition = rememberTransition(visibility, label = "dropdown")
    val progress by transition.animateFloat(transitionSpec = { if (targetState) EvannaMotion.enter else EvannaMotion.exit },
        label = "dropdown progress") { if (it) 1f else 0f }
    if (visibility.isIdle && !visibility.currentState) return

    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val chevronInset = with(LocalDensity.current) { 24.dp.roundToPx() }
    val position = remember(chevronInset) { MenuPosition(chevronInset) { origin = it } }
    Popup(position, onDismissRequest = { if (expanded) onDismissRequest() }, properties = PopupProperties(focusable = expanded)) {
        Surface(modifier.widthIn(min = 220.dp, max = 300.dp).width(IntrinsicSize.Max).heightIn(max = 420.dp)
            .graphicsLayer {
                transformOrigin = origin
                alpha = progress
                scaleX = 0.72f + 0.28f * progress
                scaleY = scaleX
            }.then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}.blockTouches()),
            shape = shape,
            color = EvannaDesign.colorScheme.surfaceContainerHigh.copy(alpha = MENU_SURFACE_ALPHA),
            border = border, shadowElevation = 16.dp) {
            CompositionLocalProvider(LocalMenuChecks provides checkmarks) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
            }
        }
    }
}

/**
 * One menu row, as iOS draws it: 17 sp text, a checkmark before the chosen option, the icon after the text.
 * [destructive] rows are red, like Remove.
 */
@Composable
internal fun DropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean? = null, icon: ImageVector? = null, destructive: Boolean = false,
) {
    val dark = LocalLookStyle.current.look.dark
    val base = if (destructive) AppleColors.red(dark) else EvannaDesign.colorScheme.onSurface
    val action = if (selected == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = MenuItemInset).clip(MenuRowShape)
        .then(action).padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) base else base.copy(alpha = 0.38f)) {
            if (selected != null || LocalMenuChecks.current) Box(Modifier.width(MenuCheckWidth)) {
                if (selected == true) Icon(PlayerIcons.Check, contentDescription = null, modifier = Modifier.size(17.dp))
            } else Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) { ProvideTextStyle(MenuText, text) }
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.padding(start = 16.dp).size(21.dp))
        }
    }
}

/** A thin line between groups of menu rows. */
@Composable
internal fun MenuDivider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(0.5.dp)
        .background(AppleColors.separator(LocalLookStyle.current.look.dark)))
}

/** A small gray title over a group of menu rows, lined up with their text. */
@Composable
internal fun MenuSectionTitle(text: String) {
    val start = MenuItemInset + 10.dp + if (LocalMenuChecks.current) MenuCheckWidth else 6.dp
    Text(text, style = EvannaDesign.typography.labelMedium, fontSize = 13.sp, color = EvannaDesign.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = start, end = 16.dp, top = 4.dp, bottom = 2.dp).semantics { heading() })
}

/** Menu rows for a set of options; the chosen one carries a checkmark. Use in a menu opened with checkmarks = true. */
@Composable
internal fun <T : Any> DropdownChoices(options: List<T>, selected: T?, expanded: Boolean, onSelect: (T) -> Unit, enabled: Boolean = true,
    text: @Composable (T) -> Unit,
) {
    // Keep the checkmark on the tapped row while the menu closes, even if the choice is saved asynchronously.
    var tapped by remember(options) { mutableStateOf<T?>(null) }
    LaunchedEffect(expanded) { if (expanded) tapped = null }
    val shown = if (expanded) selected else tapped ?: selected
    Column(Modifier.selectableGroup()) {
        for (option in options) key(option) {
            DropdownMenuItem(text = { text(option) }, selected = shown == option, enabled = enabled,
                onClick = { tapped = option; onSelect(option) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlertDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null,
    containerColor: Color = EvannaDesign.colorScheme.surfaceContainerHigh,
) {
    val palette = EvannaDesign.colorScheme
    Dialog(onDismissRequest) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, EvannaMotion.enter) }
        Surface(Modifier.fillMaxWidth().graphicsLayer {
            alpha = progress.value
            scaleX = 0.96f + 0.04f * progress.value
            scaleY = scaleX
            translationY = 8.dp.toPx() * (1f - progress.value)
        }, shape = EvannaDesign.shapes.extraLarge, color = containerColor) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalContentColor provides palette.onSurface) {
                    ProvideTextStyle(EvannaDesign.typography.titleLarge, title)
                }
                Box(Modifier.weight(1f, fill = false)) {
                    CompositionLocalProvider(LocalContentColor provides palette.onSurfaceVariant) {
                        ProvideTextStyle(EvannaDesign.typography.bodyMedium, text)
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
internal fun OutputSheet(origin: Offset?, onDismissRequest: () -> Unit, shape: Shape, containerColor: Color, scrimColor: Color,
    dragHandle: @Composable () -> Unit, title: String = "Audio output", content: @Composable ColumnScope.() -> Unit,
) {
    var closing by remember { mutableStateOf(false) }
    val close = { closing = true }
    val progress = remember { Animatable(0f) }
    val dismissSheet by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(closing) {
        progress.animateTo(if (closing) 0f else 1f, if (closing) EvannaMotion.exit else EvannaMotion.enter)
        if (closing) dismissSheet()
    }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        var sheetCenter by remember { mutableStateOf<Offset?>(null) }
        var offset by remember { mutableFloatStateOf(0f) }
        val threshold = with(LocalDensity.current) { 56.dp.toPx() }
        suspend fun settle(velocity: Float) {
            if (offset > threshold || (offset > 0f && velocity > threshold * 8f)) close()
            else animate(offset, 0f, animationSpec = tween(160)) { value, _ -> offset = value }
        }
        val scroll = remember(threshold) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || offset == 0f || available.y >= 0f) return Offset.Zero
                    val consumed = available.y.coerceAtLeast(-offset)
                    offset += consumed
                    return Offset(0f, consumed)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    offset += available.y
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (offset == 0f) return Velocity.Zero
                    settle(available.y)
                    return Velocity(0f, available.y)
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = progress.value }
                .background(scrimColor).clickable(onClick = close).semantics { contentDescription = "Dismiss ${title.lowercase()}" })
            Box(Modifier.align(Alignment.BottomCenter).statusBarsPadding().onGloballyPositioned {
                val center = it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f))
                sheetCenter = center.takeIf { it.isFinite }
            }) {
                Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().graphicsLayer {
                    val shown = progress.value
                    val center = sheetCenter
                    val from = if (origin != null && center != null) origin - center else Offset(0f, size.height / 2f)
                    translationX = from.x * (1f - shown)
                    translationY = from.y * (1f - shown) + offset * shown
                    scaleX = 0.26f + 0.74f * shown
                    scaleY = 0.10f + 0.90f * shown
                    alpha = shown
                }.nestedScroll(scroll).then(if (closing) Modifier.clearAndSetSemantics {} else Modifier
                    .semantics { paneTitle = title; dismiss { close(); true } }),
                    shape = shape, color = containerColor, border = evannaHairline(0.6f)) {
                    Column(Modifier.navigationBarsPadding().imePadding()) {
                        Box(Modifier.fillMaxWidth().draggable(rememberDraggableState { delta -> offset = (offset + delta).coerceAtLeast(0f) },
                            orientation = Orientation.Vertical, onDragStopped = { velocity -> settle(velocity) }),
                            contentAlignment = Alignment.Center) { dragHandle() }
                        content()
                    }
                }
            }
            if (closing) Box(Modifier.matchParentSize().blockTouches())
        }
    }
}
