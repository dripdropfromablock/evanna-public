package com.evanna.player.casting

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.sources.upnp.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.Inet4Address

private const val DEVICE = "urn:schemas-upnp-org:device-1-0"
private const val SERVICE = "urn:schemas-upnp-org:service-1-0"
private const val SOAP = "http://schemas.xmlsoap.org/soap/envelope/"

internal data class RendererReading(val phase: String, val uri: String, val positionMs: Long?, val durationMs: Long?) {
    val stopped get() = phase == "STOPPED" || phase == "NO_MEDIA_PRESENT"
}

internal fun rendererCompleted(uri: String, previous: RendererReading?, current: RendererReading): Boolean {
    val duration = current.durationMs ?: return false
    if (previous == null || duration < 6000 || previous.durationMs != duration || previous.uri != uri || current.uri != uri ||
        previous.phase != "PLAYING" || current.phase != "STOPPED") return false
    val lastPosition = previous.positionMs ?: return false
    return lastPosition in (duration - 2000)..duration && current.positionMs == duration
}

internal class RendererLink(
    val peer: Inet4Address, val local: Inet4Address, private val connectivity: ConnectivityManager,
    val network: Network,
) {
    fun checkActive() {
        require(connectivity.activeNetwork == network && connectivity.getLinkProperties(network)?.linkAddresses
            ?.any { it.address == local } == true) { "RENDERER_NETWORK_CHANGED" }
    }
}

private data class ServiceEndpoint(val type: String, val control: HttpUrl, val description: HttpUrl)
private data class RendererDescription(val name: String, val id: String, val services: Map<String, ServiceEndpoint>)

