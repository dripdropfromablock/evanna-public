package com.evanna.player

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.evanna.player.ui.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** At most this many lyric lines go on one card, so shares stay short. */
internal const val SHARE_MAX_LINES = 4

/** Ticks or unticks line [index]; null when a fifth line would be ticked. */
internal fun toggledPick(picked: Set<Int>, index: Int): Set<Int>? = when {
    index in picked -> picked - index
    picked.size >= SHARE_MAX_LINES -> null
    else -> picked + index
}

/** The ticked lines' words in song order; blank lines and lines past the end are left out. */
internal fun pickedWords(lines: List<String>, picked: Set<Int>): List<String> =
    picked.sorted().mapNotNull { lines.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }.take(SHARE_MAX_LINES)

/** The lines just before and after the ticked ones, for the Lyrics page look; blank lines are skipped. */
internal fun contextAround(lines: List<String>, picked: Set<Int>): Pair<String?, String?> {
    if (picked.isEmpty()) return null to null
    val before = (picked.min() - 1 downTo 0).firstNotNullOfOrNull { lines.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
    val after = (picked.max() + 1..lines.lastIndex).firstNotNullOfOrNull { lines.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
    return before to after
}

/** One song on an album card's Liner notes: its title and length ("3:41"). */
internal data class ShareTrack(val title: String, val length: String)

/** At most this many songs are listed on the Liner notes look. */
internal const val SHARE_LINER_TRACKS = 6

/** What a share card shows (docs/mockups/share-cards.html, share-lyric-looks.html, share-album-looks.html). */
internal sealed interface ShareCard {
    val sheetTitle: String
    /** [before] and [after] are the lines either side of the ticked ones, shown faint by the Lyrics page look. */
    data class LyricCard(val lines: List<String>, val song: String, val artist: String,
        val before: String? = null, val after: String? = null) : ShareCard {
        override val sheetTitle get() = "Share lyrics"
    }
    /** [meta] is "2026 · 10 songs · 38 min"; [tracks] and [more] ("+4 more · 38 min") feed Liner notes, known on the album page. */
    data class AlbumCard(val album: String, val artist: String, val meta: String? = null,
        val tracks: List<ShareTrack> = emptyList(), val more: String? = null) : ShareCard {
        override val sheetTitle get() = "Share album"
    }
}

/**
 * Every look a card can take, in the order the share sheet lists them: the first ones shipped first (Card stays the
 * default), then the newer ones from the mockups.
 */
internal enum class ShareLook(val label: String, val forLyrics: Boolean) {
    LYRIC_CARD("Card", true), LYRIC_BIG("Big words", true), LYRIC_COVER("Cover", true), LYRIC_PAGE("Lyrics page", true),
    LYRIC_POSTER("Poster", true), LYRIC_QUOTE("Quote", true), LYRIC_COLOUR("Colour", true),
    ALBUM_CARD("Card", false), ALBUM_FLOATING("Floating", false), ALBUM_FULL("Full cover", false), ALBUM_FLOW("Cover Flow", false),
    ALBUM_RECORD("Record", false), ALBUM_PRINT("Gallery print", false), ALBUM_POSTER("Poster", false),
    ALBUM_LINER("Liner notes", false), ALBUM_TYPE("Big type", false);

    /** The glass card, the one look Instagram gets as a sticker to move. */
    val glass get() = this == LYRIC_CARD || this == ALBUM_CARD

    companion object {
        /** The looks this card can take: Liner notes needs the album's songs. */
        fun available(card: ShareCard): List<ShareLook> = entries.filter {
            it.forLyrics == (card is ShareCard.LyricCard) && (it != ALBUM_LINER || (card as ShareCard.AlbumCard).tracks.isNotEmpty())
        }

        /** The saved look, also from before there were names for each (GLASS, BOLD, COVER); else the first one. */
        fun stored(card: ShareCard, value: String?): ShareLook {
            val lyric = card is ShareCard.LyricCard
            val found = when (value) {
                "GLASS" -> if (lyric) LYRIC_CARD else ALBUM_CARD
                "BOLD" -> if (lyric) LYRIC_BIG else ALBUM_FLOATING
                "COVER" -> if (lyric) LYRIC_COVER else ALBUM_FULL
                else -> entries.firstOrNull { it.name == value }
            }
            val choices = available(card)
            return found?.takeIf { it in choices } ?: choices.first()
        }

        fun ordered(card: ShareCard, value: String?): List<ShareLook> {
            val last = stored(card, value)
            return listOf(last) + (available(card) - last)
        }
    }
}

/** UI_SETTINGS keys: the look last picked for each kind of card. */
internal fun shareStyleKey(card: ShareCard) = if (card is ShareCard.LyricCard) "share-lyric-style" else "share-album-style"

/**
 * A story picture (1080 × 1920), plus its layers for Instagram: the glow alone and, for the glass card, the card alone as a
 * sticker. The other looks have no card to move, so Instagram gets the whole picture.
 */
internal class SharePictures(val story: Bitmap, val background: Bitmap, val sticker: Bitmap?)

private const val STORY_WIDTH = 1080
private const val STORY_HEIGHT = 1920
private const val CARD_WIDTH = 840
/** Previews in the sheet's strip are drawn at this share of full size. */
private const val THUMB_SCALE = 0.2f
private const val FALLBACK_ACCENT = 0xFFF0C27A.toInt()

/**
 * Draws one card in any look, over the cover's glow (the same glow as behind Now Playing). The glow and the cover's colour
 * are worked out once and shared by the full picture and the small previews. Slow; call off the main thread.
 */
internal class ShareRenderer(private val card: ShareCard, private val cover: Bitmap?) {
    /** The cover's own colour, as Now Playing samples it, and a lighter one that reads as text on the dark glow. */
    private val accent: Int = cover?.let { runCatching { sampleArtworkAccent(it) }.getOrNull() }
        ?.let { android.graphics.Color.argb(255, (it.red * 255).roundToInt(), (it.green * 255).roundToInt(), (it.blue * 255).roundToInt()) }
        ?: FALLBACK_ACCENT
    private val textAccent: Int = readableOnDark(accent)
    private val glow: Bitmap by lazy { cover?.let { backdropPicture(it, STORY_WIDTH, STORY_HEIGHT) } ?: plainGlow() }

    fun pictures(look: ShareLook): SharePictures {
        val sticker = if (look.glass) sticker() else null
        return SharePictures(story(look, 1f, sticker), glow, sticker)
    }

    fun thumbnail(look: ShareLook): Bitmap = story(look, THUMB_SCALE, null)

    private fun sticker(): Bitmap = when (card) {
        is ShareCard.LyricCard -> lyricSticker(card, cover)
        is ShareCard.AlbumCard -> albumSticker(card, cover)
    }

    private fun story(look: ShareLook, scale: Float, sticker: Bitmap?): Bitmap {
        val picture = Bitmap.createBitmap((STORY_WIDTH * scale).roundToInt(), (STORY_HEIGHT * scale).roundToInt(), Bitmap.Config.ARGB_8888)
        Canvas(picture).apply {
            scale(scale, scale)
            drawBitmap(glow, null, android.graphics.Rect(0, 0, STORY_WIDTH, STORY_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))
            if (look.glass) {
                val card = sticker ?: sticker()
                drawBitmap(card, (STORY_WIDTH - card.width) / 2f, (STORY_HEIGHT - card.height) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
                return@apply
            }
            when (val shown = this@ShareRenderer.card) {
                is ShareCard.LyricCard -> when (look) {
                    ShareLook.LYRIC_BIG -> bigWords(shown, cover)
                    ShareLook.LYRIC_COVER -> lyricsUnderCover(shown, cover)
                    ShareLook.LYRIC_PAGE -> lyricsPage(shown, cover)
                    ShareLook.LYRIC_POSTER -> lyricPoster(shown, cover, textAccent)
                    ShareLook.LYRIC_QUOTE -> lyricQuote(shown, textAccent)
                    else -> lyricColour(shown, cover, accent)
                }
                is ShareCard.AlbumCard -> when (look) {
                    ShareLook.ALBUM_FLOATING -> floatingCover(shown, cover)
                    ShareLook.ALBUM_FULL -> fullCover(shown, cover)
                    ShareLook.ALBUM_FLOW -> coverFlow(shown, cover)
                    ShareLook.ALBUM_RECORD -> record(shown, cover)
                    ShareLook.ALBUM_PRINT -> galleryPrint(shown, cover)
                    ShareLook.ALBUM_POSTER -> albumPoster(shown, cover, textAccent)
                    ShareLook.ALBUM_LINER -> linerNotes(shown, cover, textAccent)
                    else -> bigType(shown, cover, textAccent)
                }
            }
        }
        return picture
    }
}

/** [argb] lifted toward white until it reads as text on the dark glow (relative luminance at least 0.3). */
internal fun readableOnDark(argb: Int): Int {
    var color = argb or 0xFF000000.toInt()
    repeat(10) {
        if (relativeLuminance(color) >= 0.3) return color
        color = blend(color, 0xFFFFFFFF.toInt(), 0.18f)
    }
    return color
}

private fun blend(from: Int, to: Int, amount: Float): Int {
    fun channel(shift: Int) = ((from shr shift and 255) + ((to shr shift and 255) - (from shr shift and 255)) * amount).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun colourInk(argb: Int): Int =
    if (contrastRatio(0xFFFFFFFF.toInt(), argb) >= 4.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

/**
 * The lines as text blocks, from [start] px shrunk in 6 px steps (not below [smallest]) until they fit in [room]; returns the
 * blocks and the gap between them.
 */
internal fun fittedWords(lines: List<String>, width: Int, start: Float, smallest: Float, room: Float, gapRatio: Float = 0.38f,
    paint: (Float) -> TextPaint = { textPaint(it, 700) }): Pair<List<StaticLayout>, Int> {
    var size = start
    var maxLines = 6
    while (true) {
        val ink = paint(size)
        val words = lines.map { textBlock(it, ink, width, maxLines) }
        val gap = (size * gapRatio).roundToInt()
        if (words.sumOf { it.height } + gap * (words.size - 1).coerceAtLeast(0) <= room || (size <= smallest && maxLines == 1))
            return words to gap
        if (size > smallest) size = maxOf(smallest, size - 6f) else maxLines--
    }
}

private fun height(words: Pair<List<StaticLayout>, Int>) = words.first.sumOf { it.height } + words.second * (words.first.size - 1).coerceAtLeast(0)

private fun Canvas.drawWords(words: Pair<List<StaticLayout>, Int>, x: Float, top: Float) {
    var y = top
    for (block in words.first) { drawBlock(block, x, y); y += block.height + words.second }
}

/** Lyric B: large words straight on the glow, the song small at the bottom. */
private fun Canvas.bigWords(card: ShareCard.LyricCard, cover: Bitmap?) {
    val left = 96f
    val width = STORY_WIDTH - 2 * 96
    val side = 120f
    val textLeft = side + 33f
    val title = textBlock(card.song, textPaint(42f, 600), (width - textLeft).toInt(), 1)
    val artist = textBlock(card.artist, textPaint(39f, 400, 179), (width - textLeft).toInt(), 1)
    val column = title.height + artist.height + 8f + markHeight()
    val row = maxOf(side, column)
    val rowTop = STORY_HEIGHT - 240f - row
    val words = fittedWords(card.lines, width, 90f, 56f, rowTop - 400f)
    val top = (200f + (rowTop - 120f - 200f - height(words)) / 2).coerceAtLeast(160f)
    drawWords(words, left, top)
    drawCover(cover, left, rowTop + (row - side) / 2, side, 15f)
    var line = rowTop + (row - column) / 2
    drawBlock(title, left + textLeft, line); line += title.height
    drawBlock(artist, left + textLeft, line); line += artist.height + 8f
    drawMark(left + textLeft, line)
}

/** Lyric C: the cover first, the words under it, then "Song · Artist" and the mark; the group sits in the middle. */
private fun Canvas.lyricsUnderCover(card: ShareCard.LyricCard, cover: Bitmap?) {
    val side = 840f
    val left = (STORY_WIDTH - side) / 2
    val words = fittedWords(card.lines, side.toInt(), 54f, 40f, 460f)
    val name = android.text.SpannableStringBuilder().apply {
        append(card.song, android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" · " + card.artist, android.text.style.ForegroundColorSpan(android.graphics.Color.argb(179, 255, 255, 255)), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val nameBlock = StaticLayout.Builder.obtain(name, 0, name.length, textPaint(39f, 400), side.toInt())
        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
    val group = side + 72f + height(words) + 42f + nameBlock.height + 18f + markHeight()
    val top = ((STORY_HEIGHT - group) / 2).coerceAtLeast(80f)
    drawShadowedCover(cover, left, top, side, 36f)
    val wordsTop = top + side + 72f
    drawWords(words, left, wordsTop)
    val nameTop = wordsTop + height(words) + 42f
    drawBlock(nameBlock, left, nameTop)
    drawMark(left, nameTop + nameBlock.height + 18f)
}

/** Album B: a big cover with a soft shadow and the names centred under it. */
private fun Canvas.floatingCover(card: ShareCard.AlbumCard, cover: Bitmap?) {
    val side = 840f
    val left = (STORY_WIDTH - side) / 2
    val name = textBlock(card.album, textPaint(60f, 700), side.toInt(), 2, Layout.Alignment.ALIGN_CENTER)
    val artist = textBlock(card.artist, textPaint(47f, 400, 179), side.toInt(), 1, Layout.Alignment.ALIGN_CENTER)
    val group = side + 66f + name.height + artist.height + 24f + markHeight()
    val top = ((STORY_HEIGHT - group) / 2 - 40f).coerceAtLeast(80f)
    drawShadowedCover(cover, left, top, side, 36f)
    val nameTop = top + side + 66f
    drawBlock(name, left, nameTop)
    drawBlock(artist, left, nameTop + name.height)
    drawMark((STORY_WIDTH - markWidth()) / 2, nameTop + name.height + artist.height + 24f)
}

/** Album C: the cover fills the top and fades into the glow, like the full cover in Now Playing; the names sit under it. */
private fun Canvas.fullCover(card: ShareCard.AlbumCard, cover: Bitmap?) {
    val coverHeight = 1260
    if (cover != null && cover.width > 0 && cover.height > 0) {
        val layer = Bitmap.createBitmap(STORY_WIDTH, coverHeight, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Canvas(layer).apply {
            val scale = maxOf(STORY_WIDTH.toFloat() / cover.width, coverHeight.toFloat() / cover.height)
            val w = cover.width * scale
            val h = cover.height * scale
            drawBitmap(cover, null, android.graphics.RectF((STORY_WIDTH - w) / 2, (coverHeight - h) / 2,
                (STORY_WIDTH + w) / 2, (coverHeight + h) / 2), paint)
            drawRect(0f, 0f, STORY_WIDTH.toFloat(), coverHeight.toFloat(), Paint().apply {
                shader = LinearGradient(0f, coverHeight * 0.6f, 0f, coverHeight.toFloat(), android.graphics.Color.BLACK,
                    android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            })
        }
        drawBitmap(layer, 0f, 0f, null)
    } else drawCover(null, (STORY_WIDTH - 720f) / 2, 260f, 720f, 36f)
    val left = 96f
    val width = STORY_WIDTH - 2 * 96
    val name = textBlock(card.album, textPaint(66f, 700), width, 2)
    val artist = textBlock(card.artist, textPaint(51f, 400, 179), width, 1)
    val top = 1296f
    drawBlock(name, left, top)
    drawBlock(artist, left, top + name.height)
    drawMark(left, top + name.height + artist.height + 24f)
}

/** The cover with a soft shadow under it, as it floats over the glow. */
private fun Canvas.drawShadowedCover(cover: Bitmap?, left: Float, top: Float, side: Float, radius: Float) {
    drawRoundRect(left, top, left + side, top + side, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(255, 12, 12, 14)
        setShadowLayer(66f, 0f, 54f, android.graphics.Color.argb(150, 0, 0, 0))
    })
    drawCover(cover, left, top, side, radius)
}

/** The cover cropped to fill [width] × [height] at ([left], [top]), square-cornered; nothing when the song has none. */
private fun Canvas.drawCoverFill(cover: Bitmap?, left: Float, top: Float, width: Float, height: Float, alpha: Int = 255) {
    if (cover == null || cover.width <= 0 || cover.height <= 0) return
    val scale = maxOf(width / cover.width, height / cover.height)
    val w = cover.width * scale
    val h = cover.height * scale
    save()
    clipRect(left, top, left + width, top + height)
    drawBitmap(cover, null, android.graphics.RectF(left + (width - w) / 2, top + (height - h) / 2, left + (width + w) / 2, top + (height + h) / 2),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
    restore()
}

/** The cover over the top [height] px, fading out from [fadeFrom] (a share of the height) to the bottom. */
private fun Canvas.drawFadingCover(cover: Bitmap?, height: Int, fadeFrom: Float) {
    if (cover == null || cover.width <= 0 || cover.height <= 0) return
    val layer = Bitmap.createBitmap(STORY_WIDTH, height, Bitmap.Config.ARGB_8888)
    Canvas(layer).apply {
        drawCoverFill(cover, 0f, 0f, STORY_WIDTH.toFloat(), height.toFloat())
        drawRect(0f, 0f, STORY_WIDTH.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, height * fadeFrom, 0f, height.toFloat(), android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT,
                Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        })
    }
    drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    layer.recycle()
}

/** A dark fade from the bottom, so words read over a full-picture cover: clear above [clear], [middle] dark at [at], near black at [dark]. */
private fun Canvas.posterShade(clear: Float, at: Float, dark: Float, middle: Int, bottom: Int) {
    drawRect(0f, 0f, STORY_WIDTH.toFloat(), STORY_HEIGHT.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, STORY_HEIGHT.toFloat(),
            intArrayOf(android.graphics.Color.argb(0, 6, 8, 12), android.graphics.Color.argb(0, 6, 8, 12),
                android.graphics.Color.argb(middle, 6, 8, 12), android.graphics.Color.argb(bottom, 6, 8, 12)),
            floatArrayOf(0f, clear, at, dark), Shader.TileMode.CLAMP)
    })
}

/** The song small: cover, title, artist and the mark beside it, at ([left], [top]); returns the row's height. */
private fun Canvas.songRow(card: ShareCard.LyricCard, cover: Bitmap?, left: Float, top: Float, width: Int, side: Float): Float {
    val textLeft = side + 36f
    val title = textBlock(card.song, textPaint(42f, 600), (width - textLeft).toInt(), 1)
    val artist = textBlock(card.artist, textPaint(39f, 400, 179), (width - textLeft).toInt(), 1)
    val column = title.height + artist.height + 8f + markHeight()
    val row = maxOf(side, column)
    drawCover(cover, left, top + (row - side) / 2, side, 15f)
    var line = top + (row - column) / 2
    drawBlock(title, left + textLeft, line); line += title.height
    drawBlock(artist, left + textLeft, line); line += artist.height + 8f
    drawMark(left + textLeft, line)
    return row
}

/** Lyric: as on Evanna's Lyrics page, the ticked lines bright and the lines either side faint and soft, the song on top. */
private fun Canvas.lyricsPage(card: ShareCard.LyricCard, cover: Bitmap?) {
    val left = 84f
    val width = STORY_WIDTH - 2 * 84
    val row = songRow(card, cover, left, 210f, width, 126f)
    val faint = textPaint(78f, 700, 77).apply { maskFilter = android.graphics.BlurMaskFilter(5f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
    val before = card.before?.let { textBlock(it, faint, width, 2) }
    val after = card.after?.let { textBlock(it, faint, width, 2) }
    val gap = 54f
    val area = 210f + row + 120f
    val contextHeight = (before?.let { it.height + gap } ?: 0f) + (after?.let { gap + it.height } ?: 0f)
    val words = fittedWords(card.lines, width, 90f, 60f, STORY_HEIGHT - 200f - area - contextHeight, gapRatio = 0.5f)
    val block = contextHeight + height(words)
    var y = (area + (STORY_HEIGHT - 200f - area - block) / 2).coerceAtLeast(area)
    before?.let { drawBlock(it, left, y); y += it.height + gap }
    drawWords(words, left, y); y += height(words)
    after?.let { drawBlock(it, left, y + gap) }
}

/** Lyric: the cover fills the picture and the words sit on a dark fade at the bottom, like the album Poster. */
private fun Canvas.lyricPoster(card: ShareCard.LyricCard, cover: Bitmap?, accent: Int) {
    drawCoverFill(cover, 0f, 0f, STORY_WIDTH.toFloat(), STORY_HEIGHT.toFloat())
    posterShade(0.22f, 0.46f, 0.78f, 128, 240)
    val left = 84f
    val width = STORY_WIDTH - 2 * 84
    val name = textBlock(songAndArtist(card.song, card.artist, accent), textPaint(42f, 400), width, 2)
    val markTop = STORY_HEIGHT - 210f - markHeight()
    val nameTop = markTop - 18f - name.height
    val words = fittedWords(card.lines, width, 75f, 54f, 860f, paint = { textPaint(it, 700).apply { letterSpacing = -0.01f } })
    drawWords(words, left, nameTop - 48f - height(words))
    drawBlock(name, left, nameTop)
    drawMark(left, markTop)
}

/** Lyric: serif italic words under a large opening quote mark in the cover's colour. */
private fun Canvas.lyricQuote(card: ShareCard.LyricCard, accent: Int) {
    val left = 108f
    val width = STORY_WIDTH - left.toInt() - 96
    val words = fittedWords(card.lines, width, 81f, 54f, 760f, gapRatio = 0.08f, paint = { textPaint(it, 400, serif = true, italic = true) })
    val title = textBlock(card.song, textPaint(39f, 600).apply { letterSpacing = 0.02f }, width, 1)
    val artist = textBlock(card.artist, textPaint(39f, 400, 179), width, 1)
    val quoteRoom = 300f
    val group = quoteRoom + height(words) + 78f + 6f + 54f + title.height + artist.height + 18f + markHeight()
    val top = ((STORY_HEIGHT - group) / 2).coerceAtLeast(120f)
    drawText("\u201C", left - 6f, top + 330f, textPaint(450f, 700, ink = accent, serif = true).apply { alpha = 230 })
    var y = top + quoteRoom
    drawWords(words, left, y); y += height(words) + 78f
    drawRect(left, y, left + 120f, y + 6f, Paint().apply { color = accent }); y += 6f + 54f
    drawBlock(title, left, y); y += title.height
    drawBlock(artist, left, y); y += artist.height + 18f
    drawMark(left, y)
}

/** Lyric: a block in the cover's colour with dark words (light ones on a dark cover colour) and the cover small on top. */
private fun Canvas.lyricColour(card: ShareCard.LyricCard, cover: Bitmap?, accent: Int) {
    val ink = colourInk(accent)
    val left = 90f
    val pad = 72f
    val width = (STORY_WIDTH - 2 * left - 2 * pad).toInt()
    val side = 114f
    val title = textBlock(card.song, textPaint(39f, 700, ink = ink), (width - side - 30f).toInt(), 1)
    val artist = textBlock(card.artist, textPaint(36f, 400, ink = ink), (width - side - 30f).toInt(), 1)
    val header = maxOf(side, (title.height + artist.height).toFloat())
    val words = fittedWords(card.lines, width, 69f, 48f, 900f, gapRatio = 0.26f,
        paint = { textPaint(it, 800, ink = ink).apply { letterSpacing = -0.01f } })
    val blockHeight = pad + header + 66f + height(words) + 48f + markHeight() + pad
    val top = ((STORY_HEIGHT - blockHeight) / 2 - 40f).coerceAtLeast(80f)
    drawRoundRect(left, top, STORY_WIDTH - left, top + blockHeight, 54f, 54f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent or 0xFF000000.toInt()
        setShadowLayer(120f, 0f, 54f, android.graphics.Color.argb(115, 0, 0, 0))
    })
    val x = left + pad
    var y = top + pad
    drawCover(cover, x, y + (header - side) / 2, side, 15f)
    val nameTop = y + (header - title.height - artist.height) / 2
    drawBlock(title, x + side + 30f, nameTop)
    drawBlock(artist, x + side + 30f, nameTop + title.height)
    y += header + 66f
    drawWords(words, x, y); y += height(words) + 48f
    drawMark(x, y, ink = ink)
}

/** The album's name, artist, details line and mark, [align]ed at ([left], [top]) in [width]; returns their height. */
private fun Canvas.albumNames(card: ShareCard.AlbumCard, left: Float, top: Float, width: Int, nameSize: Float, align: Layout.Alignment,
    artistInk: Int = white(179), nameWeight: Int = 700): Float {
    val name = textBlock(card.album, textPaint(nameSize, nameWeight), width, 2, align)
    val artist = textBlock(card.artist, textPaint(nameSize * 0.75f, 400, ink = artistInk), width, 1, align)
    val meta = card.meta?.let { textBlock(it, textPaint(33f, 400, 128).apply { fontFeatureSettings = "tnum" }, width, 1, align) }
    var y = top
    drawBlock(name, left, y); y += name.height
    drawBlock(artist, left, y + 6f); y += artist.height + 6f
    meta?.let { drawBlock(it, left, y + 18f); y += it.height + 18f }
    val markX = if (align == Layout.Alignment.ALIGN_CENTER) left + (width - markWidth()) / 2 else left
    drawMark(markX, y + 30f)
    return y + 30f + markHeight() - top
}

private fun albumNamesHeight(card: ShareCard.AlbumCard, width: Int, nameSize: Float): Float {
    val name = textBlock(card.album, textPaint(nameSize, 700), width, 2)
    val artist = textBlock(card.artist, textPaint(nameSize * 0.75f, 400), width, 1)
    val meta = card.meta?.let { textBlock(it, textPaint(33f, 400), width, 1) }
    return name.height + artist.height + 6f + (meta?.let { it.height + 18f } ?: 0f) + 30f + markHeight()
}

/** Album: the cover stands on a dark glass floor with its reflection, like the Carousel; square corners, as on a sleeve. */
private fun Canvas.coverFlow(card: ShareCard.AlbumCard, cover: Bitmap?) {
    val side = 744f
    val left = (STORY_WIDTH - side) / 2
    val gap = 336f
    val names = albumNamesHeight(card, 840, 60f)
    val top = ((STORY_HEIGHT - (side + gap + names)) / 2 - 30f).coerceAtLeast(80f)
    val floor = top + side
    drawRect(0f, floor, STORY_WIDTH.toFloat(), floor + 540f, Paint().apply {
        shader = LinearGradient(0f, floor, 0f, floor + 540f, white(13), white(0), Shader.TileMode.CLAMP)
    })
    if (cover != null && cover.width > 0 && cover.height > 0) {
        val reflection = Bitmap.createBitmap(side.toInt(), side.toInt(), Bitmap.Config.ARGB_8888)
        Canvas(reflection).apply {
            save(); scale(1f, -1f, 0f, side / 2); drawCoverFill(cover, 0f, 0f, side, side); restore()
            drawRect(0f, 0f, side, side, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, side * 0.48f, android.graphics.Color.argb(230, 0, 0, 0), android.graphics.Color.TRANSPARENT,
                    Shader.TileMode.CLAMP)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            })
        }
        drawBitmap(reflection, left, floor, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = 140
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(1.35f, 1.35f, 1.35f, 1f) })
        })
        reflection.recycle()
    }
    drawRect(0f, floor - 1.5f, STORY_WIDTH.toFloat(), floor + 1.5f, Paint().apply {
        shader = LinearGradient(0f, 0f, STORY_WIDTH.toFloat(), 0f, intArrayOf(white(0), white(56), white(0)), null, Shader.TileMode.CLAMP)
    })
    drawShadowedCover(cover, left, top, side, 0f)
    albumNames(card, 120f, floor + gap, 840, 60f, Layout.Alignment.ALIGN_CENTER)
}

/** Album: a record slides out of the sleeve, printed with the cover in its middle, like the disc on the sideways Details page. */
private fun Canvas.record(card: ShareCard.AlbumCard, cover: Bitmap?) {
    val sleeve = 654f
    val disc = 636f
    val names = albumNamesHeight(card, 864, 66f)
    val top = ((STORY_HEIGHT - (sleeve + 96f + names)) / 2).coerceAtLeast(80f)
    val sleeveLeft = 84f
    val cx = 426f + disc / 2
    val cy = top + 12f + disc / 2
    val r = disc / 2
    drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D0D0F.toInt(); setShadowLayer(90f, 0f, 42f, android.graphics.Color.argb(140, 0, 0, 0))
    })
    val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f; color = 0xFF1C1C1F.toInt() }
    var ring = 126f
    while (ring < r - 8f) { drawCircle(cx, cy, ring, groove); ring += 7.8f }
    drawCircle(cx, cy, r - 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = white(20) })
    val label = 105f
    if (cover != null && cover.width > 0 && cover.height > 0) {
        val scale = label * 2 / minOf(cover.width, cover.height)
        drawCircle(cx, cy, label, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale); postTranslate(cx - cover.width * scale / 2, cy - cover.height * scale / 2)
                })
            }
        })
    } else drawCircle(cx, cy, label, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() })
    drawCircle(cx, cy, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0D0D0F.toInt() })
    drawShadowedCover(cover, sleeveLeft, top, sleeve, 9f)
    albumNames(card, 108f, top + sleeve + 96f, 864, 66f, Layout.Alignment.ALIGN_NORMAL)
}

