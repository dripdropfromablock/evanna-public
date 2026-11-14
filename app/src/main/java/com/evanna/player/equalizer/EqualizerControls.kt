package com.evanna.player.equalizer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.evanna.player.EvannaLook
import com.evanna.player.EvannaMotion
import com.evanna.player.LocalLookStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The EQ screen's colors, read from the mockup's CSS (`mockups/looks.html`) for each look.
 * [fill] is the accent under text (Apple's darker red keeps white text at 4.5:1); [accent] draws lines, arcs and thumbs.
 */
internal class EqPalette(val card: Color, val chip: Color, val track: Color, val line: Color,
    val accent: Color, val fill: Color, val onFill: Color, val accentSoft: Color, val grid: Color, val zero: Color,
    val text: Color, val muted: Color, val tonal: Color, val onTonal: Color, val segment: Color, val whiteThumbs: Boolean)

@Composable
internal fun eqPalette(): EqPalette {
    val c = EvannaDesign.colorScheme
    return when (LocalLookStyle.current.look) {
        EvannaLook.APPLE_DARK -> EqPalette(card = c.surfaceContainer, chip = c.surfaceContainerHigh, track = c.surfaceContainerHighest,
            line = Color.Transparent, accent = c.primary, fill = c.secondaryContainer, onFill = c.onSecondaryContainer,
            accentSoft = c.primary.copy(alpha = 0.18f), grid = c.onSurface.copy(alpha = 0.08f), zero = c.onSurface.copy(alpha = 0.26f), text = c.onSurface, muted = c.onSurfaceVariant,
            tonal = c.secondaryContainer, onTonal = c.onSecondaryContainer, segment = c.surfaceContainerHigh, whiteThumbs = true)
        EvannaLook.APPLE_LIGHT -> EqPalette(card = c.surfaceContainer, chip = c.surfaceContainerHighest, track = Color(0xFFD1D1D1),
            line = Color.Transparent, accent = c.primary, fill = c.secondaryContainer, onFill = c.onSecondaryContainer,
            accentSoft = c.primary.copy(alpha = 0.12f), grid = c.onSurface.copy(alpha = 0.10f), zero = c.onSurface.copy(alpha = 0.30f), text = c.onSurface, muted = c.onSurfaceVariant,
            tonal = c.secondaryContainer, onTonal = c.onSecondaryContainer, segment = c.surfaceContainerHighest, whiteThumbs = true)
    }
}

/** The mockup's text sizes on the EQ screen. */
internal fun eqText(size: TextUnit, line: TextUnit, weight: FontWeight = FontWeight.Normal, tabular: Boolean = false) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size, lineHeight = line, fontFeatureSettings = if (tabular) "tnum" else null)

/** Signed decibels with one decimal and a real minus sign, as the knobs show them: +2.0, −7.8, and 0.0 for flat. */
internal fun eqSigned(db: Float): String =
    if ((db * 10).roundToInt() == 0) "0.0" else String.format(Locale.ROOT, "%+.1f", db).replace('-', '−')

/** The short form above a graphic slider: 0, +6, +0.5, −1.5. */
internal fun eqShort(db: Float): String {
    val tenths = (db * 10).roundToInt()
    if (tenths == 0) return "0"
    val whole = abs(tenths) / 10; val rest = abs(tenths) % 10
    return (if (tenths > 0) "+" else "−") + whole + (if (rest == 0) "" else ".$rest")
}

/** Where the log frequency slider sits for [hz] (0 at 20 Hz, 1 at 24 kHz), and back. */
internal fun frequencyPosition(hz: Float) = ((log10(hz.coerceIn(20f, 24000f)) - log10(20f)) / (log10(24000f) - log10(20f))).coerceIn(0f, 1f)
internal fun frequencyAt(position: Float) = 10f.pow(log10(20f) + position.coerceIn(0f, 1f) * (log10(24000f) - log10(20f))).roundToInt().toFloat().coerceIn(20f, 24000f)

/** Where the log Q slider sits for [q] (0 at 0.1, 1 at 12), and back with two decimals. */
internal fun qPosition(q: Float) = (ln(q.coerceIn(0.1f, 12f) / 0.1f) / ln(120f)).coerceIn(0f, 1f)
internal fun qAt(position: Float) = ((0.1f * exp(position.coerceIn(0f, 1f) * ln(120f))) * 100).roundToInt().div(100f).coerceIn(0.1f, 12f)

