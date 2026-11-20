package com.evanna.player.sources

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkPolicy
import org.json.JSONObject

internal class RemoteEntry(val key:String,val title:String,val artist:String?=null,val album:String?=null,
    val durationUs:Long?=null,val folder:Boolean=false,val details:JSONObject=JSONObject())
internal class SourcePage(val entries:List<RemoteEntry>,val nextOffset:Int?=null)

/** Adapters return private resolution details; only the catalog assigns public opaque identities. */
internal interface SourceClient {
    fun browse(connection:SourceConnection,parent:SourceItem?,offset:Int,query:String?,cancel:CancelScope):SourcePage
    fun resolve(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ResolvedNetworkMedia
}

internal fun SourceConnection.networkPolicy()=NetworkPolicy(config.getString("url"),config.optBoolean("allowLanHttp",false))
