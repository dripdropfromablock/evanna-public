package com.evanna.player.webmusic

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.evanna.player.MainActivity

internal object WebSiteListUiProbe {
    fun run(instrumentation: Instrumentation, fixtures: String): String {
        val (valid, invalid) = fixtures.split('|')
        val context = instrumentation.targetContext
        val store = webSiteListStore(context)
        check(store.current() == null) { "Use a fresh source list for this check" }
        val screen = checkNotNull(instrumentation.uiAutomation)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        fun await(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 24_000
            while (SystemClock.elapsedRealtime() < deadline) {
                screen.rootInActiveWindow?.let { root -> find(root, matches) }?.let { return it }
                SystemClock.sleep(250)
            }
            error("Missing $label")
        }
        fun node(label: String) = await(label) { it.text?.toString() == label || it.contentDescription?.toString() == label }
        fun tap(label: String) {
            await(label) { candidate ->
                val matches = candidate.text?.toString() == label || candidate.contentDescription?.toString() == label
                matches && generateSequence(candidate) { it.parent }.firstOrNull { it.isClickable }
                    ?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
        fun input(value: String) {
            val field = await("editable field") { it.isEditable }
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) { "Could not enter test text" }
            await("entered text") { it.isEditable && it.text?.toString() == value }
        }

        try {
            tap("Sources")
        tap("Music websites")
            node("Monochrome")
            node("AMP3")
            node("cobalt")
            input(valid)
            tap("Add list")
            node("Source list saved.")
            node("Archive QA")
            input(invalid)
            tap("Replace list")
            node("This source list needs a supported version. Your saved list is still available.")
            node("Archive QA")
            tap("Back")
            tap("Find music")
            input("Hallelujah Leonard Cohen")
            tap("Search")
            tap("Hallelujah")
            tap("Archive QA")
            val address = await("Archive search") { it.isEditable && it.text?.startsWith("https://archive.org/") == true }
            val url = checkNotNull(httpsWebAddress(address.text.toString()))
            check(url.queryParameter("query")?.contains("Hallelujah", ignoreCase = true) == true)
            check(url.queryParameter("query")?.contains("Leonard Cohen", ignoreCase = true) == true)
            return "PASS: added an HTTPS list, retained it after an invalid replacement, and opened the recording in Try on"
        } finally {
            store.remove()
        }
    }

    private fun find(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && matches(node)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            find(child, matches)?.let { return it }
        }
        return null
    }
}
