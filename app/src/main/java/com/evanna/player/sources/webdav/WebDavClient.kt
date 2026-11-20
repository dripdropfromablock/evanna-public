package com.evanna.player.sources.webdav

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.*
import com.evanna.player.sources.*
import okhttp3.Credentials
import okhttp3.HttpUrl
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** Config: collection url (with optional base path), allowLanHttp, username, password; read-only Basic auth. */
internal class WebDavClient:SourceClient {
    override fun browse(connection:SourceConnection,parent:SourceItem?,offset:Int,query:String?,cancel:CancelScope):SourcePage {
        require(offset in 0..10000 && (query?.length ?: 0)<=256 && (parent==null || parent.folder))
        val policy=policy(connection.config)
        val directory=parent?.let {childUrl(policy.origin,it.details.getString("path"),policy)} ?: policy.origin
        require(directory.encodedPath.endsWith('/')){"INVALID_SOURCE_FOLDER"}
        val response=HttpAccess(policy).fetch(directory.toString(),MAX_BYTES,headers(connection.config)+mapOf("Depth" to "1"),
            "PROPFIND",PROPFIND,"application/xml; charset=utf-8",cancel)
        status(response.code)
        if(response.code!=207)throw IOException("WEBDAV_MULTISTATUS_REQUIRED")
        val entries=parse(response.bytes,directory,policy,cancel)
            .filter {query.isNullOrBlank() || it.title.contains(query,true)}
            .sortedWith(compareByDescending<RemoteEntry> {it.folder}.thenBy {it.title.lowercase()}.thenBy {it.key})
        return SourcePage(entries.drop(offset).take(PAGE),if(offset+PAGE<entries.size)offset+PAGE else null)
    }

    override fun resolve(connection:SourceConnection,item:SourceItem,cancel:CancelScope):ResolvedNetworkMedia {
        require(!item.folder);val policy=policy(connection.config)
        val url=childUrl(policy.origin,item.details.getString("path"),policy)
        val auth=headers(connection.config)
        val response=HttpAccess(policy).fetch(url.toString(),1024,auth,method="HEAD",scope=cancel)
        status(response.code)
        if(!audio(url,response.headers["Content-Type"].orEmpty()))throw IOException("SOURCE_AUDIO_REQUIRED")
        return ResolvedNetworkMedia(url.toString(),auth,policy)
    }

