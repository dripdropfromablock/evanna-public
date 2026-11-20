package com.evanna.player.user

import android.content.Context
import com.evanna.player.sources.SourceCatalog
import com.evanna.player.sources.SourceReference
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.hasTreeReadGrant

internal class PersonalSourceInfo(val title:String,val artist:String?,val available:Boolean)
internal fun personalSourceInfo(context:Context,id:String):PersonalSourceInfo? {
    SourceReference.fromPersonalId(id)?.let {ref ->
        val catalog=SourceCatalog(context)
        val item=catalog.store.item(ref) ?: return null
        return PersonalSourceInfo(item.title,item.artist,runCatching {catalog.available(ref)}.getOrDefault(false))
    }
    val song=LocalLibrary.database(context).song(id) ?: return null
    val file=playlistFile(context,id)
    return PersonalSourceInfo(song.title,song.artist,song.available && file?.available==true && hasTreeReadGrant(context,android.net.Uri.parse(file.root)))
}
