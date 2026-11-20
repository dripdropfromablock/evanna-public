package com.evanna.player.sources

import android.content.Context
import java.io.File
import java.io.IOException

internal const val WEB_PLAYBACK_CONNECTION_ID = "c8515e08-d385-461a-a7ed-4febf1ac5199"

/** Reads cached audio from playback queues saved by earlier versions. */
internal class WebPlaybackCache(context: Context, store: SourceStore) {
    private val files = OfflineMusic(File(context.cacheDir, "web-playback"), store)

    fun available(item: SourceItem) = files.file(item) != null
    fun media(item: SourceItem) = files.media(item) ?: throw IOException("WEB_DOWNLOAD_EXPIRED")
}
