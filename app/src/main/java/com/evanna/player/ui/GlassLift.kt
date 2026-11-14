package com.evanna.player.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.evanna.player.ui.glass.GlassTier
import com.evanna.player.ui.glass.LocalGlass
import com.evanna.player.ui.glass.rimColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * iOS 26's "lift into glass" (docs/ui-plan-liquid-glass.md, phase 3): while a control is held it turns into a piece of
 * clear glass, then settles back when let go. Drawn, not sampled, so it costs nothing at rest and works on every tier;
 * with Liquid glass off controls only sink, as before.
 */
@Composable
internal fun glassLiftOn(): Boolean = LocalGlass.current.tier != GlassTier.SOLID

/** A quick tap still shows this much of the lift before it settles. */
private const val MIN_LIFT_MS = 140L

/** 0 at rest, 1 while [interaction] is pressed; a tap lifts for at least [MIN_LIFT_MS]. */
@Composable
internal fun rememberLift(interaction: InteractionSource): State<Float> {
    val lift = remember(interaction) { Animatable(0f) }
    LaunchedEffect(interaction) {
        val presses = mutableListOf<PressInteraction.Press>()
        var pressedAt = 0L
        var settle: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    presses += event
                    pressedAt = SystemClock.uptimeMillis()
                    settle?.cancel()
                    launch { lift.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 650f)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    presses -= if (event is PressInteraction.Release) event.press else (event as PressInteraction.Cancel).press
                    if (presses.isEmpty()) settle = launch {
                        delay((MIN_LIFT_MS - (SystemClock.uptimeMillis() - pressedAt)).coerceAtLeast(0))
                        lift.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 320f))
                    }
                }
            }
        }
    }
    return lift.asState()
}

/**
 * Behind a plain icon or badge: a clear glass platter that grows out from under it while it is held. [outset] lets the
 * platter reach past the control's own bounds. Meant for controls over the dark Now Playing backdrop.
 */
@Composable
internal fun Modifier.liftIntoGlass(interaction: InteractionSource, shape: Shape, outset: Dp = 0.dp): Modifier {
    if (!glassLiftOn()) return this
    val lift = rememberLift(interaction)
    return drawBehind {
        val held = lift.value.coerceIn(0f, 1.2f)
        if (held <= 0.01f) return@drawBehind
        val grow = 0.82f + 0.18f * held
        val width = (size.width + 2 * outset.toPx()) * grow
        val height = (size.height + 2 * outset.toPx()) * grow
        val outline = shape.createOutline(Size(width, height), layoutDirection, this)
        val a = held.coerceAtMost(1f)
        translate((size.width - width) / 2, (size.height - height) / 2) {
            drawOutline(outline, Color.White.copy(alpha = 0.13f * a))
            // Light falls from above: a sheen on the upper half and a rim bright at the top, faint at the bottom.
            drawOutline(outline, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f * a), Color.Transparent), 0f, height * 0.6f))
            drawOutline(outline, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f * a), Color.White.copy(alpha = 0.08f * a)), 0f, height),
                style = Stroke(1.dp.toPx()))
        }
    }
}

/** A slider knob, 38 × 24 dp by default: white at rest; held, with glass on, it swells into clear glass over the track. */
@Composable
internal fun GlassThumb(lift: State<Float>, glass: Boolean, dark: Boolean, size: DpSize = DpSize(38.dp, 24.dp)) {
    Box(Modifier.graphicsLayer {
        val held = if (glass) lift.value else 0f
        scaleX = 1f + 0.3f * held
        scaleY = scaleX
        // A shadow under clear glass would show through it as a smudge.
        shadowElevation = 3.dp.toPx() * (1f - held.coerceIn(0f, 1f))
        shape = CircleShape
    }.size(size).drawBehind { drawGlassKnob(if (glass) lift.value else 0f, dark) })
}

/**
 * A control's knob (switch, slider): a white capsule at rest. As [held] goes to 1 the white clears to glass, so the track
 * shows through, and a rim and sheen appear. The caller grows it and drops its shadow.
 */
internal fun DrawScope.drawGlassKnob(held: Float, dark: Boolean, topLeft: Offset = Offset.Zero, knob: Size = size,
    fill: Color = Color.White,
) {
    val h = held.coerceIn(0f, 1f)
    val corner = CornerRadius(knob.minDimension / 2f)
    drawRoundRect(fill.copy(alpha = fill.alpha * (1f - 0.84f * h)), topLeft, knob, corner)
    if (h <= 0.01f) return
    // On light tracks the glass needs a darker lower rim to keep its edge.
    val (top, rim) = rimColors(dark)
    val bottom = if (dark) rim else Color.Black.copy(alpha = 0.18f)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.3f * h), Color.Transparent), topLeft.y, topLeft.y + knob.height * 0.6f),
        topLeft, knob, corner)
    drawRoundRect(Brush.verticalGradient(listOf(top.copy(alpha = top.alpha * h), bottom.copy(alpha = bottom.alpha * h)), topLeft.y, topLeft.y + knob.height),
        topLeft, knob, corner, style = Stroke(1.dp.toPx()))
}