/** Album: the cover framed like a print on a wall, a warm mat in a thin dark frame, with a small label under it. */
private fun Canvas.galleryPrint(card: ShareCard.AlbumCard, cover: Bitmap?) {
    val mat = 768f
    val left = (STORY_WIDTH - mat) / 2
    val names = albumNamesHeight(card, mat.toInt(), 51f)
    val top = ((STORY_HEIGHT - (mat + 108f + names)) / 2 - 20f).coerceAtLeast(80f)
    drawRect(left - 9f, top - 9f, left + mat + 9f, top + mat + 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1D.toInt(); setShadowLayer(120f, 0f, 60f, android.graphics.Color.argb(140, 0, 0, 0))
    })
    drawRect(left, top, left + mat, top + mat, Paint().apply { color = 0xFFEFE8DC.toInt() })
    val inset = 72f
    if (cover != null) drawCoverFill(cover, left + inset, top + inset, mat - 2 * inset, mat - 2 * inset)
    else drawCover(null, left + inset, top + inset, mat - 2 * inset, 0f)
    drawRect(left + inset, top + inset, left + mat - inset, top + mat - inset, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = android.graphics.Color.argb(64, 0, 0, 0)
    })
    albumNames(card, left, top + mat + 108f, mat.toInt(), 51f, Layout.Alignment.ALIGN_NORMAL, nameWeight = 600)
}

