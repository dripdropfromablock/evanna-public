package com.evanna.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.LocalLookStyle
import kotlin.math.abs
import kotlin.math.roundToInt

internal class SliderPosition(val value: Float, val valueRange: ClosedFloatingPointRange<Float>) {
    val fraction: Float get() = if (valueRange.endInclusive > valueRange.start)
        ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f) else 0f
}

@Composable
internal fun Slider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    thumb: (@Composable (SliderPosition) -> Unit)? = null, track: (@Composable (SliderPosition) -> Unit)? = null,
    onOpenOutput: ((Offset?) -> Unit)? = null,
) {
    val colors = EvannaDesign.colorScheme
    val empty = AppleColors.strongFill(LocalLookStyle.current.look.dark)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onValueChangeFinished)
    val openOutput by rememberUpdatedState(onOpenOutput)
    val hasOutputAction = onOpenOutput != null
    val width = valueRange.endInclusive - valueRange.start
    val active = enabled && width > 0f
    val position = SliderPosition(value.coerceIn(valueRange), valueRange)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun showOutput() {
        val origin = coordinates?.takeIf { it.isAttached }?.let {
            it.localToScreen(Offset(it.size.width / 2f, it.size.height / 2f))
        }?.takeIf { it.isFinite }
        openOutput?.invoke(origin)
    }
    var thumbWidth by remember { mutableIntStateOf(0) }
    fun commit(value: Float) {
        change(value.coerceIn(valueRange))
        finish?.invoke()
    }
    fun valueAt(x: Float, sliderWidth: Int): Float {
        val length = (sliderWidth - thumbWidth).coerceAtLeast(1)
        val fraction = ((x - thumbWidth / 2f) / length).coerceIn(0f, 1f)
        return valueRange.start + width * if (rtl) 1f - fraction else fraction
    }
    val dragGestures = if (!active) Modifier else Modifier.pointerInput(valueRange, rtl, interactionSource, hasOutputAction) {
        awaitEachGesture {
            // The tap detector consumes down, but a horizontal drag can still take over at touch slop.
            val down = awaitFirstDown(requireUnconsumed = !hasOutputAction)
            var drag: DragInteraction.Start? = null
            try {
                while (true) {
                    val touch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (touch.isConsumed) break
                    val travel = touch.position - down.position
                    if (!touch.pressed) {
                        if (drag != null || (!hasOutputAction && abs(travel.x) < viewConfiguration.touchSlop &&
                                abs(travel.y) < viewConfiguration.touchSlop)) {
                            touch.consume()
                            commit(valueAt(touch.position.x, size.width))
                            drag?.let { interactionSource.tryEmit(DragInteraction.Stop(it)) }
                            drag = null
                        }
                        break
                    }
                    if (drag == null) {
                        if (abs(travel.y) > viewConfiguration.touchSlop && abs(travel.y) > abs(travel.x)) break
                        if (abs(travel.x) < viewConfiguration.touchSlop) continue
                        drag = DragInteraction.Start().also { interactionSource.tryEmit(it) }
                    }
                    touch.consume()
                    change(valueAt(touch.position.x, size.width))
                }
            } finally {
                drag?.let { interactionSource.tryEmit(DragInteraction.Cancel(it)) }
            }
        }
    }
    val tapGestures = if (!hasOutputAction) Modifier else Modifier.pointerInput(valueRange, rtl, active) {
        detectTapGestures(
            onTap = if (active) ({ point -> commit(valueAt(point.x, size.width)) }) else null,
            onDoubleTap = { showOutput() },
            onLongPress = { showOutput() },
        )
    }
    Layout(content = {
        Box(Modifier.graphicsLayer { scaleX = if (rtl) -1f else 1f }) {
            // iOS 26: a 6 dp track filled with the accent, under a white oval knob with a soft shadow.
            if (track != null) track(position) else Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val rounding = CornerRadius(size.height / 2f)
                drawRoundRect(empty, cornerRadius = rounding)
                drawRoundRect(if (active) colors.primary else colors.onSurfaceVariant,
                    size = Size(size.width * position.fraction, size.height), cornerRadius = rounding)
            }
        }
        Box(Modifier.onSizeChanged { thumbWidth = it.width }) {
            if (thumb != null) thumb(position) else Box(Modifier.size(38.dp, 24.dp)
                .shadow(3.dp, CircleShape).background(Color.White, CircleShape))
        }
    }, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
        // A disabled default slider fades as one piece, as iOS does, so the track never shows through the knob.
        .then(if (!active && thumb == null && track == null) Modifier.graphicsLayer { alpha = 0.5f } else Modifier)
        .then(if (hasOutputAction) Modifier.onGloballyPositioned { coordinates = it } else Modifier)
        .then(dragGestures).then(tapGestures).semantics {
        if (active || !hasOutputAction) progressBarRangeInfo = ProgressBarRangeInfo(position.value, valueRange)
        if (onOpenOutput != null) {
            onClick(label = "Open audio output") { showOutput(); true }
            if (!active) role = Role.Button
        }
        if (active) setProgress { target ->
            val next = target.coerceIn(valueRange)
            if (next == position.value) false else { commit(next); true }
        } else if (!hasOutputAction) disabled()
    }.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (onOpenOutput != null && event.key in listOf(Key.Enter, Key.Spacebar, Key.DirectionCenter)) {
            showOutput()
            return@onKeyEvent true
        }
        if (!active) return@onKeyEvent false
        val step = width / 20f
        val target = when (event.key) {
            Key.DirectionLeft -> position.value + if (rtl) step else -step
            Key.DirectionRight -> position.value + if (rtl) -step else step
            Key.DirectionUp -> position.value + step
            Key.DirectionDown -> position.value - step
            Key.MoveHome -> valueRange.start
            Key.MoveEnd -> valueRange.endInclusive
            else -> return@onKeyEvent false
        }
        commit(target)
        true
    }.focusable(active || hasOutputAction, interactionSource)) { children, constraints ->
        val thumbPlaceable = children[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val travel = (constraints.maxWidth - thumbPlaceable.width).coerceAtLeast(0)
        val trackPlaceable = children[0].measure(constraints.copy(minWidth = travel, maxWidth = travel, minHeight = 0))
        val height = maxOf(constraints.minHeight, trackPlaceable.height, thumbPlaceable.height)
        layout(constraints.maxWidth, height) {
            trackPlaceable.placeRelative(thumbPlaceable.width / 2, (height - trackPlaceable.height) / 2)
            thumbPlaceable.placeRelative((travel * position.fraction).roundToInt(), (height - thumbPlaceable.height) / 2)
        }
    }
}

@Composable
internal fun LinearProgressIndicator(modifier: Modifier = Modifier) {
    val colors = EvannaDesign.colorScheme
    val movement = rememberInfiniteTransition(label = "loading")
    val fraction by movement.animateFloat(-0.3f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "loading segment")
    Canvas(modifier.height(4.dp).progressSemantics()) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(colors.surfaceContainerHighest, cornerRadius = radius)
        val start = fraction.coerceAtLeast(0f) * size.width
        val end = (fraction + 0.3f).coerceIn(0f, 1f) * size.width
        drawRoundRect(colors.primary, topLeft = Offset(start, 0f), size = Size(end - start, size.height), cornerRadius = radius)
    }
}
