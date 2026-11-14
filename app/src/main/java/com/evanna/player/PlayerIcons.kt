package com.evanna.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Player icons that material-icons-core lacks. Plain shapes drawn here; Icon tints them. */
internal object PlayerIcons {
    private val ink = SolidColor(Color.Black)
    private fun icon(name: String, build: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()
    private fun ImageVector.Builder.line(block: PathBuilder.() -> Unit) = path(stroke = ink, strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    private fun ImageVector.Builder.speaker() = path(fill = ink) {
        moveTo(3f, 9f); horizontalLineTo(7f); lineTo(12f, 5f); verticalLineTo(19f); lineTo(7f, 15f); horizontalLineTo(3f); close()
    }
    private fun ImageVector.Builder.loop() {
        line { moveTo(4f, 12f); verticalLineTo(9f); quadTo(4f, 7f, 6f, 7f); horizontalLineTo(19f) }
        line { moveTo(16f, 4f); lineTo(19f, 7f); lineTo(16f, 10f) }
        line { moveTo(20f, 12f); verticalLineTo(15f); quadTo(20f, 17f, 18f, 17f); horizontalLineTo(5f) }
        line { moveTo(8f, 14f); lineTo(5f, 17f); lineTo(8f, 20f) }
    }

    val Pause = icon("Pause") {
        path(fill = ink) {
            moveTo(6f, 5f); horizontalLineTo(10f); verticalLineTo(19f); horizontalLineTo(6f); close()
            moveTo(14f, 5f); horizontalLineTo(18f); verticalLineTo(19f); horizontalLineTo(14f); close()
        }
    }
    val Next = icon("Next") {
        path(fill = ink) {
            moveTo(3f, 6f); lineTo(13f, 12f); lineTo(3f, 18f); close()
            moveTo(12f, 6f); lineTo(22f, 12f); lineTo(12f, 18f); close()
        }
    }
    val Previous = icon("Previous") {
        path(fill = ink) {
            moveTo(21f, 6f); lineTo(11f, 12f); lineTo(21f, 18f); close()
            moveTo(12f, 6f); lineTo(2f, 12f); lineTo(12f, 18f); close()
        }
    }
    val Phone = icon("Phone") {
        line {
            moveTo(8f, 3f); horizontalLineTo(16f); quadTo(18f, 3f, 18f, 5f); verticalLineTo(19f)
            quadTo(18f, 21f, 16f, 21f); horizontalLineTo(8f); quadTo(6f, 21f, 6f, 19f)
            verticalLineTo(5f); quadTo(6f, 3f, 8f, 3f); close()
            moveTo(11f, 18f); horizontalLineTo(13f)
        }
    }
    val Usb = icon("USB") {
        line {
            moveTo(12f, 20f); verticalLineTo(4f)
            moveTo(9f, 7f); lineTo(12f, 4f); lineTo(15f, 7f)
            moveTo(12f, 16f); lineTo(6f, 12f); verticalLineTo(9f)
            moveTo(12f, 13f); lineTo(18f, 10f); verticalLineTo(7f)
        }
    }
    val Pipeline = icon("Audio Pipeline") {
        line {
            moveTo(4f, 11f); verticalLineTo(13f)
            moveTo(8f, 8f); verticalLineTo(16f)
            moveTo(12f, 4f); verticalLineTo(20f)
            moveTo(16f, 7f); verticalLineTo(17f)
            moveTo(20f, 10f); verticalLineTo(14f)
        }
    }
    val Shuffle = icon("Shuffle") {
        line { moveTo(3f, 7f); horizontalLineTo(7f); lineTo(15f, 17f); horizontalLineTo(20f) }
        line { moveTo(3f, 17f); horizontalLineTo(7f); lineTo(15f, 7f); horizontalLineTo(20f) }
        line { moveTo(17f, 4f); lineTo(20f, 7f); lineTo(17f, 10f) }
        line { moveTo(17f, 14f); lineTo(20f, 17f); lineTo(17f, 20f) }
    }
    /** A speech bubble with two text lines. */
    val Lyrics = icon("Lyrics") {
        line {
            moveTo(5f, 4f); horizontalLineTo(19f); quadTo(21f, 4f, 21f, 6f); verticalLineTo(15f); quadTo(21f, 17f, 19f, 17f)
            horizontalLineTo(10f); lineTo(6f, 20.5f); verticalLineTo(17f); horizontalLineTo(5f); quadTo(3f, 17f, 3f, 15f)
            verticalLineTo(6f); quadTo(3f, 4f, 5f, 4f); close()
        }
        line { moveTo(7.5f, 8.5f); horizontalLineTo(16.5f) }
        line { moveTo(7.5f, 12.5f); horizontalLineTo(13.5f) }
    }
    val LibraryLayout = icon("Library layout") {
        for (top in listOf(4f, 14f)) for (left in listOf(4f, 14f)) {
            line {
                moveTo(left, top)
                horizontalLineTo(left + 6f)
                verticalLineTo(top + 6f)
                horizontalLineTo(left)
                close()
            }
        }
    }

    val AlbumCarousel = icon("Album carousel") {
        path(stroke = ink, strokeLineWidth = 1.55f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8.5f, 4.5f); horizontalLineTo(15.5f); quadTo(17f, 4.5f, 17f, 6f)
            verticalLineTo(18f); quadTo(17f, 19.5f, 15.5f, 19.5f); horizontalLineTo(8.5f)
            quadTo(7f, 19.5f, 7f, 18f); verticalLineTo(6f); quadTo(7f, 4.5f, 8.5f, 4.5f); close()
            moveTo(4.5f, 7f); lineTo(2.5f, 8.2f); verticalLineTo(15.8f); lineTo(4.5f, 17f)
            moveTo(19.5f, 7f); lineTo(21.5f, 8.2f); verticalLineTo(15.8f); lineTo(19.5f, 17f)
            moveTo(9.5f, 12f); arcToRelative(2.5f, 2.5f, 0f, false, true, 5f, 0f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -5f, 0f); close()
        }
    }

    val Dice = icon("Dice") {
        line {
            moveTo(6f, 3f); horizontalLineTo(18f); quadTo(21f, 3f, 21f, 6f); verticalLineTo(18f)
            quadTo(21f, 21f, 18f, 21f); horizontalLineTo(6f); quadTo(3f, 21f, 3f, 18f)
            verticalLineTo(6f); quadTo(3f, 3f, 6f, 3f); close()
        }
        path(fill = ink) {
            for (center in listOf(8f, 12f, 16f)) {
                moveTo(center - 1.15f, center)
                arcToRelative(1.15f, 1.15f, 0f, false, true, 2.3f, 0f)
                arcToRelative(1.15f, 1.15f, 0f, false, true, -2.3f, 0f)
                close()
            }
        }
    }

    /** An up arrow beside a down arrow: the list's sort order. */
    val Sort = icon("Sort") {
        line { moveTo(8f, 19f); verticalLineTo(5f) }
        line { moveTo(4.5f, 8.5f); lineTo(8f, 5f); lineTo(11.5f, 8.5f) }
        line { moveTo(16f, 5f); verticalLineTo(19f) }
        line { moveTo(12.5f, 15.5f); lineTo(16f, 19f); lineTo(19.5f, 15.5f) }
    }
    /** Three dots in a row: the More button. */
    val More = icon("More") {
        path(fill = ink) {
            for (x in listOf(5.5f, 12f, 18.5f)) { moveTo(x - 1.9f, 12f); arcTo(1.9f, 1.9f, 0f, false, true, x + 1.9f, 12f); arcTo(1.9f, 1.9f, 0f, false, true, x - 1.9f, 12f); close() }
        }
    }
    /** Three list lines with a play mark: the queue. */
    val Queue = icon("Queue") {
        line { moveTo(4f, 6f); horizontalLineTo(16f) }
        line { moveTo(4f, 11f); horizontalLineTo(16f) }
        line { moveTo(4f, 16f); horizontalLineTo(11f) }
        path(fill = ink) { moveTo(15f, 14f); verticalLineTo(20.5f); lineTo(20.2f, 17.25f); close() }
    }
    /** Over-ear headphones: the EQ screen's headphone preset. */
    val Headphones = icon("Headphones") {
        path(fill = ink) {
            moveTo(12f, 2f); arcToRelative(9f, 9f, 0f, false, false, -9f, 9f); verticalLineToRelative(7f)
            arcToRelative(3f, 3f, 0f, false, false, 3f, 3f); horizontalLineToRelative(3f); verticalLineToRelative(-8f); horizontalLineTo(5f)
            verticalLineToRelative(-2f); arcToRelative(7f, 7f, 0f, false, true, 14f, 0f); verticalLineToRelative(2f); horizontalLineToRelative(-4f)
            verticalLineToRelative(8f); horizontalLineToRelative(3f); arcToRelative(3f, 3f, 0f, false, false, 3f, -3f); verticalLineToRelative(-7f)
            arcToRelative(9f, 9f, 0f, false, false, -9f, -9f); close()
        }
    }
    /** A page with a folded corner and a note: the song file, first stop of the sound path. */
    val SongFile = icon("Song file") {
        line {
            moveTo(7f, 2.5f); horizontalLineTo(14f); lineTo(19f, 7.5f); verticalLineTo(20f); quadTo(19f, 21.5f, 17.5f, 21.5f)
            horizontalLineTo(7.5f); quadTo(6f, 21.5f, 6f, 20f); verticalLineTo(4f); quadTo(6f, 2.5f, 7f, 2.5f); close()
        }
        line { moveTo(14f, 2.5f); verticalLineTo(8f); horizontalLineTo(19f) }
        line { moveTo(11.6f, 17f); verticalLineTo(11.8f); lineTo(15f, 11f) }
        path(fill = ink) { moveTo(8.4f, 17f); arcToRelative(1.6f, 1.6f, 0f, false, true, 3.2f, 0f); arcToRelative(1.6f, 1.6f, 0f, false, true, -3.2f, 0f); close() }
    }
    /** Three sliders: Evanna's own processing stop. */
    val Sliders = icon("Sliders") {
        line { moveTo(5f, 20f); verticalLineTo(12f); moveTo(5f, 8f); verticalLineTo(4f) }
        line { moveTo(12f, 20f); verticalLineTo(14f); moveTo(12f, 10f); verticalLineTo(4f) }
        line { moveTo(19f, 20f); verticalLineTo(16f); moveTo(19f, 12f); verticalLineTo(4f) }
        line { moveTo(3f, 10f); horizontalLineTo(7f); moveTo(10f, 12f); horizontalLineTo(14f); moveTo(17f, 14f); horizontalLineTo(21f) }
    }
    /** A small box with a headphone jack: a USB DAC. */
    val Dac = icon("DAC") {
        line {
            moveTo(6.5f, 6.5f); horizontalLineTo(17.5f); quadTo(20.5f, 6.5f, 20.5f, 9.5f); verticalLineTo(14.5f)
            quadTo(20.5f, 17.5f, 17.5f, 17.5f); horizontalLineTo(6.5f); quadTo(3.5f, 17.5f, 3.5f, 14.5f); verticalLineTo(9.5f)
            quadTo(3.5f, 6.5f, 6.5f, 6.5f); close()
        }
        line { moveTo(13.5f, 12f); arcToRelative(2f, 2f, 0f, false, true, 4f, 0f); arcToRelative(2f, 2f, 0f, false, true, -4f, 0f); close() }
        line { moveTo(8f, 10.5f); verticalLineTo(13.5f) }
    }
    // iOS-style glyphs for rows and menus, drawn like SF Symbols at a regular weight.
    private fun ImageVector.Builder.thin(width: Float = 1.9f, block: PathBuilder.() -> Unit) = path(stroke = ink,
        strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    /** A row's disclosure chevron (chevron.right). */
    val ChevronRight = icon("Chevron right") { thin(2.6f) { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }
    /** A pop-up button's chevrons (chevron.up.chevron.down). */
    val ChevronUpDown = icon("Chevron up down") {
        thin(2.2f) { moveTo(8f, 9.5f); lineTo(12f, 5.5f); lineTo(16f, 9.5f) }
        thin(2.2f) { moveTo(8f, 14.5f); lineTo(12f, 18.5f); lineTo(16f, 14.5f) }
    }
    /** The chosen option in a list or menu (checkmark). */
    val Check = icon("Check") { thin(2.6f) { moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f) } }
    val Search = icon("Search") {
        thin(2.2f) { moveTo(17f, 10.5f); arcToRelative(6.5f, 6.5f, 0f, true, true, -13f, 0f); arcToRelative(6.5f, 6.5f, 0f, true, true, 13f, 0f); close() }
        thin(2.2f) { moveTo(15.4f, 15.4f); lineTo(20.5f, 20.5f) }
    }
    val Close = icon("Close") { thin(2.6f) { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) } }
    val Plus = icon("Plus") { thin { moveTo(12f, 4.5f); verticalLineTo(19.5f); moveTo(4.5f, 12f); horizontalLineTo(19.5f) } }
    val Info = icon("Info") {
        thin { moveTo(21f, 12f); arcToRelative(9f, 9f, 0f, true, true, -18f, 0f); arcToRelative(9f, 9f, 0f, true, true, 18f, 0f); close() }
        thin(2.2f) { moveTo(12f, 11f); verticalLineTo(16.5f) }
        path(fill = ink) { moveTo(10.75f, 7.6f); arcToRelative(1.25f, 1.25f, 0f, true, true, 2.5f, 0f); arcToRelative(1.25f, 1.25f, 0f, true, true, -2.5f, 0f); close() }
    }
    /** Stacked squares: an album (square.stack). */
    val Album = icon("Album") {
        thin {
            moveTo(6.5f, 9f); horizontalLineTo(17.5f); quadTo(19.5f, 9f, 19.5f, 11f); verticalLineTo(18.5f); quadTo(19.5f, 20.5f, 17.5f, 20.5f)
            horizontalLineTo(6.5f); quadTo(4.5f, 20.5f, 4.5f, 18.5f); verticalLineTo(11f); quadTo(4.5f, 9f, 6.5f, 9f); close()
        }
        thin { moveTo(6.5f, 5.75f); horizontalLineTo(17.5f) }
        thin { moveTo(8.5f, 2.75f); horizontalLineTo(15.5f) }
    }
    /** A half-filled circle: light and dark (circle.lefthalf.filled). */
    val Appearance = icon("Appearance") {
        thin(2f) { moveTo(20.5f, 12f); arcToRelative(8.5f, 8.5f, 0f, true, true, -17f, 0f); arcToRelative(8.5f, 8.5f, 0f, true, true, 17f, 0f); close() }
        path(fill = ink) { moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, false, false, 12f, 20.5f); close() }
    }
    /** A circular arrow: rescan (arrow.clockwise). */
    val Refresh = icon("Refresh") {
        thin(2f) { moveTo(19.5f, 12f); arcToRelative(7.5f, 7.5f, 0f, true, true, -2.2f, -5.3f) }
        thin(2f) { moveTo(17.9f, 2.9f); lineTo(17.5f, 6.9f); lineTo(13.5f, 6.6f) }
    }
    /** Back (chevron.left). */
    val ChevronLeft = icon("Chevron left") { thin(2.6f) { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    val ChevronUp = icon("Chevron up") { thin(2.6f) { moveTo(5f, 15.5f); lineTo(12f, 8.5f); lineTo(19f, 15.5f) } }
    val ChevronDown = icon("Chevron down") { thin(2.6f) { moveTo(5f, 8.5f); lineTo(12f, 15.5f); lineTo(19f, 8.5f) } }
    /** A play triangle with softened corners (play.fill). */
    val Play = icon("Play") {
        path(fill = ink, stroke = ink, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(7.5f, 4.8f); lineTo(19.2f, 12f); lineTo(7.5f, 19.2f); close()
        }
    }
    private fun PathBuilder.heart() {
        moveTo(12f, 20.2f)
        curveTo(12f, 20.2f, 3f, 14.6f, 3f, 8.7f)
        curveTo(3f, 6f, 5.1f, 3.9f, 7.7f, 3.9f)
        curveTo(9.5f, 3.9f, 11.1f, 4.9f, 12f, 6.4f)
        curveTo(12.9f, 4.9f, 14.5f, 3.9f, 16.3f, 3.9f)
        curveTo(18.9f, 3.9f, 21f, 6f, 21f, 8.7f)
        curveTo(21f, 14.6f, 12f, 20.2f, 12f, 20.2f)
        close()
    }
    /** Favorite, off (heart). */
    val Heart = icon("Heart") { thin(2f) { heart() } }
    /** Favorite, on (heart.fill). */
    val HeartFill = icon("Heart filled") { path(fill = ink) { heart() } }
    /** A filled cog with a round hole (gearshape.fill): the Settings tab. */
    val Gear = icon("Gear") {
        path(fill = ink, pathFillType = PathFillType.EvenOdd) {
            fun at(radius: Float, degrees: Float) = (degrees * PI / 180).let { 12f + radius * cos(it).toFloat() to 12f + radius * sin(it).toFloat() }
            for (tooth in 0 until 8) {
                val middle = tooth * 45f
                listOf(8.3f to middle - 16f, 10.7f to middle - 8.5f, 10.7f to middle + 8.5f, 8.3f to middle + 16f)
                    .forEachIndexed { index, (radius, degrees) ->
                        val (x, y) = at(radius, degrees)
                        if (tooth == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                    }
            }
            close()
            moveTo(15.4f, 12f); arcToRelative(3.4f, 3.4f, 0f, true, true, -6.8f, 0f); arcToRelative(3.4f, 3.4f, 0f, true, true, 6.8f, 0f); close()
        }
    }
    /** A heartbeat line: signal checks (waveform.path.ecg). */
    val Pulse = icon("Pulse") { thin(2f) { moveTo(2.5f, 12.5f); horizontalLineTo(7f); lineTo(9.25f, 6.5f); lineTo(13f, 18f); lineTo(15.5f, 10f); lineTo(17f, 12.5f); horizontalLineTo(21.5f) } }

    val Bluetooth = icon("Bluetooth") { line { moveTo(7f, 7f); lineTo(17f, 17f); lineTo(12f, 22f); verticalLineTo(2f); lineTo(17f, 7f); lineTo(7f, 17f) } }
    val Repeat = icon("Repeat") { loop() }
    val RepeatOne = icon("RepeatOne") { loop(); line { moveTo(10.75f, 10.5f); lineTo(12.25f, 9.5f); verticalLineTo(14.5f) } }
    val VolumeDown = icon("VolumeDown") { speaker(); line { moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f) } }
    val VolumeUp = icon("VolumeUp") {
        speaker()
        line { moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f) }
        line { moveTo(18f, 6f); quadTo(22f, 12f, 18f, 18f) }
    }
}
