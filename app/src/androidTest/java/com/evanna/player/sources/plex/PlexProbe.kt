package com.evanna.player.sources.plex

import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkPolicy
import org.json.JSONObject

internal object PlexProbe {
    fun run():String {
            val pin=PlexAuth.parsePin(JSONObject("""{"id":12,"code":"abcdef","expiresIn":30}"""),1000)
            check(pin.expiresAtMs==31000L)
            check(PlexAuth.browserUrl("client",pin).startsWith("https://app.plex.tv/auth#?"))
            check(runCatching {PlexAuth.parsePin(JSONObject("""{"id":12,"code":"abcdef","expiresIn":0}"""),1000)}.isFailure)
            check(runCatching {PlexAuth.checkPin(pin,JSONObject().put("id",12),31000)}.exceptionOrNull()?.message=="PLEX_PIN_EXPIRED")
            check(PlexAuth.checkPin(pin,JSONObject().put("id",12).put("authToken","secret"),2000)=="secret")
            check(runCatching {plexStatus(401)}.exceptionOrNull()?.message=="CONNECTION_LOGIN_REQUIRED")
            check(runCatching {plexStatus(302)}.isFailure)
            val cancelled=CancelScope().apply {cancel()}
            check(runCatching {PlexAuth().createPin("client",cancelled)}.isFailure)
            val policy=NetworkPolicy("https://music.example")
            for(key in listOf("https://evil.example/library/parts/1/file.flac","//evil.example/file","/library/parts/1/file?X-Plex-Token=secret","/library/parts/../metadata/1","/library/parts/1/%2e%2e/file","/library/parts/1/file#fragment")) {
                check(runCatching {PlexClient.partUrl(policy,key)}.isFailure){"Accepted malicious Part key"}
            }
            val data=JSONObject("""{"offset":0,"size":1,"totalSize":2,"Metadata":[{"type":"track","ratingKey":"10","title":"Song","grandparentTitle":"Artist","parentTitle":"Album","duration":1234,"Media":[{"id":"20","Part":[{"id":"30","key":"/library/parts/30/1/file.flac","file":"/private/path"}]},{"id":"21","Part":[{"id":"31","key":"/library/parts/31/1/file.mp3"}]}]}]}""")
            val page=PlexClient.parsePage(data,0)
            check(page.entries.size==2 && page.nextOffset==1)
            check(page.entries[0].key!=page.entries[1].key)
            check(page.entries[0].durationUs==1234000L)
            check(!page.entries[0].details.toString().contains("private"))
            val track=data.getJSONArray("Metadata").getJSONObject(0)
            check(PlexClient.originalPart(track,page.entries[1].details)=="/library/parts/31/1/file.mp3")
            track.getJSONArray("Media").remove(1)
            check(runCatching {PlexClient.originalPart(track,page.entries[1].details)}.isFailure)
            check(runCatching {PlexClient.parsePage(JSONObject(data.toString()).put("offset",1),0)}.isFailure)
            check(runCatching {PlexClient.connectionUrl("https://user:secret@music.example",false)}.isFailure)
            check(runCatching {PlexClient.connectionUrl("http://music.example",false)}.isFailure)
            check(runCatching {PlexClient.connectionUrl("https://music.example/?token=secret",false)}.isFailure)
            return "PASS: Plex PIN expiry, auth, pagination, version identity, private metadata and URL validation fixtures."
    }
}
