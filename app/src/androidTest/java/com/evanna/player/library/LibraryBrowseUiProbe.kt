package com.evanna.player.library

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import com.evanna.player.MainActivity
import com.evanna.player.playback.PlaybackRuntime
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/** Uses only the existing generated test folder; browsing must leave the actual queue/output alone. */
@UnstableApi
internal object LibraryBrowseUiProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val db = LocalLibrary.database(context)
        val root = db.roots().single { it.name == "Evanna-library-check" }
        val song = db.page(filter = LibraryFilter(kind = LibraryGroupKind.FOLDER, value = root.uri)).songs
            .first { it.available && it.artist != null && it.album != null }
        val owner = PlaybackRuntime.owner(context)
        runBlocking { owner.initialize() }
        val originalQueue = owner.queue.value
        val originalGeneration = owner.snapshot.value.generation
        val screen = AtomicReference<MainActivity?>()
        var initialOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val app = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) screen.set(activity) }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        fun nodes() = instrumentation.uiAutomation.rootInActiveWindow
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            do {
                if (condition()) return
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            error(message)
        }
        fun visible(text: String) = find(nodes()) { it.isVisibleToUser && it.text?.toString() == text } != null
        /** Screen titles are headings; the bottom bar repeats some of them as plain tab labels. */
        fun heading(text: String) = find(nodes()) { it.isVisibleToUser && it.isHeading && it.text?.toString() == text } != null
        fun clickWhere(label: String, stage: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
            await("$stage control unavailable: $label") {
                // A heading can repeat a tab label, so only text owned by a clickable control counts.
                val node = find(nodes()) { it.isVisibleToUser && it.isEnabled && predicate(it) && target(it) != null }
                if (node != null) click(node) else {
                    find(nodes()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                }
            }
        }
        /** Skips text fields: a search box can hold the same text as the result being clicked. */
        fun click(text: String, stage: String = "Browse") = clickWhere(text, stage) { !it.isEditable && it.text?.toString() == text }
        fun clickDescription(text: String) = clickWhere(text, "Browse") { it.contentDescription?.toString() == text }
        fun search(text: String) {
            await("Search field unavailable") {
                val field = find(nodes()) { it.isEditable && it.isEnabled }
                field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }) == true
            }
        }
        fun songVisible() = find(nodes()) { it.isVisibleToUser && it.text?.toString()?.startsWith(song.title) == true } != null
        fun checkSongs() {
            val count = db.page(filter = LibraryFilter(search = song.title)).total
            await("Filtered songs did not settle") { visible("$count songs") && songVisible() }
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            screen.set(instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
            initialOrientation = checkNotNull(screen.get()).requestedOrientation
            await("Library did not open") { heading("Library") }
            click("Search")
            await("Search did not open") { heading("Search") }
            search(song.title)
            checkSongs()
            click("Albums")
            click(checkNotNull(song.album))
            await("Album drilldown missing") { visible("Back to albums") && songVisible() }

            val previous = checkNotNull(screen.get())
            instrumentation.runOnMainSync { previous.recreate() }
            await("Activity did not recreate") { screen.get() !== previous && visible("Back to albums") && songVisible() }
            check(find(nodes()) { it.isEditable && it.text?.toString() == song.title } != null) { "Search was lost during recreation" }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back did not return to album groups") { heading("Search") && visible(checkNotNull(song.album)) }

            click("Artists")
            click(checkNotNull(song.artist))
            await("Artist drilldown missing") { visible("Back to artists") && songVisible() }
            click("Folders")
            click(root.name)
            await("Folder drilldown missing") { visible("Back to folders") && songVisible() }
            search("Evanna-no-such-song-7d39")
            await("Empty search retained stale songs") { visible("No matching songs. Try another search or clear it.") && !songVisible() }
            search("unmatched-first")
            search(song.title)
            checkSongs()
            clickDescription("Clear search")
            await("Clear search did not reload folder") {
                visible("${db.page(filter = LibraryFilter(kind = LibraryGroupKind.FOLDER, value = root.uri)).total} songs") &&
                    find(nodes()) { it.isEditable && it.text.isNullOrEmpty() } != null
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Folder Back left the library") { heading("Search") && visible(root.name) }
            instrumentation.sendStatus(1, Bundle().apply { putString("progress", "Portrait browsing/recreation passed; checking landscape folder rows") })
            instrumentation.runOnMainSync { checkNotNull(screen.get()).requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("Landscape configuration did not arrive") { screen.get()?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE }
            click(root.name, "Landscape")
            await("Landscape song rows cannot be reached") {
                if (songVisible()) true else {
                    find(nodes()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                }
            }
            check(owner.queue.value == originalQueue && owner.snapshot.value.generation == originalGeneration) { "Browsing changed the queue/output" }
            return "Actual search/clear/empty and rapid-query results, album/artist/folder drilldown, Back, activity recreation and reachable landscape rows passed; queue/output generation unchanged; no audio started."
        } finally {
            screen.get()?.let { activity -> instrumentation.runOnMainSync { activity.requestedOrientation = initialOrientation; activity.finish() } }
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val value = current ?: return null
            if (value.isClickable && value.isEnabled) return value
            current = value.parent
        }
        return null
    }
    private fun click(node: AccessibilityNodeInfo): Boolean = target(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
}
