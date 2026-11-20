package com.evanna.player.sources.upnp

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.sources.*
import okhttp3.HttpUrl
import java.io.IOException
import java.net.InetAddress

internal class UpnpServer(val name:String,val serverId:String,val serviceType:String,val controlUrl:HttpUrl,val baseUrl:HttpUrl)

/** Config: url = selected device descriptor, allowLanHttp, optional serverId = UDN identity pin.
 * Browse starts at object 0; catalog stores each RemoteEntry.key as details._key. No credentials.
 * Resource URLs are never saved and BrowseMetadata resolves them again for each playback.
 */
internal class UpnpClient:SourceClient {
    fun describe(connection:SourceConnection,cancel:CancelScope):UpnpServer {
        require(connection.enabled){"CONNECTION_UNAVAILABLE"}
        cancel.checkActive()
        val policy=controlPolicy(connection)
        val response=HttpAccess(policy).fetch(policy.origin.toString(),scope=cancel)
        if(response.code!=200)throw IOException("UPNP_DESCRIPTION_UNAVAILABLE")
        val server=parseDescription(response.bytes,policy.origin)
        val expected=connection.config.optString("serverId")
        require(expected.isEmpty() || expected==server.serverId){"UPNP_SERVER_ID_CHANGED"}
        return server
    }

    override fun browse(connection:SourceConnection,parent:SourceItem?,offset:Int,query:String?,cancel:CancelScope):SourcePage {
        require(offset>=0){"UPNP_OFFSET_INVALID"}
        require(parent==null || parent.ref.connectionId==connection.id && parent.folder){"UPNP_FOLDER_INVALID"}
        val server=describe(connection,cancel)
        val id=parent?.details?.getString("_key") ?: "0"
        if(!query.isNullOrBlank()) {
            require(connection.config.optBoolean("titleSearch")){"SOURCE_SEARCH_NOT_SUPPORTED"}
            val response=request(connection,server,"Search",searchBody(server.serviceType,id,query,offset),cancel)
            return parsePage(response,server,offset,"Search").let {page->SourcePage(page.entries.filterNot {it.folder},page.nextOffset)}
        }
        return parseBrowse(browseRequest(connection,server,id,offset,false,cancel),server,offset)
    }

    fun supportsTitleSearch(connection:SourceConnection,server:UpnpServer,cancel:CancelScope):Boolean = try {
        val bytes=request(connection,server,"GetSearchCapabilities",body(server.serviceType,"GetSearchCapabilities",""),cancel)
        response(bytes,server,"GetSearchCapabilities").value("SearchCaps").split(',').any {it.trim() in setOf("*","dc:title")}
    }catch(_:Exception){cancel.checkActive();false}

