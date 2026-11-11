package com.evanna.player.library

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import com.evanna.player.MainActivity

@UnstableApi
internal object HiddenTabBackProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val db = LocalLibrary.database(context)
        val root = db.roots().single { it.name == "Evanna-library-check" }
        val song = db.page(filter = LibraryFilter(kind = LibraryGroupKind.FOLDER, value = root.uri)).songs
            .first { it.available && it.artist != null && it.album != null }
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
        fun heading(text: String) = find(nodes()) { it.isVisibleToUser && it.isHeading && it.text?.toString() == text } != null
        fun click(text: String) = await("Control unavailable: $text") {
            val node = find(nodes()) { it.isVisibleToUser && it.isEnabled && !it.isEditable && it.text?.toString() == text }
            var target = node
            repeat(6) {
                if (target?.isClickable == true && target?.isEnabled == true)
                    return@await target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                target = target?.parent
            }
            false
        }
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            await("Library did not open") { heading("Library") }
            click("Search")
            await("Search did not open") { heading("Search") }
            await("Search field unavailable") {
                val field = find(nodes()) { it.isEditable && it.isEnabled }
                field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, song.title)
                }) == true
            }
            await("Filtered song did not settle") {
                find(nodes()) { it.isVisibleToUser && it.text?.toString()?.startsWith(song.title) == true } != null
            }
            click("Albums")
            click(checkNotNull(song.album))
            await("Album drilldown missing") { visible("Back to albums") }
            click("Settings")
            await("Settings did not open") { heading("Settings") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            click("Search")
            await("Hidden-tab Back changed the retained album") { visible("Back to albums") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Visible album Back did not return to album groups") { heading("Search") && visible(checkNotNull(song.album)) }
            return "Back from Settings left the retained Search album untouched; Back on the visible Search album still returned to album groups. No playback requested."
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
        }
    }

    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
}
