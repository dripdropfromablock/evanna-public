package com.evanna.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.evanna.player.boxBlur
import com.evanna.player.ui.glass.GlassTier
import com.evanna.player.ui.glass.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * A blurred still of the app window, taken once when a menu or sheet opens (BitChord's `PlayerFrost` lesson,
 * docs/ui-plan-liquid-glass.md phase 4). A still, not the live page: nothing behind re-blurs on every progress tick,
 * and it works on Android 11, where there is no live blur. [origin] is the window's top-left on screen.
 */
internal class WindowStill(val image: ImageBitmap, val windowSize: IntSize, val origin: IntOffset)

/** Frost needs glass on, and Reduce transparency and Increase contrast off. */
@Composable
internal fun frostOn(): Boolean = LocalGlass.current.let { it.tier != GlassTier.SOLID && !it.reduceTransparency && !it.increaseContrast }

/**
 * The still for an overlay that is up while [enabled]; null until it is taken, if it can't be, or when not [enabled].
 * The window is copied at 1/[scale] size and box-blurred, so it costs a few milliseconds and well under a megabyte.
 */
@Composable
internal fun rememberWindowStill(enabled: Boolean, scale: Int = 8, radius: Int = 3): WindowStill? {
    val view = LocalView.current
    var still by remember { mutableStateOf<WindowStill?>(null) }
    LaunchedEffect(enabled) {
        still = null
        if (!enabled) return@LaunchedEffect
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return@LaunchedEffect
        val bitmap = Bitmap.createBitmap((decor.width / scale).coerceAtLeast(1), (decor.height / scale).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888)
        val copied = suspendCancellableCoroutine { done ->
            try {
                PixelCopy.request(window, bitmap, { result -> if (done.isActive) done.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                done.resume(false)  // the window has no surface yet
            }
        }
        if (!copied) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            boxBlur(pixels, bitmap.width, bitmap.height, radius)
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val at = IntArray(2).also(decor::getLocationOnScreen)
        still = WindowStill(bitmap.asImageBitmap(), IntSize(decor.width, decor.height), IntOffset(at[0], at[1]))
    }
    return still
}

/** Draws [still] where the window was, for a drawing whose top-left is at [topLeft] on screen. */
internal fun DrawScope.drawStill(still: WindowStill, topLeft: Offset, alpha: Float = 1f) {
    drawImage(still.image, IntOffset.Zero, IntSize(still.image.width, still.image.height),
        IntOffset((still.origin.x - topLeft.x).roundToInt(), (still.origin.y - topLeft.y).roundToInt()), still.windowSize,
        alpha = alpha, filterQuality = FilterQuality.Low)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
