package com.evanna.player.sources.plex

import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal class PlexPin(val id:Long,val code:String,val expiresAtMs:Long) {
    internal var nextPollAtMs=Long.MIN_VALUE
}
internal class PlexAccount(val id:String,val token:String)
internal class PlexServer(val id:String,val name:String,val token:String,val connections:List<String>)

/** Plex's documented traditional PIN flow. Never asks Evanna for a Plex password. */
internal class PlexAuth {
    // shortcut: legacy tokens need browser reconnect on revocation; adopt device JWT with minSdk28-compatible Ed25519.
    private val policy=NetworkPolicy("https://plex.tv")
    fun createPin(clientId:String,cancel:CancelScope):PlexPin {
        val response=request("/api/v2/pins?strong=true",clientId,cancel,method="POST")
        return parsePin(plexObject(response),nowMs())
    }
    /** Caller schedules polls on its worker at >=1 second intervals; expiry bounds the session. */
    fun pollPin(pin:PlexPin,clientId:String,cancel:CancelScope):String? = synchronized(pin) {
        val now=nowMs()
        if(now>=pin.expiresAtMs)throw IOException("PLEX_PIN_EXPIRED")
        if(now<pin.nextPollAtMs)throw IOException("PLEX_POLL_TOO_SOON")
        pin.nextPollAtMs=now+1000
        val response=request("/api/v2/pins/${pin.id}",clientId,cancel)
        checkPin(pin,plexObject(response),nowMs())
    }
    fun account(clientId:String,token:String,cancel:CancelScope):PlexAccount {
        val user=plexObject(request("/api/v2/user",clientId,cancel,token))
        return PlexAccount(plexId(user.optString("id")),plexHeader(token))
    }
    /** Resources contain only servers permitted by the authenticated account. No endpoints are probed here. */
    fun servers(clientId:String,account:PlexAccount,cancel:CancelScope,allowLanHttp:Boolean=false):List<PlexServer> {
        val resourceBody=request("/api/v2/resources?includeHttps=1&includeRelay=0",clientId,cancel,account.token,resources=true)
        val resources=try {plexJsonDepth(resourceBody);JSONArray(resourceBody)}catch(_:Exception){throw IOException("PLEX_RESPONSE_INVALID")}
        require(resources.length()<=256){"PLEX_RESPONSE_TOO_LARGE"}
        return buildList {
            for(index in 0 until resources.length()) {
                val server=resources.getJSONObject(index)
                if("server" !in server.optString("provides").split(',') || server.optBoolean("relay",false))continue
                val connections=server.optJSONArray("connections") ?: continue
                require(connections.length()<=32){"PLEX_RESPONSE_TOO_LARGE"}
                val urls=buildList {
                    for(c in 0 until connections.length()) {
                        val connection=connections.getJSONObject(c)
                        if(connection.optBoolean("relay",false))continue
                        runCatching {PlexClient.connectionUrl(connection.getString("uri"),allowLanHttp)}.getOrNull()?.let(::add)
                    }
                }.distinct().sortedBy {if(it.startsWith("https:"))0 else 1}
                val token=server.optString("accessToken")
                if(urls.isNotEmpty() && token.isNotBlank())add(PlexServer(plexHeader(server.getString("clientIdentifier")),
                    plexText(server.optString("name"),"Plex server"),plexHeader(token),urls))
            }
        }
    }
    private fun request(path:String,clientId:String,cancel:CancelScope,token:String?=null,method:String="GET",resources:Boolean=false):String {
        cancel.checkActive()
        val requestPolicy=if(resources)NetworkPolicy("https://clients.plex.tv")else policy
        val response=HttpAccess(requestPolicy).fetch(requestPolicy.origin.resolve(path)!!.toString(),maxBytes=1024*1024,
            headers=plexHeaders(clientId,token),method=method,body=if(method=="POST")"" else null,scope=cancel)
        plexStatus(response.code)
        return response.bytes.toString(Charsets.UTF_8)
    }
    companion object {
        internal fun nowMs()=TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
        internal fun parsePin(json:JSONObject,nowMs:Long):PlexPin {
            val id=json.getLong("id");require(id>0){"PLEX_PIN_INVALID"}
            val code=json.getString("code");require(code.matches(Regex("[a-zA-Z0-9]{4,128}"))){"PLEX_PIN_INVALID"}
            val seconds=json.optLong("expiresIn",300).coerceAtMost(600)
            if(seconds<=0)throw IOException("PLEX_PIN_EXPIRED")
            return PlexPin(id,code,Math.addExact(nowMs,seconds*1000))
        }
        internal fun checkPin(pin:PlexPin,json:JSONObject,nowMs:Long):String? {
            if(nowMs>=pin.expiresAtMs || json.optLong("expiresIn",1)<=0)throw IOException("PLEX_PIN_EXPIRED")
            require(json.getLong("id")==pin.id){"PLEX_PIN_INVALID"}
            return if(json.isNull("authToken") || json.optString("authToken").isBlank())null else plexHeader(json.getString("authToken"))
        }
        fun browserUrl(clientId:String,pin:PlexPin):String {
            fun encode(value:String)=URLEncoder.encode(value,"UTF-8")
            return "https://app.plex.tv/auth#?clientID=${encode(plexHeader(clientId))}&code=${encode(pin.code)}&context%5Bdevice%5D%5Bproduct%5D=Evanna"
        }
    }
}

internal fun plexStatus(code:Int) {
    when(code) {
        200->Unit
        401,403->throw IOException("CONNECTION_LOGIN_REQUIRED")
        404->throw IOException("PLEX_ITEM_UNAVAILABLE")
        in 300..399->throw IOException("PLEX_REDIRECT_NOT_ALLOWED")
        else->throw IOException("PLEX_REQUEST_FAILED")
    }
}
internal fun plexHeaders(clientId:String,token:String?=null)=buildMap {
    put("Accept","application/json");put("X-Plex-Product","Evanna");put("X-Plex-Client-Identifier",plexHeader(clientId))
    token?.let {put("X-Plex-Token",plexHeader(it))}
}
internal fun plexHeader(value:String):String {
    require(value.length in 1..8192 && value.all {it.code in 33..126}){"PLEX_CREDENTIAL_INVALID"};return value
}
internal fun plexId(value:String):String {
    require(value.matches(Regex("[0-9]{1,20}"))){"PLEX_ITEM_INVALID"};return value
}
internal fun plexText(value:String,fallback:String="Unknown"):String = value.filterNot(Char::isISOControl).take(512).ifBlank {fallback}
internal fun plexObject(value:String):JSONObject = try {plexJsonDepth(value);JSONObject(value)}
    catch(_:Exception){throw IOException("PLEX_RESPONSE_INVALID")}
internal fun plexJsonDepth(value:String) {
    var depth=0;var quoted=false;var escaped=false
    for(character in value) {
        if(quoted) {
            if(escaped)escaped=false else if(character=='\\')escaped=true else if(character=='"')quoted=false
        } else when(character) {
            '"'->quoted=true
            '{','['->{depth++;require(depth<=32){"PLEX_RESPONSE_INVALID"}}
            '}',']'->depth--
        }
    }
}
