package com.evanna.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanna.player.ui.LocalContentColor
import com.evanna.player.ui.ProvideTextStyle
import com.evanna.player.ui.SquirclePillShape
import com.evanna.player.ui.continuous

internal data class EvannaPalette(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondaryContainer: Color, val onSecondaryContainer: Color,
    val background: Color, val onBackground: Color, val surface: Color, val onSurface: Color,
    val onSurfaceVariant: Color, val surfaceContainerLow: Color, val surfaceContainer: Color,
    val surfaceContainerHigh: Color, val surfaceContainerHighest: Color, val outline: Color, val outlineVariant: Color,
    val error: Color,
)

internal class EvannaShapes(
    val extraSmall: Shape, val small: Shape, val medium: Shape, val large: Shape, val extraLarge: Shape,
)

internal data class EvannaTypography(
    val headlineMedium: TextStyle, val headlineSmall: TextStyle, val titleLarge: TextStyle,
    val titleMedium: TextStyle, val titleSmall: TextStyle, val bodyLarge: TextStyle, val bodyMedium: TextStyle,
    val bodySmall: TextStyle, val labelLarge: TextStyle, val labelMedium: TextStyle, val labelSmall: TextStyle,
)

/** Neutral palettes also serve as the bases for the artwork tints. */
internal val EvannaColors = EvannaPalette(
    primary = Color(0xFFDEDEDE), onPrimary = Color.Black,
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color(0xFFF0F0F0),
    secondaryContainer = Color(0xFF3A3A3A), onSecondaryContainer = Color(0xFFF0F0F0),
    background = Color(0xFF101010), onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF191919), onSurface = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFFC8C8C8), surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF202020), surfaceContainerHigh = Color(0xFF282828), surfaceContainerHighest = Color(0xFF363636),
    outline = Color(0xFF949494), outlineVariant = Color(0xFF515151), error = Color(0xFFF0F0F0),
)

internal val AppleDarkColors = EvannaPalette(
    primary = Color(0xFFDEDEDE), onPrimary = Color.Black,
    primaryContainer = Color(0xFF303030), onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFFDEDEDE), onSecondaryContainer = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    onSurfaceVariant = Color(0xFFC8C8C8), surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainer = Color(0xFF1C1C1C), surfaceContainerHigh = Color(0xFF2C2C2C), surfaceContainerHighest = Color(0xFF3A3A3A),
    outline = Color(0xFF8E8E8E), outlineVariant = Color(0xFF383838), error = Color.White,
)

internal val LightGroupedBackground = Color(0xFFF2F2F2)

internal val AppleLightColors = EvannaPalette(
    primary = Color(0xFF454545), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E7E7), onPrimaryContainer = Color.Black,
    secondaryContainer = Color(0xFF454545), onSecondaryContainer = Color.White,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF555555), surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = Color(0xFF8E8E8E), outlineVariant = Color(0xFFC6C6C6), error = Color.Black,
)

/**
 * Apple's corner radii from the spec: thumbnails 5 and 7, grouped cells 10, the mini player 14. Cut as Apple's continuous
 * corners (curvature eases into each edge) rather than circular arcs (docs/ui-plan-liquid-glass.md, phase 1).
 */
private val AppleShapes = EvannaShapes(
    extraSmall = RoundedCornerShape(5.dp).continuous(),
    small = RoundedCornerShape(7.dp).continuous(),
    medium = RoundedCornerShape(10.dp).continuous(),
    large = RoundedCornerShape(10.dp).continuous(),
    extraLarge = RoundedCornerShape(14.dp).continuous(),
)

/**
 * Dark (the default) and Light. Keep the stored IDs so upgrades preserve the choice. The old "Evanna dark" look was
 * removed; its stored ID now opens as Dark (see [storedLook]).
 */
internal enum class EvannaLook(val label: String, val apple: Boolean, val dark: Boolean) {
    APPLE_DARK("Dark", true, true), APPLE_LIGHT("Light", true, false);
    val colors: EvannaPalette get() = when (this) { APPLE_DARK -> AppleDarkColors; APPLE_LIGHT -> AppleLightColors }
}
/** SharedPreferences file for appearance choices; [LOOK_PREFERENCE] holds an [EvannaLook] name. */
internal const val UI_SETTINGS = "ui-settings"
internal const val LOOK_PREFERENCE = "look"
/** Unknown values, and "EVANNA" from before the Evanna dark look was removed, open as Dark. */
internal fun storedLook(value: Any?) = EvannaLook.entries.firstOrNull { it.name == value } ?: EvannaLook.APPLE_DARK

/**
 * What the looks draw differently: Evanna cards have a hairline, Apple cells are flat, Apple chips are capsules, and
 * grouped screens (Settings, Equalizer) use [groupedBackground] behind their cards.
 */
