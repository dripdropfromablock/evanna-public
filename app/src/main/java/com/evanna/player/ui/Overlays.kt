package com.evanna.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlin.math.roundToInt
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
import androidx.compose.ui.layout.Layout
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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
import com.evanna.player.ui.glass.contrastEdge
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
private val MenuPop = spring<Float>(dampingRatio = 0.72f, stiffness = 420f)
/** iOS menu text: 17 sp with slightly tight tracking. */
private val MenuText = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)

/** True in a menu that has choices: every row keeps the leading checkmark column, so all the text lines up. */
private val LocalMenuChecks = staticCompositionLocalOf { false }

/** The rim light along a menu's edge, as on the frosted glass dock; a plain strong edge with Increase contrast on. */
@Composable
internal fun menuRim(): BorderStroke {
    val dark = LocalLookStyle.current.look.dark
    val contrast = increaseContrastOn()
    return remember(dark, contrast) {
        if (contrast) BorderStroke(1.dp, contrastEdge(dark))
        else rimColors(dark).let { (top, bottom) -> BorderStroke(1.dp, Brush.verticalGradient(listOf(top, bottom))) }
    }
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
    // iOS menus pop out of the button: under-damped on the way in (the small overshoot is the pop), quick on the way out.
    val progress by transition.animateFloat(transitionSpec = { if (targetState) MenuPop else EvannaMotion.exit },
        label = "dropdown progress") { if (it) 1f else 0f }
    if (visibility.isIdle && !visibility.currentState) return

    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val chevronInset = with(LocalDensity.current) { 24.dp.roundToPx() }
    val position = remember(chevronInset) { MenuPosition(chevronInset) { origin = it } }
    Popup(position, onDismissRequest = { if (expanded) onDismissRequest() }, properties = PopupProperties(focusable = expanded)) {
        Surface(modifier.widthIn(min = 220.dp, max = 300.dp).width(IntrinsicSize.Max).heightIn(max = 420.dp)
            .graphicsLayer {
                transformOrigin = origin
                alpha = (progress * 2f).coerceIn(0f, 1f)
                scaleX = 0.55f + 0.45f * progress
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

/** A held row, as [LiftMenu] lifts it: its picture, and where it was on screen. */
internal class Lifted(val picture: ImageBitmap, val bounds: Rect)

private val LiftCardShape = RoundedCornerShape(20.dp).continuous()
private val LiftPlatePad = 8.dp
private val LiftPictureShape = RoundedCornerShape(12.dp).continuous()
private val LiftMenuWidth = 260.dp

private fun mix(from: Float, to: Float, t: Float) = from + (to - from) * t

/**
 * Apple Music's long-press menu (after BitChord's `HeldContextMenu`, GPL-3.0; docs/ui-plan-liquid-glass.md phase 4): the
 * held row pops off the list on a plate, the page behind blurs and dims, and the actions hang off the row. [lifted] is
 * non-null while the menu is wanted; the last one is kept so the row can settle back into the list. With glass off or
 * Reduce transparency on, the page only dims. [onPicture] runs when the lifted row itself is tapped.
 */
@Composable
internal fun LiftMenu(lifted: Lifted?, onDismiss: () -> Unit, onPicture: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var last by remember { mutableStateOf<Lifted?>(null) }
    SideEffect { if (lifted != null) last = lifted }
    val open = lifted != null
    val item = lifted ?: last ?: return
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) progress.animateTo(1f, MenuPop)
        else {
            progress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            last = null
        }
    }
    val palette = EvannaDesign.colorScheme
    val dark = LocalLookStyle.current.look.dark
    val frost = frostOn()
    val rim = menuRim()
    val dismiss by rememberUpdatedState(onDismiss)
    Dialog({ if (open) dismiss() }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        val still = rememberWindowStill(frost, radius = 4)
        // The blur pushes the page back, so the dimming can be light; without it the dimming does all the work.
        val scrim = Color.Black.copy(alpha = when {
            still != null && dark -> 0.30f
            still != null -> 0.10f
            dark -> 0.62f
            else -> 0.36f
        })
        // The row lifts on the page colour it was drawn on, as an iOS cell does, so it looks exactly as it did in the list.
        val plate = palette.background
        val insets = WindowInsets.safeDrawing
        var overlayTopLeft by remember { mutableStateOf(Offset.Zero) }
        Layout(content = {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .drawBehind {
                    still?.let { drawStill(it, overlayTopLeft) }
                    drawRect(scrim)
                }
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = open) { dismiss() }
                .semantics { contentDescription = "Close menu" })
            Box(Modifier.shadow(20.dp, LiftCardShape, clip = false).clip(LiftCardShape).background(plate).border(rim, LiftCardShape)
                .then(if (onPicture != null) Modifier.clickable(enabled = open, onClick = onPicture) else Modifier)
                .padding(LiftPlatePad)) {
                Image(item.picture, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().clip(LiftPictureShape))
            }
            Surface(Modifier.pointerInput(Unit) {}, shape = MenuShape, color = palette.surfaceContainerHigh, border = rim,
                shadowElevation = 16.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
            }
        }, modifier = Modifier.fillMaxSize().onGloballyPositioned { coords ->
            coords.localToScreen(Offset.Zero).takeIf { it.isFinite }?.let { overlayTopLeft = it }
        }.then(if (open) Modifier else Modifier.blockTouches())) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val margin = 12.dp.roundToPx()
            val gap = 10.dp.roundToPx()
            val pad = LiftPlatePad.roundToPx()
            val top = insets.getTop(this) + 8.dp.roundToPx()
            val bottom = height - insets.getBottom(this) - 8.dp.roundToPx()
            val page = measurables[0].measure(Constraints.fixed(width, height))
            // The plate at the row's own size, so the picture on it is pixel for pixel the row; a full-width row is then
            // scaled down just enough to clear the screen edges.
            val itemBounds = item.bounds.translate(-overlayTopLeft)
            val plateW = (itemBounds.width.roundToInt() + pad * 2).coerceAtLeast(1)
            val plateH = (itemBounds.height.roundToInt() + pad * 2).coerceAtLeast(1)
            val card = measurables[1].measure(Constraints.fixed(plateW, plateH))
            val liftScale = ((width - margin * 2f) / plateW).coerceAtMost(1f)
            val shownW = plateW * liftScale
            val shownH = plateH * liftScale
            val menuWidth = LiftMenuWidth.roundToPx().coerceAtMost(width - margin * 2).coerceAtLeast(0)
            val menu = measurables[2].measure(Constraints(minWidth = menuWidth, maxWidth = menuWidth,
                maxHeight = (bottom - top - shownH.roundToInt() - gap).coerceAtLeast(0)))
            // The row stays where it was held when the menu fits below (or else above) it; only when neither fits does it
            // climb to make room.
            val shownLeft = (itemBounds.center.x - shownW / 2f)
                .coerceIn(margin.toFloat(), (width - margin - shownW).coerceAtLeast(margin.toFloat()))
            var shownTop = (itemBounds.center.y - shownH / 2f).coerceIn(top.toFloat(), (bottom - shownH).coerceAtLeast(top.toFloat()))
            val below = when {
                shownTop + shownH + gap + menu.height <= bottom -> true
                shownTop - gap - menu.height >= top -> false
                else -> { shownTop = (bottom - menu.height - gap - shownH).coerceAtLeast(top.toFloat()); true }
            }
            val centerX = shownLeft + shownW / 2f
            val centerY = shownTop + shownH / 2f
            // The menu hangs off the row's nearer edge.
            val alignEnd = centerX > width / 2f
            val menuX = (if (alignEnd) shownLeft + shownW - menuWidth else shownLeft).roundToInt()
                .coerceIn(margin, (width - margin - menuWidth).coerceAtLeast(margin))
            val menuY = if (below) (shownTop + shownH + gap).roundToInt() else (shownTop - gap - menu.height).roundToInt()
            val fromX = itemBounds.center.x - centerX
            val fromY = itemBounds.center.y - centerY
            layout(width, height) {
                page.place(0, 0)
                card.placeWithLayer((centerX - plateW / 2f).roundToInt(), (centerY - plateH / 2f).roundToInt()) {
                    val p = progress.value
                    scaleX = mix(1f, liftScale, p)
                    scaleY = scaleX
                    translationX = mix(fromX, 0f, p)
                    translationY = mix(fromY, 0f, p)
                    // Quick: it starts over the very row it copies, so only the plate and shadow are new.
                    alpha = (p * 3f).coerceIn(0f, 1f)
                }
                menu.placeWithLayer(menuX, menuY) {
                    val p = progress.value
                    alpha = ((p - 0.1f) / 0.9f).coerceIn(0f, 1f)
                    // Grows out of the corner nearest the row, and travels with it.
                    transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (below) 0f else 1f)
                    scaleX = mix(0.55f, 1f, p)
                    scaleY = scaleX
                    translationX = mix(fromX, 0f, p)
                    translationY = mix(fromY, 0f, p)
                }
            }
        }
    }
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