    companion object {
        private const val PAGE=100
        private const val MAX_BYTES=2*1024*1024
        private const val PROPFIND="<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"
        fun validate(input:JSONObject,cancel:CancelScope):JSONObject {
            val config=JSONObject(input.toString());val policy=policy(config)
            config.put("url",policy.origin.toString());headers(config)
            WebDavClient().browse(SourceConnection("",SourceKind.WEBDAV,"",config,true),null,0,null,cancel)
            return config
        }
        private fun policy(config:JSONObject):NetworkPolicy {
            checkPath(URI(config.getString("url")).rawPath.orEmpty())
            val policy=NetworkPolicy(config.getString("url"),config.optBoolean("allowLanHttp",false))
            require(policy.origin.query==null){"INVALID_SOURCE_ADDRESS"}
            val base=policy.origin.newBuilder().apply {if(!policy.origin.encodedPath.endsWith('/'))addPathSegment("")}.build()
            checkPath(base.encodedPath)
            return NetworkPolicy(base.toString(),config.optBoolean("allowLanHttp",false))
        }
        private fun headers(config:JSONObject):Map<String,String> {
            val username=config.optString("username");val password=config.optString("password")
            require(username.length<=512 && password.length<=4096 && ':' !in username && username.none(Char::isISOControl) && password.none(Char::isISOControl)){"INVALID_SOURCE_AUTH"}
            return if(username.isEmpty() && password.isEmpty())emptyMap() else mapOf("Authorization" to Credentials.basic(username,password,Charsets.UTF_8))
        }
        private fun status(code:Int) {
            if(code==401 || code==403)throw IOException("CONNECTION_LOGIN_REQUIRED")
            if(code !in 200..299)throw IOException("SOURCE_SERVER_UNAVAILABLE")
        }
        internal fun childUrl(directory:HttpUrl,href:String,policy:NetworkPolicy):HttpUrl {
            require(href.length in 1..8192 && href.none(Char::isISOControl) && '\\' !in href){"INVALID_WEBDAV_PATH"}
            val raw=try {URI(href)}catch(_:Exception){throw IOException("INVALID_WEBDAV_PATH")}
            require(raw.rawQuery==null && raw.rawFragment==null && raw.rawUserInfo==null){"INVALID_WEBDAV_PATH"}
            checkPath(raw.rawPath.orEmpty())
            val url=directory.resolve(href) ?: throw IOException("INVALID_WEBDAV_PATH")
            policy.validate(url)
            require(policy.sameOrigin(url) && url.encodedPath.startsWith(policy.origin.encodedPath)){"WEBDAV_PATH_OUTSIDE_COLLECTION"}
            return url
        }
        private fun checkPath(path:String) {
            path.split('/').forEach {segment ->
                val decoded=try {URLDecoder.decode(segment.replace("+","%2B"),"UTF-8")}catch(_:Exception){throw IOException("INVALID_WEBDAV_PATH")}
                require(decoded!="." && decoded!=".." && decoded.none {it=='/' || it=='\\' || it.isISOControl()} &&
                    !Regex("%(2e|2f|5c|25)",RegexOption.IGNORE_CASE).containsMatchIn(decoded)){"INVALID_WEBDAV_PATH"}
            }
        }
        private fun audio(url:HttpUrl,contentType:String):Boolean {
            val mime=contentType.substringBefore(';').trim().lowercase()
            if(mime.startsWith("text/") || mime.contains("html") || mime.contains("xml") || mime.contains("json"))return false
            if(mime.startsWith("audio/") || mime in setOf("application/ogg","application/flac"))return true
            return mime in setOf("","application/octet-stream") && url.pathSegments.last().substringAfterLast('.').lowercase() in
                setOf("flac","wav","wave","mp3","m4a","aac","ogg","opus","aiff","aif","alac","ape","wv","dsf","dff")
        }
        private class Node(val name:String,val namespace:String,val children:MutableList<Node> = mutableListOf(),val text:StringBuilder=StringBuilder()) {
            fun child(name:String)=children.firstOrNull {it.namespace=="DAV:" && it.name==name}
            fun value(name:String)=child(name)?.text?.toString().orEmpty()
        }
        internal fun parse(bytes:ByteArray,directory:HttpUrl,policy:NetworkPolicy,cancel:CancelScope):List<RemoteEntry> {
            require(bytes.size in 1..MAX_BYTES){"WEBDAV_RESPONSE_TOO_LARGE"};cancel.checkActive()
            try {
                val parser=XmlPullParserFactory.newInstance().apply {isNamespaceAware=true}.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
                parser.setInput(ByteArrayInputStream(bytes),null)
                val stack=ArrayDeque<Node>();var root:Node?=null;var nodes=0;var totalText=0
                while(true) {
                    cancel.checkActive()
                    when(parser.nextToken()) {
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.DOCDECL -> throw IOException("WEBDAV_DTD_NOT_ALLOWED")
                        XmlPullParser.START_TAG -> {
                            require(++nodes<=100000 && stack.size<24 && parser.attributeCount<=32){"WEBDAV_XML_LIMIT"}
                            val node=Node(parser.name,parser.namespace.orEmpty())
                            if(stack.isEmpty()){require(root==null);root=node}else stack.last().children+=node
                            stack.addLast(node)
                        }
                        XmlPullParser.END_TAG -> stack.removeLast()
                        XmlPullParser.TEXT,XmlPullParser.CDSECT,XmlPullParser.ENTITY_REF -> {
                            if(parser.eventType==XmlPullParser.ENTITY_REF)require(parser.name in setOf("amp","lt","gt","quot","apos") || parser.name.startsWith("#"))
                            val text=parser.text.orEmpty();totalText+=text.length;require(totalText<=MAX_BYTES)
                            stack.lastOrNull()?.text?.let {require(it.length+text.length<=8192);it.append(text)}
                        }
                    }
                }
                require(stack.isEmpty());val document=root ?: throw IOException("INVALID_WEBDAV_RESPONSE")
                require(document.name=="multistatus" && document.namespace=="DAV:")
                val responses=document.children.filter {it.name=="response" && it.namespace=="DAV:"}
                require(responses.size<=10001){"WEBDAV_DIRECTORY_TOO_LARGE"}
                val entries=responses.mapNotNull {response ->
                    val href=response.value("href");val url=childUrl(directory,href,policy)
                    if(url.encodedPath.trimEnd('/')==directory.encodedPath.trimEnd('/'))return@mapNotNull null
                    require(url.encodedPath.startsWith(directory.encodedPath) &&
                        '/' !in url.encodedPath.removePrefix(directory.encodedPath).trimEnd('/')){"WEBDAV_DEPTH_VIOLATION"}
                    val props=response.children.filter {it.name=="propstat" && it.namespace=="DAV:" && Regex("HTTP/\\S+ 200(?: .*)?").matches(it.value("status").trim())}
                        .mapNotNull {it.child("prop")}
                    fun value(name:String)=props.firstNotNullOfOrNull {it.child(name)?.text?.toString()}.orEmpty()
                    if(props.isEmpty())return@mapNotNull null
                    val folder=props.any {it.child("resourcetype")?.child("collection")!=null}
                    val path=if(folder && !url.encodedPath.endsWith('/'))url.encodedPath+"/" else url.encodedPath
                    if(!folder && !audio(url,value("getcontenttype")))return@mapNotNull null
                    val title=value("displayname").ifBlank {url.pathSegments.lastOrNull {it.isNotEmpty()}.orEmpty()}.filterNot(Char::isISOControl).take(512).ifBlank {"Untitled"}
                    RemoteEntry(path,title,folder=folder,details=JSONObject().put("path",path))
                }
                return entries.distinctBy {it.key}
            } catch(failure:Exception) {
                if(cancel.cancelled)throw IOException("SOURCE_CANCELLED")
                throw IOException("INVALID_WEBDAV_RESPONSE")
            }
        }
    }
}
