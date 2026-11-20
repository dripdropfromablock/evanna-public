package com.evanna.player.sources.plex

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.sources.*
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A connection is one Plex account, server and explicitly approved origin. */
internal class PlexClient:SourceClient {
    /** Validate the chosen endpoint against the resource's machine ID before persisting its token. */
    fun selectServer(server:PlexServer,url:String,account:PlexAccount,clientId:String,allowLanHttp:Boolean,cancel:CancelScope):JSONObject {
        val address=connectionUrl(url,allowLanHttp)
        require(address in server.connections){"PLEX_CONNECTION_NOT_LISTED"}
        val config=JSONObject().put("url",address).put("allowLanHttp",allowLanHttp).put("token",server.token)
            .put("clientId",plexHeader(clientId)).put("accountId",account.id).put("serverId",server.id)
        val connection=SourceConnection("",SourceKind.PLEX,server.name,config,true)
        val identity=get(connection,"/",cancel)
        if(identity.optString("machineIdentifier")!=server.id)throw IOException("PLEX_SERVER_IDENTITY_CHANGED")
        return config
    }
    fun libraries(connection:SourceConnection,cancel:CancelScope):List<RemoteEntry> {
        val libraries=get(connection,"/library/sections",cancel).optJSONArray("Directory") ?: JSONArray()
        require(libraries.length()<=256){"PLEX_RESPONSE_TOO_LARGE"}
        return buildList {
            for(index in 0 until libraries.length()) {
                val library=libraries.getJSONObject(index)
                if(library.optString("type")!="artist")continue
                val id=plexId(library.getString("key"))
                add(RemoteEntry("section:$id",plexText(library.optString("title"),"Music library"),folder=true,
                    details=JSONObject().put("sectionId",id).put("type","section")))
            }
        }
    }
    fun selectLibrary(connection:SourceConnection,libraryId:String,cancel:CancelScope):JSONObject {
        val id=plexId(libraryId)
        if(libraries(connection,cancel).none {it.details.optString("sectionId")==id})throw IOException("PLEX_LIBRARY_UNAVAILABLE")
        return JSONObject(connection.config.toString()).put("libraryId",id)
    }
    /** Compressed artwork only; the UI must decode with its usual dimension/memory bounds. */
    fun artwork(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ByteArray? {
        require(item.ref.connectionId==connection.id){"PLEX_CONNECTION_MISMATCH"}
        check(connection.enabled && connection.kind==SourceKind.PLEX){"CONNECTION_UNAVAILABLE"}
        val ratingKey=item.details.optString("ratingKey").takeIf {it.isNotBlank()} ?: return null
        val policy=policy(connection)
        val response=HttpAccess(policy).fetch(policy.origin.resolve("/library/metadata/${plexId(ratingKey)}/thumb")!!.toString(),
            maxBytes=2*1024*1024,headers=headers(connection)+("Accept" to "image/jpeg,image/png,image/webp"),scope=cancel)
        if(response.code==404)return null
        plexStatus(response.code)
        val type=response.headers["Content-Type"]?.substringBefore(';')?.lowercase()
        if(type !in setOf("image/jpeg","image/png","image/webp"))throw IOException("PLEX_ARTWORK_INVALID")
        return response.bytes
    }
    override fun browse(connection:SourceConnection,parent:SourceItem?,offset:Int,query:String?,cancel:CancelScope):SourcePage {
        require(parent==null || parent.ref.connectionId==connection.id){"PLEX_CONNECTION_MISMATCH"}
        require(offset in 0..1000000){"PLEX_PAGE_INVALID"}
        val search=query?.trim()?.takeIf {it.isNotEmpty()}
        require(search==null || search.length<=256 && search.none(Char::isISOControl)){"PLEX_QUERY_INVALID"}
        val section=parent?.details?.optString("sectionId")?.takeIf {it.isNotBlank()}
            ?: connection.config.optString("libraryId").takeIf {it.isNotBlank()}
        if(parent==null && section==null && search==null) {
            if(offset!=0)return SourcePage(emptyList())
            return SourcePage(libraries(connection,cancel))
        }
        if(search!=null && section==null)throw IOException("PLEX_CHOOSE_MUSIC_LIBRARY")
        val path=when {
            search!=null || parent==null || parent.details.optString("type")=="section"->"/library/sections/${plexId(checkNotNull(section))}/all"
            parent.folder->"/library/metadata/${plexId(parent.details.getString("ratingKey"))}/children"
            else->throw IOException("PLEX_FOLDER_REQUIRED")
        }
        val params=buildMap {
            put("X-Plex-Container-Start",offset.toString());put("X-Plex-Container-Size",PAGE_SIZE.toString())
            if(search!=null){put("type","10");put("title",search)}
            else if(path.endsWith("/all"))put("type","9")
        }
        return parsePage(get(connection,path,cancel,params),offset,section)
    }
    override fun resolve(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ResolvedNetworkMedia {
        require(item.ref.connectionId==connection.id){"PLEX_CONNECTION_MISMATCH"}
        require(!item.folder){"PLEX_TRACK_REQUIRED"}
        val ratingKey=plexId(item.details.getString("ratingKey"))
        val metadata=get(connection,"/library/metadata/$ratingKey",cancel).optJSONArray("Metadata")
            ?: throw IOException("PLEX_ITEM_UNAVAILABLE")
        if(metadata.length()!=1)throw IOException("PLEX_ITEM_UNAVAILABLE")
        val track=metadata.getJSONObject(0)
        if(track.optString("ratingKey")!=ratingKey || track.optString("type")!="track")throw IOException("PLEX_ITEM_UNAVAILABLE")
        val section=connection.config.optString("libraryId")
        if(section.isNotBlank() && track.optString("librarySectionID")!=section)throw IOException("PLEX_LIBRARY_CHANGED")
        val policy=policy(connection)
        val url=partUrl(policy,originalPart(track,item.details))
        return ResolvedNetworkMedia(url.toString(),headers(connection),policy)
    }
    private fun headers(connection:SourceConnection)=plexHeaders(connection.config.getString("clientId"),connection.config.getString("token"))
    private fun policy(connection:SourceConnection)=NetworkPolicy(connectionUrl(connection.config.getString("url"),
        connection.config.optBoolean("allowLanHttp",false)),connection.config.optBoolean("allowLanHttp",false))
    private fun get(connection:SourceConnection,path:String,cancel:CancelScope,params:Map<String,String> = emptyMap()):JSONObject {
        cancel.checkActive()
        check(connection.enabled && connection.kind==SourceKind.PLEX){"CONNECTION_UNAVAILABLE"}
        val policy=policy(connection)
        // Catalog paths are built only from validated IDs; server-supplied keys never become browse URLs.
        val url=policy.origin.resolve(path)!!.newBuilder().apply {params.forEach { (k,v)->addQueryParameter(k,v)}}.build()
        require(policy.sameOrigin(url)){"PLEX_ORIGIN_CHANGED"}
        val response=HttpAccess(policy).fetch(url.toString(),maxBytes=2*1024*1024,headers=headers(connection),scope=cancel)
        plexStatus(response.code)
        return try {plexObject(response.bytes.toString(Charsets.UTF_8)).getJSONObject("MediaContainer")}
        catch(_:Exception){throw IOException("PLEX_RESPONSE_INVALID")}
    }
    companion object {
        internal const val PAGE_SIZE=100
        internal fun connectionUrl(value:String,allowLanHttp:Boolean):String {
            val policy=NetworkPolicy(value,allowLanHttp);val address=policy.origin
            require(address.encodedPath=="/" && address.query==null){"PLEX_CONNECTION_INVALID"}
            return address.toString()
        }
        internal fun partUrl(policy:NetworkPolicy,key:String):HttpUrl {
            require(key.length<=2048 && key.matches(Regex("/library/parts/[0-9]+/(?:[0-9]+/)?[a-zA-Z0-9._-]+")) &&
                key.split('/').none {it=="." || it==".."}){"PLEX_PART_INVALID"}
            return policy.origin.resolve(key)!!.also {policy.validate(it);require(policy.sameOrigin(it)){"PLEX_ORIGIN_CHANGED"}}
        }
        internal fun originalPart(track:JSONObject,details:JSONObject):String {
            val mediaId=plexId(details.getString("mediaId"));val partId=plexId(details.getString("partId"))
            val media=track.optJSONArray("Media") ?: throw IOException("PLEX_VERSION_UNAVAILABLE")
            require(media.length()<=32){"PLEX_RESPONSE_TOO_LARGE"}
            for(index in 0 until media.length()) {
                val version=media.getJSONObject(index)
                if(version.optString("id")!=mediaId)continue
                val parts=version.optJSONArray("Part") ?: break
                // Multipart originals need decoder concatenation; never substitute a server transcode.
                if(parts.length()!=1)throw IOException("PLEX_MULTIPART_UNSUPPORTED")
                val part=parts.getJSONObject(0)
                if(part.optString("id")!=partId || part.optBoolean("exists",true).not() || part.optBoolean("accessible",true).not())break
                if(version.optString("protocol").lowercase() in setOf("hls","dash") || version.optString("audioDecision")=="transcode")
                    throw IOException("PLEX_ORIGINAL_UNAVAILABLE")
                return part.getString("key")
            }
            throw IOException("PLEX_VERSION_UNAVAILABLE")
        }
        internal fun parsePage(container:JSONObject,offset:Int,sectionId:String?=null):SourcePage {
            val records=container.optJSONArray("Metadata") ?: JSONArray()
            val size=container.optInt("size",records.length())
            require(container.optInt("offset",offset)==offset && size==records.length() && size in 0..PAGE_SIZE){"PLEX_PAGE_INVALID"}
            val next=offset+size
            val total=container.optInt("totalSize",-1)
            require(total==-1 || total>=next){"PLEX_PAGE_INVALID"}
            val entries=buildList {
                for(index in 0 until records.length()) {
                    val track=records.getJSONObject(index);val type=track.optString("type")
                    if(type !in setOf("artist","album","track"))continue
                    val ratingKey=plexId(track.getString("ratingKey"))
                    val title=plexText(track.optString("title"));val artist=plexText(track.optString("grandparentTitle",track.optString("parentTitle")),"").ifBlank {null}
                    val details=JSONObject().put("ratingKey",ratingKey).put("type",type)
                    sectionId?.let {details.put("sectionId",plexId(it))}
                    if(type!="track")add(RemoteEntry("$type:$ratingKey",title,artist=artist,folder=true,details=details))
                    else {
                        val media=track.optJSONArray("Media") ?: continue
                        require(media.length()<=32){"PLEX_RESPONSE_TOO_LARGE"}
                        for(m in 0 until media.length()) {
                            val version=media.getJSONObject(m);val parts=version.optJSONArray("Part") ?: continue
                            if(parts.length()!=1)continue
                            val mediaId=plexId(version.getString("id"));val partId=plexId(parts.getJSONObject(0).getString("id"))
                            val duration=track.optLong("duration",-1).takeIf {it in 0..Long.MAX_VALUE/1000}?.times(1000)
                            add(RemoteEntry("track:$ratingKey:$mediaId:$partId",title,artist,plexText(track.optString("parentTitle"),"").ifBlank {null},duration,
                                details=JSONObject(details.toString()).put("mediaId",mediaId).put("partId",partId)))
                        }
                    }
                }
            }
            return SourcePage(entries,if(size>0 && next<1000000 && (total>next || total==-1 && size==PAGE_SIZE))next else null)
        }
    }
}
