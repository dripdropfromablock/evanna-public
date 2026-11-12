package com.evanna.player

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private var cachedDisc: ImageBitmap? = null

@Synchronized
private fun discTexture(assets: AssetManager): ImageBitmap? {
    if (cachedDisc == null) {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        cachedDisc = assets.open("visuals/georgia-reborn-disc.png").use {
            BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
        }
    }
    return cachedDisc
}

/** The Georgia-ReBORN shell shows through at the rim and hub of the printed cover. */
@Composable
internal fun AlbumDisc(cover: Bitmap, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val texture by produceState<ImageBitmap?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            try { discTexture(assets) } catch (_: IOException) { null }
        }
    }
    val image = remember(cover) { cover.asImageBitmap() }
    Box(modifier.clip(CircleShape).background(Color(0xFF999999)), contentAlignment = Alignment.Center) {
        texture?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        Image(image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().drawWithCache {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val printedArea = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addOval(Rect(center = center, radius = radius * 0.975f))
                    addOval(Rect(center = center, radius = radius * 0.31f))
                }
                onDrawWithContent { clipPath(printedArea) { this@onDrawWithContent.drawContent() } }
            })
    }
}
