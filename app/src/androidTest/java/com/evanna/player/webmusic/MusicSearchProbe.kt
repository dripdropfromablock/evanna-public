package com.evanna.player.webmusic

import android.app.Instrumentation
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.evanna.player.MainActivity
import com.evanna.player.network.CancelScope
import com.evanna.player.playback.PlaybackRuntime
import com.evanna.player.playback.UsbPlaybackState
import kotlinx.coroutines.runBlocking

internal object MusicSearchProbe {
    private const val ARTIST = "ahtictap"
    private const val RELEASE = "[GT118] ahtictap - my first jazz album"
    private const val FIRST = "mbI"
    private const val SECOND = "razbivaemsya"

    fun online(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val ui = SearchDriver(instrumentation)
        val owner = PlaybackRuntime.owner(context)
        runBlocking {
            owner.queue.value.items.filter { it.title in setOf(FIRST, SECOND) && it.artist.equals(ARTIST, true) }
                .forEach { owner.removeQueue(it.id) }
        }
        if (ui.contains("Minimize player")) ui.tap("Minimize player")
        if (!ui.contains("Artist, album or track")) ui.tap("Search")
        ui.input(ARTIST)
        ui.see("Internet Archive", partial = true)
        ui.tap(ARTIST, resultRow = true)
        ui.see("Back")
        ui.tap(RELEASE, scroll = true)
        ui.see("2 of 2 tracks playable")
        ui.tap("Actions for $FIRST")
        ui.see("Play")
        if (ui.contains("Choose a source")) ui.tap("Internet Archive")
        ui.tap("Play")
        await("streamed audio frames", 35_000) {
            val audio = owner.snapshot.value
            owner.queue.value.current?.title == FIRST && audio.state == UsbPlaybackState.PLAYING && audio.framesAccepted > 0
        }
        check(runBlocking { owner.queueSource(checkNotNull(owner.queue.value.current).id) }
            ?.sourceRef?.connectionId == com.evanna.player.sources.SEARCH_CONNECTION_ID)
        ui.see("Queue · 1 song")
        ui.tap("Minimize player")
        ui.tap("Back")
        ui.see("2 of 2 tracks playable")
        ui.tap("Actions for $SECOND", scroll = true)
        ui.tap("Play Next")
        await("second track immediately after first", 8_000) {
            val queue = owner.queue.value
            queue.current?.title == FIRST && queue.items.getOrNull(queue.index + 1)?.title == SECOND
        }
        ui.tap("Open Now Playing")
        ui.tap("Queue · 2 songs")
        ui.see(SECOND)
        ui.tap("Minimize player")
        ui.tap("Back")
        ui.tap("Actions for $FIRST")
        ui.tap("Add to Queue")
        await("track appended after the two queued tracks", 8_000) {
            owner.queue.value.items.map { it.title } == listOf(FIRST, SECOND, FIRST)
        }
        ui.tap("Back")
        check(!ui.contains("Download Album"))
        ui.tap("Actions for $FIRST")
        check(!ui.contains("Download"))
        return "PASS: streamed $FIRST from Internet Archive and used the playback queue without offering downloads"
    }

    fun offline(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val net = context.getSystemService(ConnectivityManager::class.java)
        check(net.activeNetwork == null) { "Disconnect the test emulator from the network for offline playback." }
        val ui = SearchDriver(instrumentation)
        if (!ui.contains("Library view: Covers")) ui.tap("Library")
        ui.tap("my first jazz album", scroll = true)
        ui.tap(SECOND, scroll = true)
        val owner = PlaybackRuntime.owner(context)
        ui.see("Minimize player")
        if (owner.snapshot.value.state in setOf(UsbPlaybackState.OPEN, UsbPlaybackState.PAUSED)) ui.tap("Play playback")
        try {
            await("local offline playback", 20_000) {
                val audio = owner.snapshot.value
                owner.queue.value.current?.title == SECOND && audio.state == UsbPlaybackState.PLAYING && audio.framesAccepted > 0
            }
        } catch (failure: IllegalStateException) {
            val audio = owner.snapshot.value
            error("${failure.message}. Current: ${owner.queue.value.current?.title}. State: ${audio.state}. Frames: ${audio.framesAccepted}. Failure: ${audio.failure}. Source failed: ${owner.sourceSelectionFailed}. Focus denied: ${owner.focusDenied}.")
        }
        val current = runBlocking { owner.queueSource(checkNotNull(owner.queue.value.current).id) }
        check(current?.libraryId != null && net.activeNetwork == null)
        ui.tap("Minimize player")
        ui.tap("Search")
        ui.input(SECOND)
        ui.tap("Actions for $SECOND", scroll = true)
        ui.see("Library ·", partial = true)
        ui.see("Play")
        check(net.activeNetwork == null)
        return "PASS: played $SECOND from the imported Library album and found the local track in Search with no active network"
    }

