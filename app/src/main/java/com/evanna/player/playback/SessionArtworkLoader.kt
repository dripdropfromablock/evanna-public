package com.evanna.player.playback

import android.content.Context
import android.graphics.Bitmap
import com.evanna.player.library.ArtworkSize
import com.evanna.player.library.LocalLibrary
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.media3.common.util.UnstableApi

@UnstableApi
internal suspend fun loadSessionArtwork(context:Context,owner:UsbSessionOwner,player:EvannaPlayer) {
    owner.queue.map {it.current?.id}.distinctUntilChanged().collectLatest { id ->
        if(id==null)return@collectLatest
        player.setCurrentArtwork(id,null)
        val source=owner.queueSource(id) ?: return@collectLatest
        val bytes=try {withContext(Dispatchers.IO) {
            val cache=LocalLibrary.artwork(context)
            val bitmap=source.libraryId?.let {cache.song(it,ArtworkSize.MEDIUM)}
                ?: source.fileUri?.let {cache.file(id,it,ArtworkSize.MEDIUM)}
            bitmap?.let(::artworkForSession)
        }}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){null}
        currentCoroutineContext().ensureActive()
        player.setCurrentArtwork(id,bytes)
    }
}

internal fun artworkForSession(bitmap:Bitmap):ByteArray? {
    if(bitmap.width !in 1..512 || bitmap.height !in 1..512)return null
    return try {
        val bytes=ByteArrayOutputStream(32*1024).use {output ->
            if(!bitmap.compress(Bitmap.CompressFormat.JPEG,85,output))return null
            output.toByteArray()
        }
        bytes.takeIf {it.size in 1..SESSION_ARTWORK_LIMIT}
    } catch(_:OutOfMemoryError){null}
}
