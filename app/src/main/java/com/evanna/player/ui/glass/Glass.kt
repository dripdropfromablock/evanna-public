package com.evanna.player.ui.glass

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.LocalLookStyle
import com.evanna.player.ui.Surface
import com.evanna.player.ui.glass.backdrop.backdrops.LayerBackdrop
import com.evanna.player.ui.glass.backdrop.drawBackdrop
import com.evanna.player.ui.glass.backdrop.effects.blur
import com.evanna.player.ui.glass.backdrop.effects.colorControls
import com.evanna.player.ui.glass.backdrop.effects.lens
import com.evanna.player.ui.glass.backdrop.highlight.Highlight
import com.evanna.player.ui.glass.backdrop.shadow.Shadow

/**
 * How much of Apple's Liquid Glass this phone and these settings can draw (docs/ui-plan-liquid-glass.md, phase 1).
 * Glass is only for the floating control layer (the dock, the mini player); lists and covers stay solid.
 */
internal enum class GlassTier {
    /** Glass off: filled controls with a hairline. */
    SOLID,
    /** No live blur (Android 11 and older, or Reduce transparency): a dense tinted fill with a light rim. */
    FROSTED,
    /** Android 12: blur, colour lift, rim and shadow, but no lens bend. */
    BLUR,
    /** Android 13 and newer: the lens bends what is behind the edge, as on iOS. */
    FULL,
}

/** UI_SETTINGS keys: glass on (default) or off, and Reduce transparency (default off). */
internal const val GLASS_PREFERENCE = "liquid-glass"
internal const val REDUCE_TRANSPARENCY_PREFERENCE = "reduce-transparency"

/** RenderEffect (blur) needs Android 12; AGSL shaders (the lens) need Android 13. */
internal fun glassTier(sdkInt: Int, enabled: Boolean, reduceTransparency: Boolean): GlassTier = when {
    !enabled -> GlassTier.SOLID
    reduceTransparency || sdkInt < Build.VERSION_CODES.S -> GlassTier.FROSTED
    sdkInt < Build.VERSION_CODES.TIRAMISU -> GlassTier.BLUR
    else -> GlassTier.FULL
}

/** Whether the app's content has to be recorded for glass to sample: only the live tiers read it. */
internal val GlassTier.samplesBackdrop get() = this == GlassTier.BLUR || this == GlassTier.FULL

/** The tier in force and, for the live tiers, the recorded app content the glass bends and blurs. */
@Immutable
internal class GlassContext(val tier: GlassTier, val backdrop: LayerBackdrop?, val reduceTransparency: Boolean = false,
    /** Increase contrast (phase 6): glass is drawn solid black or white with a clear edge, and nothing turns see-through. */
    val increaseContrast: Boolean = false)

/** UI_SETTINGS key for Increase contrast (default off). */
internal const val INCREASE_CONTRAST_PREFERENCE = "increase-contrast"

/** Increase contrast's solid fill: the page's own black or white, so controls read as cut out of it. */
internal fun contrastFill(dark: Boolean): Color = if (dark) Color.Black else Color.White

/** Increase contrast's edge: strong enough to see against the page and against the fill (3:1 for a control's edge). */
internal fun contrastEdge(dark: Boolean): Color = if (dark) Color(0xFFA0A0A0) else Color(0xFF6E6E6E)

internal val LocalGlass = staticCompositionLocalOf { GlassContext(GlassTier.SOLID, null) }

/** The fill a frosted surface uses: dense, because nothing behind it is blurred and text must not show through. */
internal fun frostColor(dark: Boolean, reduceTransparency: Boolean): Color =
    (if (dark) Color(0xFF1C1C1E) else Color(0xFFF7F7F7)).copy(alpha = if (reduceTransparency) 0.97f else 0.9f)