/** A gain from a drag: tenths of a decibel, with a small catch around 0 so flat is easy to hit. */
internal fun snapGain(db: Float, range: ClosedFloatingPointRange<Float> = -15f..15f): Float {
    val tenths = ((db * 10).roundToInt() / 10f).coerceIn(range)
    return if (abs(tenths) < 0.25f && 0f in range) 0f else tenths
}

@Composable
internal fun EqCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = eqPalette()
    Surface(modifier.fillMaxWidth(), shape = EvannaDesign.shapes.small, color = p.card) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

/** Graphic | Parametric: the app's iOS segmented control. */
@Composable
internal fun EqSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true) = SingleChoiceSegmentedButtonRow(options, selected, onSelect, modifier, enabled)

/** Band types: a filled capsule, accent-filled when selected. */
@Composable
internal fun EqChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = eqPalette()
    Row(Modifier.heightIn(min = 30.dp).clip(CircleShape).background(if (selected) p.fill else p.chip)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = eqText(13.sp, 18.sp, FontWeight.Medium), maxLines = 1, color = if (selected) p.onFill else p.text)
    }
}

/** The app's iOS switch, with a name for TalkBack. */
@Composable
internal fun AppleSwitch(checked: Boolean, enabled: Boolean, description: String, onChange: (Boolean) -> Unit) {
    Switch(checked, onChange, Modifier.semantics { contentDescription = description }, enabled)
}

private fun DrawScope.thumb(p: EqPalette, center: Offset) {
    if (p.whiteThumbs) {
        drawCircle(Color.Black.copy(alpha = 0.22f), 10.5.dp.toPx(), center + Offset(0f, 1.dp.toPx()))
        drawCircle(Color.White, 10.dp.toPx(), center)
    } else {
        val width = 16.dp.toPx()
        val height = 7.dp.toPx()
        drawRoundRect(p.accent, center - Offset(width / 2, height / 2), Size(width, height), CornerRadius(2.dp.toPx()))
        drawLine(p.onFill, center - Offset(3.dp.toPx(), 0f), center + Offset(3.dp.toPx(), 0f), 1.dp.toPx())
    }
}

/**
 * The response curve (the mockup's plot): ±15 dB on a log axis from 20 Hz to 24 kHz at 48 kHz, one point per 2 dp,
 * tinted down to 0 dB, with frequency labels under it. Each parametric band has a handle; the chosen one is a ring.
 * With the callbacks, a drag near a handle moves that band and a tap elsewhere adds one there.
 */
