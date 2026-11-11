package com.evanna.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign

internal class FieldColors(val focused: Color, val unfocused: Color)

@Composable
internal fun fieldColors(focused: Color = EvannaDesign.colorScheme.surfaceContainerHigh,
    unfocused: Color = EvannaDesign.colorScheme.surfaceContainer) = FieldColors(focused, unfocused)

@Composable
internal fun TextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: String? = null, placeholder: String? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default, shape: Shape = EvannaDesign.shapes.small,
    colors: FieldColors = fieldColors(),
) = InputField(value, onValueChange, modifier, label, placeholder, leadingIcon, trailingIcon,
    keyboardOptions, keyboardActions, shape, colors, enabled = true, outlined = false, isError = false)

@Composable
internal fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, isError: Boolean = false,
) = InputField(value, onValueChange, modifier, label, null, null, null, KeyboardOptions.Default, KeyboardActions.Default,
    EvannaDesign.shapes.small, FieldColors(Color.Transparent, Color.Transparent), enabled, outlined = true, isError = isError)

@Composable
private fun InputField(value: String, onValueChange: (String) -> Unit, modifier: Modifier, label: String?, placeholder: String?,
    leadingIcon: (@Composable () -> Unit)?, trailingIcon: (@Composable () -> Unit)?, keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions, shape: Shape, colors: FieldColors, enabled: Boolean, outlined: Boolean, isError: Boolean,
) {
    val palette = EvannaDesign.colorScheme
    val type = EvannaDesign.typography
    var focused by remember { mutableStateOf(false) }
    val ink = if (enabled) palette.onSurface else palette.onSurface.copy(alpha = 0.38f)
    val labelInk = when {
        isError -> palette.error
        focused -> palette.primary
        else -> palette.onSurfaceVariant
    }
    val raised = label != null && (focused || value.isNotEmpty())
    val hint = if (raised) placeholder else label ?: placeholder
    val selection = remember(palette.primary) { TextSelectionColors(palette.primary, palette.primary.copy(alpha = 0.35f)) }
    CompositionLocalProvider(LocalTextSelectionColors provides selection, LocalContentColor provides palette.onSurfaceVariant) {
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            textStyle = type.bodyLarge.copy(color = ink), cursorBrush = SolidColor(palette.primary),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            modifier = modifier.onFocusChanged { focused = it.isFocused }.semantics {
                (label ?: placeholder)?.let { contentDescription = it }
                if (isError) error("Invalid value")
            },
            decorationBox = { input ->
                Row(Modifier.heightIn(min = 56.dp).clip(shape).background(if (focused) colors.focused else colors.unfocused)
                    .then(if (outlined) Modifier.border(if (focused) 2.dp else 1.dp, if (enabled) labelInk else palette.outlineVariant, shape) else Modifier)
                    .padding(start = if (leadingIcon == null) 14.dp else 12.dp, end = if (trailingIcon == null) 14.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    leadingIcon?.let { it(); Spacer(Modifier.width(12.dp)) }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (raised && label != null) Text(label, color = labelInk, style = type.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Box {
                            if (value.isEmpty() && hint != null) Text(hint, color = palette.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            input()
                        }
                    }
                    trailingIcon?.invoke()
                }
            })
    }
}