    fun ccMixter(instrumentation: Instrumentation): String {
        val title = "Breathe (Travel Light)"
        val ui = SearchDriver(instrumentation)
        ui.tap("Search")
        ui.input(title)
        ui.tap("Actions for $title", scroll = true)
        ui.see("Open source")
        check(!ui.contains("Play") && !ui.contains("Download"))
        ui.tap("Open source")
        ui.see("https://ccmixter.org/files/snowflake/60146")
        return "PASS: found the ccMixter recording in Search and opened its exact upload page"
    }

    fun torrentInputs(instrumentation: Instrumentation, documentName: String): String {
        val ui = SearchDriver(instrumentation)
        ui.tap("Search")
        ui.input("my first jazz album")
        ui.tap("Peer-to-peer")
        ui.tap("Open .torrent file", scroll = true)
        ui.see("Show roots")
        if (!ui.contains(documentName)) {
            ui.tap("Show roots")
            ui.tap("Downloads")
        }
        ui.tap(documentName, scroll = true)
        val selectedFile = "Select JazzAlbumThreaded/$SECOND.mp3"
        ui.see(selectedFile, scroll = true)
        ui.tap(selectedFile, scroll = true)
        ui.see("Download 1 selected", scroll = true)
        ui.tap("Select all", scroll = true, scrollUp = true)
        ui.see("Download 2 selected", scroll = true)
        return "PASS: opened a local .torrent and switched between one file and the whole release"
    }

    fun magnet(instrumentation: Instrumentation, address: String): String {
        val context = instrumentation.targetContext
        val folder = checkNotNull(searchMusicFolder(context))
        val ui = SearchDriver(instrumentation)
        val release = P2pTorrent.fromMagnet(address, context.cacheDir, CancelScope())
        val parsed = release.bytes.inputStream().use(P2pTorrent::fromDocument)
        check(parsed.hash == release.hash && release.files.size == 2)
        val track = release.files.single { it.path.endsWith("$SECOND.mp3") }
        P2pTransfers.enqueue(context, release, setOf(track.index), folder)
        val task = checkNotNull(P2pTransfers.read(context, release.hash))
        val started = instrumentation.uiAutomation.executeShellCommand("cmd jobscheduler run -f ${context.packageName} ${task.slot}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
        var lastProgress: String? = null
        try {
            await("selected track from magnet", 110_000) {
                val current = P2pTransfers.read(context, release.hash)
                val progress = P2pTransfers.progress[release.hash]
                if (progress != lastProgress) {
                    android.util.Log.i("EvannaTorrentProbe", "${current?.state}, $progress")
                    lastProgress = progress
                }
                check(current?.state?.startsWith("Stopped") != true) { current?.state.orEmpty() }
                current?.state == "Saved to Library" && current.saved == setOf(track.index)
            }
        } catch (failure: IllegalStateException) {
            error("${failure.message}. Scheduler: $started. Progress: $lastProgress. Network allowed: ${P2pTransfers.allowedNetwork(context)}.")
        }
        ui.tap("Search")
        ui.tap("Downloads")
        ui.see(release.name)
        ui.see("Saved to Library")
        return "PASS: opened a magnet from the local BitTorrent peer, parsed its .torrent metadata and transferred only $SECOND"
    }

    fun soulseek(instrumentation: Instrumentation, address: String): String {
        val context = instrumentation.targetContext
        val settings = SoulseekSettings(address, "evanna-local-test-key-only", localHttp = true)
        val companion = settings.client()
        val cancel = CancelScope()
        val releases = companion.search("JazzAlbum", cancel)
        val release = releases.firstOrNull { row ->
            row.files.any { it.path.endsWith("$FIRST.mp3") } && row.files.any { it.path.endsWith("$SECOND.mp3") }
        } ?: error("The local Soulseek peer did not return both fixture tracks.")
        val selected = release.files.single { it.path.endsWith("$FIRST.mp3") }
        companion.enqueue(release, listOf(selected), cancel)
        await("the selected Soulseek file to finish", 45_000) {
            val transfers = companion.downloads(cancel).filter { it.peer == release.peer }
            check(transfers.none { it.filename == "$SECOND.mp3" }) { "Unselected Soulseek track was queued." }
            transfers.any { it.filename == "$FIRST.mp3" && it.bytes == selected.size &&
                it.status.contains("Completed", ignoreCase = true) }
        }
        SoulseekConnection.save(context, settings)
        val ui = SearchDriver(instrumentation)
        ui.tap("Search")
        ui.tap("Downloads")
        ui.see("Soulseek via slskd")
        ui.see("$FIRST.mp3")
        return "PASS: found two openly shared Soulseek tracks, transferred only $FIRST via slskd, and showed it in Evanna Downloads"
    }

    private fun await(label: String, millis: Long, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(200)
        }
        error("Timed out waiting for $label")
    }