@Composable
internal fun EqCurve(config: SoundConfig, plotHeight: Dp, handles: List<Pair<Float, Float>> = emptyList(), selected: Int = -1,
    onSelect: ((Int) -> Unit)? = null, onHandle: ((Int, Float, Float) -> Unit)? = null, onAdd: ((Float, Float) -> Unit)? = null) {
    val p = eqPalette()
    val measurer = rememberTextMeasurer()
    val edge = eqText(10.sp, 12.sp).copy(color = p.muted)
    val axis = eqText(10.5.sp, 14.sp).copy(color = p.muted)
    val latestHandles by rememberUpdatedState(handles)
    val move by rememberUpdatedState(onHandle)
    val add by rememberUpdatedState(onAdd)
    val select by rememberUpdatedState(onSelect)
    // The axis labels get one line under the plot: 18 dp at the normal font size, more at large ones.
    val labels = plotHeight + 4.dp + with(LocalDensity.current) { 14.sp.toDp() }
    val l0 = log10(20f); val l1 = log10(24000f)
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = "EQ curve, plus and minus 15 dB" }) {
        val count = max(2, (maxWidth.value / 2f).toInt() + 1)
        val frequencies = remember(count) { DoubleArray(count) { 10.0.pow(log10(20.0) + (log10(24000.0) - log10(20.0)) * it / (count - 1)) } }
        val left = remember(config, count) { soundCurveDb(config, frequencies) }
        Canvas(Modifier.fillMaxWidth().height(labels)
            .pointerInput(onHandle != null) {
                if (onHandle == null) return@pointerInput
                val h = plotHeight.toPx()
                fun hz(px: Float) = 10f.pow(l0 + (px / size.width).coerceIn(0f, 1f) * (l1 - l0))
                fun db(py: Float) = ((h / 2 - py) * 30f / h).coerceIn(-15f, 15f)
                fun nearest(at: Offset): Int? = latestHandles.indices.minByOrNull { (handlePoint(latestHandles[it], size.width.toFloat(), h) - at).getDistance() }
                    ?.takeIf { (handlePoint(latestHandles[it], size.width.toFloat(), h) - at).getDistance() <= 28.dp.toPx() }
                var dragging: Int? = null
                detectDragGestures(onDragStart = { at -> dragging = nearest(at)?.also { select?.invoke(it) } },
                    onDragEnd = { dragging = null }, onDragCancel = { dragging = null }) { change, _ ->
                    val i = dragging ?: return@detectDragGestures
                    change.consume(); move?.invoke(i, hz(change.position.x), db(change.position.y))
                }
            }
            .pointerInput(onAdd != null) {
                if (onAdd == null) return@pointerInput
                val h = plotHeight.toPx()
                detectTapGestures { at ->
                    if (at.y > h) return@detectTapGestures
                    val hit = latestHandles.indexOfFirst { (handlePoint(it, size.width.toFloat(), h) - at).getDistance() <= 28.dp.toPx() }
                    if (hit >= 0) select?.invoke(hit)
                    else add?.invoke(10f.pow(l0 + (at.x / size.width).coerceIn(0f, 1f) * (l1 - l0)), ((h / 2 - at.y) * 30f / h).coerceIn(-15f, 15f))
                }
            }) {
            val w = size.width; val h = plotHeight.toPx(); val hair = 1.dp.toPx()
            fun x(f: Float) = (log10(f) - l0) / (l1 - l0) * w
            fun y(db: Float) = h / 2 - db * (h / 30f)
            for (db in listOf(-10f, -5f, 5f, 10f)) drawLine(p.grid, Offset(0f, y(db)), Offset(w, y(db)), hair)
            for (f in listOf(100f, 1000f, 10000f)) drawLine(p.grid, Offset(x(f), 0f), Offset(x(f), h), hair)
            drawLine(p.zero, Offset(0f, y(0f)), Offset(w, y(0f)), hair)
            fun path(values: DoubleArray) = Path().apply {
                values.forEachIndexed { i, v ->
                    val px = w * i / (values.size - 1); val py = y(v.toFloat().coerceIn(-15.5f, 15.5f))
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            val curve = path(left)
            drawPath(Path().apply { addPath(curve); lineTo(w, y(0f)); lineTo(0f, y(0f)); close() }, p.accentSoft)
            drawPath(curve, p.accent, style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round))
            handles.forEachIndexed { i, (f, db) ->
                val c = Offset(x(f.coerceIn(20f, 24000f)), y(db.coerceIn(-15f, 15f)))
                if (i == selected) {
                    drawCircle(p.card, 8.dp.toPx(), c); drawCircle(p.accent, 8.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                } else drawCircle(p.text.copy(alpha = 0.75f), 4.5.dp.toPx(), c)
            }
            val top = measurer.measure("+15", edge); val bottom = measurer.measure("−15", edge)
            drawText(top, topLeft = Offset(w - 2.dp.toPx() - top.size.width, 11.dp.toPx() - top.firstBaseline))
            drawText(bottom, topLeft = Offset(w - 2.dp.toPx() - bottom.size.width, h - 3.dp.toPx() - bottom.firstBaseline))
            // At large font sizes "10k" would run into "20k": a label that would touch the one before it is left out.
            var shownEnd = Float.NEGATIVE_INFINITY
            for ((f, label) in listOf(20f to "20", 100f to "100", 1000f to "1k", 10000f to "10k", 20000f to "20k")) {
                val t = measurer.measure(label, axis)
                val start = x(f).coerceIn(6.dp.toPx(), w - 8.dp.toPx()) - t.size.width / 2f
                if (start < shownEnd + 4.dp.toPx()) continue
                drawText(t, topLeft = Offset(start, h + 4.dp.toPx()))
                shownEnd = start + t.size.width
            }
        }
    }
}

/** Where a (frequency, gain) handle sits on a plot [width] wide and [height] tall (20 Hz to 24 kHz, ±15 dB). */
private fun handlePoint(handle: Pair<Float, Float>, width: Float, height: Float): Offset {
    val l0 = log10(20f); val l1 = log10(24000f)
    return Offset((log10(handle.first.coerceIn(20f, 24000f)) - l0) / (l1 - l0) * width, height / 2 - handle.second.coerceIn(-15f, 15f) * (height / 30f))
}

/** One graphic band: its gain above, a 116 dp track from −15 to +15 dB filled from 0, and its frequency below. Drag up or down. */
@Composable
internal fun BandSlider(gain: Float, label: String, description: String, enabled: Boolean, onGain: (Float) -> Unit) {
    val p = eqPalette()
    val latest by rememberUpdatedState(onGain)
    val small = eqText(10.5.sp, 14.sp, FontWeight.Medium, tabular = true)
    Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(horizontal = 1.dp)) { FitText(eqShort(gain), small, p.muted) }
        Canvas(Modifier.padding(vertical = 6.dp).width(28.dp).height(116.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun at(y: Float) = snapGain(15f - (y / size.height).coerceIn(0f, 1f) * 30f)
                detectVerticalDragGestures(onDragStart = { latest(at(it.y)) }) { change, _ -> change.consume(); latest(at(change.position.y)) }
            }
            .semantics {
                contentDescription = description; stateDescription = "${eqSigned(gain)} dB"
                progressBarRangeInfo = ProgressBarRangeInfo(gain.coerceIn(-15f, 15f), -15f..15f, steps = 59)
                if (enabled) setProgress { target -> latest(snapGain(target)); true } else disabled()
            }) {
            val cx = size.width / 2; val track = 4.dp.toPx(); val h = size.height
            val value = (15f - gain.coerceIn(-15f, 15f)) / 30f * h; val zero = h / 2
            drawRoundRect(p.track, Offset(cx - track / 2, 0f), Size(track, h), CornerRadius(track / 2))
            drawLine(p.zero, Offset(cx - 8.dp.toPx(), zero), Offset(cx + 8.dp.toPx(), zero), 1.dp.toPx())
            drawRoundRect(p.accent, Offset(cx - track / 2, min(value, zero)), Size(track, abs(value - zero)), CornerRadius(track / 2))
            thumb(p, Offset(cx, value))
        }
        Box(Modifier.padding(horizontal = 1.dp)) { FitText(label, small, p.muted) }
    }
}

