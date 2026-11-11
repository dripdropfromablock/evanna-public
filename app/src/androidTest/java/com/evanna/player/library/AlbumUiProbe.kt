package com.evanna.player.library

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.evanna.player.MainActivity
import com.evanna.player.decoder.MusicTags

/** This synthetic UI index is allowed only in the separate validation installation. */
internal object AlbumUiProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        check(context.packageName == "com.evanna.player.validation") { "Requires isolated validation application" }
        val db = LocalLibrary.database(context)
        check(db.roots().isEmpty()) { "Validation index must be empty" }
        val root = "content://fixture/album-ui"
        db.addRoot(root, "Album UI fixtures", false); db.begin(root, "seed")
        for (n in 1..3) {
            val id = n.toString(16).padStart(64, '0')
            val document = LibraryDocument("$n", id, "content://fixture/$n", "$n.flac", "audio/flac", 10, 20)
            val tags = MusicTags(if (n == 3) "Artist B" else "Artist A", if (n == 2) 2 else 1, 2,
                if (n == 2) 2 else 1, if (n == 3) 1 else 2, 2019)
            db.record(root, "seed", document, LibraryAudio("UI song $n", "Track artist", "Evanna UI album", 1000, "FLAC", 44100, 24, 2, tags))
        }
        db.finish(root, "seed")
        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
            return null
        }
        fun node(text: String) = find(instrumentation.uiAutomation.rootInActiveWindow) { it.isVisibleToUser && it.text?.toString() == text }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { label }; SystemClock.sleep(100) }
        }
        fun click(text: String) {
            await("Album UI control unavailable: $text (keep phone unlocked)") {
                var target = node(text)
                repeat(6) {
                    if (target?.isClickable == true && target?.isEnabled == true)
                        return@await target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    target = target?.parent
                }
                false
            }
        }
        fun reveal(text: String) {
            await("Album text cannot be reached by scrolling: $text") {
                if (node(text) != null) true else {
                    find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    false
                }
            }
        }
        var activity: MainActivity? = null
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            click("Search"); click("Albums")
            click("Artist A")
            await("Album detail did not open") { node("Back to albums") != null }
            reveal("Artist A"); reveal("2019 · 2 songs"); reveal("UI song 1")
            check(node("UI song 3") == null)
            check(find(instrumentation.uiAutomation.rootInActiveWindow) { it.contentDescription?.toString() == "Track 1" } != null)
            if (context.resources.configuration.fontScale >= 1.9f)
                return "At 200% text, album artist/year and song/track labels remain reachable through the actual scroll container; no playback requested."
            fun search(text: String) {
                val field = find(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable }
                check(field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }) == true)
            }
            search("no-such-album-song")
            await("Empty album search did not settle") { node("No matching songs. Try another search or clear it.") != null }
            check(node("Back to albums") != null) { "An empty search discarded the selected album" }
            search("")
            await("Album search clear did not restore songs") { node("UI song 1") != null }
            while (monitor.waitForActivityWithTimeout(1) != null) { }
            instrumentation.runOnMainSync { activity?.recreate() }
            activity = monitor.waitForActivityWithTimeout(15000) as? MainActivity ?: error("Album recreation did not complete")
            await("Album identity lost on recreation") { node("UI song 1") != null && node("Back to albums") != null }
            db.writableDatabase.execSQL("UPDATE tracks SET album_artist='Artist A revised' WHERE album_artist='Artist A'")
            instrumentation.runOnMainSync { LocalLibrary.scan(context, Uri.parse(root), false) }
            await("Obsolete album identity was retained after tag refresh") { node("Artist A revised") != null && node("Back to albums") == null }
            click("Artist A revised")
            await("Refreshed album songs were lost") { node("UI song 1") != null }
            click("Back to albums"); click("Artist B")
            await("Same-title album opened the wrong artist") { node("UI song 3") != null && node("Artist B") != null }
            check(node("UI song 1") == null)
            return "Actual album artist/year/track label, empty album search, same-title drilldown and recreation pass; vanished identity returns to albums after metadata refresh; isolated validation data only; no playback requested."
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            db.writableDatabase.execSQL("DELETE FROM files WHERE root=?", arrayOf(root))
            db.writableDatabase.execSQL("DELETE FROM roots WHERE uri=?", arrayOf(root))
            db.writableDatabase.execSQL("DELETE FROM tracks WHERE NOT EXISTS(SELECT 1 FROM files WHERE files.id=tracks.id)")
        }
    }
}
