package com.evanna.player

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Now Playing's moving backdrop (plan task U2): three blurred, saturated copies of the cover turning slowly, drawn on a
 * tiny buffer and stretched to fill the sheet. Android 11 has no RenderEffect, so the blur runs on the CPU. Turn times
 * and directions follow the UI spec; sizes and offsets follow the mockup. A cover-dependent scrim keeps white text
 * readable (DESIGN.md contrast floor).
 */
@Composable
internal fun PlayerBackdrop(cover: Bitmap?, lightLook: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    BoxWithConstraints(modifier) {
        val dpi = (LocalDensity.current.density * 160).roundToInt()
        val buffer = backdropBufferSize(constraints.maxWidth, constraints.maxHeight, dpi)
        val renderer = remember(cover, buffer, lightLook) { cover?.let { BackdropRenderer(it, buffer, lightLook) } }
        var outgoing by remember { mutableStateOf<ImageBitmap?>(null) }
        var fade by remember { mutableFloatStateOf(1f) }
        var fadeElapsedMs by remember(renderer) { mutableDoubleStateOf(0.0) }
        var phaseMs by remember(renderer) { mutableDoubleStateOf(0.0) }
        var frame by remember { mutableIntStateOf(0) }
        // A new cover fades in over the last frame of the old one.
        DisposableEffect(renderer) { onDispose { outgoing = renderer?.snapshot(); fade = 0f } }
        LaunchedEffect(renderer, durationScale) {
            if (renderer == null || durationScale == 0f) {
                outgoing = null
                return@LaunchedEffect
            }
            val powerScale = if (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) 2.1f else 1f
            val guard = BackdropFrameGuard()
            var previousFrame = SystemClock.uptimeMillis()
            // Frames stop by themselves while the sheet is closed, the app is in the background or the screen is off.
            while (true) {
                val now = withFrameMillis { SystemClock.uptimeMillis() }
                val elapsed = (now - previousFrame).toDouble() / durationScale
                previousFrame = now
                phaseMs += elapsed / powerScale
                if (outgoing != null) {
                    fadeElapsedMs += elapsed
                    fade = backdropFade(fadeElapsedMs.toLong())
                    if (fade >= 1f) outgoing = null
                }
                if (!guard.holding(now)) {
                    val began = SystemClock.currentThreadTimeMillis()  // CPU time, so other apps' work does not count
                    renderer.render(phaseMs.toLong())
                    guard.record(SystemClock.currentThreadTimeMillis() - began, now)
                    frame++
                }
                when {
                    outgoing != null -> Unit               // the cross-fade moves every frame
                    guard.holding(now) -> delay(1_000)     // a slow phone: hold still, then try again
                    else -> delay(BACKDROP_FRAME_MS - 8)   // about 24 fps with the frame wait
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            frame.let { }  // read so a new frame redraws
            val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
            if (renderer == null) drawRect(BACKDROP_PLACEHOLDER)
            else drawImage(renderer.image, IntOffset.Zero, buffer, IntOffset.Zero, target, filterQuality = FilterQuality.Low)
            outgoing?.let {
                drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset.Zero, target, alpha = 1f - fade, filterQuality = FilterQuality.Low)
            }
        }
    }
}

private val BACKDROP_PLACEHOLDER = Color(0xFF424242)
private const val BACKDROP_FRAME_MS = 42L  // about 24 fps

/** Size of the small buffer: 1.3x overscan, then 24x smaller on dense screens (420 dpi or more) and 16x otherwise. */
internal fun backdropBufferSize(widthPx: Int, heightPx: Int, densityDpi: Int): IntSize {
    val scale = if (densityDpi >= 420) 24f else 16f
    return IntSize(max(1, (widthPx * 1.3f / scale).roundToInt()), max(1, (heightPx * 1.3f / scale).roundToInt()))
}

/** One full turn takes [periodMs]; a negative [direction] turns anticlockwise. */
internal fun backdropAngle(elapsedMs: Long, periodMs: Long, direction: Int) = direction * 360f * ((elapsedMs % periodMs).toFloat() / periodMs)

/** Song-change cross-fade: one second, cubic-bezier(0, 0, 0.3, 1). */
private val BackdropFadeEasing = CubicBezierEasing(0f, 0f, 0.3f, 1f)
internal fun backdropFade(elapsedMs: Long) = BackdropFadeEasing.transform((elapsedMs / 1000f).coerceIn(0f, 1f))

/** After three frames in a row that took more than 15 ms of work, the backdrop holds still for five seconds, then tries again. */
internal class BackdropFrameGuard {
    private var slow = 0
    private var holdUntil = Long.MIN_VALUE
    fun holding(nowMs: Long) = nowMs < holdUntil
    fun record(workMs: Long, nowMs: Long) {
        slow = if (workMs > 15) slow + 1 else 0
        if (slow >= 3) { slow = 0; holdUntil = nowMs + 5_000 }
    }
}

/** Brightest the blurred backdrop may get: 64 % white text then still reaches 4.5:1. */
internal const val BACKDROP_MAX_LUMINANCE = 0.07

/** Black scrim strength, at least [base], that keeps [brightest] at or below [BACKDROP_MAX_LUMINANCE] after the white [lift]. */
internal fun backdropScrim(brightest: Int, base: Float, lift: Float): Float {
    var alpha = base
    while (alpha < 0.95f && relativeLuminance(scrimmedColor(brightest, alpha, lift)) > BACKDROP_MAX_LUMINANCE) alpha += 0.01f
    return alpha
}

/** [argb] under a black layer of [alpha], then a white layer of [lift]. */
internal fun scrimmedColor(argb: Int, alpha: Float, lift: Float): Int {
    fun channel(shift: Int): Int {
        val darkened = (argb shr shift and 255) * (1 - alpha)
        return (darkened * (1 - lift) + 255 * lift).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** Three box-blur passes (close to a Gaussian), in place on ARGB pixels; edges repeat their last pixel. */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val scratch = IntArray(pixels.size)
    repeat(3) {
        blurLine(pixels, scratch, width, height, radius, horizontal = true)
        blurLine(scratch, pixels, width, height, radius, horizontal = false)
    }
}

private fun blurLine(from: IntArray, to: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val span = 2 * radius + 1
    for (line in 0 until lines) {
        fun at(i: Int) = from[if (horizontal) line * width + i.coerceIn(0, length - 1) else i.coerceIn(0, length - 1) * width + line]
        var r = 0; var g = 0; var b = 0
        for (i in -radius..radius) { val p = at(i); r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255 }
        for (i in 0 until length) {
            to[if (horizontal) line * width + i else i * width + line] = (0xFF shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
            val drop = at(i - radius); val add = at(i + radius + 1)
            r += (add shr 16 and 255) - (drop shr 16 and 255); g += (add shr 8 and 255) - (drop shr 8 and 255); b += (add and 255) - (drop and 255)
        }
    }
}

/** Draws frames into a reused bitmap; nothing is allocated per frame. */
private class BackdropRenderer(cover: Bitmap, private val size: IntSize, lightLook: Boolean) {
    private val source: Bitmap
    private val target = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    private val canvas = AndroidCanvas(target)
    private val pixels = IntArray(size.width * size.height)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val lift = if (lightLook) 0.10f else 0.05f
    private val scrim: Float
    val image: ImageBitmap = target.asImageBitmap()

    init {
        // Saturate once per cover (x2.5), at 64 px: the blur removes all detail anyway.
        source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val saturate = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(2.5f) }) }
        AndroidCanvas(source).drawBitmap(cover, null, RectF(0f, 0f, 64f, 64f), saturate)
        val sample = IntArray(64 * 64).also { source.getPixels(it, 0, 64, 0, 0, 64, 64); boxBlur(it, 64, 64, 6) }
        scrim = backdropScrim(sample.maxBy(::relativeLuminance), base = if (lightLook) 0.30f else 0.50f, lift = lift)
        render(0)
    }

    fun render(elapsedMs: Long) {
        val w = size.width.toFloat(); val h = size.height.toFloat(); val side = w * 1.44f
        canvas.drawColor(0xFF424242.toInt())
        layer(0.50f * w, 0.154f * h + side / 2, side, backdropAngle(elapsedMs, 120_000, -1) - 24f)
        layer(0f, -0.256f * h + side / 2, side, backdropAngle(elapsedMs, 90_000, 1) + 38f)
        layer(0.389f * w, 0.538f * h + side / 2, side, backdropAngle(elapsedMs, 70_000, 1) - 70f)
        canvas.drawColor(android.graphics.Color.argb((scrim * 255).roundToInt(), 0, 0, 0))
        canvas.drawColor(android.graphics.Color.argb((lift * 255).roundToInt(), 255, 255, 255))
        target.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
        boxBlur(pixels, size.width, size.height, 6)
        target.setPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
    }

    private fun layer(cx: Float, cy: Float, side: Float, degrees: Float) {
        canvas.save()
        canvas.rotate(degrees, cx, cy)
        rect.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
        canvas.drawBitmap(source, null, rect, paint)
        canvas.restore()
    }

    fun snapshot(): ImageBitmap = target.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
}
