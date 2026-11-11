package com.evanna.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.EvannaMotion

internal class ActionColors(val container: Color, val content: Color)

internal object ButtonDefaults {
    fun textButtonColors(contentColor: Color) = ActionColors(Color.Transparent, contentColor)
}

internal object IconButtonDefaults {
    fun iconButtonColors(contentColor: Color) = ActionColors(Color.Transparent, contentColor)
    fun filledIconButtonColors(containerColor: Color, contentColor: Color) = ActionColors(containerColor, contentColor)
}

@Composable
private fun Action(
    onClick: () -> Unit, modifier: Modifier, enabled: Boolean, colors: ActionColors, outlined: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = EvannaDesign.colorScheme
    val ink = if (enabled) colors.content else palette.onSurface.copy(alpha = 0.38f)
    val fill = if (enabled || colors.container == Color.Transparent) colors.container else palette.onSurface.copy(alpha = 0.10f)
    Row(
        modifier.heightIn(min = 48.dp).padding(vertical = 4.dp).widthIn(min = 56.dp).clip(CircleShape).background(fill)
            .then(if (outlined) Modifier.border(1.dp, if (enabled) palette.outline else palette.outlineVariant, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            ProvideTextStyle(EvannaDesign.typography.labelLarge) { content() }
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
internal fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ActionColors = IconButtonDefaults.iconButtonColors(LocalContentColor.current), content: @Composable () -> Unit,
) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape)
        .background(if (enabled) colors.container else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        val ink = if (enabled) colors.content else EvannaDesign.colorScheme.onSurface.copy(alpha = 0.38f)
        CompositionLocalProvider(LocalContentColor provides ink, content = content)
    }
}

@Composable
internal fun FilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ActionColors, content: @Composable () -> Unit,
) = IconButton(onClick, modifier, enabled, colors, content)

internal class ChipColors(val container: Color, val label: Color)
internal object FilterChipDefaults {
    fun filterChipColors(containerColor: Color, labelColor: Color) = ChipColors(containerColor, labelColor)
}

internal val LocalChipTouchHeight = staticCompositionLocalOf { 48.dp }

@Composable
internal fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, enabled: Boolean = true,
    shape: Shape = EvannaDesign.shapes.extraSmall, border: Boolean? = true, colors: ChipColors? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val palette = EvannaDesign.colorScheme
    val fill = if (selected) palette.secondaryContainer else colors?.container ?: Color.Transparent
    val ink = when {
        !enabled -> palette.onSurface.copy(alpha = 0.38f)
        selected -> palette.onSecondaryContainer
        else -> colors?.label ?: palette.onSurfaceVariant
    }
    Box(Modifier.heightIn(min = LocalChipTouchHeight.current)
        .selectable(selected, enabled = enabled, role = Role.Checkbox, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 32.dp).clip(shape).background(fill)
            .then(if (border == true && !selected) Modifier.border(1.dp, palette.outlineVariant, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides ink) {
                if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(6.dp)) }
                ProvideTextStyle(EvannaDesign.typography.labelLarge, label)
            }
        }
    }
}

@Composable
internal fun RadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = EvannaDesign.colorScheme
    val ink = when {
        !enabled -> palette.onSurface.copy(alpha = 0.38f)
        selected -> palette.primary
        else -> palette.onSurfaceVariant
    }
    Box(modifier.sizeIn(minWidth = if (onClick == null) 36.dp else 48.dp, minHeight = 48.dp)
        .then(if (onClick == null) Modifier else Modifier.clip(CircleShape).selectable(selected, enabled, Role.RadioButton, onClick = onClick)),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).border(2.dp, ink, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(ink, CircleShape))
        }
    }
}

@Composable
internal fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = EvannaDesign.colorScheme
    val fill by animateColorAsState(if (checked && enabled) palette.primary else palette.surfaceContainerHighest, label = "switch fill")
    val position by animateDpAsState(if (checked) 19.dp else 3.dp, label = "switch position")
    val thumb = if (!enabled) palette.onSurface.copy(alpha = 0.38f) else if (checked) palette.onPrimary else palette.onSurfaceVariant
    Box(modifier.sizeIn(minWidth = 52.dp, minHeight = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(46.dp, 30.dp).background(fill, CircleShape)) {
            Box(Modifier.align(Alignment.CenterStart).offset(x = position).size(24.dp).background(thumb, CircleShape))
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

@Composable
internal fun SingleChoiceSegmentedButtonRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = EvannaDesign.colorScheme
    val position by animateFloatAsState(selected.toFloat(), EvannaMotion.enter, label = "segment selection")
    Row(modifier.clip(CircleShape).background(colors.surfaceContainerHighest).padding(3.dp)
        .slidingSelectionPill(options.size, { position }) { colors.onSurface.copy(alpha = SELECTION_PILL_ALPHA) }
        .selectableGroup()) {
        options.forEachIndexed { index, label ->
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                .selectable(index == selected, role = Role.RadioButton, onClick = { onSelect(index) })
                .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, style = EvannaDesign.typography.labelLarge, color = colors.onSurface)
            }
        }
    }
}
