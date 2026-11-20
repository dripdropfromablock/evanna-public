package com.evanna.player.sources.jellyfin

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.*
import com.evanna.player.sources.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Config: url, allowLanHttp, accessToken, userId, deviceId. Login consumes username/password. */
internal class JellyfinClient:SourceClient {
    override fun browse(connection:SourceConnection,parent:SourceItem?,offset:Int,query:String?,cancel:CancelScope):SourcePage {
        return browse(connection, parent, offset, query, cancel, searchAlbums = false)
    }

    fun searchMusic(connection: SourceConnection, query: String, cancel: CancelScope): SourcePage =
        browse(connection, null, 0, query, cancel, searchAlbums = true)

    fun albumTracks(connection: SourceConnection, albumId: String, offset: Int, cancel: CancelScope): SourcePage =
        browse(connection, null, offset, null, cancel, searchAlbums = false, albumId = id(albumId))

    private fun browse(connection: SourceConnection, parent: SourceItem?, offset: Int, query: String?,
                       cancel: CancelScope, searchAlbums: Boolean, albumId: String? = null): SourcePage {
        require(offset in 0..10_000_000 && (query?.length ?: 0)<=256)
        val config=connection.config; val user=id(config.getString("userId"))
        if(parent==null && query.isNullOrBlank() && albumId == null) {
            val json=request(config,endpoint(config,"Users",user,"Views"),cancel)
            val items=json.getJSONArray("Items");require(items.length()<=1000){"SOURCE_PAGE_TOO_LARGE"}
            val libraries=(0 until items.length()).map {items.getJSONObject(it)}.filter {it.optString("CollectionType")=="music"}
                .map {RemoteEntry("library:${id(it.getString("Id"))}",label(it.optString("Name")),folder=true,
                    details=JSONObject().put("id",it.getString("Id")).put("type","library"))}
            return SourcePage(libraries.drop(offset).take(PAGE),if(offset+PAGE<libraries.size)offset+PAGE else null)
        }
        require(parent==null || parent.folder)
        val inAlbum = albumId != null || parent?.details?.optString("type") == "MusicAlbum"
        val url=endpoint(config,"Items").newBuilder().addQueryParameter("userId",user)
            .addQueryParameter("StartIndex",offset.toString()).addQueryParameter("Limit",PAGE.toString())
            .addQueryParameter("Recursive","true").addQueryParameter("SortBy",if(inAlbum)"ParentIndexNumber,IndexNumber,SortName" else "SortName").addQueryParameter("SortOrder","Ascending")
            .addQueryParameter("Fields","PrimaryImageAspectRatio,MediaSources").addQueryParameter("EnableTotalRecordCount","true")
            .addQueryParameter("IncludeItemTypes",when {
                searchAlbums -> "MusicAlbum,Audio"
                !query.isNullOrBlank() || inAlbum -> "Audio"
                else -> "MusicAlbum"
            })
        (albumId ?: parent?.details?.getString("id")?.let(::id))?.let { url.addQueryParameter("ParentId", it) }
        query?.takeIf {it.isNotBlank()}?.let {url.addQueryParameter("SearchTerm",it)}
        return parseItems(request(config,url.build(),cancel),offset).also {page ->
            page.entries.forEach {entry ->
                entry.details.optString("imageId").takeIf {it.isNotBlank()}?.let {imageId ->
                    entry.details.put("artwork",endpoint(config,"Items",imageId,"Images","Primary").newBuilder()
                        .addQueryParameter("maxWidth","512").addQueryParameter("quality","85").build().toString())
                }
            }
        }
    }