/** The tint laid over live glass, after the blur: a thin wash, so the page still shows through as colour. */
internal fun glassTint(dark: Boolean): Color = (if (dark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha = 0.4f)

/** The rim light along a frosted edge: bright at the top where the light falls, fading towards the bottom. */
internal fun rimColors(dark: Boolean): Pair<Color, Color> =
    if (dark) Color.White.copy(alpha = 0.30f) to Color.White.copy(alpha = 0.06f)
    else Color.White.copy(alpha = 0.95f) to Color.Black.copy(alpha = 0.08f)

// Values from BitChord's glass, measured there against Apple's frames.
private const val BACKDROP_SCALE = 0.33f    // the backdrop is recorded and processed at a third of the size
private const val BLUR_DP = 8f
private const val LENS_DP = 24f
private const val SATURATION = 1.5f
private const val FROST_BLUR_DP = 16f       // with no lens, the blur alone must break up the text behind
private const val FROST_BACKDROP_SCALE = 0.25f

/**
 * A floating control surface in the glass tier in force. [solidColor] and [border] are the old look, used for
 * [GlassTier.SOLID] so turning glass off changes nothing. [shape] must be corner-based: the lens reads its corners.
 */
@Composable
internal fun GlassSurface(modifier: Modifier, shape: CornerBasedShape, solidColor: Color, border: BorderStroke?,
    shadowElevation: Dp, content: @Composable () -> Unit,
) {
    val glass = LocalGlass.current
    val dark = LocalLookStyle.current.look.dark
    val backdrop = glass.backdrop
    when {
        glass.increaseContrast ->
            Surface(modifier, shape, contrastFill(dark), BorderStroke(1.dp, contrastEdge(dark)), shadowElevation, content)
        glass.tier == GlassTier.SOLID -> Surface(modifier, shape, solidColor, border, shadowElevation, content)
        !glass.tier.samplesBackdrop || backdrop == null ->
            Surface(modifier.glassRim(shape, dark), shape, frostColor(dark, glass.reduceTransparency), null, shadowElevation, content)
        else -> {
            val density = LocalDensity.current
            val full = glass.tier == GlassTier.FULL
            val scale = if (full) BACKDROP_SCALE else FROST_BACKDROP_SCALE
            val blurPx = with(density) { (if (full) BLUR_DP else FROST_BLUR_DP).dp.toPx() } * scale
            val lensPx = with(density) { LENS_DP.dp.toPx() } * scale
            val tint = glassTint(dark)
            Box(modifier.drawBackdrop(backdrop, shape = { shape },
                effects = {
                    colorControls(saturation = SATURATION)
                    blur(blurPx)
                    if (full) lens(refractionHeight = lensPx, refractionAmount = lensPx, depthEffect = true, chromaticAberration = true)
                },
                highlight = { Highlight.Default },
                shadow = { Shadow.Default },
                onDrawSurface = { drawRect(tint) },
                backdropScale = scale), propagateMinConstraints = true) { content() }
        }
    }
}

/** The frosted tier's stand-in for the glass highlight: a thin stroke, light at the top and faint at the bottom. */
@Composable
private fun Modifier.glassRim(shape: CornerBasedShape, dark: Boolean): Modifier {
    val (top, bottom) = remember(dark) { rimColors(dark) }
    return drawWithContent {
        drawContent()
        val width = 1.dp.toPx()
        drawOutline(shape.createOutline(size, layoutDirection, this), Brush.verticalGradient(listOf(top, bottom)),
            style = Stroke(width))
    }
}

/** How strong the page colour is at [t] (0 top, 1 bottom) of the floor under the floating dock: eased in, never a line. */
internal fun floorAlpha(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3 - 2 * x) * 0.94f
}

/** The floor under the floating dock: the page colour faded in from nothing, so lists don't run out from under the glass. */
internal fun floorBrush(page: Color): Brush =
    Brush.verticalGradient(colorStops = Array(13) { i -> (i / 12f).let { it to page.copy(alpha = floorAlpha(it)) } })
