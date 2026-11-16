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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

/** What a share card shows (docs/mockups/share-cards.html, A for both). */
internal sealed interface ShareCard {
    val sheetTitle: String
    data class LyricCard(val lines: List<String>, val song: String, val artist: String) : ShareCard {
        override val sheetTitle get() = "Share lyrics"
    }
    data class AlbumCard(val album: String, val artist: String) : ShareCard {
        override val sheetTitle get() = "Share album"
    }
}

/** A story picture (1080 × 1920), plus its two layers for Instagram: the glow alone and the card alone. */
internal class SharePictures(val story: Bitmap, val background: Bitmap, val sticker: Bitmap)

private const val STORY_WIDTH = 1080
private const val STORY_HEIGHT = 1920
private const val CARD_WIDTH = 840

/** Draws the card over the cover's glow, the same glow as behind Now Playing. Takes a moment; call off the main thread. */
internal fun drawSharePictures(card: ShareCard, cover: Bitmap?): SharePictures {
    val background = cover?.let { backdropPicture(it, STORY_WIDTH, STORY_HEIGHT) } ?: plainGlow()
    val sticker = when (card) {
        is ShareCard.LyricCard -> lyricSticker(card, cover)
        is ShareCard.AlbumCard -> albumSticker(card, cover)
    }
    val story = background.copy(Bitmap.Config.ARGB_8888, true)
    Canvas(story).drawBitmap(sticker, (STORY_WIDTH - sticker.width) / 2f, (STORY_HEIGHT - sticker.height) / 2f, null)
    return SharePictures(story, background, sticker)
}

private fun plainGlow(): Bitmap = Bitmap.createBitmap(STORY_WIDTH, STORY_HEIGHT, Bitmap.Config.ARGB_8888).also {
    Canvas(it).drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, STORY_HEIGHT.toFloat(), 0xFF2C2C30.toInt(), 0xFF111113.toInt(), Shader.TileMode.CLAMP)
    })
}

private fun textPaint(size: Float, weight: Int, alpha: Int = 255) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    typeface = Typeface.create(Typeface.SANS_SERIF, weight, false)
    color = android.graphics.Color.argb(alpha, 255, 255, 255)
}

private fun textBlock(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()

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
private fun Canvas.drawNote(x: Float, y: Float, size: Float, alpha: Int) {
    val s = size / 24f
    val ink = android.graphics.Color.argb(alpha, 255, 255, 255)
    drawPath(Path().apply { moveTo(x + 9 * s, y + 18 * s); lineTo(x + 9 * s, y + 6 * s); lineTo(x + 20 * s, y + 4 * s); lineTo(x + 20 * s, y + 16 * s) },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * s; color = ink; strokeJoin = Paint.Join.ROUND })
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    drawCircle(x + 6.5f * s, y + 18 * s, 2.8f * s, dot)
    drawCircle(x + 17.5f * s, y + 16 * s, 2.8f * s, dot)
}

private const val MARK_SIZE = 31f

/** "♪ Evanna" in small faint letters; returns its height. */
private fun Canvas.drawMark(x: Float, y: Float): Float {
    val paint = textPaint(MARK_SIZE, 500, 140)
    val metrics = paint.fontMetrics
    val height = metrics.descent - metrics.ascent
    drawNote(x, y + (height - MARK_SIZE) / 2, MARK_SIZE, 140)
    drawText("Evanna", x + MARK_SIZE * 1.15f, y - metrics.ascent, paint)
    return height
}

private fun markHeight(): Float = textPaint(MARK_SIZE, 500).fontMetrics.let { it.descent - it.ascent }

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
private fun openInstagramStory(context: Context, background: Uri, sticker: Uri) {
    val intent = Intent(ADD_TO_STORY).setPackage(INSTAGRAM).apply {
        putExtra("source_application", META_APP_ID)
        setDataAndType(background, "image/png")
        putExtra("interactive_asset_uri", sticker)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.grantUriPermission(INSTAGRAM, sticker, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    val pictures by produceState<SharePictures?>(null, card) {
        val art = try { cover() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        value = try { withContext(Dispatchers.Default) { drawSharePictures(card, art) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { failed = true; null }
    }
    var note by remember(card) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val instagram = remember(context) { instagramStories(context) }
    fun act(action: suspend (SharePictures) -> String?) {
        val ready = pictures ?: return
        if (working) return
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
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = pictures != null && !working
                if (instagram) ShareButton(ShareGlyph.STORY, "Instagram Stories", enabled) {
                    act { ready ->
                        val (background, sticker) = withContext(Dispatchers.IO) {
                            ShareFiles.write(context, listOf("background" to ready.background, "sticker" to ready.sticker))
                        }
                        openInstagramStory(context, background, sticker)
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