/** Album: the cover fills the picture; a dark fade at the bottom holds a big title, like a film poster. */
private fun Canvas.albumPoster(card: ShareCard.AlbumCard, cover: Bitmap?, accent: Int) {
    drawCoverFill(cover, 0f, 0f, STORY_WIDTH.toFloat(), STORY_HEIGHT.toFloat())
    posterShade(0.38f, 0.62f, 1f, 140, 235)
    val left = 84f
    val width = STORY_WIDTH - 2 * 84
    val title = fittedWords(listOf(card.album), width, 138f, 84f, 420f,
        paint = { textPaint(it, 800).apply { letterSpacing = -0.035f } }).first.first()
    val artist = textBlock(card.artist, textPaint(51f, 500, ink = accent), width, 1)
    val meta = card.meta?.let { textBlock(it, textPaint(36f, 400, 158).apply { fontFeatureSettings = "tnum" }, width, 1) }
    val markTop = STORY_HEIGHT - 210f - markHeight()
    var y = markTop - 30f - (meta?.let { it.height + 12f } ?: 0f) - artist.height - 24f - title.height
    drawBlock(title, left, y); y += title.height + 24f
    drawBlock(artist, left, y); y += artist.height
    meta?.let { drawBlock(it, left, y + 12f) }
    drawMark(left, markTop)
}

