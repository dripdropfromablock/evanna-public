package com.evanna.player.sources.upnp

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Controlled protocol fixtures; no multicast, real server or speaker is required. */
internal object UpnpProbe {
    fun run():String {
        val location="http://192.168.1.5:8200/root/device.xml".toHttpUrl()
        val descriptor="""<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
            <deviceType>urn:schemas-upnp-org:device:MediaServer:2</deviceType><friendlyName>Fixture</friendlyName><UDN>uuid:fixture</UDN>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:2</serviceType><controlURL>/control</controlURL></service></serviceList>
            </device></root>"""
        val server=UpnpClient.parseDescription(descriptor.toByteArray(),location)
        check(server.name=="Fixture" && server.serverId=="uuid:fixture" && server.controlUrl.toString()=="http://192.168.1.5:8200/control")
        check(runCatching {UpnpClient.parseDescription(descriptor.replace("/control","http://192.168.1.6/control").toByteArray(),location)}.isFailure)
        check(runCatching {UpnpClient.parseDescription(descriptor.replace("/control","http://user:password@192.168.1.5:8200/control").toByteArray(),location)}.isFailure)
        check(runCatching {SecureUpnpXml.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><x>&x;</x>".toByteArray())}.isFailure)
        check(runCatching {SecureUpnpXml.parse(ByteArray(SecureUpnpXml.MAX_BYTES+1){32})}.isFailure)
        check(runCatching {SecureUpnpXml.parse(("<x>".repeat(33)+"</x>".repeat(33)).toByteArray())}.isFailure)
        check(SecureUpnpXml.parse("<x>&#65;&#x42;</x>".toByteArray()).text=="AB")
        val didl="""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="folder"><dc:title>Albums</dc:title></container>
            <item id="song&amp;1"><dc:title>A &amp; B</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><upnp:artist>Artist</upnp:artist>
            <res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_CI=1">/converted.mp3</res>
            <res protocolInfo="http-get:*:audio/flac:DLNA.ORG_CI=0" duration="01:02:03.125" size="5000000000">../song.flac</res></item>
            <item id="video"><dc:title>Movie</dc:title><upnp:class>object.item.videoItem</upnp:class></item></DIDL-Lite>"""
        val items=DidlParser.parse(didl.toByteArray(),server.baseUrl)
        check(items.objectCount==3 && items.entries.size==2 && items.entries[0].folder)
        val song=items.entries[1]
        check(song.key=="song&1" && song.title=="A & B" && song.durationUs==3723125000L)
        check(song.details.getString("mime")=="audio/flac" && song.details.getString("size")=="5000000000")
        check(items.resources["song&1"]=="http://192.168.1.5:8200/song.flac")
        val converted=DidlParser.parse(didl.replace("DLNA.ORG_CI=0","DLNA.ORG_CI=1").toByteArray(),server.baseUrl)
        check(converted.resources.isEmpty())
        check(DidlParser.parse(didl.replace("../song.flac","file:///etc/passwd").toByteArray(),server.baseUrl).resources.isEmpty())
        check(DidlParser.parse(didl.replace("../song.flac","https://user:secret@example.com/song.flac").toByteArray(),server.baseUrl).resources.isEmpty())
        val missing=DidlParser.parse(didl.replace(" duration=\"01:02:03.125\" size=\"5000000000\"","").toByteArray(),server.baseUrl).entries[1]
        check(missing.durationUs==null && !missing.details.has("size"))
        check(runCatching {DidlParser.parse(didl.replace("id=\"video\"","id=\"song&amp;1\"").toByteArray(),server.baseUrl)}.isFailure)
        val request=UpnpClient.browseBody(server.serviceType,"a<&\"",7,false)
        val requestTree=SecureUpnpXml.parse(request.toByteArray())
        check(requestTree.children.single().children.single().child("ObjectID")!!.text=="a<&\"")
        check(request.contains("<StartingIndex>7</StartingIndex>") && request.contains("<RequestedCount>100</RequestedCount>"))
        val soap="""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="${server.serviceType}"><Result>${xmlEscape(didl)}</Result><NumberReturned>3</NumberReturned><TotalMatches>105</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
        check(UpnpClient.parseBrowse(soap.toByteArray(),server,0).nextOffset==3)
        check(runCatching {UpnpClient.parseBrowse(soap.replace("<NumberReturned>3","<NumberReturned>0").toByteArray(),server,0)}.isFailure)
        val packet="HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\nUSN: uuid:fixture::urn:schemas-upnp-org:device:MediaServer:1\r\nLOCATION: http://192.168.1.5:8200/device.xml\r\nCACHE-CONTROL: max-age=1800\r\n\r\n"
        check(UpnpDiscovery.parseResponse(packet.toByteArray(),"192.168.1.5",10)!!.serverId=="uuid:fixture")
        check(UpnpDiscovery.parseResponse(packet.replace("192.168.1.5:8200","192.168.1.6:8200").toByteArray(),"192.168.1.5",10)==null)
        check(UpnpDiscovery.parseResponse(packet.replace("max-age=1800","max-age=0").toByteArray(),"192.168.1.5",10)==null)
        return "UPnP fixtures: descriptor origins, XML entity/size bounds, original audio, relative URLs, 64-bit size, paging, SOAP escaping, SSDP host/expiry verified"
    }
}