    override fun resolve(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ResolvedNetworkMedia {
        require(!item.folder);cancel.checkActive()
        val config=connection.config;val itemId=id(item.details.getString("id"))
        val json=request(config,endpoint(config,"Users",id(config.getString("userId")),"Items",itemId),cancel)
        require(json.optString("Type")=="Audio" && json.optString("Id")==itemId){"SOURCE_AUDIO_REQUIRED"}
        val url=endpoint(config,"Audio",itemId,"stream").newBuilder().addQueryParameter("static","true").build()
        return ResolvedNetworkMedia(url.toString(),headers(config),connection.networkPolicy())
    }

    companion object {
        private const val PAGE=100
        fun authenticate(input:JSONObject,cancel:CancelScope):JSONObject {
            val config=JSONObject(input.toString()).apply {remove("accessToken");put("deviceId",UUID.randomUUID().toString())}
            val username=config.getString("username");require(username.isNotBlank() && username.length<=512)
            val body=JSONObject().put("Username",username).put("Pw",config.getString("password")).toString()
            val json=request(config,endpoint(config,"Users","AuthenticateByName"),cancel,body)
            config.put("accessToken",json.getString("AccessToken")).put("userId",id(json.getJSONObject("User").getString("Id")))
            config.remove("password");headers(config)
            return config
        }
        internal fun endpoint(config:JSONObject,vararg segments:String):HttpUrl {
            val base=config.getString("url").toHttpUrl();NetworkPolicy(base.toString(),config.optBoolean("allowLanHttp",false))
            require(base.query==null){"INVALID_SOURCE_ADDRESS"}
            return base.newBuilder().apply {if(!base.encodedPath.endsWith('/'))addPathSegment("");segments.forEach {addPathSegment(it)}}.build()
        }
        internal fun headers(config:JSONObject):Map<String,String> {
            fun quoted(value:String):String {require(value.length in 1..4096 && value.none {it=='"' || it=='\\' || it.isISOControl()}){"INVALID_SOURCE_AUTH"};return value}
            val token=config.optString("accessToken").takeIf {it.isNotBlank()}?.let {", Token=\"${quoted(it)}\""}.orEmpty()
            return mapOf("Authorization" to "MediaBrowser Client=\"Evanna\", Device=\"Android\", DeviceId=\"${quoted(config.getString("deviceId"))}\", Version=\"1.6\"$token")
        }
        private fun request(config:JSONObject,url:HttpUrl,cancel:CancelScope,body:String?=null):JSONObject {
            val response=HttpAccess(NetworkPolicy(config.getString("url"),config.optBoolean("allowLanHttp",false)))
                .fetch(url.toString(),2*1024*1024,headers(config),if(body==null)"GET" else "POST",body,scope=cancel)
            if(response.code==401 || response.code==403)throw IOException("CONNECTION_LOGIN_REQUIRED")
            if(response.code !in 200..299)throw IOException("SOURCE_SERVER_UNAVAILABLE")
            return try {JSONObject(response.bytes.toString(Charsets.UTF_8))}catch(_:Exception){throw IOException("INVALID_SERVER_RESPONSE")}
        }
        internal fun parseItems(json:JSONObject,offset:Int):SourcePage {
            val items=json.getJSONArray("Items");require(items.length()<=PAGE){"SOURCE_PAGE_TOO_LARGE"}
            val entries=(0 until items.length()).mapNotNull {index ->
                val item=items.getJSONObject(index);val type=item.optString("Type")
                if(type!="MusicAlbum" && type!="Audio")return@mapNotNull null
                val itemId=id(item.getString("Id"));val ticks=if(item.has("RunTimeTicks"))item.getLong("RunTimeTicks").also {require(it>=0)} else null
                RemoteEntry("$type:$itemId",label(item.optString("Name")),item.optJSONArray("Artists")?.optString(0)?.let(::label),
                    item.optString("Album").takeIf {it.isNotBlank()}?.let(::label),ticks?.div(10),type=="MusicAlbum",
                    JSONObject().put("id",itemId).put("type",type).apply {
                        if (type == "Audio") {
                            val container = item.optString("Container").ifBlank {
                                item.optJSONArray("MediaSources")?.optJSONObject(0)?.optString("Container").orEmpty()
                            }
                            put("container", container)
                            put("disc", item.optInt("ParentIndexNumber"))
                            put("track", item.optInt("IndexNumber"))
                            if (item.optString("AlbumId").isNotBlank()) put("albumId", id(item.optString("AlbumId")))
                            put("albumArtist", item.optString("AlbumArtist"))
                        }
                        if(!item.optJSONObject("ImageTags")?.optString("Primary").isNullOrBlank())put("imageId",itemId)
                        else if(item.optString("AlbumPrimaryImageTag").isNotBlank() && item.optString("AlbumId").isNotBlank())put("imageId",id(item.getString("AlbumId")))
                    })
            }
            val next=offset+items.length()
            return SourcePage(entries,if(items.length()>0 && next<json.optInt("TotalRecordCount",next))next else null)
        }
        private fun id(value:String)=value.also {require(it.length in 1..2048 && it.none(Char::isISOControl)){"INVALID_SERVER_ITEM"}}
        private fun label(value:String)=value.filterNot(Char::isISOControl).take(512).ifBlank {"Untitled"}
    }
}
