package com.evanna.player.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/** Evanna's note mark: the missing-cover placeholder and the Library tab icon. Icon tints it. */
internal val musicNote: ImageVector = ImageVector.Builder(defaultWidth = 48.dp, defaultHeight = 48.dp,
    viewportWidth = 48f, viewportHeight = 48f).apply {
    // The note spans x 4..36 and y 7..45; shift it so it sits in the centre of icons and covers.
    group(translationX = 4f, translationY = -2f) {
        path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
            moveTo(13f, 11f); lineTo(36f, 7f); lineTo(36f, 31f)
            curveTo(36f, 39f, 23f, 41f, 23f, 34f); curveTo(23f, 29f, 29f, 27f, 32f, 28f)
            lineTo(32f, 16f); lineTo(17f, 19f); lineTo(17f, 35f)
            curveTo(17f, 43f, 4f, 45f, 4f, 38f); curveTo(4f, 33f, 10f, 31f, 13f, 32f); close()
        }
    }
}.build()

@Composable
internal fun LibraryArtwork(id: String, revision: Int, modifier: Modifier = Modifier,
    shape: Shape = EvannaDesign.shapes.small, load: suspend () -> Bitmap?) {
    var bitmap by remember(id, revision) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id, revision) {
        bitmap = try { load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    ArtworkImage(bitmap, modifier, shape)
}

@Composable
internal fun ArtworkImage(bitmap: Bitmap?, modifier: Modifier = Modifier.size(56.dp), shape: Shape = EvannaDesign.shapes.small) {
    // Missing covers read as a quiet studio sleeve — darker ground and a hairline edge, not a flat grey box.
    // The hairline frames real covers too, so library rows and the mini player keep one consistent framing.
    Box(modifier.clip(shape).background(EvannaDesign.colorScheme.surfaceContainerLow)
        .border(1.dp, EvannaDesign.colorScheme.outlineVariant.copy(alpha = 0.24f), shape), contentAlignment = Alignment.Center) {
        if (bitmap == null) Icon(musicNote, contentDescription = null, modifier = Modifier.fillMaxSize(0.45f),
            tint = EvannaDesign.colorScheme.onSurfaceVariant)
        else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