/** Album: the whole cover on top, then the first songs and their lengths, like the back of a sleeve. */
private fun Canvas.linerNotes(card: ShareCard.AlbumCard, cover: Bitmap?, accent: Int) {
    val side = STORY_WIDTH.toFloat()
    if (cover != null) drawCoverFill(cover, 0f, 0f, side, side) else drawCover(null, 240f, 240f, 600f, 36f)
    drawRect(0f, side, side, STORY_HEIGHT.toFloat(), Paint().apply {
        shader = LinearGradient(0f, side, 0f, STORY_HEIGHT.toFloat(), android.graphics.Color.argb(140, 10, 14, 20),
            android.graphics.Color.argb(204, 10, 14, 20), Shader.TileMode.CLAMP)
    })
    val left = 84f
    val width = STORY_WIDTH - 2 * 84
    var y = side + 54f
    val name = textBlock(card.album, textPaint(63f, 700), width, 1)
    val artist = textBlock(card.artist, textPaint(42f, 400, ink = accent), width, 1)
    drawBlock(name, left, y); y += name.height
    drawBlock(artist, left, y); y += artist.height + 30f
    val rule = Paint().apply { color = white(20) }
    val number = textPaint(37f, 400, 115).apply { fontFeatureSettings = "tnum" }
    val titleInk = textPaint(37f, 400)
    val lengthInk = textPaint(37f, 400, 140).apply { fontFeatureSettings = "tnum"; textAlign = Paint.Align.RIGHT }
    val rowHeight = 72f
    card.tracks.take(SHARE_LINER_TRACKS).forEachIndexed { index, track ->
        drawRect(left, y, left + width, y + 3f, rule)
        val baseline = y + rowHeight / 2 - (titleInk.ascent() + titleInk.descent()) / 2
        drawText("${index + 1}", left, baseline, number)
        val room = width - 60f - lengthInk.measureText(track.length) - 30f
        drawText(TextUtils.ellipsize(track.title, titleInk, room, TextUtils.TruncateAt.END).toString(), left + 60f, baseline, titleInk)
        drawText(track.length, left + width, baseline, lengthInk)
        y += rowHeight
    }
    card.more?.let {
        drawRect(left, y, left + width, y + 3f, rule)
        val more = textPaint(33f, 400, 128).apply { fontFeatureSettings = "tnum" }
        drawText(it, left, y + 60f - (more.ascent() + more.descent()) / 2 - 6f, more)
        y += 66f
    }
    drawMark(left, y + 30f)
}

