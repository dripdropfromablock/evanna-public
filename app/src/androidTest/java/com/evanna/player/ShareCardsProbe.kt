package com.evanna.player

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.evanna.player.playback.PlayerUiProbe
import java.io.File

/** Synthetic, silent render checks; writes only preview sheets to the test app's files. */
internal object ShareCardsProbe {
    @UnstableApi
    fun ui(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences(UI_SETTINGS, Context.MODE_PRIVATE)
        val keys = listOf("share-lyric-style", "share-album-style")
        val saved = keys.associateWith { settings.getString(it, null) }
        check(settings.edit().remove(keys[0]).remove(keys[1]).commit())
        val driver = PlayerUiProbe.Driver(instrumentation)
        val cover = artwork()
        val lyric = ShareCard.LyricCard(listOf("Lights on in the harbor,", "the tide keeps the time,"),
            "Lights on the Water", "Mira Vale", "The last bus leaves at midnight,", "and I count every boat home.")
        val album = ShareCard.AlbumCard("Glass Harbor", "Mira Vale", "2026 · 10 songs · 38 min",
            List(6) { ShareTrack("Harbor song ${it + 1}", "3:41") }, "+4 more · 38 min")
        var shown by mutableStateOf<ShareCard?>(null)
        var activity: MainActivity? = null
        fun radio(label: String): AccessibilityNodeInfo? {
            var node = driver.find { it.text?.toString() == label }
            repeat(6) {
                if (node?.isCheckable == true || node?.className?.toString() == "android.widget.RadioButton") return node
                node = node?.parent
            }
            return null
        }
        fun reveal(label: String) {
            driver.await("Share look not reachable: $label") {
                val node = radio(label)
                if (node?.isVisibleToUser == true) true else {
                    if (node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) != true) {
                        driver.find { it.isScrollable && it.actionList.any { action ->
                            action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                        } }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }
                    false
                }
            }
        }
        fun select(card: ShareCard, look: ShareLook) {
            reveal(look.label)
            driver.await("Share radio did not accept click: ${look.label}") {
                radio(look.label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            driver.await("Share look selection not saved: ${look.label}") {
                val node = radio(look.label)
                settings.getString(shareStyleKey(card), null) == look.name && (node?.isChecked == true || node?.isSelected == true)
            }
        }
        fun open(card: ShareCard) {
            instrumentation.runOnMainSync { shown = card }
            driver.await("Share sheet did not open") { driver.heading(card.sheetTitle) }
        }
        fun close(card: ShareCard) {
            driver.clickDescription("Close")
            driver.await("Share sheet did not close") { !driver.heading(card.sheetTitle) }
        }
        fun ready() {
            driver.await("Share picture did not finish rendering") {
                var node = driver.find { it.text?.toString() == "Save to Photos" }
                repeat(6) {
                    if (node?.isClickable == true) return@await node?.isEnabled == true
                    node = node?.parent
                }
                false
            }
        }
        fun screenshot(name: String, waitForPicture: Boolean = true) {
            if (waitForPicture) ready()
            instrumentation.waitForIdleSync()
            val folder = File(context.getExternalFilesDir(null), "share-card-probe")
            check(folder.isDirectory || folder.mkdirs())
            val picture = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(folder, name).outputStream().use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { picture.recycle() }
        }
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    EvannaTheme {
                        Box(Modifier.fillMaxSize().background(EvannaDesign.colorScheme.background)) {
                            shown?.let { card -> ShareSheet(card, { cover }) { shown = null } }
                        }
                    }
                }
            }
            for ((card, picked, filename) in listOf(
                Triple(lyric, ShareLook.LYRIC_QUOTE, "share-picker-lyrics.png"),
                Triple(album, ShareLook.ALBUM_LINER, "share-picker-album.png"))) {
                open(card)
                for (look in ShareLook.available(card)) select(card, look)
                select(card, picked)
                close(card)
                open(card)
                driver.await("Saved share look did not reopen selected and first") {
                    val selected = radio(picked.label)
                    val first = driver.find { it.isCheckable || it.className?.toString() == "android.widget.RadioButton" }
                    selected != null && selected.isVisibleToUser && (selected.isChecked || selected.isSelected) && selected == first
                }
                screenshot(filename)
                close(card)
            }
            return "All 7 lyric/9 album radio choices selected through accessibility; Quote and Liner notes reopen first and selected; screenshots saved; no export action invoked."
        } catch (failure: Throwable) {
            runCatching { screenshot("share-picker-failure.png", waitForPicture = false) }
            runCatching {
                val tree = StringBuilder()
                var count = 0
                fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                    if (node == null || count++ >= 180) return
                    tree.append("  ".repeat(depth)).append(node.className).append(" text=").append(node.text)
                        .append(" description=").append(node.contentDescription)
                        .append(" checkable=").append(node.isCheckable).append(" checked=").append(node.isChecked)
                        .append(" selected=").append(node.isSelected).append(" clickable=").append(node.isClickable)
                        .append(" enabled=").append(node.isEnabled).append(" visible=").append(node.isVisibleToUser)
                        .append(" actions=").append(node.actionList.map { it.id }).append('\n')
                    repeat(node.childCount) { visit(node.getChild(it), depth + 1) }
                }
                visit(instrumentation.uiAutomation.rootInActiveWindow, 0)
                val folder = File(context.getExternalFilesDir(null), "share-card-probe")
                check(folder.isDirectory || folder.mkdirs())
                File(folder, "share-picker-failure.txt").writeText("$failure\n$tree")
            }
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            val restore = settings.edit()
            for ((key, value) in saved) if (value == null) restore.remove(key) else restore.putString(key, value)
            check(restore.commit())
        }
    }

    fun run(instrumentation: Instrumentation): String {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "share-card-probe")
        check(folder.isDirectory || folder.mkdirs())
        val cover = artwork()
        val lyric = ShareCard.LyricCard(
            listOf("Lights on in the harbor,", "the tide keeps the time,", "and I count every boat home."),
            "Lights on the Water", "Mira Vale", "The last bus leaves at midnight,", "until the morning comes.")
        val album = ShareCard.AlbumCard("Glass Harbor", "Mira Vale", "2026 · 10 songs · 38 min",
            listOf("Lights on the Water", "Night Bus", "Paper Moons", "Low Tide", "Glass Harbor", "Count Every Boat")
                .map { ShareTrack(it, "3:41") }, "+4 more · 38 min")
        var checked = 0
        fun render(card: ShareCard, art: Bitmap?, name: String?) {
            val looks = ShareLook.available(card)
            val renderer = ShareRenderer(card, art)
            val sheet = name?.let {
                Bitmap.createBitmap(3 * 216, ((looks.size + 2) / 3) * 420, Bitmap.Config.ARGB_8888)
            }
            val canvas = sheet?.let(::Canvas)?.apply { drawColor(Color.rgb(24, 24, 28)) }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 21f }
            var background: Bitmap? = null
            try {
                looks.forEachIndexed { index, look ->
                    val pictures = renderer.pictures(look)
                    background = pictures.background
                    try {
                        check(pictures.story.width == 1080 && pictures.story.height == 1920) { "${look.name}: story size" }
                        check(pictures.background.width == 1080 && pictures.background.height == 1920) { "${look.name}: background size" }
                        check((pictures.sticker != null) == look.glass) { "${look.name}: Instagram sticker" }
                        val thumb = renderer.thumbnail(look)
                        try {
                            check(thumb.width == 216 && thumb.height == 384) { "${look.name}: thumbnail size" }
                            check(Color.alpha(thumb.getPixel(108, 192)) == 255) { "${look.name}: transparent story" }
                            val x = index % 3 * 216
                            val y = index / 3 * 420
                            canvas?.drawBitmap(thumb, null, Rect(x, y, x + 216, y + 384), null)
                            canvas?.drawText(look.label, x + 9f, y + 411f, label)
                        } finally { thumb.recycle() }
                        checked++
                    } finally {
                        pictures.story.recycle()
                        pictures.sticker?.recycle()
                    }
                }
                if (sheet != null) File(folder, checkNotNull(name)).outputStream().use {
                    check(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                sheet?.recycle()
                background?.recycle()
            }
        }
        try {
            check(ShareLook.available(lyric).size == 7)
            check(ShareLook.available(album).size == 9)
            render(lyric, cover, "share-lyrics.png")
            render(album, cover, "share-albums.png")
            val long = "A deliberately long title that wraps across several lines ".repeat(4)
            val fitted = fittedWords(List(4) { long }, 912, 90f, 60f, 650f, gapRatio = 0.5f)
            check(fitted.first.size == 4) { "Fitting dropped a selected lyric line" }
            check(fitted.first.sumOf { it.height } + fitted.second * 3 <= 650) { "Long lyrics overflow their allotted space" }
            render(lyric.copy(lines = List(4) { long }, song = long, artist = long, before = long, after = long),
                null, "share-lyrics-long-no-cover.png")
            render(album.copy(album = long, artist = long, tracks = album.tracks.map { it.copy(title = long) }),
                null, "share-albums-long-no-cover.png")
        } finally { cover.recycle() }
        return "$checked story/thumbnail pairs passed (16 looks, with artwork and long text without artwork). Preview sheets: ${folder.absolutePath}"
    }

    private fun artwork() = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            drawColor(Color.rgb(21, 42, 74))
            drawRect(0f, 310f, 600f, 600f, Paint().apply { color = Color.rgb(10, 20, 36) })
            drawCircle(460f, 130f, 54f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(243, 233, 210) })
            val lights = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 122) }
            for (x in listOf(130f, 250f, 370f, 480f)) drawCircle(x, 310f, 9f, lights)
        }
    }
}
