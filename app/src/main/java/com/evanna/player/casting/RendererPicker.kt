package com.evanna.player.casting

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.evanna.player.network.CancelScope
import com.evanna.player.playback.EvannaPlayer
import com.evanna.player.playback.PlaybackService
import com.evanna.player.sources.upnp.DiscoveredUpnpServer
import com.evanna.player.sources.upnp.UpnpDiscovery
import com.evanna.player.ui.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(UnstableApi::class)
@Composable
internal fun RendererPicker(controller: MediaController?) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var lanHttp by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var search by remember { mutableStateOf<CancelScope?>(null) }
    var results by remember { mutableStateOf(emptyList<DiscoveredUpnpServer>()) }
    var pending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var extras by remember(controller) { mutableStateOf(controller?.playlistMetadata?.extras) }
    var knownVolume by remember(controller) { mutableStateOf<Int?>(null) }
    DisposableEffect(controller) {
        fun refresh() {
            extras = controller?.playlistMetadata?.extras
            knownVolume = controller?.takeIf { it.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME) }?.deviceVolume
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { refresh() }
        }
        refresh()
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener); search?.cancel() }
    }

    fun send(action: String, args: Bundle = Bundle.EMPTY) {
        if (controller == null || pending || search != null) return
        pending = true
        message = null
        val future = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener({
            val success = try { future.get().resultCode == SessionResult.RESULT_SUCCESS } catch (_: Exception) { false }
            pending = false
            message = if (success) null else if (action == PlaybackService.OPEN_RENDERER)
                "Could not select this speaker. Check its address, access and DLNA support."
            else "Could not confirm the speaker stopped. Check the network and try again."
            if (success && action == PlaybackService.OPEN_RENDERER) expanded = false
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    fun select(location: String, id: String? = null) {
        send(PlaybackService.OPEN_RENDERER, Bundle().apply {
            putString(PlaybackService.RENDERER_ADDRESS, location)
            putString(PlaybackService.RENDERER_UDN, id)
            putBoolean(PlaybackService.RENDERER_ALLOW_HTTP, lanHttp)
        })
    }

    val output = extras?.takeIf { it.getString(EvannaPlayer.OUTPUT_ROUTE) == EvannaPlayer.DLNA_ROUTE }
    val busy = pending || search != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (output != null) {
            Text(output.getString(EvannaPlayer.RENDERER_NAME) ?: "Network speaker")
            output.getString(EvannaPlayer.SIGNAL_STATUS)?.let { Text(it) }
            val volume = knownVolume
            if (volume != null && controller != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Speaker volume $volume", Modifier.weight(1f))
                    TextButton(enabled = !busy && volume > controller.deviceInfo.minVolume,
                        onClick = { controller.decreaseDeviceVolume(0) }) { Text("Decrease") }
                    TextButton(enabled = !busy && volume < controller.deviceInfo.maxVolume,
                        onClick = { controller.increaseDeviceVolume(0) }) { Text("Increase") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { send(PlaybackService.STOP_RENDERER) }) { Text("Stop") }
                TextButton(enabled = !busy, onClick = { send(PlaybackService.DISCONNECT_RENDERER) }) { Text("Disconnect") }
            }
        }
        TextButton(enabled = controller != null && !pending, onClick = {
            expanded = !expanded
            if (!expanded) { search?.cancel(); results = emptyList(); message = null }
        }) { Text(if (expanded) "Hide speaker setup" else "Choose a network speaker") }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow HTTP on this local network", Modifier.weight(1f))
                Switch(lanHttp, { lanHttp = it }, enabled = !busy)
            }
            Text("The speaker receives audio over unencrypted HTTP. Use a network you trust.")
            OutlinedTextField(address, { address = it.take(4096) }, "DLNA device description URL", Modifier.fillMaxWidth(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && address.isNotBlank(), onClick = { select(address.trim()) }) { Text("Connect") }
                val currentSearch = search
                if (currentSearch != null) TextButton(onClick = { currentSearch.cancel() }) { Text("Cancel search") }
                else OutlinedButton(enabled = !busy, onClick = {
                    val cancel = CancelScope()
                    search = cancel
                    message = null
                    results = emptyList()
                    scope.launch {
                        try {
                            results = withContext(Dispatchers.IO) { UpnpDiscovery.discover(context, cancel, UpnpDiscovery.RENDERER_TARGET) }
                            if (results.isEmpty() && !cancel.cancelled) message = "No speakers found. You can enter a description URL."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (!cancel.cancelled) message = "Speaker discovery is unavailable. You can enter a description URL."
                        } finally {
                            cancel.cancel()
                            if (search === cancel) search = null
                        }
                    }
                }) { Text("Find speakers") }
            }
            for (found in results) {
                val description = found.location.toHttpUrlOrNull() ?: continue
                TextButton(enabled = !busy && (description.isHttps || lanHttp), onClick = {
                    if (found.expiresAtElapsedMs > SystemClock.elapsedRealtime()) select(found.location, found.serverId)
                    else { results = results - found; message = "This result expired. Search again." }
                }) { Text("Speaker at ${description.host}:${description.port}") }
            }
            if (results.any { it.location.startsWith("http://") } && !lanHttp)
                Text("Enable HTTP on this local network to connect to the discovered HTTP speakers.")
        }
        if (pending) Text("Contacting the speaker…")
        message?.let { Text(it) }
    }
}
