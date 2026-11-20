package com.evanna.player.sources

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.evanna.player.MainActivity
import com.evanna.player.decoder.NetworkFixtureServer
import com.evanna.player.playback.PlaybackService
import com.evanna.player.playback.PlayerUiProbe
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

@UnstableApi
internal object SourcesPlaybackProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        check(context.packageName == "com.evanna.player.sourcesqa") { "Use the isolated Sources QA build." }
        val address = NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress } ?: error("The emulator needs a local IPv4 address.")
        val flac = instrumentation.context.assets.open("decoder-seek/seek.flac").use { it.readBytes() }
        val aac = instrumentation.context.assets.open("decoder-seek/seek.aac").use { it.readBytes() }
        val audioName = "QA Range Audio"
        val radioName = "QA Live Radio"

        val ui = PlayerUiProbe.Driver(instrumentation)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        lateinit var connection: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            connection = MediaController.Builder(context, token).buildAsync()
        }
        try {
            val controller = connection.get(10, TimeUnit.SECONDS)
            fun awaitPlayer(message: String, condition: MediaController.() -> Boolean) = ui.await(message) {
                var satisfied = false
                instrumentation.runOnMainSync { satisfied = controller.condition() }
                satisfied
            }
            fun playAndPause(title: String) {
                ui.click(title)
                ui.click("Play")
                awaitPlayer("$title did not start or advance") {
                    isPlaying && mediaMetadata.title?.toString() == title && currentPosition >= 300
                }
                instrumentation.runOnMainSync { controller.pause() }
                awaitPlayer("$title did not pause") { !isPlaying && !playWhenReady }
                ui.await("$title did not return to its source") { ui.heading(title) && !ui.visible("Play") }
            }

            ui.click("Settings")
            ui.click("Sources")
            NetworkFixtureServer(flac, address).use { fixture ->
                connect(ui, "Audio URL", audioName, "Audio file URL", fixture.url.toString())
                playAndPause(audioName)
                val seekMs = 4_000L
                instrumentation.runOnMainSync { controller.seekTo(seekMs) }
                awaitPlayer("Audio URL did not seek") { abs(currentPosition - seekMs) < 300 }
                ui.click(audioName)
                ui.click("Save offline")
                ui.await("Audio URL did not save an offline copy") { ui.visible("Saved offline.") }
            }
            ui.clickDescription("Back")
            RadioFixture(aac, address).use { fixture ->
                connect(ui, "Internet radio", radioName, "Station or playlist URL", fixture.url)
                playAndPause(radioName)
            }
            ui.clickDescription("Back")
            ui.click(audioName)
            ui.click("Offline music")
            playAndPause(audioName)
            return "PASS: Sources connected and played Audio URL and Internet radio, paused both, sought in Audio URL, " +
                "and played its offline copy after the file server stopped."
        } finally {
            instrumentation.runOnMainSync {
                MediaController.releaseFuture(connection)
                activity.finish()
            }
        }
    }

    private fun connect(ui: PlayerUiProbe.Driver, kind: String, title: String, field: String, url: String) {
        ui.revealForward(kind)
        ui.click(kind)
        setText(ui, "Name", title)
        setText(ui, field, url)
        ui.await("Local HTTP switch unavailable") {
            ui.find { it.isVisibleToUser && it.className == "android.widget.Switch" && it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        ui.click("Connect")
        ui.await("$kind did not connect") {
            check(!ui.visible("Could not connect. Check the address, credentials and server settings.")) {
                "$kind rejected the test server."
            }
            ui.heading(title) && ui.visible(title)
        }
    }

    private fun setText(ui: PlayerUiProbe.Driver, label: String, value: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        ui.await("Could not fill $label") {
            ui.find { it.isVisibleToUser && it.isEditable && it.contentDescription?.toString() == label }
                ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
        }
        ui.await("$label did not retain the test value") {
            ui.find { it.isEditable && it.text?.toString() == value } != null
        }
    }

    private class RadioFixture(audio: ByteArray, address: Inet4Address) : Closeable {
        private val server = ServerSocket(0, 8, address)
        val url = "http://${address.hostAddress}:${server.localPort}/radio.aac"
        @Volatile private var client: Socket? = null
        private val worker = thread(name = "SourcesRadioFixture", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        client = socket
                        socket.soTimeout = 2_000
                        val request = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        if (request.readLine()?.startsWith("GET /radio.aac ") != true) return@use
                        while (!request.readLine().isNullOrEmpty()) Unit
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/aac\r\nConnection: close\r\n\r\n".toByteArray())
                        while (!socket.isClosed && !server.isClosed) {
                            output.write(audio)
                            output.flush()
                            SystemClock.sleep(300)
                        }
                    }
                } catch (_: IOException) {
                    // Validation, format detection and pause can close their connections early.
                } finally {
                    client = null
                }
            }
        }

        override fun close() {
            server.close()
            client?.close()
            worker.join(2_500)
            check(!worker.isAlive) { "The radio fixture did not stop." }
        }
    }
}
