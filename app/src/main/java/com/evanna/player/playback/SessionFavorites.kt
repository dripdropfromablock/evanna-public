package com.evanna.player.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.evanna.player.user.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@UnstableApi
internal suspend fun loadSessionFavorite(context:Context,owner:UsbSessionOwner,player:EvannaPlayer) {
    val store=UserData.database(context)
    combine(owner.queue.map {it.current?.id}.distinctUntilChanged(),store.changes){id,_->id}.collectLatest {id ->
        if(id==null)return@collectLatest
        player.setCurrentFavorite(id,null)
        val key=owner.queueSource(id)?.libraryId?.takeIf(::validLibraryId) ?: return@collectLatest
        val value=try {withContext(Dispatchers.IO){store.isFavorite(key)}}
            catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){null}
        currentCoroutineContext().ensureActive();player.setCurrentFavorite(id,value)
    }
}