/** Album: the cover fades out and the album's name, set very large, runs over the edge of the fade. */
private fun Canvas.bigType(card: ShareCard.AlbumCard, cover: Bitmap?, accent: Int) {
    drawFadingCover(cover, 1290, 0.52f)
    val left = 66f
    val width = STORY_WIDTH - 66 - 60
    var size = 204f
    var name: StaticLayout
    while (true) {
        name = textBlock(card.album, textPaint(size, 800).apply {
            letterSpacing = -0.04f; setShadowLayer(54f, 0f, 6f, android.graphics.Color.argb(90, 0, 0, 0))
        }, width, 3, spacing = 0.92f)
        if ((name.lineCount <= 2 && name.height <= 600) || size <= 110f) break
        size = maxOf(110f, size - 12f)
    }
    val artist = textBlock(card.artist, textPaint(54f, 500, ink = accent), width, 1)
    val meta = card.meta?.let { textBlock(it, textPaint(36f, 400, 140).apply { fontFeatureSettings = "tnum" }, width, 1) }
    var y = minOf(900f, 1560f - name.height)
    drawBlock(name, left, y); y += name.height + 42f
    drawBlock(artist, left, y); y += artist.height
    meta?.let { drawBlock(it, left, y + 6f); y += it.height + 6f }
    drawMark(left, y + 30f)
}