    private class SearchDriver(instrumentation: Instrumentation) {
        private val automation = checkNotNull(instrumentation.uiAutomation)
        init {
            instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }

        fun contains(label: String) = nodes().any { it.matches(label) }

        fun see(label: String, scroll: Boolean = false, partial: Boolean = false) {
            var nextScroll = SystemClock.elapsedRealtime() + 1_000
            try {
                await(label, 24_000) {
                    val visible = nodes()
                    val found = visible.any {
                        if (label.startsWith("Download ") && it.text?.startsWith("Download ") == true) it.refresh()
                        it.matches(label) || partial && it.text?.contains(label, ignoreCase = true) == true
                    }
                    if (!found && scroll && SystemClock.elapsedRealtime() >= nextScroll) {
                        scrollPage(visible, up = false)
                        nextScroll = SystemClock.elapsedRealtime() + 800
                    }
                    found
                }
            } catch (failure: IllegalStateException) {
                val visible = nodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
                val description = visible.distinct().joinToString(" | ")
                android.util.Log.w("EvannaSearchProbe", "${failure.message}. Visible: $description")
                error("${failure.message}. Visible: $description")
            }
        }

        fun tap(label: String, scroll: Boolean = false, resultRow: Boolean = false, scrollUp: Boolean = false) {
            var nextScroll = SystemClock.elapsedRealtime() + 2_000
            await(label, 26_000) {
                val nodes = nodes()
                val hit = nodes.any { node ->
                    val ancestors = generateSequence(node) { it.parent }.toList()
                    val topResult = !resultRow || android.graphics.Rect().also(node::getBoundsInScreen).centerY() <
                        android.graphics.Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }.bottom * 0.7
                    topResult && node.matches(label) && ancestors.none {
                        it.isEditable || it.matches("Artist, album or track") ||
                            label != "Open Now Playing" && it.matches("Open Now Playing")
                    } &&
                        ancestors.firstOrNull { it.isClickable && it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                if (!hit && scroll && SystemClock.elapsedRealtime() >= nextScroll) {
                    scrollPage(nodes, scrollUp)
                    nextScroll = SystemClock.elapsedRealtime() + 800
                }
                hit
            }
        }


        private fun scrollPage(nodes: List<AccessibilityNodeInfo>, up: Boolean) {
            val action = if (up) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            nodes.firstOrNull { action in it.actionList }?.performAction(action.id)
        }

        fun input(value: String) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            await("Search input", 12_000) {
                nodes().any { node ->
                    (node.isEditable || node.matches("Artist, album or track")) &&
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
            }
            see(value)
        }

        private fun AccessibilityNodeInfo.matches(label: String) =
            text?.toString().equals(label, ignoreCase = true) || contentDescription?.toString().equals(label, ignoreCase = true)

        private fun nodes(): List<AccessibilityNodeInfo> = buildList {
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser) add(node)
                repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
            }
            automation.rootInActiveWindow?.let(::visit)
        }
    }
}
