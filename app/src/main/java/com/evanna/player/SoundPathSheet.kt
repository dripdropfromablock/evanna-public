package com.evanna.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanna.player.playback.SoundPath
import com.evanna.player.playback.SoundStop
import com.evanna.player.playback.StopKind
import com.evanna.player.playback.StopState
import com.evanna.player.ui.*
import kotlin.math.abs
import kotlin.math.pow

/** A lost-sound warning keeps one fixed amber in every look, so it never reads as the cover's accent. */
private val ProblemAmber = Color(0xFFF2B44C)
private val OnProblemAmber = Color(0xFF2B1D05)
private const val PULSE_PERIOD_MS = 3600f
private const val PULSE_TRAVEL_MS = 2800f

/**
 * The quality badge's sheet (mockup option A, docs/mockups/audio-quality.html): what happened to the sound in one line,
 * then each stop from the song file down to the listener on a line. While music plays a light runs down the line; it takes
 * the accent after a stop that changes the sound and flickers past lost sound. Tap a stop for the numbers behind it.
 */
@Composable
internal fun SoundPathSheet(path: SoundPath?, onOtherOutput: (() -> Unit)?, onClose: () -> Unit) {
    val colors = EvannaDesign.colorScheme
    OutputSheet(origin = null, onDismissRequest = onClose, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // Solid: Now Playing's words and controls must not show through the path's text.
        containerColor = colors.surfaceContainer, scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Box(Modifier.padding(vertical = 10.dp).size(36.dp, 4.dp)
                .background(colors.onSurfaceVariant.copy(alpha = 0.45f), CircleShape))
        }, title = "Sound path") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
            Text("Sound path", style = EvannaDesign.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            if (path == null) {
                Text("Connecting to playback…", style = EvannaDesign.typography.bodyMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
                return@Column
            }
            PlayingChip(path.playing, Modifier.padding(top = 14.dp))
            Text(path.verdict, style = EvannaDesign.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.3).sp),
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp).semantics { heading() })
            Text(path.why, style = EvannaDesign.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            PathLine(path, onOtherOutput)
        }
    }
}

