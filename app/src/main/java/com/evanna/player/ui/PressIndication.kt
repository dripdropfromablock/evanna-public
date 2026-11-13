package com.evanna.player.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's press feedback for every clickable that has none of its own (EvannaTheme provides it as LocalIndication).
 * A pressed control sinks a little and springs back; there is no tinted box on press or hover, so nothing is left behind
 * after a tap. Keyboard focus still gets an outline, as [controlFocus] draws for the custom controls.
 */
internal object PressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressIndicationNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x5157
}

/** A tap shorter than this still shows the whole dip. */
private const val MIN_PRESS_MS = 90L

private class PressIndicationNode(private val source: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val depth = Animatable(0f)
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            var focuses = 0
            var pressedAt = 0L
            var settle: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses += interaction
                        pressedAt = SystemClock.uptimeMillis()
                        settle?.cancel()
                        launch { depth.animateTo(1f, EvannaMotion.press) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        presses -= if (interaction is PressInteraction.Release) interaction.press else (interaction as PressInteraction.Cancel).press
                        if (presses.isEmpty()) settle = launch {
                            delay((MIN_PRESS_MS - (SystemClock.uptimeMillis() - pressedAt)).coerceAtLeast(0))
                            depth.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
                        }
                    }
                    is FocusInteraction.Focus -> focuses++
                    is FocusInteraction.Unfocus -> focuses = (focuses - 1).coerceAtLeast(0)
                }
                if (focused != focuses > 0) { focused = focuses > 0; invalidateDraw() }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val dip = depth.value
        if (dip > 0f && size.width > 0f) {
            // About 6% smaller, but never more than 10 dp off a wide row's width.
            val shrink = minOf(size.width * 0.06f, 10.dp.toPx()) * dip
            val factor = 1f - shrink / size.width
            scale(factor, factor) { this@draw.drawContent() }
        } else drawContent()
        if (focused) {
            val inset = 1.dp.toPx()
            drawRoundRect(currentValueOf(LocalContentColor), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                CornerRadius(12.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
    }
}
