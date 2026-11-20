package com.evanna.player.sources.upnp

import com.evanna.player.sources.RemoteEntry
import okhttp3.HttpUrl
import org.json.JSONObject

internal class DidlPage(val entries:List<RemoteEntry>,val objectCount:Int,val resources:Map<String,String>)

internal object DidlParser {
    private const val DIDL="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    private const val DC="http://purl.org/dc/elements/1.1/"
    private const val UPNP="urn:schemas-upnp-org:metadata-1-0/upnp/"
    fun parse(bytes:ByteArray,base:HttpUrl):DidlPage {
        val root=SecureUpnpXml.parse(bytes)
        require(root.name=="DIDL-Lite" && root.namespace==DIDL){"UPNP_DIDL_INVALID"}
        val objects=root.children.filter {it.namespace==DIDL && it.name in setOf("item","container")}
        require(objects.size<=UpnpClient.PAGE_SIZE){"UPNP_PAGE_TOO_LARGE"}
        val entries=mutableListOf<RemoteEntry>();val resources=mutableMapOf<String,String>();val keys=mutableSetOf<String>()
        for(node in objects) {
            val key=node.attributes["id"].orEmpty()
            require(key.length in 1..8192 && key.none(Char::isISOControl) && keys.add(key)){"UPNP_ITEM_ID_INVALID"}
            val folder=node.name=="container"
            if(!folder && !node.value("class",UPNP).startsWith("object.item.audioItem"))continue
            val details=JSONObject();var duration:Long?=null
            if(!folder) {
                val candidates=node.children.filter {it.name=="res" && it.namespace==DIDL}.mapNotNull {res ->
                    val protocol=res.attributes["protocolInfo"]?.split(':',limit=4) ?: return@mapNotNull null
                    if(protocol.size!=4 || protocol[0]!="http-get" || !protocol[2].startsWith("audio/",true))return@mapNotNull null
                    val flags=protocol[3].split(';')
                    if(flags.any {it.trim().equals("DLNA.ORG_CI=1",true)})return@mapNotNull null
                    val value=res.text.trim();if(value.isEmpty())return@mapNotNull null
                    val url=runCatching {base.resolve(value)?.also(::validateUpnpUrl)}.getOrNull() ?: return@mapNotNull null
                    Triple(res,url,flags.any {it.trim().equals("DLNA.ORG_CI=0",true)})
                }
                val selected=candidates.firstOrNull {it.third} ?: candidates.firstOrNull()
                if(selected!=null) {
                    val (res,url,original)=selected
                    resources[key]=url.toString()
                    details.put("mime",res.attributes.getValue("protocolInfo").split(':',limit=4)[2])
                        .put("protocolInfo",res.attributes.getValue("protocolInfo"))
                        .put("quality",if(original)"original" else "unspecified")
                    res.attributes["size"]?.toLongOrNull()?.takeIf {it>=0}?.let {details.put("size",it.toString())}
                    duration=durationUs(res.attributes["duration"])
                }else details.put("unavailable","UPNP_ORIGINAL_AUDIO_UNAVAILABLE")
            }
            node.value("albumArtURI",UPNP).takeIf {it.isNotBlank()}?.let {value ->
                runCatching {base.resolve(value)?.also(::validateUpnpUrl)}.getOrNull()?.let {details.put("artwork",it.toString())}
            }
            entries+=RemoteEntry(key,clean(node.value("title",DC)) ?: "Untitled",clean(node.value("artist",UPNP)),
                clean(node.value("album",UPNP)),duration,folder,details)
        }
        return DidlPage(entries,objects.size,resources)
    }
    private fun clean(value:String)=value.filterNot(Char::isISOControl).trim().take(512).ifBlank {null}
    private fun durationUs(value:String?):Long? {
        if(value==null || !value.matches(Regex("[0-9]{1,6}:[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,6})?")))return null
        val parts=value.split(':');val seconds=parts[2].split('.')
        return (parts[0].toLong()*3600+parts[1].toLong()*60+seconds[0].toLong())*1000000+
            if(seconds.size==2)seconds[1].padEnd(6,'0').toLong()else 0
    }
}

internal fun validateUpnpUrl(url:HttpUrl) {
    require(url.scheme in setOf("http","https") && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() && url.fragment==null){"UPNP_ADDRESS_INVALID"}
}