private val AlertShape = RoundedCornerShape(34.dp).continuous()
private val AlertTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold)
private val AlertMessage = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp)
private val AlertButtonGap = 8.dp

/**
 * An iOS 26 alert: a round, opaque panel with the title and message at the leading edge, over a light dim. The buttons
 * are capsules: two that fit sit side by side (Cancel leading); otherwise they stack with Cancel at the bottom.
 * The confirm button is accent-filled when it answers a Cancel ([prominent]), red-lettered when [destructive].
 * Several buttons may share the confirm slot, as a list of choices.
 */
@Composable
internal fun AlertDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null,
    containerColor: Color = EvannaDesign.colorScheme.surfaceContainerHigh,
    destructive: Boolean = false, prominent: Boolean = dismissButton != null && !destructive,
) {
    val palette = EvannaDesign.colorScheme
    val dark = LocalLookStyle.current.look.dark
    val rim = menuRim()
    val confirmRole = when {
        destructive -> AlertRole.DESTRUCTIVE
        prominent -> AlertRole.PROMINENT
        else -> AlertRole.PLAIN
    }
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        // iOS dims less than Android: the page stays visible around the alert.
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(if (dark) 0.5f else 0.3f) }
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 520f)) }
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            // iOS alerts grow in from slightly larger, not up from below.
            Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth().graphicsLayer {
                alpha = progress.value.coerceIn(0f, 1f)
                scaleX = 1.08f - 0.08f * progress.value
                scaleY = scaleX
            }, shape = AlertShape, color = containerColor, border = rim, shadowElevation = 24.dp) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 14.dp)) {
                    Column(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompositionLocalProvider(LocalContentColor provides palette.onSurface) {
                            ProvideTextStyle(AlertTitle, title)
                            Box(Modifier.weight(1f, fill = false)) { ProvideTextStyle(AlertMessage, text) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    AlertButtons(dismissButton, confirmButton, confirmRole)
                }
            }
        }
    }
}

