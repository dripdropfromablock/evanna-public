package com.evanna.player.casting

import com.evanna.player.network.CancelScope
import com.evanna.player.playback.QueueSource
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

internal enum class RendererPhase { READY, STARTING, PLAYING, PAUSED, STOPPED, ENDED, EXTERNAL, UNREACHABLE, UNSUPPORTED }

internal data class RemotePlaybackState(
    val name: String,
    val phase: RendererPhase = RendererPhase.READY,
    val durationMs: Long? = null,
    val positionMs: Long = 0,
    val canPause: Boolean = false,
    val canSeek: Boolean = false,
    val volume: Int? = null,
    val volumeMin: Int = 0,
    val volumeMax: Int = 0,
) {
    val playing get() = phase == RendererPhase.PLAYING
    val label get() = when (phase) {
        RendererPhase.READY -> "Ready to cast"
        RendererPhase.STARTING -> "Connecting to the speaker"
        RendererPhase.PLAYING -> "Playing on the speaker"
        RendererPhase.PAUSED -> "Paused on the speaker"
        RendererPhase.STOPPED -> "Speaker stopped"
        RendererPhase.ENDED -> "Track finished"
        RendererPhase.EXTERNAL -> "Another controller is using this speaker. Press Play to take over."
        RendererPhase.UNREACHABLE -> "Speaker state could not be confirmed. Check the network and try again."
        RendererPhase.UNSUPPORTED -> "This item could not be cast. Check access or choose another track or output."
    }
}