internal class DlnaRendererClient private constructor(
    val name: String, val link: RendererLink?, private val http: HttpAccess,
    private val transport: ServiceEndpoint, private val transportActions: Set<String>,
    private val rendering: ServiceEndpoint?, private val volumeRange: IntRange?,
    private val sinkMime: Set<String>,
    private val reopen: ((CancelScope, String) -> DlnaRendererClient)?,
    private val deviceId: String,
) {
    val canPause get() = "Pause" in transportActions
    val canSeek get() = "Seek" in transportActions
    val volumeMin get() = volumeRange?.first ?: 0
    val volumeMax get() = volumeRange?.last ?: 0

    fun refresh(scope: CancelScope): DlnaRendererClient {
        val currentLink = link ?: return this
        if (runCatching { currentLink.checkActive() }.isSuccess) return this
        return checkNotNull(reopen)(scope, deviceId)
    }

    fun mimeFor(candidates: List<String>): String? =
        candidates.firstOrNull { it in sinkMime || "audio/*" in sinkMime || "*" in sinkMime }

    fun setItem(uri: String, mime: String, title: String, artist: String?, scope: CancelScope) {
        check(mimeFor(listOf(mime)) != null)
        val metadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${safeXml(title)}</dc:title><upnp:artist>${safeXml(artist.orEmpty())}</upnp:artist><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo="http-get:*:$mime:*">${xmlEscape(uri)}</res></item></DIDL-Lite>"""
        av("SetAVTransportURI", scope, "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
    }

    fun play(scope: CancelScope) { av("Play", scope, "Speed" to "1") }
    fun pause(scope: CancelScope) { av("Pause", scope) }
    fun stop(scope: CancelScope) { av("Stop", scope) }
    fun seek(positionMs: Long, scope: CancelScope) {
        val seconds = positionMs.coerceAtLeast(0) / 1000
        val target = "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
        av("Seek", scope, "Unit" to "REL_TIME", "Target" to target)
    }

    fun transportState(scope: CancelScope): String {
        val response = av("GetTransportInfo", scope)
        require(response.value("CurrentTransportStatus") == "OK") { "RENDERER_TRANSPORT_ERROR" }
        return response.value("CurrentTransportState").also {
            require(it in setOf("STOPPED", "NO_MEDIA_PRESENT", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING"))
        }
    }

    fun reading(scope: CancelScope): RendererReading {
        val phase = transportState(scope)
        if (phase == "NO_MEDIA_PRESENT") return RendererReading(phase, "", null, null)
        val position = av("GetPositionInfo", scope)
        val uri = position.value("TrackURI").takeUnless { it == "NOT_IMPLEMENTED" }.orEmpty()
        val duration = timeMs(position.value("TrackDuration"))?.takeIf { it > 0 }
        val at = timeMs(position.value("RelTime"))?.takeIf { duration == null || it <= duration }
        return RendererReading(phase, uri, at, duration)
    }

    fun volume(scope: CancelScope): Int? {
        val range = volumeRange ?: return null
        return rc("GetVolume", scope).value("CurrentVolume").toIntOrNull()?.takeIf { it in range }
    }

    fun setVolume(value: Int, scope: CancelScope) {
        val range = checkNotNull(volumeRange)
        rc("SetVolume", scope, "DesiredVolume" to value.coerceIn(range).toString())
    }

    private fun rc(action: String, scope: CancelScope, vararg args: Pair<String, String>): UpnpXmlNode {
        link?.checkActive()
        return soap(http, checkNotNull(rendering), action, scope, "InstanceID" to "0", "Channel" to "Master", *args)
    }

    private fun av(action: String, scope: CancelScope, vararg args: Pair<String, String>): UpnpXmlNode {
        link?.checkActive()
        require(action in transportActions) { "RENDERER_ACTION_NOT_SUPPORTED" }
        return soap(http, transport, action, scope, "InstanceID" to "0", *args)
    }

    companion object {
        fun connect(context: Context, address: String, allowLanHttp: Boolean, id: String?, scope: CancelScope): DlnaRendererClient {
            val location = address.toHttpUrl().also(::validateUpnpUrl)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: throw IOException("RENDERER_NETWORK_UNAVAILABLE")
            val properties = connectivity.getLinkProperties(network) ?: throw IOException("RENDERER_NETWORK_UNAVAILABLE")
            val peer = network.getAllByName(location.host).filterIsInstance<Inet4Address>().singleOrNull()
                ?: throw IOException("RENDERER_NEEDS_IPV4")
            require(isLanAddress(peer)) { "RENDERER_REQUIRES_LOCAL_NETWORK" }
            // shortcut: same IPv4 subnet only, until routed LANs have device coverage.
            val local = properties.linkAddresses.firstOrNull { link ->
                val bytes = link.address.address
                val remote = peer.address
                bytes.size == 4 && isLanAddress(link.address) && (0 until link.prefixLength).all {
                    val mask = 0x80 shr (it % 8)
                    (bytes[it / 8].toInt() and mask) == (remote[it / 8].toInt() and mask)
                }
            }?.address as? Inet4Address ?: throw IOException("RENDERER_REQUIRES_LOCAL_NETWORK")
            require(local != peer) { "RENDERER_ADDRESS_INVALID" }
            val link = RendererLink(peer, local, connectivity, network)
            link.checkActive()
            val policy = NetworkPolicy(location.toString(), allowLanHttp, requireLan = true, pinnedAddress = peer)
            return open(location, policy, HttpAccess(policy, network.socketFactory), id, scope, link) { nextScope, expected ->
                connect(context, address, allowLanHttp, expected, nextScope)
            }
        }

        internal fun fixture(address: String, scope: CancelScope): DlnaRendererClient {
            val location = address.toHttpUrl()
            val policy = NetworkPolicy(address, allowLanHttp = true, testLoopback = true, requireLan = true)
            return open(location, policy, HttpAccess(policy), null, scope, null, null)
        }

        private fun open(location: HttpUrl, policy: NetworkPolicy, http: HttpAccess, id: String?,
            scope: CancelScope, link: RendererLink?, reopen: ((CancelScope, String) -> DlnaRendererClient)?): DlnaRendererClient {
            fun xml(url: HttpUrl): UpnpXmlNode {
                link?.checkActive()
                val response = http.fetch(url.toString(), scope = scope)
                if (response.code != 200) throw IOException("RENDERER_DESCRIPTION_UNAVAILABLE")
                return SecureUpnpXml.parse(response.bytes)
            }
            val device = description(xml(location), location, policy)
            require(id == null || device.id == id) { "RENDERER_ID_CHANGED" }
            val transport = device.services["AVTransport"] ?: throw IOException("RENDERER_TRANSPORT_MISSING")
            val actions = actions(xml(transport.description))
            require(actions.containsAll(setOf("SetAVTransportURI", "Play", "Stop", "GetTransportInfo", "GetPositionInfo"))) {
                "RENDERER_TRANSPORT_UNSUPPORTED"
            }
            val manager = device.services["ConnectionManager"] ?: throw IOException("RENDERER_CAPABILITIES_MISSING")
            require("GetProtocolInfo" in actions(xml(manager.description)))
            link?.checkActive()
            val protocols = soap(http, manager, "GetProtocolInfo", scope).value("Sink").split(',').mapNotNull {
                val fields = it.trim().split(':', limit = 4)
                // Only HTTP transfers with no advertised profile restriction are selected here.
                if (fields.size == 4 && fields[0].equals("http-get", true) && fields[1] == "*" &&
                    !fields[3].contains("DLNA.ORG_PN=", true)) fields[2].trim().lowercase() else null
            }.toSet()
            require(protocols.any { it == "*" || it.startsWith("audio/") || it == "application/ogg" }) { "RENDERER_NO_AUDIO_FORMATS" }
            val rendering = device.services["RenderingControl"]
            val volumeRange = rendering?.let {
                val spec = xml(it.description)
                if (!actions(spec).containsAll(setOf("GetVolume", "SetVolume"))) null
                else {
                    val range = spec.child("serviceStateTable", SERVICE)?.children
                        ?.singleOrNull { variable -> variable.value("name", SERVICE) == "Volume" }
                        ?.child("allowedValueRange", SERVICE)
                    val min = range?.value("minimum", SERVICE)?.toIntOrNull()
                    val max = range?.value("maximum", SERVICE)?.toIntOrNull()
                    val step = range?.value("step", SERVICE).orEmpty()
                    when {
                        range == null -> 0..100
                        min != null && max != null && min in 0..65534 && max in (min + 1)..65535 && (step.isBlank() || step == "1") -> min..max
                        else -> null
                    }
                }
            }
            return DlnaRendererClient(device.name, link, http, transport, actions, rendering, volumeRange, protocols, reopen, device.id)
        }

        private fun actions(root: UpnpXmlNode): Set<String> {
            require(root.name == "scpd" && root.namespace == SERVICE)
            return root.child("actionList", SERVICE)?.children.orEmpty().filter { it.name == "action" && it.namespace == SERVICE }
                .map { it.value("name", SERVICE) }.toSet()
        }

        private fun description(root: UpnpXmlNode, location: HttpUrl, policy: NetworkPolicy): RendererDescription {
            require(root.name == "root" && root.namespace == DEVICE)
            fun checked(url: HttpUrl?): HttpUrl {
                requireNotNull(url)
                validateUpnpUrl(url)
                require(policy.sameOrigin(url)) { "RENDERER_FOREIGN_CONTROL_ADDRESS" }
                return url
            }
            val base = root.value("URLBase", DEVICE).let { if (it.isBlank()) location else checked(location.resolve(it)) }
            val deviceType = Regex("urn:schemas-upnp-org:device:MediaRenderer:[1-9][0-9]{0,2}")
            fun devices(parent: UpnpXmlNode): Sequence<UpnpXmlNode> = sequence {
                for (device in parent.children.filter { it.name == "device" && it.namespace == DEVICE }) {
                    yield(device)
                    device.child("deviceList", DEVICE)?.let { yieldAll(devices(it)) }
                }
            }
            val device = devices(root).singleOrNull { deviceType.matches(it.value("deviceType", DEVICE)) }
                ?: throw IOException("RENDERER_DEVICE_MISSING")
            val id = device.value("UDN", DEVICE)
            require(id.startsWith("uuid:") && id.length in 6..256 && id.none(Char::isISOControl))
            val pattern = Regex("urn:schemas-upnp-org:service:(AVTransport|RenderingControl|ConnectionManager):([1-9][0-9]{0,2})")
            val endpoints = mutableMapOf<String, ServiceEndpoint>()
            for (node in device.child("serviceList", DEVICE)?.children.orEmpty()) {
                if (node.name != "service" || node.namespace != DEVICE) continue
                val type = node.value("serviceType", DEVICE)
                val match = pattern.matchEntire(type) ?: continue
                val kind = match.groupValues[1]
                if ((endpoints[kind]?.type?.substringAfterLast(':')?.toInt() ?: 0) >= match.groupValues[2].toInt()) continue
                val control = node.value("controlURL", DEVICE).also { require(it.isNotBlank()) }
                val scpd = node.value("SCPDURL", DEVICE).also { require(it.isNotBlank()) }
                endpoints[kind] = ServiceEndpoint(type, checked(base.resolve(control)), checked(base.resolve(scpd)))
            }
            val label = device.value("friendlyName", DEVICE).filterNot(Char::isISOControl).take(100).ifBlank { "Network speaker" }
            return RendererDescription(label, id, endpoints)
        }
    }
}

private fun safeXml(value: String) = xmlEscape(value.filterNot(Char::isISOControl).take(512))

private fun soap(http: HttpAccess, service: ServiceEndpoint, action: String, scope: CancelScope,
    vararg args: Pair<String, String>): UpnpXmlNode {
    val parameters = args.joinToString("") { (name, value) -> "<$name>${xmlEscape(value)}</$name>" }
    val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="$SOAP" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${service.type}">$parameters</u:$action></s:Body></s:Envelope>"""
    val response = http.fetch(service.control.toString(), headers = mapOf("SOAPAction" to "\"" + service.type + "#$action\""),
        method = "POST", body = body, contentType = "text/xml; charset=utf-8", scope = scope)
    if (response.code != 200) throw IOException("RENDERER_COMMAND_FAILED")
    val envelope = SecureUpnpXml.parse(response.bytes)
    require(envelope.name == "Envelope" && envelope.namespace == SOAP)
    return envelope.child("Body", SOAP)?.child("${action}Response", service.type)
        ?: throw IOException("RENDERER_RESPONSE_INVALID")
}

private val upnpTime = Regex("([0-9]{1,6}):([0-5][0-9]):([0-5][0-9])(?:\\.([0-9]{1,3}))?")
private fun timeMs(input: String): Long? {
    val match = upnpTime.matchEntire(input) ?: return null
    val (_, h, m, s, fraction) = match.groupValues
    return (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + fraction.padEnd(3, '0').toInt()
}
