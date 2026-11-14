package com.evanna.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanna.player.EvannaDesign
import com.evanna.player.EvannaMotion
import com.evanna.player.LocalLookStyle
import kotlin.math.roundToInt

internal class ActionColors(val container: Color, val content: Color)

internal object ButtonDefaults {
    fun textButtonColors(contentColor: Color) = ActionColors(Color.Transparent, contentColor)
}

internal object IconButtonDefaults {
    fun iconButtonColors(contentColor: Color) = ActionColors(Color.Transparent, contentColor)
    fun filledIconButtonColors(containerColor: Color, contentColor: Color) = ActionColors(containerColor, contentColor)
}

/** How a button looks in an alert's button row: an iOS capsule. [NONE] everywhere else. */
internal enum class AlertRole { NONE, PROMINENT, PLAIN, CANCEL, DESTRUCTIVE }
internal val LocalAlertRole = staticCompositionLocalOf { AlertRole.NONE }
private val AlertButtonText = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp, textAlign = TextAlign.Center)

@Composable
private fun Action(
    onClick: () -> Unit, modifier: Modifier, enabled: Boolean, colors: ActionColors, outlined: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val role = LocalAlertRole.current
    if (role != AlertRole.NONE) return AlertAction(onClick, modifier, enabled, role, content)
    val palette = EvannaDesign.colorScheme
    val ink = if (enabled) colors.content else palette.onSurface.copy(alpha = 0.38f)
    // Outlined buttons are iOS bordered buttons: a capsule tinted with their text colour, no outline.
    val container = if (outlined) colors.content.copy(alpha = 0.14f) else colors.container
    val fill = if (enabled || container == Color.Transparent) container else palette.onSurface.copy(alpha = 0.10f)
    Row(
        // The click comes before the pill's shape and fill, so the whole pill sinks on press (PressIndication).
        modifier.heightIn(min = 48.dp).padding(vertical = 4.dp).widthIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).clip(CircleShape).background(fill)
            .padding(horizontal = if (outlined) 16.dp else 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            ProvideTextStyle(EvannaDesign.typography.labelLarge) { content() }
        }
    }
}

/** An iOS 26 alert button: a full-width capsule, gray, accent-filled when [AlertRole.PROMINENT], red text when destructive. */
@Composable
private fun AlertAction(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, role: AlertRole, content: @Composable RowScope.() -> Unit) {
    val palette = EvannaDesign.colorScheme
    val dark = LocalLookStyle.current.look.dark
    val fill = if (role == AlertRole.PROMINENT) palette.primary else AppleColors.strongFill(dark)
    val ink = when (role) {
        AlertRole.PROMINENT -> palette.onPrimary
        AlertRole.DESTRUCTIVE -> AppleColors.red(dark)
        else -> palette.onSurface
    }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).clip(CircleShape).background(fill)
        .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            // Plain choices read lighter than Cancel and the main action, as in iOS.
            ProvideTextStyle(AlertButtonText.copy(fontWeight = if (role == AlertRole.PLAIN) FontWeight.Normal else FontWeight.SemiBold)) {
                content()
            }
        }
    }
}

@Composable
internal fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ActionColors = ActionColors(EvannaDesign.colorScheme.primary, EvannaDesign.colorScheme.onPrimary),
    content: @Composable RowScope.() -> Unit,
) {
    Action(onClick, modifier, enabled, colors, content = content)
}

@Composable
internal fun FilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val colors = EvannaDesign.colorScheme
    Action(onClick, modifier, enabled, ActionColors(colors.secondaryContainer, colors.onSecondaryContainer), content = content)
}

@Composable
internal fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ActionColors = ButtonDefaults.textButtonColors(EvannaDesign.colorScheme.primary),
    content: @Composable RowScope.() -> Unit,
) {
    Action(onClick, modifier, enabled, colors, outlined = true, content = content)
}

@Composable
internal fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ActionColors = ButtonDefaults.textButtonColors(EvannaDesign.colorScheme.primary), content: @Composable RowScope.() -> Unit,
) = Action(onClick, modifier, enabled, colors, content = content)

@Composable
internal fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, EvannaMotion.press, label = "control press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun Modifier.controlFocus(interaction: MutableInteractionSource, shape: Shape): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val ink = EvannaDesign.colorScheme.onSurface
    return when {
        focused -> border(2.dp, ink, shape)
        hovered -> border(1.dp, ink.copy(alpha = 0.32f), shape)
        else -> this
    }
}