private fun plainGlow(): Bitmap = Bitmap.createBitmap(STORY_WIDTH, STORY_HEIGHT, Bitmap.Config.ARGB_8888).also {
    Canvas(it).drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, STORY_HEIGHT.toFloat(), 0xFF2C2C30.toInt(), 0xFF111113.toInt(), Shader.TileMode.CLAMP)
    })
}

/** White text at [alpha] unless [ink] gives another colour. */
private fun textPaint(size: Float, weight: Int, alpha: Int = 255, ink: Int? = null, serif: Boolean = false, italic: Boolean = false) =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, weight, italic)
        color = ink ?: android.graphics.Color.argb(alpha, 255, 255, 255)
    }

private fun textBlock(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, spacing: Float = 1f): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(alignment).setLineSpacing(0f, spacing)
        .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()

/** "Song · Artist": the song bold, the artist in [artistInk]. */
private fun songAndArtist(song: String, artist: String, artistInk: Int): CharSequence = android.text.SpannableStringBuilder().apply {
    append(song, android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    append(" · $artist", android.text.style.ForegroundColorSpan(artistInk), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

private fun white(alpha: Int) = android.graphics.Color.argb(alpha, 255, 255, 255)

private fun Canvas.drawBlock(block: StaticLayout, x: Float, y: Float) {
    save(); translate(x, y); block.draw(this); restore()
}

/** The dark glass card: 58 % near-black, so the glow shows through a little, with a faint light edge. */
private fun cardBitmap(height: Int, radius: Float): Pair<Bitmap, Canvas> {
    val bitmap = Bitmap.createBitmap(CARD_WIDTH, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRoundRect(0f, 0f, CARD_WIDTH.toFloat(), height.toFloat(), radius, radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(148, 18, 18, 20) })
    canvas.drawRoundRect(1.5f, 1.5f, CARD_WIDTH - 1.5f, height - 1.5f, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = android.graphics.Color.argb(16, 255, 255, 255)
    })
    return bitmap to canvas
}

/** The cover cropped to a rounded square; a note on grey when the song has none. */
private fun Canvas.drawCover(cover: Bitmap?, left: Float, top: Float, side: Float, radius: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (cover == null || cover.width <= 0 || cover.height <= 0) {
        paint.color = 0xFF2C2C2E.toInt()
        drawRoundRect(left, top, left + side, top + side, radius, radius, paint)
        drawNote(left + side * 0.3f, top + side * 0.3f, side * 0.4f, 200)
        return
    }
    val scale = side / minOf(cover.width, cover.height)
    paint.shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(left + (side - cover.width * scale) / 2, top + (side - cover.height * scale) / 2)
        })
    }
    drawRoundRect(left, top, left + side, top + side, radius, radius, paint)
}

/** A music note [size] tall at ([x], [y]), in white at [alpha]. */
private fun Canvas.drawNote(x: Float, y: Float, size: Float, alpha: Int) = drawNoteIn(x, y, size, white(alpha))

private fun Canvas.drawNoteIn(x: Float, y: Float, size: Float, ink: Int) {
    val s = size / 24f
    drawPath(Path().apply { moveTo(x + 9 * s, y + 18 * s); lineTo(x + 9 * s, y + 6 * s); lineTo(x + 20 * s, y + 4 * s); lineTo(x + 20 * s, y + 16 * s) },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * s; color = ink; strokeJoin = Paint.Join.ROUND })
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    drawCircle(x + 6.5f * s, y + 18 * s, 2.8f * s, dot)
    drawCircle(x + 17.5f * s, y + 16 * s, 2.8f * s, dot)
}

private const val MARK_SIZE = 31f

