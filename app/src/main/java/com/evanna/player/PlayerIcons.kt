package com.evanna.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

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
    val Repeat = icon("Repeat") { loop() }
    val RepeatOne = icon("RepeatOne") { loop(); line { moveTo(10.75f, 10.5f); lineTo(12.25f, 9.5f); verticalLineTo(14.5f) } }
    val VolumeDown = icon("VolumeDown") { speaker(); line { moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f) } }
    val VolumeUp = icon("VolumeUp") {
        speaker()
        line { moveTo(15.5f, 9f); quadTo(17.5f, 12f, 15.5f, 15f) }
        line { moveTo(18f, 6f); quadTo(22f, 12f, 18f, 18f) }
    }
}