/** One line in [style] that shrinks (down to 6 sp) only where large font sizes would cut it off, as in the 28 dp slider columns. */
@Composable
internal fun FitText(text: String, style: TextStyle, color: Color) {
    // The line height shrinks with the text (em), so a short box such as a 24 dp band chip does not force the smallest size.
    val line = if (style.lineHeight.isSp && style.fontSize.isSp) (style.lineHeight.value / style.fontSize.value).em else style.lineHeight
    BasicText(text, style = style.copy(color = color, lineHeight = line), maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = style.fontSize, stepSize = 0.25.sp))
}

/**
 * A 56 dp knob (Bass, Treble, Preamp): a 270° track with the value's arc and its number inside. Drag up to raise it.
 * [fraction] places the arc; [text] is what the knob shows, which can differ from [value] (the preamp shows what plays).
 */
@Composable
internal fun EqKnob(value: Float, range: ClosedFloatingPointRange<Float>, fraction: Float, text: String, label: String, description: String,
    enabled: Boolean, unit: String = " dB", onValue: (Float) -> Unit) {
    val p = eqPalette()
    val latest by rememberUpdatedState(onValue)
    val current by rememberUpdatedState(value)
    val measurer = rememberTextMeasurer()
    val inside = eqText(12.sp, 16.sp, FontWeight.SemiBold, tabular = true).copy(color = p.text)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.size(56.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var held = 0f
                val perPx = (range.endInclusive - range.start) / 150.dp.toPx()
                detectVerticalDragGestures(onDragStart = { held = current }) { change, dy ->
                    change.consume(); held = (held - dy * perPx).coerceIn(range); latest(snapGain(held, range))
                }
            }
            .semantics {
                contentDescription = description; stateDescription = "$text$unit"
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range, steps = ((range.endInclusive - range.start) * 2).roundToInt() - 1)
                if (enabled) setProgress { target -> latest(snapGain(target, range)); true } else disabled()
            }) {
            val stroke = 5.dp.toPx(); val inset = 5.dp.toPx(); val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(p.track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0.002f) drawArc(p.accent, 135f, 270f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc,
                style = Stroke(stroke, cap = StrokeCap.Round))
            val t = measurer.measure(text, inside)
            drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, (size.height - t.size.height) / 2f))
        }
        Text(label, style = eqText(12.sp, 16.sp), color = p.muted, maxLines = 1)
    }
}