/** "♪ Evanna" in small faint letters ([ink] for a coloured page); returns its height. */
private fun Canvas.drawMark(x: Float, y: Float, ink: Int = white(140)): Float {
    val paint = textPaint(MARK_SIZE, 500, ink = ink)
    val metrics = paint.fontMetrics
    val height = metrics.descent - metrics.ascent
    drawNoteIn(x, y + (height - MARK_SIZE) / 2, MARK_SIZE, ink)
    drawText("Evanna", x + MARK_SIZE * 1.15f, y - metrics.ascent, paint)
    return height
}

private fun markHeight(): Float = textPaint(MARK_SIZE, 500).fontMetrics.let { it.descent - it.ascent }

private fun markWidth(): Float = MARK_SIZE * 1.15f + textPaint(MARK_SIZE, 500).measureText("Evanna")

/** Lyric card A: the words (they shrink a little when there are many), then the cover, the song, the artist and the mark. */
private fun lyricSticker(card: ShareCard.LyricCard, cover: Bitmap?): Bitmap {
    val pad = 66
    val inner = CARD_WIDTH - 2 * pad
    var size = 57f
    var words: List<StaticLayout>
    var gap: Int
    while (true) {
        val paint = textPaint(size, 700)
        words = card.lines.map { textBlock(it, paint, inner, maxLines = 6) }
        gap = (size * 0.42f).roundToInt()
        if (words.sumOf { it.height } + gap * (words.size - 1) <= 1_000 || size <= 37f) break
        size -= 4f
    }
    val wordsHeight = words.sumOf { it.height } + gap * (words.size - 1).coerceAtLeast(0)
    val side = 138f
    val textLeft = side + 36f
    val title = textBlock(card.song, textPaint(42f, 600), (inner - textLeft).toInt(), 1)
    val artist = textBlock(card.artist, textPaint(39f, 400, 179), (inner - textLeft).toInt(), 1)
    val column = title.height + artist.height + 8f + markHeight()
    val row = maxOf(side, column)
    val songTop = pad + wordsHeight + 54f
    val (bitmap, canvas) = cardBitmap((songTop + row + pad).roundToInt(), radius = 60f)
    var y = pad.toFloat()
    for (block in words) { canvas.drawBlock(block, pad.toFloat(), y); y += block.height + gap }
    canvas.drawCover(cover, pad.toFloat(), songTop + (row - side) / 2, side, 18f)
    var line = songTop + (row - column) / 2
    canvas.drawBlock(title, pad + textLeft, line); line += title.height
    canvas.drawBlock(artist, pad + textLeft, line); line += artist.height + 8f
    canvas.drawMark(pad + textLeft, line)
    return bitmap
}

/** Album card A: the big cover, the album's name, the artist and the mark. */
private fun albumSticker(card: ShareCard.AlbumCard, cover: Bitmap?): Bitmap {
    val pad = 48f
    val side = CARD_WIDTH - 2 * pad
    val name = textBlock(card.album, textPaint(57f, 700), side.toInt(), 2)
    val artist = textBlock(card.artist, textPaint(45f, 400, 179), side.toInt(), 1)
    val nameTop = pad + side + 42f
    val markTop = nameTop + name.height + artist.height + 24f
    val (bitmap, canvas) = cardBitmap((markTop + markHeight() + pad).roundToInt(), radius = 66f)
    canvas.drawCover(cover, pad, pad, side, 36f)
    canvas.drawBlock(name, pad, nameTop)
    canvas.drawBlock(artist, pad, nameTop + name.height)
    canvas.drawMark(pad, markTop)
    return bitmap
}

