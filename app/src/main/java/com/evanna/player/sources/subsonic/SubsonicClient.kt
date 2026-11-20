package com.evanna.player.sources.subsonic

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.*
import com.evanna.player.sources.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Config: url, allowLanHttp; apiKey preferred, else username/password + authMode (token or password).
 * Password mode is an explicit legacy/LDAP choice; never downgrade authentication automatically. */
internal class SubsonicClient:SourceClient {
    /** Resolve fresh auth here: credential-bearing artwork URLs are never stored in item metadata. */
    fun artwork(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ByteArray? {
        val cover=item.details.optString("coverArt").takeIf {it.isNotBlank()} ?: return null
        val response=HttpAccess(connection.networkPolicy()).fetch(endpoint(connection.config,"getCoverArt",
            mapOf("id" to identifier(cover),"size" to "512")).toString(),300*1024,scope=cancel)
        if(response.code==401 || response.code==403)throw IOException("CONNECTION_LOGIN_REQUIRED")
        if(response.code!=200)return null
        if(response.headers["Content-Type"].orEmpty().substringBefore(';').lowercase() !in setOf("image/jpeg","image/png","image/webp"))return null
        return response.bytes
    }
    override fun browse(connection:SourceConnection,parent:SourceItem?,offset:Int,query:String?,cancel:CancelScope):SourcePage {
        require(offset in 0..10_000_000 && (query?.length ?: 0)<=256 && (parent==null || parent.folder))
        val config=connection.config
        if(!query.isNullOrBlank() && parent?.details?.optString("type")=="album") {
            val songs=request(config,"getAlbum",mapOf("id" to identifier(parent.details.getString("id"))),cancel).getJSONObject("album").optJSONArray("song") ?: JSONArray()
            require(songs.length()<=10000){"SOURCE_PAGE_TOO_LARGE"}
            val matches=JSONArray()
            for(index in 0 until songs.length())songs.getJSONObject(index).takeIf {it.optString("title").contains(query,true)}?.let(matches::put)
            return page(matches,"song",offset,true)
        }
        if(!query.isNullOrBlank()) {
            val params=mutableMapOf("query" to query,"artistCount" to "0","albumCount" to "0","songCount" to PAGE.toString(),"songOffset" to offset.toString())
            if(parent?.details?.optString("type")=="library")params["musicFolderId"]=parent.details.getString("id")
            val songs=request(config,"search3",params,cancel).optJSONObject("searchResult3")?.optJSONArray("song") ?: JSONArray()
            return page(songs,"song",offset,false)
        }
        if(parent==null) {
            val libraries=request(config,"getMusicFolders",emptyMap(),cancel).getJSONObject("musicFolders").optJSONArray("musicFolder") ?: JSONArray()
            return page(libraries,"library",offset,true)
        }
        val parentId=identifier(parent.details.getString("id"))
        return when(parent.details.getString("type")) {
            "library" -> {
                val albums=request(config,"getAlbumList2",mapOf("type" to "alphabeticalByName","musicFolderId" to parentId,
                    "size" to PAGE.toString(),"offset" to offset.toString()),cancel).optJSONObject("albumList2")?.optJSONArray("album") ?: JSONArray()
                page(albums,"album",offset,false)
            }
            "album" -> {
                val songs=request(config,"getAlbum",mapOf("id" to parentId),cancel).getJSONObject("album").optJSONArray("song") ?: JSONArray()
                page(songs,"song",offset,true)
            }
            else -> throw IOException("INVALID_SOURCE_FOLDER")
        }
    }

    override fun resolve(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ResolvedNetworkMedia {
        require(!item.folder);cancel.checkActive()
        val id=identifier(item.details.getString("id"))
        val song=request(connection.config,"getSong",mapOf("id" to id),cancel).getJSONObject("song")
        require(song.optString("id")==id && !song.optBoolean("isVideo",false)){"SOURCE_AUDIO_REQUIRED"}
        // Authentication query parameters are ephemeral: only the opaque SourceReference enters the queue.
        val url=endpoint(connection.config,"stream",mapOf("id" to id,"format" to "raw","maxBitRate" to "0"))
        return ResolvedNetworkMedia(url.toString(),emptyMap(),connection.networkPolicy())
    }

    companion object {
        private const val PAGE=100
        private val random=SecureRandom()
        fun authenticate(input:JSONObject,cancel:CancelScope):JSONObject {
            val config=JSONObject(input.toString())
            if(config.optString("apiKey").isNotBlank()) {
                require(config.getString("apiKey").length<=1024){"INVALID_SOURCE_AUTH"}
                config.remove("password");config.remove("authMode")
            } else {
                require(config.optString("username").isNotBlank() && config.has("password")){"CONNECTION_LOGIN_REQUIRED"}
                require(config.getString("username").length<=512 && config.getString("password").length<=4096){"INVALID_SOURCE_AUTH"}
                if(!config.has("authMode"))config.put("authMode","token")
                require(config.getString("authMode") in setOf("token","password")){"INVALID_SOURCE_AUTH"}
            }
            val ping=request(config,"ping",emptyMap(),cancel)
            config.put("serverVersion",ping.optString("version").take(64))
            // Legacy Subsonic has no extension endpoint; the ping response declares OpenSubsonic support.
            val extensions=if(ping.optBoolean("openSubsonic",false))
                request(config,"getOpenSubsonicExtensions",emptyMap(),cancel).optJSONArray("openSubsonicExtensions") ?: JSONArray()
                else JSONArray()
            require(extensions.length()<=100){"INVALID_SERVER_RESPONSE"}
            val apiKeySupported=(0 until extensions.length()).any {index ->
                val extension=extensions.getJSONObject(index);val versions=extension.optJSONArray("versions") ?: JSONArray()
                require(versions.length()<=16){"INVALID_SERVER_RESPONSE"}
                extension.optString("name")=="apiKeyAuthentication" && (0 until versions.length()).any {versions.optInt(it)==1}
            }
            config.put("apiKeySupported",apiKeySupported)
            if(config.optString("apiKey").isNotBlank() && !apiKeySupported)throw IOException("SOURCE_API_KEY_UNSUPPORTED")
            return config
        }
        internal fun endpoint(config:JSONObject,method:String,params:Map<String,String> = emptyMap()):HttpUrl {
            val base=config.getString("url").toHttpUrl();NetworkPolicy(base.toString(),config.optBoolean("allowLanHttp",false))
            require(base.query==null && method.matches(Regex("[A-Za-z0-9]+"))){"INVALID_SOURCE_ADDRESS"}
            val builder=base.newBuilder().apply {
                if(!base.encodedPath.endsWith('/'))addPathSegment("")
                addPathSegment("rest");addPathSegment("$method.view")
                addQueryParameter("v","1.16.1");addQueryParameter("c","Evanna");addQueryParameter("f","json")
            }
            val apiKey=config.optString("apiKey")
            if(apiKey.isNotBlank()) {
                builder.addQueryParameter("apiKey",apiKey)
                require(builder.build().encodedQuery.orEmpty().substringAfter("apiKey=").length<2048){"INVALID_SOURCE_AUTH"}
            }
            else {
                builder.addQueryParameter("u",config.getString("username"))
                val password=config.getString("password")
                when(config.optString("authMode","token")) {
                    "token" -> {
                        val salt=ByteArray(16).also(random::nextBytes).joinToString(""){"%02x".format(it)}
                        builder.addQueryParameter("t",token(password,salt));builder.addQueryParameter("s",salt)
                    }
                    "password" -> builder.addQueryParameter("p","enc:"+password.toByteArray(Charsets.UTF_8).joinToString(""){"%02x".format(it)})
                    else -> throw IOException("INVALID_SOURCE_AUTH")
                }
            }
            params.forEach {(key,value)->builder.addQueryParameter(key,value)}
            return builder.build()
        }
        internal fun token(password:String,salt:String)=MessageDigest.getInstance("MD5").digest((password+salt).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        private fun request(config:JSONObject,method:String,params:Map<String,String>,cancel:CancelScope):JSONObject {
            val policy=NetworkPolicy(config.getString("url"),config.optBoolean("allowLanHttp",false))
            val response=HttpAccess(policy).fetch(endpoint(config,method,params).toString(),2*1024*1024,scope=cancel)
            if(response.code==401 || response.code==403)throw IOException("CONNECTION_LOGIN_REQUIRED")
            if(response.code !in 200..299)throw IOException("SOURCE_SERVER_UNAVAILABLE")
            val json=try {JSONObject(response.bytes.toString(Charsets.UTF_8))}catch(_:Exception){throw IOException("INVALID_SERVER_RESPONSE")}
            return unwrap(json)
        }
        internal fun unwrap(json:JSONObject):JSONObject {
            val response=json.getJSONObject("subsonic-response")
            if(response.optString("status")!="ok")throw IOException(when(response.optJSONObject("error")?.optInt("code")) {
                40,50 -> "CONNECTION_LOGIN_REQUIRED"
                41,42,43,44 -> "SOURCE_AUTH_MODE_UNSUPPORTED"
                70 -> "SOURCE_ITEM_UNAVAILABLE"
                else -> "SOURCE_API_ERROR"
            })
            return response
        }
        private fun page(array:JSONArray,type:String,offset:Int,localPaging:Boolean):SourcePage {
            require(array.length()<=if(localPaging)10000 else PAGE){"SOURCE_PAGE_TOO_LARGE"}
            val start=if(localPaging)offset else 0;val end=minOf(start+PAGE,array.length())
            val entries=if(start>=array.length())emptyList() else (start until end).mapNotNull {entry(array.getJSONObject(it),type)}
            return SourcePage(entries,if(localPaging && end<array.length() || !localPaging && array.length()==PAGE)offset+PAGE else null)
        }
        internal fun entry(item:JSONObject,type:String):RemoteEntry? {
            if(item.optBoolean("isVideo",false))return null
            val id=identifier(item.getString("id"));val duration=if(item.has("duration"))item.getLong("duration").also {require(it in 0..Long.MAX_VALUE/1_000_000)}*1_000_000 else null
            fun label(value:String)=value.filterNot(Char::isISOControl).take(512).ifBlank {"Untitled"}
            return RemoteEntry("$type:$id",label(item.optString(if(type=="song")"title" else "name")),
                item.optString("artist").takeIf {it.isNotBlank()}?.let(::label),item.optString("album").takeIf {it.isNotBlank()}?.let(::label),
                duration,type!="song",JSONObject().put("id",id).put("type",type).apply {
                    item.optString("coverArt").takeIf {it.isNotBlank()}?.let {put("coverArt",identifier(it))}
                })
        }
        private fun identifier(value:String)=value.also {require(it.length in 1..2048 && it.none(Char::isISOControl)){"INVALID_SERVER_ITEM"}}
    }
}