@Composable
internal fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ActionColors = IconButtonDefaults.iconButtonColors(LocalContentColor.current), content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape)
        .background(if (enabled) colors.container else Color.Transparent)
        .controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        val ink = if (enabled) colors.content else EvannaDesign.colorScheme.onSurface.copy(alpha = 0.38f)
        CompositionLocalProvider(LocalContentColor provides ink) {
            Box(Modifier.pressScale(interaction)) { content() }
        }
    }
}

@Composable
internal fun FilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ActionColors, content: @Composable () -> Unit,
) = IconButton(onClick, modifier, enabled, colors, content)

/** Apple's system colours where iOS uses them on controls: the switch green, the destructive red and the gray fills. */
internal object AppleColors {
    fun green(dark: Boolean) = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    fun red(dark: Boolean) = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    /** tertiarySystemFill: search fields and segmented control tracks. */
    fun fill(dark: Boolean) = Color(0xFF767680).copy(alpha = if (dark) 0.24f else 0.12f)
    /** secondarySystemFill: an off switch, an empty slider track. */
    fun strongFill(dark: Boolean) = Color(0xFF787880).copy(alpha = if (dark) 0.32f else 0.16f)
    fun separator(dark: Boolean) = if (dark) Color(0xFF545458).copy(alpha = 0.6f) else Color(0xFF3C3C43).copy(alpha = 0.29f)
}

/**
 * The iOS 26 switch: a 64 × 28 dp capsule, green when on, with a white oval knob that stretches while it is held.
 * The 48 dp touch area stays.
 */
@Composable
internal fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = LocalLookStyle.current.look.dark
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 520f), label = "switch")
    val stretch by animateFloatAsState(if (pressed && enabled) 1f else 0f, spring(stiffness = 700f), label = "switch hold")
    val on = AppleColors.green(dark)
    val off = AppleColors.strongFill(dark)
    Box(modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp)
        .toggleable(checked, interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp, 28.dp).graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .drawBehind { drawRoundRect(lerp(off, on, progress), cornerRadius = CornerRadius(size.height / 2f)) }) {
            Box(Modifier.offset { IntOffset((2.dp.toPx() + 22.dp.toPx() * progress).roundToInt(), 2.dp.roundToPx()) }
                .graphicsLayer {
                    // Held, the knob grows towards the middle from the side it rests on.
                    transformOrigin = TransformOrigin(progress, 0.5f)
                    scaleX = 1f + 0.18f * stretch
                }
                .size(38.dp, 24.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

internal const val SELECTION_PILL_ALPHA = 0.15f

internal fun Modifier.slidingSelectionPill(count: Int, position: () -> Float, color: () -> Color): Modifier = drawBehind {
    val width = size.width / count
    val index = position()
    val slot = if (layoutDirection == LayoutDirection.Ltr) index else count - 1 - index
    drawRoundRect(color(), topLeft = Offset(width * slot, 0f), size = Size(width, size.height),
        cornerRadius = CornerRadius(minOf(width, size.height) / 2f))
}

/**
 * The iOS 26 segmented control: a gray capsule track with a raised capsule on the choice (white in light, gray in dark)
 * and no separators. It draws 36 dp tall inside a 48 dp touch row.
 */
@Composable
internal fun SingleChoiceSegmentedButtonRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = EvannaDesign.colorScheme
    val dark = LocalLookStyle.current.look.dark
    val position by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.85f, stiffness = 500f), label = "segment selection")
    val track = AppleColors.fill(dark)
    val thumb = if (dark) Color(0xFF636366) else Color.White
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).height(IntrinsicSize.Min)
        .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
        .drawBehind {
            val inset = 6.dp.toPx()
            val height = size.height - 2 * inset
            drawRoundRect(track, Offset(0f, inset), Size(size.width, height), CornerRadius(height / 2f))
            if (selected !in options.indices) return@drawBehind
            val pad = 2.dp.toPx()
            val width = (size.width - 2 * pad) / options.size
            val slot = if (layoutDirection == LayoutDirection.Ltr) position else options.size - 1 - position
            val knob = Size(width, height - 2 * pad)
            val corner = CornerRadius(knob.height / 2f)
            val at = Offset(pad + width * slot, inset + pad)
            if (!dark) drawRoundRect(Color.Black.copy(alpha = 0.08f), at + Offset(0f, 1.dp.toPx()), knob, corner)
            drawRoundRect(thumb, at, knob, corner)
        }
        .selectableGroup()) {
        options.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).fillMaxHeight()
                .selectable(index == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(index) })
                .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(label, style = EvannaDesign.typography.labelLarge, fontSize = 14.sp,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium, color = colors.onSurface, maxLines = 1)
            }
        }
    }
}