@Composable
private fun PlayingChip(playing: Boolean, modifier: Modifier) {
    val colors = EvannaDesign.colorScheme
    Row(modifier.background(colors.onSurface.copy(alpha = 0.09f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(if (playing) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f), CircleShape))
        Text(if (playing) "Playing now" else "Not playing", style = EvannaDesign.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun PathLine(path: SoundPath, onOtherOutput: (() -> Unit)?) {
    val colors = EvannaDesign.colorScheme
    val stops = path.stops
    // Each disc's center, in the column's own coordinates; the line and the light are drawn between them.
    val centers = remember(stops.size) { mutableStateListOf<Float>().apply { repeat(stops.size) { add(Float.NaN) } } }
    var phase by remember { mutableFloatStateOf(-1f) }
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    LaunchedEffect(path.playing, durationScale) {
        phase = -1f
        if (!path.playing || durationScale == 0f) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            val ms = (now - start) / 1_000_000f / durationScale
            phase = (ms % PULSE_PERIOD_MS) / PULSE_TRAVEL_MS
        }
    }
    // The disc sits 12 dp down and is 40 dp tall.
    val discCenter = with(LocalDensity.current) { 32.dp.toPx() }
    val accent = colors.primary
    val rail = colors.onSurface.copy(alpha = 0.16f)
    val lightAt: () -> Float? = lightAt@{
        val t = phase
        if (t !in 0f..1f || centers.any { it.isNaN() }) return@lightAt null
        val eased = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
        centers.first() + (centers.last() - centers.first()) * eased
    }
    Column(Modifier.fillMaxWidth().drawBehind {
        if (centers.any { it.isNaN() }) return@drawBehind
        val x = 20.dp.toPx()
        var after = StopState.SAME
        for (i in 0 until stops.size - 1) {
            if (stops[i].state == StopState.CHANGED || stops[i].state == StopState.PROBLEM) after = stops[i].state
            val color = when (after) { StopState.CHANGED -> accent.copy(alpha = 0.55f); StopState.PROBLEM -> ProblemAmber.copy(alpha = 0.7f); else -> rail }
            drawLine(color, Offset(x, centers[i]), Offset(x, centers[i + 1]), 2.dp.toPx(), StrokeCap.Round,
                if (after == StopState.PROBLEM) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null)
        }
        val y = lightAt() ?: return@drawBehind
        val t = phase
        var light = Color.White
        var strength = minOf(1f, t * 8, (1 - t) * 8)
        stops.forEachIndexed { i, stop ->
            if (y > centers[i] + 4.dp.toPx() && stop.state == StopState.CHANGED) light = accent
            if (stop.state == StopState.PROBLEM && y > centers[i] + 4.dp.toPx()) {
                light = ProblemAmber
                if (y < centers[i] + 60.dp.toPx() && (t * 40).toInt() % 2 == 1) strength *= 0.25f
            }
        }
        val tail = 64.dp.toPx()
        drawLine(Brush.verticalGradient(listOf(Color.Transparent, light.copy(alpha = strength), Color.Transparent), y - tail, y + 8.dp.toPx()),
            Offset(x, y - tail), Offset(x, y + 8.dp.toPx()), 6.dp.toPx(), StrokeCap.Round)
    }) {
        stops.forEachIndexed { index, stop ->
            StopRow(stop, Modifier.onPlaced { centers[index] = it.positionInParent().y + discCenter },
                lift = {
                    val y = lightAt()
                    val center = centers.getOrNull(index)
                    if (y == null || center == null || center.isNaN()) 0f else (1f - abs(y - center) / (24.dp.toPx())).coerceIn(0f, 1f)
                },
                onOtherOutput = onOtherOutput.takeIf { path.offerOtherOutput && stop.kind == StopKind.USB })
        }
    }
}

@Composable
private fun StopRow(stop: SoundStop, modifier: Modifier, lift: androidx.compose.ui.unit.Density.() -> Float, onOtherOutput: (() -> Unit)?) {
    val colors = EvannaDesign.colorScheme
    var open by rememberSaveable(stop.kind) { mutableStateOf(false) }
    val canOpen = stop.details.isNotEmpty()
    val turn by animateFloatAsState(if (open) 180f else 0f, EvannaMotion.enter, label = "chevron")
    val (disc, ink) = when (stop.state) {
        StopState.CHANGED -> colors.primary to colors.onPrimary
        StopState.PROBLEM -> ProblemAmber to OnProblemAmber
        else -> colors.surfaceContainerHigh to colors.onSurface
    }
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.padding(top = 12.dp).size(40.dp).graphicsLayer {
            val l = lift()
            scaleX = 1f + 0.1f * l; scaleY = scaleX
        }.background(disc, CircleShape).then(if (stop.state == StopState.SAME || stop.state == StopState.END)
            Modifier.drawBehind { drawCircle(colors.onSurface.copy(alpha = if (stop.state == StopState.END) 0.16f else 0.28f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())) }
            else Modifier), contentAlignment = Alignment.Center) {
            Icon(stop.kind.icon(), contentDescription = null, tint = if (stop.state == StopState.END) colors.onSurfaceVariant else ink,
                modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .then(if (canOpen) Modifier.clickable(role = Role.Button, onClickLabel = if (open) "Hide details" else "Show details") { open = !open } else Modifier)
                .semantics { if (stop.state == StopState.CHANGED) stateDescription = "Changed" else if (stop.state == StopState.PROBLEM) stateDescription = "Problem" }
                .padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stop.title, style = EvannaDesign.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        when (stop.state) {
                            StopState.CHANGED -> Tag("Changed", colors.primary)
                            StopState.PROBLEM -> Tag("Problem", ProblemAmber)
                            else -> {}
                        }
                    }
                    Text(stop.value, style = EvannaDesign.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
                if (canOpen) Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp).graphicsLayer { rotationZ = turn })
            }
            if (onOtherOutput != null) Button(onClick = onOtherOutput, modifier = Modifier.padding(bottom = 8.dp),
                colors = ActionColors(ProblemAmber, OnProblemAmber)) { Text("Pick another output") }
            AnimatedVisibility(open, enter = fadeIn(EvannaMotion.enter) + expandVertically(), exit = fadeOut(EvannaMotion.exit) + shrinkVertically()) {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((key, value) in stop.details) Row {
                        Text(key, style = EvannaDesign.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.75f), modifier = Modifier.width(104.dp))
                        Text(value, style = EvannaDesign.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, style = EvannaDesign.typography.labelSmall, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
}

private fun StopKind.icon(): ImageVector = when (this) {
    StopKind.FILE -> PlayerIcons.SongFile
    StopKind.EVANNA -> PlayerIcons.Sliders
    StopKind.ANDROID -> PlayerIcons.Phone
    StopKind.USB -> PlayerIcons.Usb
    StopKind.DAC -> PlayerIcons.Dac
    StopKind.BLUETOOTH -> PlayerIcons.Bluetooth
    StopKind.SPEAKER -> PlayerIcons.VolumeUp
    StopKind.HEADPHONES -> PlayerIcons.Headphones
}
