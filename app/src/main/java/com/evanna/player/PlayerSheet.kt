package com.evanna.player

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.evanna.player.ui.MorphClip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.evanna.player.library.ArtworkImage
import kotlin.math.roundToInt

/** True for screens retained below another visible page; their Back handlers must not answer for that page. */
internal val LocalCovered = compositionLocalOf { false }

/** A Back handler for a screen that can sit under the Now Playing sheet. */
@Composable
internal fun ScreenBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled && !LocalCovered.current, onBack)

internal fun playerPullTarget(startedOpen: Boolean, progress: Float, velocityDp: Float): Boolean = when {
    velocityDp <= -400f -> true
    velocityDp >= 400f -> false
    startedOpen -> progress > 0.75f
    else -> progress >= 0.25f
}

@Stable
internal class PlayerSheetMotion(initiallyOpen: Boolean) {
    private val position = Animatable(if (initiallyOpen) 1f else 0f)
    private var dragPosition by mutableStateOf<Float?>(null)
    private var releaseVelocity = 0f
    private var flightTrack by mutableStateOf<String?>(null)
    var dragging by mutableStateOf(false)
        private set
    /** Counts finished pulls. A quick pull can start and end between two frames, so [dragging] alone may never look changed. */
    var releases by mutableIntStateOf(0)
        private set
    var travelPx = 0
    var miniCover by mutableStateOf<LayoutCoordinates?>(null)
    var fullCover by mutableStateOf<LayoutCoordinates?>(null)
    var fullCorner by mutableStateOf(10.dp)
    /** Now Playing shows the full cover, so the flying cover lands with its faded edge. */
    var fullFade by mutableStateOf(false)
    /** The mini player capsule. While it is on screen, Now Playing grows out of it instead of fading in place. */
    var miniBox by mutableStateOf<LayoutCoordinates?>(null)
    val morphs get() = miniBox?.isAttached == true

    val progress get() = (dragPosition ?: position.value).coerceIn(0f, 1f)
    val offsetY get() = travelPx * (1f - progress)

    fun beginPull(track: String?): Boolean {
        if (travelPx <= 0 || dragging) return false
        if (progress == 0f || progress == 1f) captureTrack(track)
        dragPosition = progress
        dragging = true
        return true
    }

    fun pull(deltaY: Float) {
        if (dragging && travelPx > 0) dragPosition = (progress - deltaY / travelPx).coerceIn(0f, 1f)
    }

    fun endPull(velocityY: Float = 0f) {
        if (!dragging) return
        releaseVelocity = if (travelPx > 0) -velocityY / travelPx else 0f
        dragging = false
        releases++
    }

    suspend fun settle(open: Boolean, track: String?) {
        val held = dragPosition
        if (held != null) {
            position.snapTo(held)
            dragPosition = null
        } else if (progress == 0f || progress == 1f) captureTrack(track)
        val velocity = releaseVelocity
        releaseVelocity = 0f
        position.animateTo(if (open) 1f else 0f, EvannaMotion.playerSpring, initialVelocity = velocity)
    }

    /** The cover flies between the mini player and Now Playing while the page grows or shrinks, by tap or by finger. */
    fun isFlying(track: String?): Boolean = track != null && track == flightTrack &&
        progress > 0f && progress < 1f && miniCover?.isAttached == true && fullCover?.isAttached == true

    private fun captureTrack(track: String?) {
        flightTrack = track.takeIf { miniCover?.isAttached == true }
    }
}

internal fun Modifier.playerPull(motion: PlayerSheetMotion, fromOpen: Boolean, track: String?, onOpenChange: (Boolean) -> Unit): Modifier =
    pointerInput(motion, fromOpen, track) {
        val tracker = VelocityTracker()
        var pulling = false
        fun cancelPull() {
            if (pulling) motion.endPull()
            pulling = false
        }
        try {
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onVerticalDrag = { change, delta ->
                    if (!pulling && (if (fromOpen) delta > 0f else delta < 0f)) pulling = motion.beginPull(track)
                    if (pulling) {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        motion.pull(delta)
                    }
                },
                onDragEnd = {
                    if (pulling && motion.dragging) {
                        val velocity = tracker.calculateVelocity().y
                        val open = playerPullTarget(fromOpen, motion.progress, velocity / density)
                        motion.endPull(velocity)
                        onOpenChange(open)
                    }
                    pulling = false
                },
                onDragCancel = ::cancelPull,
            )
        } finally {
            // A changed track can restart pointerInput before the detector receives a cancel event.
            cancelPull()
        }
    }