/** Operated by the player's route command lane. Blocking SOAP and file operations run on IO. */
internal class RemotePlaybackSession(
    private val openItem: (DlnaRendererClient, QueueSource, CancelScope) -> PrivateTrackRelay,
) {
    val commands = Mutex()
    private var client: DlnaRendererClient? = null
    private var relay: PrivateTrackRelay? = null
    private var expectedUri: String? = null
    private var completedUri: String? = null
    private var itemDurationMs: Long? = null
    private var prior: RendererReading? = null
    private var expectPlaying = false
    private var awaitingPlayingUntil = 0L
    private var volumeAvailable = false
    private var operation: CancelScope? = null
    private val mutableState = MutableStateFlow<RemotePlaybackState?>(null)
    val state = mutableState.asStateFlow()
    val selected get() = client != null

    fun interrupt() { operation?.cancel() }

    fun connect(renderer: DlnaRendererClient) {
        check(expectedUri == null && relay == null)
        client = renderer
        volumeAvailable = true
        mutableState.value = RemotePlaybackState(renderer.name)
    }

    suspend fun disconnect(): Boolean {
        if (!stopItem()) return false
        client = null
        mutableState.value = null
        return true
    }

    suspend fun play(item: QueueSource, atMs: Long = 0, automatic: Boolean = false): Boolean {
        if (client == null) return false
        return run(retry = !automatic) { renderer, scope ->
            val completed = completedUri.takeIf { automatic }
            completedUri = null
            if (automatic && completed == null) return@run false
            val resumable = relay?.healthy == true && state.value?.phase == RendererPhase.PAUSED
            if (state.value?.playing == true && renderer.reading(scope).let { it.uri == expectedUri && it.phase == "PLAYING" })
                return@run true
            if (resumable && renderer.reading(scope).let { it.uri == expectedUri && it.phase == "PAUSED_PLAYBACK" }) {
                requestPlay(renderer, scope)
                reconcile(renderer, scope)
                return@run true
            }
            if (expectedUri != null && !stop(renderer, scope)) return@run false
            publish(RendererPhase.STARTING)
            var ready: PrivateTrackRelay? = null
            try {
                ready = openItem(renderer, item, scope)
                scope.checkActive()
            } catch (_: Exception) {
                ready?.close()
                publish(if (scope.cancelled) RendererPhase.STOPPED else RendererPhase.UNSUPPORTED)
                return@run false
            }
            if (completed != null) {
                val reading = try { renderer.reading(scope) } catch (failure: Exception) {
                    ready?.close()
                    throw failure
                }
                if (reading.uri != completed || !reading.stopped) {
                    ready?.close()
                    update(reading, null)
                    return@run false
                }
            }
            relay = checkNotNull(ready)
            itemDurationMs = ready.durationMs
            // Set URI may succeed even if its acknowledgement is lost. Keep the proof needed for Stop.
            expectedUri = ready.uri
            renderer.setItem(ready.uri, ready.mime, item.title, item.artist, scope)
            if (atMs > 0 && renderer.canSeek) renderer.seek(atMs.coerceAtMost(ready.durationMs), scope)
            requestPlay(renderer, scope)
            reconcile(renderer, scope)
            true
        }
    }

    suspend fun pause(): Boolean {
        val renderer = client ?: return false
        if (!renderer.canPause || expectedUri == null) return false
        return run { renderer, scope ->
            val reading = renderer.reading(scope)
            if (reading.uri != expectedUri) { update(reading, null); return@run false }
            expectPlaying = false
            prior = null
            renderer.pause(scope)
            reconcile(renderer, scope)
            state.value?.phase == RendererPhase.PAUSED
        }
    }

    suspend fun seek(positionMs: Long): Boolean {
        if (client?.canSeek != true || expectedUri == null) return false
        return run { renderer, scope ->
            val reading = renderer.reading(scope)
            if (reading.uri != expectedUri) { update(reading, null); return@run false }
            prior = null
            val duration = reading.durationMs ?: itemDurationMs ?: return@run false
            val position = positionMs.coerceAtMost(duration)
            if (position >= duration - 2000) expectPlaying = false
            renderer.seek(position, scope)
            reconcile(renderer, scope)
            true
        }
    }

    suspend fun setVolume(value: Int): Boolean {
        if (client == null) return false
        if (state.value?.volume == null) return false
        return run { renderer, scope ->
            // A volume fault must not tear down established stream ownership.
            try {
                renderer.setVolume(value, scope)
                val volume = renderer.volume(scope)
                mutableState.value = state.value?.copy(volume = volume)
                volume != null
            } catch (_: Exception) {
                volumeAvailable = false
                mutableState.value = state.value?.copy(volume = null)
                false
            }
        }
    }

    suspend fun stopItem(): Boolean {
        if (client == null) return true
        completedUri = null
        if (expectedUri == null) return true
        return run(retry = true) { renderer, scope -> stop(renderer, scope) }
    }

    private fun stop(renderer: DlnaRendererClient, scope: CancelScope): Boolean {
        expectPlaying = false
        prior = null
        val reading = renderer.reading(scope)
        if (!reading.stopped || awaitingPlayingUntil != 0L) {
            if (reading.uri.isBlank()) { lose(); return false }
            if (reading.uri != expectedUri) {
                clearItem()
                publish(RendererPhase.EXTERNAL)
                return true
            }
            renderer.stop(scope)
            var confirmed = renderer.transportState(scope)
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (confirmed == "TRANSITIONING" && SystemClock.elapsedRealtime() < deadline) {
                scope.checkActive()
                SystemClock.sleep(150)
                confirmed = renderer.transportState(scope)
            }
            if (confirmed != "STOPPED" && confirmed != "NO_MEDIA_PRESENT") { lose(); return false }
        }
        clearItem()
        publish(RendererPhase.STOPPED)
        return true
    }

    /** Returns true exactly once when a poll establishes completion of Evanna's item. */
    suspend fun poll(): Boolean {
        if (client == null) return false
        if (state.value?.phase == RendererPhase.UNREACHABLE) return false
        return run(readOnly = true) { renderer, scope ->
            if (relay?.healthy == false) {
                // The relay cannot supply bytes after source or interface loss. Try to stop what we own.
                stop(renderer, scope)
                publish(RendererPhase.UNREACHABLE)
                false
            } else reconcile(renderer, scope)
        }
    }

    private fun reconcile(renderer: DlnaRendererClient, scope: CancelScope): Boolean {
        val reading = renderer.reading(scope)
        val volume = if (volumeAvailable) try { renderer.volume(scope) } catch (_: Exception) {
            scope.checkActive()
            volumeAvailable = false
            null
        } else null
        scope.checkActive()
        return update(reading, volume)
    }

    private fun requestPlay(renderer: DlnaRendererClient, scope: CancelScope) {
        awaitingPlayingUntil = SystemClock.elapsedRealtime() + 8000L
        renderer.play(scope)
        expectPlaying = true
    }

    private fun update(reading: RendererReading, volume: Int?): Boolean {
        val uri = expectedUri
        val owned = uri != null && reading.uri == uri
        if (owned && reading.phase in setOf("PLAYING", "PAUSED_PLAYBACK")) awaitingPlayingUntil = 0L
        val pending = awaitingPlayingUntil != 0L && (owned || reading.uri.isBlank())
        val ended = owned && expectPlaying && reading.durationMs?.let {
            kotlin.math.abs(it - (itemDurationMs ?: it)) <= 2000
        } == true && rendererCompleted(checkNotNull(uri), prior, reading)
        val phase = when {
            ended -> RendererPhase.ENDED
            owned && reading.phase == "PLAYING" -> RendererPhase.PLAYING
            owned && reading.phase == "PAUSED_PLAYBACK" -> RendererPhase.PAUSED
            !owned && reading.uri.isNotBlank() && !reading.stopped -> RendererPhase.EXTERNAL
            pending -> if (SystemClock.elapsedRealtime() < awaitingPlayingUntil) RendererPhase.STARTING else RendererPhase.UNREACHABLE
            owned && reading.phase == "TRANSITIONING" -> RendererPhase.STARTING
            reading.stopped -> when (state.value?.phase) {
                RendererPhase.READY -> RendererPhase.READY
                RendererPhase.ENDED -> RendererPhase.ENDED
                RendererPhase.UNSUPPORTED -> RendererPhase.UNSUPPORTED
                else -> RendererPhase.STOPPED
            }
            else -> RendererPhase.UNREACHABLE
        }
        val duration = if (owned) reading.durationMs ?: itemDurationMs else state.value?.durationMs?.takeIf {phase == RendererPhase.ENDED}
        val position = if (owned) reading.positionMs ?: 0 else state.value?.positionMs?.takeIf {phase == RendererPhase.ENDED} ?: 0
        when (phase) {
            RendererPhase.ENDED -> {
                if (ended) completedUri = uri
                clearItem()
            }
            RendererPhase.STOPPED, RendererPhase.EXTERNAL -> { completedUri = null; clearItem() }
            RendererPhase.UNREACHABLE -> lose()
            RendererPhase.PAUSED -> { expectPlaying = false; prior = null }
            else -> prior = if (owned) reading else null
        }
        val renderer = checkNotNull(client)
        mutableState.value = RemotePlaybackState(renderer.name, phase, duration, position, renderer.canPause,
            renderer.canSeek && owned && phase in setOf(RendererPhase.PLAYING, RendererPhase.PAUSED) && duration != null,
            volume, renderer.volumeMin, renderer.volumeMax)
        return ended
    }

    private suspend fun run(retry: Boolean = false, readOnly: Boolean = false,
        block: (DlnaRendererClient, CancelScope) -> Boolean): Boolean {
        val active = client ?: return false
        val scope = CancelScope()
        operation = scope
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val renderer = if (retry) active.refresh(scope) else active
                    client = renderer
                    renderer.link?.checkActive()
                    block(renderer, scope)
                } catch (_: Exception) {
                    if (!readOnly || !scope.cancelled) lose()
                    false
                }
            }
        } finally {
            if (operation === scope) operation = null
        }
    }

    private fun clearItem() {
        closeRelay()
        expectedUri = null
        itemDurationMs = null
        awaitingPlayingUntil = 0L
    }

    private fun closeRelay() {
        relay?.let { runCatching { it.close() } }
        relay = null
        expectPlaying = false
        prior = null
    }

    private fun lose() {
        completedUri = null
        closeRelay()
        publish(RendererPhase.UNREACHABLE)
    }

    private fun publish(phase: RendererPhase) {
        val renderer = checkNotNull(client)
        mutableState.value = RemotePlaybackState(renderer.name, phase, canPause = renderer.canPause)
    }

    /** On service destruction the HTTP endpoint must close even when Stop could not be confirmed. */
    suspend fun release(): Boolean {
        val stopped = disconnect()
        if (!stopped) withContext(NonCancellable + Dispatchers.IO) { lose() }
        return stopped
    }
}
