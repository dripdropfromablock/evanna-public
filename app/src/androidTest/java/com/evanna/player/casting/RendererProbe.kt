package com.evanna.player.casting

import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.util.UnstableApi
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.playback.QueueSource
import com.evanna.player.playback.EvannaPlayer
import com.evanna.player.playback.PlaybackFocus
import com.evanna.player.playback.PlaybackRoute
import com.evanna.player.playback.PlayerUiProbe
import com.evanna.player.playback.UsbPlaybackController
import com.evanna.player.playback.UsbSessionOwner
import com.evanna.player.sources.upnp.SecureUpnpXml
import com.evanna.player.sources.upnp.xmlEscape
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
internal object RendererProbe {
    fun run(): String = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val bytes = ByteArray(4096) { (it % 255).toByte() }
        var relayAddress: String? = null
        val session = RemotePlaybackSession { _, _, _ ->
            PrivateTrackRelay.fixture(loopback, bytes).also { relayAddress = it.uri }
        }
        val item = QueueSource(null, "Fixture & song", "Artist", null, 100_000_000L) { error("Phone decoder started") }
        Speaker(loopback).use { speaker ->
            val client = DlnaRendererClient.fixture(speaker.url, CancelScope())
            check(client.canSeek && client.canPause)
            check(client.mimeFor(listOf("audio/mp4", "audio/flac")) == "audio/flac")
            check(client.mimeFor(listOf("audio/wav")) == null)
            session.connect(client)
            check(session.play(item) && session.state.value?.playing == true)
            val uri = checkNotNull(relayAddress)
            check(speaker.track == uri && speaker.title == "Fixture & song")
            val policy = NetworkPolicy(uri, allowLanHttp = true, testLoopback = true)
            val http = HttpAccess(policy)
            check(http.fetch(uri, headers = mapOf("Range" to "bytes=3-8")).let { response ->
                response.code == 206 && response.headers["Content-Range"] == "bytes 3-8/4096" &&
                    response.bytes.contentEquals(bytes.copyOfRange(3, 9))
            })
            check(http.fetch(uri, method = "HEAD").let { it.code == 200 && it.bytes.isEmpty() && it.headers["Content-Length"] == "4096" })
            check(http.fetch(uri, headers = mapOf("Range" to "bytes=0-1,4-5")).code == 416)
            check(http.fetch(uri.substringBeforeLast('/') + "/different").code == 404)
            check(session.pause() && session.state.value?.phase == RendererPhase.PAUSED)
            check(session.play(item) && relayAddress == uri)
            check(session.seek(40_000) && session.state.value?.positionMs == 40_000L)
            check(session.setVolume(55) && session.state.value?.volume == 55)
            speaker.position = "00:00:42"
            speaker.phase = "STOPPED"
            check(!session.poll() && session.state.value?.phase == RendererPhase.STOPPED)
            check(runCatching { http.fetch(uri) }.isFailure)

            check(session.play(item) && relayAddress != uri)
            speaker.position = "00:01:39"
            check(!session.poll())
            speaker.position = "00:01:40"
            speaker.phase = "STOPPED"
            check(session.poll() && session.state.value?.phase == RendererPhase.ENDED)
            check(!session.poll())
            speaker.track = "http://192.168.1.30/external-after-completion"
            speaker.phase = "PLAYING"
            check(!session.play(item, automatic = true))
            check(session.state.value?.phase == RendererPhase.EXTERNAL && speaker.track.endsWith("/external-after-completion"))

            check(session.play(item))
            speaker.acknowledgeStop = false
            check(!session.disconnect() && session.selected && session.state.value?.phase == RendererPhase.UNREACHABLE)
            val revoked = checkNotNull(relayAddress)
            check(runCatching { HttpAccess(NetworkPolicy(revoked, true, true)).fetch(revoked) }.isFailure)
            speaker.acknowledgeStop = true
            check(session.disconnect() && !session.selected && speaker.phase == "STOPPED")

            session.connect(client)
            check(session.play(item))
            speaker.track = "http://192.168.1.30/external"
            check(!session.poll() && session.state.value?.phase == RendererPhase.EXTERNAL)
            check(session.disconnect() && speaker.phase == "PLAYING")

            session.connect(client)
            speaker.rejectPlay = true
            check(!session.play(item) && session.state.value?.phase == RendererPhase.UNREACHABLE)
            check(session.disconnect())
            speaker.rejectPlay = false

            session.connect(client)
            check(session.play(item))
            speaker.close()
            check(!session.poll() && session.state.value?.phase == RendererPhase.UNREACHABLE)
            check(!session.disconnect() && session.selected)
        }
        Speaker(loopback, allowSeek = false).use { limited ->
            val client = DlnaRendererClient.fixture(limited.url, CancelScope())
            check(!client.canSeek)
            check(runCatching { client.seek(0, CancelScope()) }.isFailure)
        }
        Speaker(loopback, control = "http://192.168.0.12/control").use { foreign ->
            check(runCatching { DlnaRendererClient.fixture(foreign.url, CancelScope()) }.isFailure)
        }
        Speaker(loopback).use { handoff(it, loopback, bytes) }
        "DLNA SOAP and relay: advertised actions, format gates, range/HEAD, pause/seek/volume, completion, takeover, route handoff and loss"
    }

    private suspend fun handoff(speaker: Speaker, loopback: InetAddress, bytes: ByteArray) {
        val opened = AtomicInteger()
        val focus = object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        }
        val owner = UsbSessionOwner(UsbPlaybackController {
            opened.incrementAndGet()
            PlayerUiProbe.Output()
        }, focus)
        val output = RemotePlaybackSession { _, _, _ -> PrivateTrackRelay.fixture(loopback, bytes) }
        val player = withContext(Dispatchers.Main) { EvannaPlayer(owner, output) }
        suspend fun await(condition: suspend () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Media3 did not reflect the renderer state" }
                delay(20)
            }
        }
        try {
            check(owner.replaceQueue(PlayerUiProbe.source("Handoff track")))
            check(owner.enqueue(PlayerUiProbe.source("Second track")))
            check(owner.open(7) && owner.play() && owner.snapshot.value.route == PlaybackRoute.DIRECT_USB)
            val renderer = withContext(Dispatchers.Main) { player.openRenderer { DlnaRendererClient.fixture(speaker.url, it) } }
            check(renderer.get(10, TimeUnit.SECONDS) && owner.snapshot.value.route == null && !focus.held)
            withContext(Dispatchers.Main) { player.play() }
            await { withContext(Dispatchers.Main) { player.isPlaying } }
            withContext(Dispatchers.Main) {
                check(player.playlistMetadata.extras?.getString(EvannaPlayer.OUTPUT_ROUTE) == EvannaPlayer.DLNA_ROUTE)
                check(player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE)
                check(player.currentMediaItem?.localConfiguration == null)
            }
            speaker.position = "00:01:39"
            await { output.state.value?.positionMs == 99_000L }
            speaker.position = "00:01:40"
            speaker.phase = "STOPPED"
            await { withContext(Dispatchers.Main) { player.isPlaying && player.currentMediaItemIndex == 1 } }
            check(speaker.title == "Second track")
            val automatic = withContext(Dispatchers.Main) { player.openDevice(8, onlyIfUnselected = true) }
            check(!automatic.get(5, TimeUnit.SECONDS) && speaker.phase == "PLAYING" && opened.get() == 1)
            val attached = withContext(Dispatchers.Main) { player.openDevice(8, autoAttach = true) }
            check(!attached.get(5, TimeUnit.SECONDS) && speaker.phase == "PLAYING" && opened.get() == 1)
            speaker.acknowledgeStop = false
            val denied = withContext(Dispatchers.Main) { player.openDevice(8) }
            check(!denied.get(10, TimeUnit.SECONDS) && owner.snapshot.value.route == null && opened.get() == 1)
            speaker.acknowledgeStop = true
            val phone = withContext(Dispatchers.Main) { player.openDevice(8) }
            check(phone.get(10, TimeUnit.SECONDS) && owner.snapshot.value.route == PlaybackRoute.DIRECT_USB && opened.get() == 2)
            check(speaker.phase == "STOPPED" && !focus.held)
        } finally {
            withContext(Dispatchers.Main) { player.release() }
        }
    }

    private class Speaker(
        bind: InetAddress, private val allowSeek: Boolean = true, private val control: String = "/av",
    ) : Closeable {
        private val socket = ServerSocket(0, 12, bind)
        val url = "http://${bind.hostAddress}:${socket.localPort}/device"
        @Volatile var track = ""
        @Volatile var phase = "NO_MEDIA_PRESENT"
        @Volatile var position = "00:00:00"
        @Volatile var acknowledgeStop = true
        @Volatile var rejectPlay = false
        @Volatile var title = ""
        private var volume = 32
        private val worker = Thread({
            while (!socket.isClosed) {
                val connection = try { socket.accept() } catch (_: java.io.IOException) { break }
                try {
                    connection.use { client ->
                        client.soTimeout = 2000
                        val input = client.getInputStream().bufferedReader()
                        val request = input.readLine().split(' ')
                        var length = 0
                        var soap = ""
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            if (line.startsWith("SOAPAction:", true)) soap = line.substringAfter(':').trim().trim('"')
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val n = input.read(body, offset, length - offset)
                            check(n > 0)
                            offset += n
                        }
                        val reply = if (request[0] == "GET") describe(request[1]) else respond(soap, String(body))
                        val bytes = reply.toByteArray(Charsets.UTF_8)
                        val status = if (reply.isEmpty()) "500 Fixture rejected command" else "200 OK"
                        val output = client.getOutputStream()
                        output.write("HTTP/1.1 $status\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                } catch (_: Exception) { }
            }
        }, "DLNA fixture").apply { isDaemon = true; start() }

        private fun describe(path: String): String = when (path) {
            "/device" -> """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Fixture speaker</friendlyName><UDN>uuid:fixture</UDN><serviceList>
                ${endpoint("AVTransport", control, "/avdesc")}
                ${endpoint("RenderingControl", "/rc", "/rcdesc")}
                ${endpoint("ConnectionManager", "/cm", "/cmdesc")}
                </serviceList></device></root>"""
            "/avdesc" -> actions(listOf("SetAVTransportURI", "Play", "Pause", "Stop", "GetPositionInfo", "GetTransportInfo") +
                if (allowSeek) listOf("Seek") else emptyList())
            "/rcdesc" -> actions(listOf("GetVolume", "SetVolume"))
            "/cmdesc" -> actions(listOf("GetProtocolInfo"))
            else -> ""
        }

        private fun endpoint(kind: String, control: String, scpd: String) =
            "<service><serviceType>urn:schemas-upnp-org:service:$kind:1</serviceType><controlURL>$control</controlURL><SCPDURL>$scpd</SCPDURL></service>"

        private fun actions(actions: List<String>) = """<scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList>${actions.joinToString("") {
            "<action><name>$it</name></action>"
        }}</actionList></scpd>"""

        private fun respond(soap: String, body: String): String {
            val service = soap.substringBefore('#')
            val action = soap.substringAfter('#')
            val args = SecureUpnpXml.parse(body.toByteArray()).child("Body")!!.children.single()
            check(args.name == action && args.namespace == service)
            val values = when (action) {
                "GetProtocolInfo" -> "<Sink>http-get:*:audio/flac:*,http-get:*:audio/mpeg:DLNA.ORG_PN=MP3</Sink>"
                "SetAVTransportURI" -> {
                    track = args.value("CurrentURI")
                    val metadata = SecureUpnpXml.parse(args.value("CurrentURIMetaData").toByteArray())
                    title = metadata.child("item")!!.value("title")
                    check(metadata.child("item")!!.value("res") == track)
                    phase = "STOPPED"
                    position = "00:00:00"
                    ""
                }
                "GetPositionInfo" -> "<TrackURI>${xmlEscape(track)}</TrackURI><TrackDuration>00:01:40</TrackDuration><RelTime>$position</RelTime>"
                "GetTransportInfo" -> "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentTransportState>$phase</CurrentTransportState>"
                "Play" -> { if (rejectPlay) return ""; phase = "PLAYING"; "" }
                "Pause" -> { phase = "PAUSED_PLAYBACK"; "" }
                "Stop" -> { if (acknowledgeStop) phase = "STOPPED"; "" }
                "Seek" -> { position = args.value("Target"); "" }
                "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
                "SetVolume" -> { volume = args.value("DesiredVolume").toInt(); "" }
                else -> return ""
            }
            return """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="$service">$values</u:${action}Response></s:Body></s:Envelope>"""
        }

        override fun close() {
            socket.close()
            worker.join(2000)
        }
    }
}
