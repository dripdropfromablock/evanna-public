package com.evanna.player

/** The queue is a page inside Now Playing (`PlayerPage.QUEUE`), not a screen of its own. */
internal enum class PlayerScreen {
    LIBRARY, CAROUSEL, SEARCH, NOW_PLAYING, PLAYLISTS, FAVORITES, RECENT, MOST,
    OUTPUT, AUDIO_OUTPUT, EQUALIZER, LIBRARY_SETTINGS, APPEARANCE, SETTINGS_TOOLS, DIAGNOSTICS, DECODE, NOTICES
}
/** Bottom-bar destinations. OUTPUT is labelled Settings. */
internal val playerTabs = listOf(PlayerScreen.CAROUSEL, PlayerScreen.LIBRARY, PlayerScreen.SEARCH, PlayerScreen.OUTPUT)
internal val startPages = listOf(PlayerScreen.LIBRARY, PlayerScreen.CAROUSEL)
internal const val START_PAGE_PREFERENCE = "start-page"
internal fun storedStartPage(value: Any?): PlayerScreen =
    startPages.firstOrNull { it.name == value } ?: PlayerScreen.LIBRARY
internal data class PlayerQueueItem(val id: String, val title: String, val artist: String? = null)
internal data class PlayerPresentation(
    val id: String? = null, val title: String = "", val artist: String? = null, val album: String? = null,
    val connected: Boolean = false, val playing: Boolean = false, val buffering: Boolean = false, val ended: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long? = null, val canPlayPause: Boolean = false, val canSeek: Boolean = false,
    val canPrevious: Boolean = false, val canNext: Boolean = false, val canShuffle: Boolean = false, val canRepeat: Boolean = false,
    val shuffle: Boolean = false, val repeat: Int = 0, val queue: List<PlayerQueueItem> = emptyList(), val queueIndex: Int = -1,
    val route: String = "NONE", val sourceQuality: String = "", val error: String? = null, val queueWarning: String? = null,
    val usbGeneration:Long=0, val usbVolume:com.truesample.usbaudio.VolumeRange?=null,
    val signalStages:List<com.evanna.player.playback.SignalStage> = emptyList(),
    val sleepLabel:String="",
    val favorite:Boolean?=null,
) {
    val hasSong get() = id?.startsWith("evanna:local:") == true
}

internal fun playerDestination(stack: List<PlayerScreen>, target: PlayerScreen): List<PlayerScreen> {
    val previous = stack.indexOf(target)
    return if (previous >= 0) stack.take(previous + 1) else stack + target
}

/** A completed hide must not pop a page opened while the sheet was closing. */
internal fun playerSheetDismissed(stack: List<PlayerScreen>): List<PlayerScreen> =
    if (stack.lastOrNull() == PlayerScreen.NOW_PLAYING) stack.dropLast(1) else stack

/** Back from another tab returns to the start page for this session. */
internal fun playerTab(tab: PlayerScreen, home: PlayerScreen = PlayerScreen.LIBRARY): List<PlayerScreen> {
    require(tab in playerTabs)
    require(home in startPages)
    return if (tab == home) listOf(home) else listOf(home, tab)
}

internal fun selectedTab(stack: List<PlayerScreen>): PlayerScreen = stack.lastOrNull { it in playerTabs } ?: PlayerScreen.LIBRARY

/** Tab roots use the bottom bar and system Back; pushed screens also show a Back arrow. */
internal fun isTabRoot(stack: List<PlayerScreen>): Boolean = stack.size == 1 || (stack.size == 2 && stack.last() in playerTabs)

/** The tab under the pushed screens. */
internal fun baseTab(stack: List<PlayerScreen>): PlayerScreen =
    stack.getOrNull(1)?.takeIf { it in playerTabs } ?: stack.firstOrNull() ?: PlayerScreen.LIBRARY

/** The pushed screen drawn over the tabs (Playlists, Favorites, ...); null on a tab root, and for Now Playing, which is a sheet of its own.
 *  A screen pushed from Now Playing (or over another pushed screen) stays mounted underneath the sheet: opening Now Playing over
 *  [LIBRARY, FAVORITES] still yields FAVORITES, and Settings pushed from the sheet ([LIBRARY, NOW_PLAYING, OUTPUT]) stays OUTPUT,
 *  while a plain tab under the sheet ([LIBRARY, SEARCH, NOW_PLAYING]) still yields null. */
internal fun pushedScreen(stack: List<PlayerScreen>): PlayerScreen? {
    val underSheet = if (stack.lastOrNull() == PlayerScreen.NOW_PLAYING) stack.dropLast(1) else stack
    val shown = underSheet.lastOrNull() ?: return null
    val isTabArrangement = shown in playerTabs && underSheet == playerTab(shown, underSheet.first())
    return shown.takeIf { !isTabArrangement }
}

internal fun playbackTime(ms: Long?): String {
    if (ms == null || ms < 0) return "--:--"
    val seconds = ms / 1000
    val minute = (seconds / 60 % 60).toString().padStart(2, '0')
    val second = (seconds % 60).toString().padStart(2, '0')
    return if (seconds >= 3600) "${seconds / 3600}:$minute:$second" else "${seconds / 60}:$second"
}

internal fun playbackSeekTarget(startId: String?, currentId: String?, requestedMs: Long, durationMs: Long?, canSeek: Boolean): Long? {
    if (startId == null || startId != currentId || !canSeek || durationMs == null || durationMs <= 0) return null
    return requestedMs.coerceIn(0, durationMs)
}
