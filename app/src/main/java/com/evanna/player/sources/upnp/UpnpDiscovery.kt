package com.evanna.player.sources.upnp

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.evanna.player.network.CancelScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Discovery results expire; only a user-selected, freshly described server becomes a connection. */
internal class DiscoveredUpnpServer(val location:String,val serverId:String,val expiresAtElapsedMs:Long,val bootId:String?)

internal object UpnpDiscovery {
    private const val TARGET="urn:schemas-upnp-org:device:MediaServer:1"
    const val RENDERER_TARGET="urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val MAX_RESULTS=128

    /** Call on an IO worker after a user action. Cancel with the owning screen; never runs in background. */
    fun discover(context:Context,cancel:CancelScope,target:String=TARGET):List<DiscoveredUpnpServer> {
        require(target in setOf(TARGET,RENDERER_TARGET))
        cancel.checkActive()
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork ?: throw IOException("UPNP_NETWORK_UNAVAILABLE")
        val properties=connectivity.getLinkProperties(network) ?: throw IOException("UPNP_NETWORK_UNAVAILABLE")
        // shortcut: discovery uses IPv4 on the active interface; add IPv6 multicast after device coverage.
        val address=properties.linkAddresses.map {it.address}.firstOrNull {it is Inet4Address && isLanAddress(it)}
            ?: throw IOException("UPNP_MULTICAST_UNAVAILABLE")
        val networkInterface=properties.interfaceName?.let(NetworkInterface::getByName) ?: throw IOException("UPNP_MULTICAST_UNAVAILABLE")
        val wifi=context.applicationContext.getSystemService(WifiManager::class.java)
        val lock=wifi?.createMulticastLock("evanna-upnp-discovery")?.apply {setReferenceCounted(false)}
        val deadline=SystemClock.elapsedRealtime()+4000
        try {
            lock?.acquire()
            MulticastSocket(null).use {socket ->
                socket.reuseAddress=true;socket.bind(InetSocketAddress(address,0));network.bindSocket(socket)
                socket.networkInterface=networkInterface;socket.timeToLive=2
                val unregister=cancel.watch {socket.close()}
                try {
                    cancel.checkActive()
                    val request=("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(request,request.size,InetAddress.getByName("239.255.255.250"),1900))
                    val results=linkedMapOf<String,DiscoveredUpnpServer>();var packets=0
                    while(SystemClock.elapsedRealtime()<deadline && packets<512 && results.size<MAX_RESULTS) {
                        cancel.checkActive()
                        if(connectivity.activeNetwork!=network)throw IOException("UPNP_NETWORK_CHANGED")
                        socket.soTimeout=minOf(250L,(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)).toInt()
                        val buffer=ByteArray(8193);val packet=DatagramPacket(buffer,buffer.size)
                        try {socket.receive(packet)}catch(_:SocketTimeoutException){continue}
                        packets++
                        if(packet.length>8192 || !isLanAddress(packet.address))continue
                        val found=parseResponse(packet.data.copyOfRange(packet.offset,packet.offset+packet.length),packet.address.hostAddress.orEmpty(),SystemClock.elapsedRealtime(),target) ?: continue
                        results[found.serverId]=found
                    }
                    cancel.checkActive()
                    return results.values.filter {it.expiresAtElapsedMs>SystemClock.elapsedRealtime()}
                }finally {unregister()}
            }
        }catch(failure:Exception) {
            cancel.checkActive()
            throw IOException(if(failure is SecurityException)"UPNP_LAN_PERMISSION_DENIED" else "UPNP_DISCOVERY_UNAVAILABLE")
        }finally {if(lock?.isHeld==true)lock.release()}
    }

    fun parseResponse(bytes:ByteArray,responderHost:String,nowElapsedMs:Long,target:String=TARGET):DiscoveredUpnpServer?=runCatching {
        require(bytes.size in 1..8192)
        val lines=bytes.toString(Charsets.US_ASCII).split("\r\n")
        require(lines.size in 2..65 && lines[0].trim().equals("HTTP/1.1 200 OK",true))
        val headers=mutableMapOf<String,String>()
        for(line in lines.drop(1)) {
            if(line.isEmpty())break
            require(!line.startsWith(' ') && !line.startsWith('\t'))
            val parts=line.split(':',limit=2);require(parts.size==2)
            val key=parts[0].lowercase();val value=parts[1].trim()
            require(headers.put(key,value)==null && value.none(Char::isISOControl))
        }
        require(headers["st"].equals(target,true))
        val id=headers.getValue("usn").substringBefore("::")
        require(id.startsWith("uuid:") && id.length in 6..256)
        val location=headers.getValue("location").toHttpUrlOrNull() ?: return@runCatching null
        validateUpnpUrl(location)
        // Never fetch an advertisement's arbitrary hostname or a different machine.
        require(location.host.equals(responderHost.substringBefore('%'),true))
        val age=Regex("(?:^|,)\\s*max-age\\s*=\\s*([0-9]{1,8})(?:\\s*(?:,|$))",RegexOption.IGNORE_CASE)
            .find(headers.getValue("cache-control"))?.groupValues?.get(1)?.toLongOrNull() ?: return@runCatching null
        require(age in 1..86400)
        DiscoveredUpnpServer(location.toString(),id,nowElapsedMs+age*1000,headers["bootid.upnp.org"]?.take(32))
    }.getOrNull()
}