/**
 * A band editor slider (Frequency, Gain, Q): the name and value above a 4 dp track. [position] runs 0..1 along the track;
 * [centered] fills from the middle (gain). TalkBack hears [valueText].
 */
@Composable
internal fun EqHSlider(name: String, valueText: String, position: Float, centered: Boolean, description: String, enabled: Boolean,
    onPosition: (Float) -> Unit) {
    val p = eqPalette()
    Column(Modifier.fillMaxWidth().padding(top = 11.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(name, style = eqText(13.sp, 18.sp), color = p.text, modifier = Modifier.weight(1f))
            Text(valueText, style = eqText(13.sp, 18.sp, FontWeight.Medium, tabular = true), color = p.text)
        }
        // The track sits 9 dp under the row, as drawn; the slider's 48 dp touch area reaches over its neighbours.
        // Held, the track thickens and the knob lifts into glass, as the other sliders do.
        val interaction = remember { MutableInteractionSource() }
        val lift = rememberLift(interaction)
        val glass = glassLiftOn()
        val dark = LocalLookStyle.current.look.dark
        val trackHeight by animateDpAsState(if (lift.value > 0.5f && enabled) 6.dp else 4.dp, label = "EQ track height")
        Box(Modifier.fillMaxWidth().height(13.dp)) {
            Slider(value = position.coerceIn(0f, 1f), onValueChange = onPosition, enabled = enabled, interactionSource = interaction,
                thumb = { GlassThumb(lift, glass && enabled, dark, DpSize(20.dp, 20.dp)) },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
                        val f = state.value.coerceIn(0f, 1f); val r = CornerRadius(size.height / 2f)
                        drawRoundRect(p.track, cornerRadius = r)
                        val from = if (centered) min(f, 0.5f) else 0f; val to = if (centered) max(f, 0.5f) else f
                        drawRoundRect(p.accent, Offset(size.width * from, 0f), Size(size.width * (to - from), size.height), r)
                        if (centered) drawLine(p.zero, Offset(size.width / 2, -4.dp.toPx()), Offset(size.width / 2, size.height + 4.dp.toPx()), 1.dp.toPx())
                    }
                },
                modifier = Modifier.fillMaxWidth().requiredHeight(48.dp).offset(y = 4.5.dp)
                    .semantics { contentDescription = description; stateDescription = valueText })
        }
    }
}

/** The compact numbered band chip; the chosen band is filled with the accent. */
@Composable
internal fun BandChip(label: String, selected: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val p = eqPalette()
    Box(Modifier.size(width = 22.dp, height = 24.dp).clip(CircleShape).background(if (selected) p.fill else p.chip)
        .selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        FitText(label, eqText(11.5.sp, 14.sp, FontWeight.SemiBold, tabular = true), if (selected) p.onFill else p.text)
    }
}

/** The dashed "+" chip that adds a band. */
@Composable
internal fun AddBandChip(enabled: Boolean, onClick: () -> Unit) {
    val p = eqPalette()
    Box(Modifier.size(width = 22.dp, height = 24.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(CircleShape)
        .drawBehind {
            drawCircle(p.muted, size.minDimension / 2 - 0.5.dp.toPx(),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
        }
        .selectable(false, enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = "Add band" },
        contentAlignment = Alignment.Center) {
        Text("+", style = eqText(11.5.sp, 14.sp, FontWeight.SemiBold), color = p.muted)
    }
}
