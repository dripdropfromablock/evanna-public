package com.evanna.player.sources.jellyfin

import org.json.JSONObject
import org.json.JSONArray

internal object JellyfinProbe {
    fun run():String {
        val page=JellyfinClient.parseItems(JSONObject("""{"Items":[{"Id":"a","Name":"Album","Type":"MusicAlbum"},{"Id":"t","Name":"Track","Type":"Audio","Artists":["Artist"],"RunTimeTicks":12340000},{"Id":"v","Name":"Video","Type":"Movie"}],"TotalRecordCount":3}"""),0)
        check(page.entries.size==2 && page.entries[0].folder)
        check(page.entries[1].durationUs==1234000L && page.entries[1].artist=="Artist")
        val config=JSONObject().put("url","https://example.com/jellyfin").put("deviceId","test").put("accessToken","secret")
        val url=JellyfinClient.endpoint(config,"Audio","id /?","stream").newBuilder().addQueryParameter("static","true").build()
        check(url.encodedPath=="/jellyfin/Audio/id%20%2F%3F/stream" && !url.toString().contains("secret"))
        check(JellyfinClient.headers(config)["Authorization"]!!.contains("Token=\"secret\""))
        check(runCatching {JellyfinClient.headers(JSONObject(config.toString()).put("accessToken","bad\"token"))}.isFailure)
        check(runCatching {JellyfinClient.parseItems(JSONObject("""{"Items":[{"Id":"a","Name":"Bad","Type":"Audio","RunTimeTicks":-1}]}"""),0)}.isFailure)
        val oversized=JSONArray();repeat(101){oversized.put(JSONObject().put("Id","$it").put("Name","T").put("Type","Audio"))}
        check(runCatching {JellyfinClient.parseItems(JSONObject().put("Items",oversized),0)}.isFailure)
        val next=JellyfinClient.parseItems(JSONObject("""{"Items":[{"Id":"a","Name":"A","Type":"Audio"}],"TotalRecordCount":12}"""),4)
        check(next.nextOffset==5)
        return "PASS: Jellyfin music filtering, ticks, original URL encoding and authorization boundary"
    }
}