internal fun Modifier.dockingCover(motion: PlayerSheetMotion, flying: Boolean, corner: Dp = 5.dp,
    mini: Boolean = false, report: Boolean = true, fade: Boolean = false): Modifier =
    (if (report) onGloballyPositioned {
        if (mini) motion.miniCover = it else {
            motion.fullCover = it
            motion.fullCorner = corner
            motion.fullFade = fade
        }
    } else this).graphicsLayer { alpha = if (flying) 0f else 1f }

internal fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
}

internal fun Modifier.playerFocusScope(blocked: Boolean): Modifier =
    then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)
        .focusProperties { onEnter = { if (blocked) cancelFocusChange() } }.focusGroup()

@Composable
internal fun PlayerSheet(motion: PlayerSheetMotion, cover: Bitmap?, flying: Boolean, ready: Boolean,
    track: String?, onOpenChange: (Boolean) -> Unit, content: @Composable () -> Unit,
) {
    val currentTrack by rememberUpdatedState(track)
    val changeOpen by rememberUpdatedState(onOpenChange)
    val density = LocalDensity.current.density
    var sheet by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val scroll = remember(motion, density) {
        object : NestedScrollConnection {
            private var pulling = false

            // A list gets first use of the drag. Only the remainder at its top pulls the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                if (!pulling) pulling = motion.beginPull(currentTrack)
                if (!pulling) return Offset.Zero
                motion.pull(available.y)
                return Offset(0f, available.y)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!pulling || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                val consumed = available.y.coerceAtLeast(-motion.offsetY)
                motion.pull(consumed)
                return Offset(0f, consumed)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!pulling) return Velocity.Zero
                pulling = false
                val open = playerPullTarget(true, motion.progress, available.y / density)
                motion.endPull(available.y)
                changeOpen(open)
                return Velocity(0f, available.y)
            }

            fun cancel() { if (pulling) motion.endPull() }
        }
    }
    DisposableEffect(scroll) { onDispose(scroll::cancel) }
    Box(Modifier.fillMaxSize().nestedScroll(scroll).playerPull(motion, true, track, onOpenChange)) {
        // The page behind dims as Now Playing grows over it.
        if (motion.morphs) Box(Modifier.matchParentSize().graphicsLayer { alpha = motion.progress }.background(Color.Black.copy(alpha = 0.4f)))
        // Now Playing grows out of the mini player, as the quality badge grows into Sound path: only its edges move,
        // nothing slides up from the bottom. Without a mini player on screen it fades in place.
        Box(Modifier.matchParentSize().graphicsLayer {
            val p = motion.progress
            val from = if (p < 1f) coverBounds(motion.miniBox, sheet) else null
            clip = from != null
            if (from != null) this.shape = MorphClip(androidx.compose.ui.geometry.lerp(from, Rect(Offset.Zero, size), p),
                from.height / 2f + (32.dp.toPx() - from.height / 2f) * p)
        }.onGloballyPositioned { sheet = it }.playerFocusScope(blocked = !ready)) { content() }
        if (flying) FlyingCover(motion, cover, sheet)
        // New touches wait for the animation; the pointer already pulling the sheet keeps its original target.
        if (!ready) Box(Modifier.matchParentSize().blockTouches())
    }
}

/**
 * The full cover's edges: its lower part fades into the backdrop and a light shade sits under the status bar.
 * [strength] 0 draws the plain cover, 1 the full fade, so the flying cover can turn into the full cover as it lands.
 */
internal fun Modifier.coverFade(strength: () -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        val s = strength().coerceIn(0f, 1f)
        drawContent()
        if (s > 0f) {
            drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Black.copy(alpha = 1f - s)), blendMode = BlendMode.DstIn)
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f * s), 1f to Color.Transparent, endY = 120.dp.toPx()))
        }
    }

/**
 * The flight into the full cover. A box flying over the growing sheet looked wrong, so here the cover rides the sheet:
 * it leaves the mini player's artwork and soon spans the sheet's width at its top, kept to the cover's shape, clipped to
 * the sheet's rounded edge, and fades at the bottom as it goes. Its last frame is the full cover itself.
 */