/** Share pictures are written to the app's cache (shared through its file provider) and replaced on the next share. */
private object ShareFiles {
    fun write(context: Context, pictures: List<Pair<String, Bitmap>>): List<Uri> {
        val folder = File(context.cacheDir, "share-cards")
        folder.deleteRecursively()
        if (!folder.mkdirs()) throw IOException("SHARE_FOLDER")
        val stamp = System.currentTimeMillis()
        return pictures.map { (name, bitmap) ->
            val file = File(folder, "$name-$stamp.png")
            FileOutputStream(file).use { png(bitmap, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        }
    }
}

private fun png(bitmap: Bitmap, out: OutputStream) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG") }

/** Saves the picture to Pictures/Evanna, where the phone's gallery finds it. Android 9 needs storage access first. */
@Suppress("DEPRECATION")
private fun saveToPhotos(context: Context, bitmap: Bitmap) {
    val name = "Evanna " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date()) + ".png"
    if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Evanna")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("PHOTO_NOT_CREATED")
        try {
            resolver.openOutputStream(uri)?.use { png(bitmap, it) } ?: throw IOException("PHOTO_NOT_WRITTEN")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    } else {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Evanna")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("PHOTO_FOLDER")
        val file = File(folder, name)
        FileOutputStream(file).use { png(bitmap, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png"), null)
    }
}

/** Evanna's Meta app ID. Instagram asks for it before it takes a story; it is public by design, not a secret. */
private const val META_APP_ID = "1766506227749671"
private const val INSTAGRAM = "com.instagram.android"
private const val ADD_TO_STORY = "com.instagram.share.ADD_TO_STORY"

private fun instagramStories(context: Context): Boolean =
    context.packageManager.resolveActivity(Intent(ADD_TO_STORY).setPackage(INSTAGRAM).setType("image/png"), 0) != null

/** Opens Instagram's story screen with the glow as the background and the card as a sticker the user can move. */
private fun openInstagramStory(context: Context, background: Uri, sticker: Uri?) {
    val intent = Intent(ADD_TO_STORY).setPackage(INSTAGRAM).apply {
        putExtra("source_application", META_APP_ID)
        setDataAndType(background, "image/png")
        if (sticker != null) putExtra("interactive_asset_uri", sticker)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    sticker?.let { context.grantUriPermission(INSTAGRAM, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.grantUriPermission(INSTAGRAM, background, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(intent)
}

private enum class ShareGlyph { STORY, SAVE, MORE }

/**
 * The share sheet (docs/mockups/share-cards.html): a preview of the story picture, then Instagram Stories (when Instagram
 * is on the phone), Save to Photos and More apps (Android's share menu).
 */
@Composable
internal fun ShareSheet(card: ShareCard, cover: suspend () -> Bitmap?, onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = EvannaDesign.colorScheme
    val scope = rememberCoroutineScope()
    var failed by remember(card) { mutableStateOf(false) }
    // The look last picked for this kind of card opens first.
    val settings = remember(context) { context.getSharedPreferences(UI_SETTINGS, Context.MODE_PRIVATE) }
    val looks = remember(card) { ShareLook.ordered(card, settings.getString(shareStyleKey(card), null)) }
    var style by remember(card) { mutableStateOf(looks.first()) }
    var renderer by remember(card) { mutableStateOf<ShareRenderer?>(null) }
    var thumbnails by remember(card) { mutableStateOf<Map<ShareLook, Bitmap>>(emptyMap()) }
    LaunchedEffect(card) {
        val loaded = try { cover() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        renderer = withContext(Dispatchers.Default) { ShareRenderer(card, loaded) }
    }
    LaunchedEffect(renderer) {
        val ready = renderer ?: return@LaunchedEffect
        for (look in looks) {
            val thumbnail = try { withContext(Dispatchers.Default) { ready.thumbnail(look) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
            thumbnails = thumbnails + (look to thumbnail)
        }
    }
    // The last picture stays on screen while the next look is drawn.
    var pictures by remember(card) { mutableStateOf<SharePictures?>(null) }
    var renderedStyle by remember(card) { mutableStateOf<ShareLook?>(null) }
    LaunchedEffect(renderer, style) {
        val ready = renderer ?: return@LaunchedEffect
        val look = style
        failed = false
        pictures = try { withContext(Dispatchers.Default) { ready.pictures(look) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { failed = true; null }
        renderedStyle = look
    }
    var note by remember(card) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val instagram = remember(context) { instagramStories(context) }
    fun act(action: suspend (SharePictures) -> String?) {
        val ready = pictures ?: return
        if (working || renderedStyle != style) return
        working = true
        scope.launch {
            note = try { action(ready) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ActivityNotFoundException) { "No app on this phone can take the picture." }
                catch (_: Exception) { "Something went wrong. Try again." }
            working = false
        }
    }
    val save = { act { ready -> withContext(Dispatchers.IO) { saveToPhotos(context, ready.story) }; "Saved to Pictures/Evanna." } }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) save() else note = "Evanna needs storage access to save the picture."
    }
    OutputSheet(onDismissRequest = onClose, shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        containerColor = colors.surfaceContainer, scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = { close -> SheetHeader(card.sheetTitle, close) }, title = card.sheetTitle) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(200.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(Color.Black),
                contentAlignment = Alignment.Center) {
                val ready = pictures
                when {
                    ready != null -> Image(remember(ready) { ready.story.asImageBitmap() }, contentDescription = when (card) {
                        is ShareCard.LyricCard -> "Lyric card: ${card.lines.joinToString(" ")}. ${card.song}, ${card.artist}"
                        is ShareCard.AlbumCard -> "Album card: ${card.album}, ${card.artist}"
                    }, modifier = Modifier.fillMaxSize())
                    failed -> Text("The picture could not be made.", style = EvannaDesign.typography.bodySmall, color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                    else -> ActivityIndicator()
                }
            }
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup()
                .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (look in looks) {
                    val selected = look == style
                    Column(Modifier.width(72.dp).clip(RoundedCornerShape(12.dp))
                        .selectable(selected, enabled = !working, role = Role.RadioButton, onClick = {
                            style = look
                            note = null
                            settings.edit().putString(shareStyleKey(card), look.name).apply()
                        }).padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                            .border(if (selected) 3.dp else 1.dp, if (selected) colors.onSurface else colors.onSurface.copy(alpha = 0.12f),
                                RoundedCornerShape(9.dp)).padding(3.dp).clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            thumbnails[look]?.let { thumbnail ->
                                Image(remember(thumbnail) { thumbnail.asImageBitmap() }, null, Modifier.fillMaxSize())
                            }
                        }
                        Text(look.label, style = EvannaDesign.typography.labelMedium,
                            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 7.dp).fillMaxWidth())
                    }
                }
            }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = pictures != null && renderedStyle == style && !working
                if (instagram) ShareButton(ShareGlyph.STORY, "Instagram Stories", enabled) {
                    act { ready ->
                        // The glass card goes over the glow as a sticker to move; the other looks go as the whole picture.
                        val sticker = ready.sticker
                        val uris = withContext(Dispatchers.IO) {
                            ShareFiles.write(context, if (sticker != null) listOf("background" to ready.background, "sticker" to sticker)
                                else listOf("story" to ready.story))
                        }
                        openInstagramStory(context, uris[0], uris.getOrNull(1))
                        null
                    }
                }
                ShareButton(ShareGlyph.SAVE, "Save to Photos", enabled) {
                    if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else save()
                }
                ShareButton(ShareGlyph.MORE, "More apps", enabled) {
                    act { ready ->
                        val (picture) = withContext(Dispatchers.IO) { ShareFiles.write(context, listOf("evanna-card" to ready.story)) }
                        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, picture).apply {
                            clipData = ClipData.newRawUri(card.sheetTitle, picture)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, card.sheetTitle))
                        null
                    }
                }
            }
            Text(note ?: if (instagram) " " else "Instagram Stories shows here when Instagram is on this phone.",
                style = EvannaDesign.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** A round 56 dp button with its picture and a label under it. */
@Composable
private fun ShareButton(glyph: ShareGlyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = EvannaDesign.colorScheme
    val ink = colors.onSurface
    Column(Modifier.width(92.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            ComposeCanvas(Modifier.size(26.dp)) { drawGlyph(glyph, if (enabled) ink else ink.copy(alpha = 0.4f)) }
        }
        Text(label, style = EvannaDesign.typography.labelMedium, color = if (enabled) ink else ink.copy(alpha = 0.4f),
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun DrawScope.drawGlyph(glyph: ShareGlyph, ink: Color) {
    val s = size.width / 24f
    val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(vararg points: Float) {
        val path = androidx.compose.ui.graphics.Path()
        path.moveTo(points[0] * s, points[1] * s)
        for (i in 2 until points.size step 2) path.lineTo(points[i] * s, points[i + 1] * s)
        drawPath(path, ink, style = stroke)
    }
    when (glyph) {
        // A dashed story ring in a warm-to-violet gradient around a plus, not Instagram's own logo.
        ShareGlyph.STORY -> {
            drawCircle(Brush.linearGradient(listOf(Color(0xFFF9A23A), Color(0xFFE1306C), Color(0xFF7B3FE4)),
                start = Offset(0f, size.height), end = Offset(size.width, 0f)), radius = 9.5f * s,
                style = Stroke(width = 2.6f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.6f * s, 2f * s))))
            line(12f, 8f, 12f, 16f); line(8f, 12f, 16f, 12f)
        }
        ShareGlyph.SAVE -> { line(12f, 4f, 12f, 15f); line(7f, 10.5f, 12f, 15.5f, 17f, 10.5f); line(5f, 19.5f, 19f, 19.5f) }
        ShareGlyph.MORE -> { line(12f, 15f, 12f, 4f); line(8f, 8f, 12f, 4f, 16f, 8f); line(6f, 12f, 6f, 19f, 18f, 19f, 18f, 12f) }
    }
}
