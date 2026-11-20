package com.evanna.player.sources.subsonic

import org.json.JSONObject

internal object SubsonicProbe {
    fun run():String {
        val config=JSONObject().put("url","https://example.com/music").put("apiKey","private key").put("username","must-not-send")
        val url=SubsonicClient.endpoint(config,"stream",mapOf("id" to "id /?","format" to "raw"))
        check(url.encodedPath=="/music/rest/stream.view" && url.queryParameter("u")==null)
        check(url.queryParameter("apiKey")=="private key" && url.queryParameter("format")=="raw")
        check(SubsonicClient.token("password","salt")=="b305cadbb3bce54f3aa59c64fec00dea")
        val envelope=JSONObject("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"secret"}}}""")
        val failure=runCatching {SubsonicClient.unwrap(envelope)}.exceptionOrNull()!!
        check(failure.message=="CONNECTION_LOGIN_REQUIRED" && !failure.toString().contains("secret"))
        val entry=SubsonicClient.entry(JSONObject("""{"id":"string-id","title":"Song","duration":3,"isVideo":false}"""),"song")!!
        check(entry.key=="song:string-id" && entry.durationUs==3000000L)
        check(SubsonicClient.entry(JSONObject("""{"id":"v","title":"Video","isVideo":true}"""),"song")==null)
        check(runCatching {SubsonicClient.entry(JSONObject("""{"id":"x","title":"Bad","duration":9223372036854775807}"""),"song")}.isFailure)
        val legacy=JSONObject().put("url","https://example.com").put("username","User").put("password","never-in-url")
        val first=SubsonicClient.endpoint(legacy,"ping");val second=SubsonicClient.endpoint(legacy,"ping")
        check(first.queryParameter("s")!=second.queryParameter("s") && first.queryParameter("p")==null && !first.toString().contains("never-in-url"))
        check(runCatching {SubsonicClient.endpoint(JSONObject(legacy.toString()).put("url","https://example.com/?credential=bad"),"ping")}.isFailure)
        return "PASS: OpenSubsonic API-key isolation, token fixture, safe errors, original stream contract and audio filtering"
    }
}
