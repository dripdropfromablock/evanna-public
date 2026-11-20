package com.evanna.player.sources.upnp

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.IOException

internal class UpnpXmlNode(val name:String,val namespace:String,val attributes:Map<String,String>) {
    val children=mutableListOf<UpnpXmlNode>()
    private val content=StringBuilder()
    val text:String get()=content.toString()
    fun append(value:String){content.append(value)}
    fun child(name:String,namespace:String?=null)=children.singleOrNull {it.name==name && (namespace==null || it.namespace==namespace)}
    fun value(name:String,namespace:String?=null)=child(name,namespace)?.text?.trim().orEmpty()
}

/** Pull-parser never processes declarations; reject them before reading any entity value. */
internal object SecureUpnpXml {
    const val MAX_BYTES=1024*1024
    fun parse(bytes:ByteArray):UpnpXmlNode {
        require(bytes.size<=MAX_BYTES){"UPNP_XML_TOO_LARGE"}
        try {
            val parser=XmlPullParserFactory.newInstance().apply {isNamespaceAware=true}.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
            parser.setInput(ByteArrayInputStream(bytes),null)
            val stack=ArrayDeque<UpnpXmlNode>();var root:UpnpXmlNode?=null;var nodes=0;var textBytes=0
            while(true) {
                when(parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT->break
                    XmlPullParser.DOCDECL->throw IOException("UPNP_XML_DECLARATION_NOT_ALLOWED")
                    XmlPullParser.START_TAG->{
                        require(++nodes<=10000 && stack.size<32 && parser.attributeCount<=32){"UPNP_XML_TOO_COMPLEX"}
                        val attributes=buildMap {
                            for(index in 0 until parser.attributeCount) {
                                if(parser.getAttributeNamespace(index).isNullOrEmpty())put(parser.getAttributeName(index),parser.getAttributeValue(index))
                            }
                        }
                        val node=UpnpXmlNode(parser.name,parser.namespace.orEmpty(),attributes)
                        if(stack.isEmpty()){require(root==null);root=node}else stack.last().children+=node
                        stack.addLast(node)
                    }
                    XmlPullParser.END_TAG->stack.removeLast()
                    XmlPullParser.TEXT,XmlPullParser.CDSECT,XmlPullParser.ENTITY_REF->{
                        if(parser.eventType==XmlPullParser.ENTITY_REF)require(parser.name in setOf("amp","lt","gt","quot","apos") || parser.name.startsWith("#"))
                        val value=parser.text ?: throw IOException("UPNP_XML_ENTITY_NOT_ALLOWED")
                        textBytes+=value.length;require(textBytes<=MAX_BYTES)
                        if(stack.isNotEmpty())stack.last().append(value)
                    }
                }
            }
            require(stack.isEmpty());return root ?: throw IOException("UPNP_XML_EMPTY")
        }catch(_:Exception){throw IOException("UPNP_XML_INVALID")}
    }
}

internal fun xmlEscape(value:String):String {
    require(value.none {it.isISOControl() && it!='\t' && it!='\r' && it!='\n'})
    return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}
