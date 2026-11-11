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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
    var travelPx = 0
    var miniCover by mutableStateOf<LayoutCoordinates?>(null)
    var fullCover by mutableStateOf<LayoutCoordinates?>(null)
    var fullCorner by mutableStateOf(10.dp)

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
    mini: Boolean = false, report: Boolean = true): Modifier =
    (if (report) onGloballyPositioned {
        if (mini) motion.miniCover = it else {
            motion.fullCover = it
            motion.fullCorner = corner
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
        Box(Modifier.matchParentSize().graphicsLayer { translationY = motion.offsetY }
            .onGloballyPositioned { sheet = it }.playerFocusScope(blocked = !ready)) { content() }
        if (flying) FlyingCover(motion, cover, sheet)
        // New touches wait for the animation; the pointer already pulling the sheet keeps its original target.
        if (!ready) Box(Modifier.matchParentSize().blockTouches())
    }
}

@Composable
private fun FlyingCover(motion: PlayerSheetMotion, cover: Bitmap?, sheet: LayoutCoordinates?) {
    Layout(content = {
        ArtworkImage(cover, Modifier.fillMaxSize().graphicsLayer {
            shape = RoundedCornerShape(lerp(8.dp, motion.fullCorner, motion.progress))
            clip = true
        }, RectangleShape)
    }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val from = coverBounds(motion.miniCover)
        // The artwork flies between the stationary endpoints while the chrome slides underneath it.
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