internal class LookStyle(val look: EvannaLook, val hairlineCards: Boolean, val chipShape: Shape?, val groupedBackground: Color)
internal val LocalLookStyle = staticCompositionLocalOf { LookStyle(EvannaLook.APPLE_DARK, hairlineCards = false, chipShape = null, AppleDarkColors.background) }

private val DefaultShapes = EvannaShapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Platform sans with deliberate weight and tracking: headlines tighten slightly and
 * titles lean semibold so hierarchy comes from the theme, not per-screen tweaks.
 * The device supplies the sans-serif font.
 */
private val BaseTypography = EvannaTypography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
)

private val DefaultTypography = BaseTypography.copy(
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp,
    ),
    titleMedium = BaseTypography.titleMedium.copy(letterSpacing = (-0.1).sp),
    bodyLarge = BaseTypography.bodyLarge.copy(letterSpacing = 0.sp),
)

/** Tabular numerals for everything that ticks: timecodes, dB readouts, rates, positions. */
internal object EvannaType {
    private const val TABULAR = "tnum"

    /** Elapsed/remaining timecodes and "N." queue/track positions. */
    val playbackTime = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp, fontFeatureSettings = TABULAR,
    )

    /** Hardware dB readouts and sample rates ("−14.5 dB", "192 kHz"). */
    val technical = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp, fontFeatureSettings = TABULAR,
    )
}

/**
 * Motion tokens: high-frequency feedback stays near-instant; screen changes get one
 * entrance that is faster to leave than to enter; ambient color washes stay slow and
 * calm. Compose scales all of these with MotionDurationScale when animations are off.
 */
internal object EvannaMotion {
    /** Presses, toggle dots, chip selection: no bounce, no delay. */
    val press: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
    val playerSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 200f)

    /** Pushed-screen and crossfade entrances. */
    val enter: TweenSpec<Float> = tween(durationMillis = 200, easing = FastOutSlowInEasing)

    /** Exits are faster than entrances. */
    val exit: TweenSpec<Float> = tween(durationMillis = 140, easing = FastOutSlowInEasing)

    /** Ambient color changes. */
    val ambient: TweenSpec<Color> = tween(durationMillis = 400, easing = FastOutSlowInEasing)
}

/** Shared spacing tokens (4/8 dp grid). Screens consume these, not magic numbers. */
internal object EvannaSpacing {
    val screen = 16.dp
    val card = 16.dp
    val rowGap = 8.dp
    val rowMinHeight = 56.dp
    val touchTarget = 48.dp
}

/** The one hairline border token: structure where scanning needs it, never decoration. */
@Composable
internal fun evannaHairline(alpha: Float = 0.24f) =
    BorderStroke(1.dp, EvannaDesign.colorScheme.outlineVariant.copy(alpha = alpha))

/** Apple's large titles as in the mockup: 32 sp bold. */
private val AppleTypography = BaseTypography.copy(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp,
    ),
)

private val LocalPalette = staticCompositionLocalOf { EvannaColors }
private val LocalTypography = staticCompositionLocalOf { DefaultTypography }
private val LocalShapes = staticCompositionLocalOf { DefaultShapes }

internal object EvannaDesign {
    val colorScheme: EvannaPalette @Composable get() = LocalPalette.current
    val typography: EvannaTypography @Composable get() = LocalTypography.current
    val shapes: EvannaShapes @Composable get() = LocalShapes.current
}

/** Now Playing sits on the dark cover backdrop in every look, so it always uses a dark scheme with the look's accent. */
internal fun playerColorScheme(look: EvannaLook, artwork: Color?) =
    (if (look.apple) AppleDarkColors else EvannaColors).withArtwork(
        artwork, dark = true, filledChips = look.apple, surfaceTint = 0.18f,
    )

@Composable
internal fun ProvideEvannaColors(colors: EvannaPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides colors, LocalContentColor provides colors.onSurface, content = content)
}

@Composable
internal fun EvannaTheme(look: EvannaLook = EvannaLook.APPLE_DARK, artwork: Color? = null, content: @Composable () -> Unit) {
    val colors = remember(look, artwork) { look.colors.withArtwork(artwork, look.dark, filledChips = look.apple) }
    val style = LookStyle(look, hairlineCards = !look.apple, chipShape = if (look.apple) SquirclePillShape() else null,
        groupedBackground = if (look == EvannaLook.APPLE_LIGHT) LightGroupedBackground else colors.background)
    val typography = if (look.apple) AppleTypography else DefaultTypography
    CompositionLocalProvider(LocalLookStyle provides style, LocalTypography provides typography,
        LocalShapes provides if (look.apple) AppleShapes else DefaultShapes,
        // Every clickable without its own feedback sinks on press instead of drawing a tinted box.
        androidx.compose.foundation.LocalIndication provides com.evanna.player.ui.PressIndication) {
        ProvideEvannaColors(colors) { ProvideTextStyle(typography.bodyLarge, content) }
    }
}