@Composable
private fun FullCoverFlight(motion: PlayerSheetMotion, image: ImageBitmap, sheet: LayoutCoordinates?) {
    Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawBehind {
        val from = coverBounds(motion.miniCover, sheet) ?: return@drawBehind
        val to = coverBounds(motion.fullCover, sheet) ?: return@drawBehind
        val box = coverBounds(motion.miniBox, sheet)
        val p = motion.progress
        val whole = Rect(Offset.Zero, size)
        // The same edge the sheet grows with (see PlayerSheet).
        val page = box?.let { androidx.compose.ui.geometry.lerp(it, whole, p) } ?: whole
        val radius = box?.let { it.height / 2f + (32.dp.toPx() - it.height / 2f) * p } ?: 0f
        val scale = page.width / size.width
        val carried = Rect(page.left + to.left * scale, page.top + to.top * scale, page.left + to.right * scale, page.top + to.bottom * scale)
        // The cover reaches the sheet's top and width in the first 40% of the way, then rides it. Slower, it trailed
        // as a box in the sheet's lower corner with an empty band above it.
        val t = (p / 0.4f).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        val frame = androidx.compose.ui.geometry.lerp(from, carried, e)
        if (frame.width < 1f || frame.height < 1f) return@drawBehind
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val aspect = frame.width / frame.height
        // Centre crop, as the full cover's ContentScale.Crop does.
        val src = if (iw / ih > aspect) (ih * aspect).let { Rect((iw - it) / 2f, 0f, (iw + it) / 2f, ih) }
            else (iw / aspect).let { Rect(0f, (ih - it) / 2f, iw, (ih + it) / 2f) }
        clipPath(Path().apply { addRoundRect(RoundRect(page, CornerRadius(radius))) }) {
            clipPath(Path().apply { addRoundRect(RoundRect(frame, CornerRadius(8.dp.toPx() * (1f - e)))) }) {
                drawImage(image, IntOffset(src.left.roundToInt(), src.top.roundToInt()), IntSize(src.width.roundToInt(), src.height.roundToInt()),
                    IntOffset(frame.left.roundToInt(), frame.top.roundToInt()), IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                    filterQuality = FilterQuality.Medium)
                drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Black.copy(alpha = 1f - e), startY = frame.top, endY = frame.bottom),
                    frame.topLeft, frame.size, blendMode = BlendMode.DstIn)
                val shade = minOf(120.dp.toPx(), frame.height)
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f * e), 1f to Color.Transparent, startY = frame.top, endY = frame.top + shade),
                    frame.topLeft, Size(frame.width, shade))
            }
        }
    })
}

@Composable
private fun FlyingCover(motion: PlayerSheetMotion, cover: Bitmap?, sheet: LayoutCoordinates?) {
    val image = remember(cover) { cover?.asImageBitmap() }
    if (motion.fullFade && image != null) FullCoverFlight(motion, image, sheet)
    else Layout(content = {
        ArtworkImage(cover, Modifier.fillMaxSize().graphicsLayer {
            shape = RoundedCornerShape(lerp(8.dp, motion.fullCorner, motion.progress))
            clip = true
        }, RectangleShape)
    }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val from = coverBounds(motion.miniCover)
        // The artwork flies between stationary endpoints, including during an interactive pull.
        val to = coverBounds(motion.fullCover, sheet)
        if (from == null || to == null) return@Layout layout(constraints.maxWidth, constraints.maxHeight) {}
        val frame = androidx.compose.ui.geometry.lerp(from, to, motion.progress)
        val image = measurables.single().measure(Constraints.fixed(
            frame.width.roundToInt().coerceAtLeast(1), frame.height.roundToInt().coerceAtLeast(1)))
        layout(constraints.maxWidth, constraints.maxHeight) { image.place(frame.left.roundToInt(), frame.top.roundToInt()) }
    }
}

private fun coverBounds(coordinates: LayoutCoordinates?, relativeTo: LayoutCoordinates? = null): Rect? {
    val attached = coordinates?.takeIf { it.isAttached } ?: return null
    fun point(offset: Offset) = if (relativeTo?.isAttached == true) relativeTo.localPositionOf(attached, offset) else attached.localToRoot(offset)
    return Rect(point(Offset.Zero), point(Offset(attached.size.width.toFloat(), attached.size.height.toFloat())))
}
