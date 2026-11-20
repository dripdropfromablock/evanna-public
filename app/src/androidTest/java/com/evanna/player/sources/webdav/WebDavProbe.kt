package com.evanna.player.sources.webdav

import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkPolicy

internal object WebDavProbe {
    fun run():String {
        val policy=NetworkPolicy("https://example.com/music/")
        val xml="""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/music/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/music/A%20%26%20B.flac</d:href><d:propstat><d:prop><d:displayname>A &amp; B</d:displayname><d:getcontenttype>audio/flac</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val entries=WebDavClient.parse(xml.toByteArray(),policy.origin,policy,CancelScope())
        check(entries.single().title=="A & B" && entries.single().key=="/music/A%20%26%20B.flac")
        for(href in listOf("https://evil.example/music/a.flac","/music/%2e%2e/private.flac","/elsewhere/a.flac","/music/a%2fb.flac","/music/a.flac?token=secret")) {
            check(runCatching {WebDavClient.childUrl(policy.origin,href,policy)}.isFailure){"Accepted unsafe href"}
        }
        val entity="""<!DOCTYPE x [<!ENTITY x SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:">&x;</d:multistatus>"""
        check(runCatching {WebDavClient.parse(entity.toByteArray(),policy.origin,policy,CancelScope())}.isFailure)
        val nested=("<d:multistatus xmlns:d=\"DAV:\">"+"<d:x>".repeat(30)+"</d:x>".repeat(30)+"</d:multistatus>").toByteArray()
        check(runCatching {WebDavClient.parse(nested,policy.origin,policy,CancelScope())}.isFailure)
        val failed=xml.replace("HTTP/1.1 200 OK","HTTP/1.1 403 Forbidden")
        check(WebDavClient.parse(failed.toByteArray(),policy.origin,policy,CancelScope()).isEmpty())
        val html=xml.replace("audio/flac","text/html")
        check(WebDavClient.parse(html.toByteArray(),policy.origin,policy,CancelScope()).isEmpty())
        check(runCatching {WebDavClient.parse(xml.repeat(20000).toByteArray(),policy.origin,policy,CancelScope())}.isFailure)
        check(runCatching {WebDavClient.parse(xml.toByteArray(),policy.origin,policy,CancelScope().apply {cancel()})}.isFailure)
        return "PASS: WebDAV namespaces, escaped paths, foreign/traversal/query rejection, DTD/size bounds and cancellation"
    }
}