    override fun resolve(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ResolvedNetworkMedia {
        require(item.ref.connectionId==connection.id && !item.folder){"UPNP_ITEM_INVALID"}
        val server=describe(connection,cancel);val key=item.details.getString("_key")
        val response=parseBrowseResult(browseRequest(connection,server,key,0,true,cancel),server)
        require(response.objectCount==1 && response.entries.singleOrNull()?.key==key){"UPNP_ITEM_UNAVAILABLE"}
        val url=response.resources[key] ?: throw IOException("UPNP_ORIGINAL_AUDIO_UNAVAILABLE")
        val policy=connection.networkPolicy()
        policy.validate(server.baseUrl.resolve(url)!!)
        return ResolvedNetworkMedia(url,emptyMap(),policy)
    }

    private fun browseRequest(connection:SourceConnection,server:UpnpServer,id:String,offset:Int,metadata:Boolean,cancel:CancelScope):ByteArray {
        require(id.length in 1..8192 && id.none(Char::isISOControl)){"UPNP_ITEM_ID_INVALID"}
        return request(connection,server,"Browse",browseBody(server.serviceType,id,offset,metadata),cancel)
    }

    private fun request(connection:SourceConnection,server:UpnpServer,action:String,body:String,cancel:CancelScope):ByteArray {
        val policy=controlPolicy(connection)
        val response=HttpAccess(policy).fetch(server.controlUrl.toString(),headers=mapOf("SOAPAction" to "\"${server.serviceType}#$action\""),
            method="POST",body=body,contentType="text/xml; charset=utf-8",scope=cancel)
        if(response.code!=200)throw IOException("UPNP_REQUEST_FAILED")
        return response.bytes
    }

    companion object {
        const val PAGE_SIZE=100
        private const val DEVICE="urn:schemas-upnp-org:device-1-0"
        private const val SOAP="http://schemas.xmlsoap.org/soap/envelope/"
        private val servicePattern=Regex("urn:schemas-upnp-org:service:ContentDirectory:[1-9][0-9]{0,2}")
        private val devicePattern=Regex("urn:schemas-upnp-org:device:MediaServer:[1-9][0-9]{0,2}")

        fun parseDescription(bytes:ByteArray,location:HttpUrl):UpnpServer {
            validateUpnpUrl(location)
            val root=SecureUpnpXml.parse(bytes)
            require(root.name=="root" && root.namespace==DEVICE){"UPNP_DESCRIPTION_INVALID"}
            val base=root.value("URLBase",DEVICE).let {if(it.isBlank())location else location.resolve(it) ?: error("UPNP_ADDRESS_INVALID")}
            validateUpnpUrl(base);requireSameOrigin(location,base)
            val device=root.child("device",DEVICE) ?: throw IOException("UPNP_DEVICE_MISSING")
            require(devicePattern.matches(device.value("deviceType",DEVICE))){"UPNP_NOT_MEDIA_SERVER"}
            val id=device.value("UDN",DEVICE)
            require(id.startsWith("uuid:") && id.length in 6..256 && id.none(Char::isISOControl)){"UPNP_SERVER_ID_INVALID"}
            val services=device.child("serviceList",DEVICE)?.children.orEmpty().filter {it.name=="service" && it.namespace==DEVICE}
            val service=services.filter {servicePattern.matches(it.value("serviceType",DEVICE))}
                .maxByOrNull {it.value("serviceType",DEVICE).substringAfterLast(':').toInt()} ?: throw IOException("UPNP_CONTENT_DIRECTORY_MISSING")
            val controlValue=service.value("controlURL",DEVICE);require(controlValue.isNotBlank())
            val control=base.resolve(controlValue) ?: throw IOException("UPNP_CONTROL_ADDRESS_INVALID")
            validateUpnpUrl(control);requireSameOrigin(location,control)
            val name=device.value("friendlyName",DEVICE).filterNot(Char::isISOControl).trim().take(120).ifBlank {"UPnP server"}
            return UpnpServer(name,id,service.value("serviceType",DEVICE),control,base)
        }

        fun browseBody(service:String,id:String,offset:Int,metadata:Boolean):String {
            require(servicePattern.matches(service) && offset>=0)
            return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="$SOAP" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="$service"><ObjectID>${xmlEscape(id)}</ObjectID><BrowseFlag>${if(metadata)"BrowseMetadata" else "BrowseDirectChildren"}</BrowseFlag><Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>${if(metadata)1 else PAGE_SIZE}</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"""
        }

        fun searchBody(service:String,id:String,query:String,offset:Int):String {
            require(id.length in 1..8192 && id.none(Char::isISOControl) && query.length in 1..200 && query.none(Char::isISOControl))
            require(offset>=0)
            val term=query.replace("\\","\\\\").replace("\"","\\\"")
            return body(service,"Search","<ContainerID>${xmlEscape(id)}</ContainerID>"+
                "<SearchCriteria>${xmlEscape("dc:title contains \"$term\"")}</SearchCriteria>"+
                "<Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>$PAGE_SIZE</RequestedCount><SortCriteria/>")
        }

        private fun body(service:String,action:String,arguments:String):String {
            require(servicePattern.matches(service))
            return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="$SOAP" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$arguments</u:$action></s:Body></s:Envelope>"""
        }

        fun parseBrowse(bytes:ByteArray,server:UpnpServer,offset:Int)=parsePage(bytes,server,offset,"Browse")

        private fun parsePage(bytes:ByteArray,server:UpnpServer,offset:Int,action:String):SourcePage {
            val response=response(bytes,server,action)
            val returned=count(response.value("NumberReturned"));val total=count(response.value("TotalMatches"))
            val page=parseResult(response,server)
            require(returned==page.objectCount && returned<=PAGE_SIZE && offset>=0 && offset.toLong()+returned<=Int.MAX_VALUE &&
                (returned==0 || offset.toLong()+returned<=total)){"UPNP_PAGING_INVALID"}
            val next=offset+returned
            require(returned>0 || next>=total){"UPNP_PAGING_NO_PROGRESS"}
            return SourcePage(page.entries,if(next<total)next else null)
        }

        private fun parseBrowseResult(bytes:ByteArray,server:UpnpServer):DidlPage {
            val response=response(bytes,server,"Browse")
            require(count(response.value("NumberReturned"))==1 && count(response.value("TotalMatches"))==1){"UPNP_ITEM_UNAVAILABLE"}
            return parseResult(response,server)
        }
        private fun parseResult(response:UpnpXmlNode,server:UpnpServer):DidlPage {
            val result=response.child("Result") ?: throw IOException("UPNP_RESULT_MISSING")
            return DidlParser.parse(result.text.toByteArray(Charsets.UTF_8),server.baseUrl)
        }
        private fun response(bytes:ByteArray,server:UpnpServer,action:String):UpnpXmlNode {
            val envelope=SecureUpnpXml.parse(bytes)
            require(envelope.name=="Envelope" && envelope.namespace==SOAP)
            return envelope.child("Body",SOAP)?.child("${action}Response",server.serviceType) ?: throw IOException("UPNP_RESPONSE_INVALID")
        }
        private fun count(value:String):Int {require(value.matches(Regex("[0-9]{1,10}")));return value.toIntOrNull() ?: throw IOException("UPNP_COUNT_INVALID")}
        private fun requireSameOrigin(first:HttpUrl,second:HttpUrl) {
            require(first.scheme==second.scheme && first.host==second.host && first.port==second.port){"UPNP_FOREIGN_CONTROL_ADDRESS"}
        }
        private fun controlPolicy(connection:SourceConnection)=NetworkPolicy(connection.config.getString("url"),
            connection.config.optBoolean("allowLanHttp",false),requireLan=true)
    }
}

internal fun isLanAddress(address:InetAddress)= !address.isAnyLocalAddress && !address.isMulticastAddress && !address.isLoopbackAddress &&
    (address.isSiteLocalAddress || address.isLinkLocalAddress || address.address.size==16 && (address.address[0].toInt() and 0xfe)==0xfc)
