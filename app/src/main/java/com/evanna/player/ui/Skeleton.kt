package com.evanna.player.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.LocalLookStyle

/**
 * Gray placeholders the size of the real rows and covers (docs/ui-plan-liquid-glass.md, phase 6), shown while a list
 * loads instead of a spinner, so nothing jumps when the songs arrive. They breathe gently; the pulse only runs while
 * they are on screen.
 */
@Composable
private fun rememberPulse(): State<Float> = rememberInfiniteTransition(label = "skeleton").animateFloat(0.55f, 1f,
    infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "skeleton pulse")

/** One gray block; the pulse is read in the draw step, so breathing never recomposes. */
@Composable
private fun Modifier.placeholder(shape: Shape, pulse: State<Float>): Modifier {
    val fill = AppleColors.strongFill(LocalLookStyle.current.look.dark)
    return drawBehind { drawOutline(shape.createOutline(size, layoutDirection, this), fill.copy(alpha = fill.alpha * pulse.value)) }
}

private val CoverShape = RoundedCornerShape(8.dp).continuous()
private val TileShape = RoundedCornerShape(10.dp).continuous()
private val BarShape = RoundedCornerShape(4.dp)

/** A song or artist row: a [cover] square and three lines of text. */
@Composable
internal fun SkeletonRow(cover: Dp = 52.dp, modifier: Modifier = Modifier) {
    val pulse = rememberPulse()
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(cover).placeholder(CoverShape, pulse))
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(13.dp).placeholder(BarShape, pulse))
            Box(Modifier.fillMaxWidth(0.35f).height(11.dp).placeholder(BarShape, pulse))
            if (cover >= 52.dp) Box(Modifier.fillMaxWidth(0.45f).height(9.dp).placeholder(BarShape, pulse))
        }
    }
}

/** An album tile: a square cover with its title and artist under it. */
@Composable
internal fun SkeletonTile(modifier: Modifier = Modifier) {
    val pulse = rememberPulse()
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).placeholder(TileShape, pulse))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.7f).height(13.dp).placeholder(BarShape, pulse))
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.45f).height(11.dp).placeholder(BarShape, pulse))
    }
}
