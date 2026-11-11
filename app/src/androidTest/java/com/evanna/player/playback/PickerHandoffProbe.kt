package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import com.evanna.player.MainActivity

/** Exercises Android's actual picker return while MainActivity's controller reconnects. */
@androidx.media3.common.util.UnstableApi
internal object PickerHandoffProbe {
    fun run(instrumentation: Instrumentation): String {
        if (android.os.Build.VERSION.SDK_INT < 29) error("The MediaStore picker probe requires Android 10 or newer")
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val name = "Evanna-handoff-${System.currentTimeMillis()}.flac"
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/flac")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        var activity: MainActivity? = null
        fun progress(value: String) = instrumentation.sendStatus(1, Bundle().apply { putString("progress", value) })
        try {
            instrumentation.context.assets.open("decoder/stereo-24.flac").use { input ->
                checkNotNull(resolver.openOutputStream(uri, "w")).use(input::copyTo)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            progress("Opening real file selector")
            clickText(instrumentation, "Settings")
            clickText(instrumentation, "Library settings")
            clickText(instrumentation, "Choose local file", scroll = true)
            // The picker may reopen Downloads or Recent. Our own newly published file can appear in either.
            if (!clickText(instrumentation, name, optional = true)) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val drawer = find(root) { it.contentDescription?.toString() == "Show roots" }
                check(drawer != null && click(drawer)) { "Local picker roots were unavailable" }
                clickText(instrumentation, "Downloads")
                clickText(instrumentation, name, scroll = true)
            }
            progress("Picker returned; waiting for service selection and grant")
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var selected = false
            do {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                selected = find(root) { it.packageName?.toString() == context.packageName &&
                    it.text?.toString() == "Now Playing" } != null
                if (selected) break
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            check(selected) { "The picker result did not reach the reconnected playback service" }
            check(PlaybackRuntime.diagnosticSnapshot()?.state !in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING, UsbPlaybackState.PREPARING))
            check(PlaybackRuntime.diagnosticFocusHeld() != true)
            progress("File selected without playback; checking lease release")
            val grant = FileSessionProbe.runGrant(instrumentation)
            return "Actual document-picker/controller reconnect handoff passed without auto-play. $grant"
        } finally {
            activity?.let { screen -> instrumentation.runOnMainSync { screen.finish() } }
            resolver.delete(uri, null, null)
        }
    }

    private fun clickText(instrumentation: Instrumentation, text: String, scroll: Boolean = false, optional: Boolean = false): Boolean {
        val deadline = SystemClock.elapsedRealtime() + if (optional) 2_000 else 15_000
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = find(root) { it.text?.toString() == text && it.isEnabled }
            if (node != null && click(node)) return true
            if (scroll) find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(200)
        } while (SystemClock.elapsedRealtime() < deadline)
        if (optional) return false
        error("Expected test control was unavailable: $text")
    }
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val value = current ?: return false
            if (value.isClickable && value.isEnabled) return value.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = value.parent
        }
        return false
    }
    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) find(root.getChild(index), predicate)?.let { return it }
        return null
    }
}