@Composable
private fun AlertButtons(dismiss: (@Composable () -> Unit)?, confirm: @Composable () -> Unit, confirmRole: AlertRole) {
    Layout(contents = listOf(
        { CompositionLocalProvider(LocalAlertRole provides AlertRole.CANCEL) { dismiss?.invoke() } },
        { CompositionLocalProvider(LocalAlertRole provides confirmRole) { confirm() } },
    ), Modifier.fillMaxWidth()) { (cancel, actions), constraints ->
        val buttons = cancel + actions
        val width = constraints.maxWidth
        val gap = AlertButtonGap.roundToPx()
        if (buttons.isEmpty()) return@Layout layout(width, 0) {}
        val share = (width - gap * (buttons.size - 1)) / buttons.size
        if (buttons.size <= 2 && buttons.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }) {
            val placed = buttons.map { it.measure(Constraints.fixedWidth(share)) }
            val height = placed.maxOf { it.height }
            layout(width, height) { placed.forEachIndexed { i, p -> p.placeRelative(i * (share + gap), (height - p.height) / 2) } }
        } else {
            // Stacked, the chosen action is on top and Cancel at the bottom.
            val placed = (actions + cancel).map { it.measure(Constraints.fixedWidth(width)) }
            val height = placed.sumOf { it.height } + gap * (placed.size - 1)
            layout(width, height) {
                var y = 0
                for (p in placed) { p.placeRelative(0, y); y += p.height + gap }
            }
        }
    }
}

/** A frosted sheet's tint over its blurred still: thin enough for the colours under it to show, thick enough for text. */
internal const val SHEET_FROST_TINT = 0.62f

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
    val frost = frostOn()
    val rim = menuRim()
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
        // With glass on, the sheet is frosted glass: a blurred still of the page under it, a thinner tint and a lit rim.
        val still = rememberWindowStill(frost)
        var sheetTopLeft by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = progress.value }
                .background(scrimColor).clickable(onClick = close).semantics { contentDescription = "Dismiss ${title.lowercase()}" })
            Box(Modifier.align(Alignment.BottomCenter).statusBarsPadding().onGloballyPositioned {
                val center = it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f))
                sheetCenter = center.takeIf { it.isFinite }
                it.localToScreen(Offset.Zero).takeIf { at -> at.isFinite }?.let { at -> sheetTopLeft = at }
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
                    .semantics { paneTitle = title; dismiss { close(); true } })
                    // The still moves with the sheet while it opens or is dragged; it lines up again at rest.
                    .then(if (still == null) Modifier else Modifier.clip(shape).drawBehind { drawStill(still, sheetTopLeft) }),
                    shape = shape, color = if (still == null) containerColor else containerColor.copy(alpha = SHEET_FROST_TINT),
                    border = if (still == null) evannaHairline(0.6f) else rim) {
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
