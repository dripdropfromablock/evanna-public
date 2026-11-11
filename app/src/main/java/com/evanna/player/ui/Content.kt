package com.evanna.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

internal val LocalContentColor = compositionLocalOf { Color.Unspecified }
private val LocalTextStyle = compositionLocalOf { TextStyle.Default }

@Composable
internal fun ProvideTextStyle(style: TextStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(style), content = content)
}

@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    BasicText(
        text, modifier,
        style.merge(TextStyle(color = ink, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight, textAlign = textAlign)),
        overflow = overflow, maxLines = maxLines,
    )
}

@Composable
internal fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Image(imageVector, contentDescription, modifier.size(24.dp), colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint))
}
