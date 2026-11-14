package com.evanna.player.library

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.evanna.player.*
import com.evanna.player.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private const val CAROUSEL_ALBUM_BATCH = 48
private const val CAROUSEL_FLING_PAGES = 12
private val CarouselCoverShape = RoundedCornerShape(14.dp)
private const val CAROUSEL_BACKDROP_SIZE = 48

private class CarouselBackdrops {
    private val images = LruCache<AlbumIdentity, ImageBitmap>(16)
    private var changes by mutableIntStateOf(0)

    operator fun get(album: AlbumIdentity?): ImageBitmap? {
        changes // A newly decoded cover invalidates the backdrop drawing.
        return album?.let(images::get)
    }

    suspend fun prepare(album: AlbumIdentity, cover: Bitmap) {
        if (images.get(album) != null) return
        val blurred = withContext(Dispatchers.Default) {
            val size = CAROUSEL_BACKDROP_SIZE
            val thumbnail = Bitmap.createScaledBitmap(cover, size, size, true)
            val pixels = IntArray(size * size)
            thumbnail.getPixels(pixels, 0, size, 0, 0, size, size)
            boxBlur(pixels, size, size, radius = 3)
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        images.put(album, blurred)
        changes++
    }
}

/** The song Now Playing shows, matched to album tracks by title and album (queue ids are not library ids). */
internal data class NowPlayingMark(val title: String, val album: String?, val playing: Boolean) {
    fun matches(song: LibrarySong) = song.title == title && (album.isNullOrBlank() || song.album == album)
}

/**
 * The line under a track in an album: nothing when the album's own artist made it, "feat. …" when they are joined by
 * guests ("Yeat, Gunna" on a Yeat album), and the artist as it is when someone else made it.
 */
internal fun trackGuests(artist: String?, albumArtist: String?): String? {
    val who = artist?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val main = albumArtist?.trim()?.takeIf { it.isNotEmpty() } ?: return who
    if (who.equals(main, ignoreCase = true)) return null
    for (join in listOf(", ", " & ", " x ", " feat. ", " ft. ", " featuring ", " with ", "; ", " / ")) {
        if (who.length > main.length + join.length && who.startsWith(main + join, ignoreCase = true)) {
            return "feat. " + who.substring(main.length + join.length).trim()
        }
    }
    return who
}

/** "2023 · 22 songs · 1 h 12 min"; the length only once every track's length is known. */
internal fun albumInfoLine(year: Int?, songs: Int, lengthMs: Long?): String = listOfNotNull(
    year?.toString(), if (songs == 1) "1 song" else "$songs songs", lengthMs?.takeIf { it > 0 }?.let(::albumLength),
).joinToString(" · ")

/** An album's length to the nearest minute: "38 min", "1 h", "1 h 12 min". */
internal fun albumLength(ms: Long): String {
    val minutes = (ms + 30_000) / 60_000
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

internal fun carouselAlbumIndex(page: Int, count: Int): Int = Math.floorMod(page, count)

internal fun carouselStartPage(index: Int, count: Int): Int =
    if (count <= 1) 0 else (Int.MAX_VALUE / 2 / count) * count + index

internal fun carouselPageForAlbum(page: Int, album: Int, count: Int): Int {
    val forward = Math.floorMod(album - carouselAlbumIndex(page, count), count)
    val backward = forward - count
    val candidates = listOf(page.toLong() + forward, page.toLong() + backward)
    return candidates.filter { it in 0 until Int.MAX_VALUE.toLong() }.minBy { abs(it - page) }.toInt()
}

internal fun randomCarouselAlbum(current: Int, count: Int, random: Random = Random.Default): Int? {
    if (count < 2) return null
    val draw = random.nextInt(count - 1)
    return if (draw >= current) draw + 1 else draw
}

private fun LibraryGroup.identity() = AlbumIdentity(value, albumArtist)

private data class CarouselSelection(
    val album: AlbumIdentity,
    val tracks: LibraryPage,
    val summary: AlbumSummary,
)

private data class CarouselCover(val bitmap: Bitmap?)

private suspend fun <T> readCarousel(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

@Composable
internal fun CarouselScreen(
    bottomInset: Dp,
    onLibrary: () -> Unit,
    onSelect: (LibraryContextRequest, (Boolean) -> Unit) -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit,
    onPlayNext: (String, (Boolean) -> Unit) -> Unit,
    onPlaylistSong: (String) -> Unit,
    nowPlaying: NowPlayingMark? = null,
) {
    val context = LocalContext.current
    val database = remember(context) { LocalLibrary.database(context) }
    val artwork = remember(context) { LocalLibrary.artwork(context) }
    val revision = LocalLibrary.scansFinished
    val active = !LocalCovered.current
    var retry by remember { mutableIntStateOf(0) }
    var lastAlbum by rememberSaveable { mutableIntStateOf(0) }
    var catalog by remember(revision, retry) { mutableStateOf<Result<LibraryGroupPage>?>(null) }
    LaunchedEffect(active, revision, retry) {
        if (active && catalog == null) catalog = readCarousel {
            withContext(Dispatchers.IO) { database.groups(LibraryGroupKind.ALBUM, limit = CAROUSEL_ALBUM_BATCH) }
        }
    }
    val albums = catalog?.getOrNull()
    if (albums != null && albums.total > 0) {
        key(revision, albums.total) {
            CarouselBrowser(database, artwork, albums, revision, active, lastAlbum, { lastAlbum = it },
                bottomInset, onSelect, onAdd, onPlayNext, onPlaylistSong, nowPlaying)
        }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomInset)) {
            CarouselHeader(enabled = false, onRoll = {})
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    catalog == null -> Text("Loading albums…")
                    albums == null -> {
                        Text("Couldn't load albums.")
                        TextButton(onClick = { retry++ }) { Text("Try again") }
                    }
                    else -> {
                        Text("Your albums will appear here.")
                        TextButton(onClick = onLibrary) { Text("Open Library") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarouselBrowser(
    database: LibraryDatabase, artwork: EmbeddedArtwork, albums: LibraryGroupPage, revision: Int,
    active: Boolean, initialAlbum: Int, onSettled: (Int) -> Unit, bottomInset: Dp,
    onSelect: (LibraryContextRequest, (Boolean) -> Unit) -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit, onPlayNext: (String, (Boolean) -> Unit) -> Unit,
    onPlaylistSong: (String) -> Unit, nowPlaying: NowPlayingMark?,
) {
    val count = albums.total
    val pager = rememberPagerState(initialPage = carouselStartPage(initialAlbum.coerceIn(0, count - 1), count)) {
        if (count == 1) 1 else Int.MAX_VALUE
    }
    val scope = rememberCoroutineScope()
    val reducedMotion = scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pager, pagerSnapDistance = PagerSnapDistance.atMost(if (reducedMotion) 1 else CAROUSEL_FLING_PAGES),
    )
    val pages = remember(albums) { mutableStateMapOf(0 to albums.groups) }
    val backdrops = remember(revision) { CarouselBackdrops() }
    val covers = remember(revision) { mutableStateMapOf<AlbumIdentity, CarouselCover>() }
    var metadataRetry by remember { mutableIntStateOf(0) }
    var metadataFailed by remember { mutableStateOf(false) }
    var rolling by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<CarouselSelection?>(null) }
    var failedAlbum by remember { mutableStateOf<AlbumIdentity?>(null) }
    var selectionRetry by remember { mutableIntStateOf(0) }
    var trackInfo by remember { mutableStateOf<LibrarySong?>(null) }
    var playbackFailed by remember { mutableStateOf(false) }
    var albumHeaderProgress by rememberSaveable { mutableFloatStateOf(0f) }
    val headerTravel = with(LocalDensity.current) { 136.dp.toPx() }

    fun batchFor(index: Int) = index / CAROUSEL_ALBUM_BATCH * CAROUSEL_ALBUM_BATCH
    fun nearbyBatches(index: Int) = (-CAROUSEL_FLING_PAGES..CAROUSEL_FLING_PAGES).mapTo(mutableSetOf()) {
        batchFor(carouselAlbumIndex(index + it, count))
    }
    fun albumAt(index: Int) = pages[batchFor(index)]?.getOrNull(index % CAROUSEL_ALBUM_BATCH)
    suspend fun loadBatches(batches: Set<Int>) {
        for (batch in batches) {
            if (batch !in pages) pages[batch] = withContext(Dispatchers.IO) {
                database.groups(LibraryGroupKind.ALBUM, limit = CAROUSEL_ALBUM_BATCH, offset = batch).groups
            }
        }
    }

    val settledIndex = carouselAlbumIndex(pager.settledPage, count)
    val centered = albumAt(settledIndex)
    val centeredAlbum = centered?.identity()
    val trackList = key(centeredAlbum) { rememberLazyListState() }
    suspend fun animateHeaderTo(target: Float) {
        if (target > 0f && (rolling || pager.isScrollInProgress || selection?.album != centeredAlbum)) return
        if (target != albumHeaderProgress) animate(albumHeaderProgress, target, animationSpec = EvannaMotion.exit) { value, _ ->
            albumHeaderProgress = value
        }
    }
    fun moveHeader(delta: Float): Offset {
        if (rolling || pager.isScrollInProgress) return Offset.Zero
        if (delta < 0f && selection?.album != centeredAlbum) return Offset.Zero
        if (delta > 0f && trackList.canScrollBackward) return Offset.Zero
        val before = albumHeaderProgress
        albumHeaderProgress = (before - delta / headerTravel).coerceIn(0f, 1f)
        return Offset(0f, (before - albumHeaderProgress) * headerTravel)
    }
    suspend fun settleHeader(velocity: Float) {
        val progress = albumHeaderProgress
        if (progress == 0f || trackList.canScrollBackward) return
        val target = when {
            velocity > 0f -> 0f
            velocity < 0f || progress >= 0.5f -> 1f
            else -> 0f
        }
        animateHeaderTo(target)
    }
    val headerDrag = rememberDraggableState { moveHeader(it) }
    val headerScroll = remember(headerTravel, trackList, pager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y < 0f) moveHeader(available.y) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) moveHeader(available.y) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (albumHeaderProgress in 0f..<1f && available.y < 0f) settleHeader(available.y)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settleHeader(available.y)
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(trackList) {
        var scrolledPastTop = false
        snapshotFlow { trackList.canScrollBackward to trackList.isScrollInProgress }.collectLatest { (scrolled, moving) ->
            if (scrolled) scrolledPastTop = true
            else if (!moving && scrolledPastTop) {
                animateHeaderTo(0f)
                scrolledPastTop = false
            }
        }
    }
    LaunchedEffect(active, metadataRetry) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            nearbyBatches(carouselAlbumIndex(pager.currentPage, count)) + batchFor(carouselAlbumIndex(pager.settledPage, count))
        }.collectLatest { wanted ->
            metadataFailed = readCarousel { loadBatches(wanted) }.isFailure
            if (!rolling) (pages.keys - wanted).forEach { pages.remove(it) }
        }
    }
    LaunchedEffect(active, settledIndex) {
        if (active) onSettled(settledIndex)
    }
    // A tick each time a new album comes to rest in the middle.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
        }
    }
    LaunchedEffect(active, centeredAlbum, selectionRetry) {
        val album = centeredAlbum ?: return@LaunchedEffect
        if (!active || selection?.album == album) return@LaunchedEffect
        failedAlbum = null
        readCarousel {
            withContext(Dispatchers.IO) {
                val filter = LibraryFilter(kind = LibraryGroupKind.ALBUM, value = album.title, albumArtist = album.albumArtist)
                CarouselSelection(album, database.page(filter = filter), database.albumSummary(album))
            }
        }.onSuccess { selection = it }.onFailure { failedAlbum = album }
    }

    LaunchedEffect(active, revision) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val page = pager.currentPage
            listOf(page, pager.settledPage, page - 1, page + 1)
                .filter { it in 0 until pager.pageCount }
                .mapNotNull { albumAt(carouselAlbumIndex(it, count))?.identity() }.distinct()
        }.collectLatest { wanted ->
            (covers.keys - wanted.toSet()).forEach(covers::remove)
            for (album in wanted) {
                if (album !in covers) {
                    covers[album] = CarouselCover(readCarousel { artwork.album(album, ArtworkSize.MEDIUM) }.getOrNull())
                }
                covers[album]?.bitmap?.let { backdrops.prepare(album, it) }
            }
        }
    }

    fun playFocusedAlbum(page: Int): Boolean {
        if (!active || rolling || albumHeaderProgress > 0f || pager.isScrollInProgress ||
            page != pager.settledPage || LocalLibrary.scanning || LocalLibrary.managingFolders) return false
        val album = albumAt(carouselAlbumIndex(page, count))?.identity() ?: return false
        val filter = LibraryFilter(kind = LibraryGroupKind.ALBUM, value = album.title, albumArtist = album.albumArtist)
        onSelect(LibraryContextRequest(filter, null, LibraryContextAction.PLAY_FIRST)) { success ->
            if (!success) playbackFailed = true
        }
        return true
    }

    fun moveTo(page: Int) {
        if (!active || rolling || page !in 0 until pager.pageCount || page == pager.currentPage) return
        scope.launch {
            if (reducedMotion) pager.scrollToPage(page)
            else pager.animateScrollToPage(page, animationSpec = EvannaMotion.enter)
        }
    }
    fun roll() {
        val target = randomCarouselAlbum(settledIndex, count) ?: return
        if (!active || rolling || pager.isScrollInProgress || albumHeaderProgress > 0f) return
        rolling = true
        scope.launch {
            try {
                val ready = readCarousel { loadBatches(nearbyBatches(target)) }.isSuccess
                if (!ready) {
                    metadataFailed = true
                    return@launch
                }
                val page = carouselPageForAlbum(pager.currentPage, target, count)
                if (reducedMotion) pager.scrollToPage(page)
                else pager.animateScrollToPage(page, animationSpec = tween(620))
            } finally {
                rolling = false
                val keep = nearbyBatches(carouselAlbumIndex(pager.currentPage, count)) +
                    batchFor(carouselAlbumIndex(pager.settledPage, count))
                (pages.keys - keep).forEach { pages.remove(it) }
            }
        }
    }

    val look = LocalLookStyle.current.look
    val centeredCover = centeredAlbum?.let(covers::get)
    var accent by remember(revision) { mutableStateOf<Color?>(null) }
    var themedAlbum by remember(revision) { mutableStateOf<AlbumIdentity?>(null) }
    LaunchedEffect(active, centeredAlbum, centeredCover) {
        if (!active || centeredAlbum == null || centeredCover == null) return@LaunchedEffect
        accent = centeredCover.bitmap?.let { cover -> withContext(Dispatchers.Default) { sampleArtworkAccent(cover) } }
        themedAlbum = centeredAlbum
    }
    val theme = remember(look, accent) { look.colors.withArtwork(accent, look.dark, surfaceTint = 0.12f) }
    val heroColor by animateColorAsState(
        accent?.let { lerp(theme.background, it, if (look.dark) 0.21f else 0.12f) } ?: theme.background,
        EvannaMotion.ambient, label = "Carousel backdrop",
    )
    val cardColor by animateColorAsState(theme.surfaceContainerHigh, EvannaMotion.ambient, label = "Carousel cards")
    val listColor by animateColorAsState(theme.surfaceContainer, EvannaMotion.ambient, label = "Carousel track list")
    val visibleSelection = selection?.takeIf { it.album == centeredAlbum }

    ProvideEvannaColors(theme.copy(surfaceContainer = listColor, surfaceContainerHigh = cardColor)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(heroColor, listColor)))) {
            CarouselArtworkBackdrop(pager, backdrops, themedAlbum,
                albumAtPage = { albumAt(carouselAlbumIndex(it, count))?.identity() },
                modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to heroColor.copy(alpha = if (look.dark) 0.70f else 0.80f),
                0.38f to heroColor.copy(alpha = if (look.dark) 0.54f else 0.69f),
                0.74f to listColor.copy(alpha = 0.88f),
                1f to listColor,
            )))
            val cardWidth = minOf(maxWidth * 0.64f, maxHeight * 0.42f, 304.dp)
            val centerPadding = (maxWidth - cardWidth) / 2
            val fontScale = LocalDensity.current.fontScale
            val captionHeight = maxOf(56.dp, 46.dp * fontScale)
            val cardHeight = cardWidth + captionHeight
            val browseHeight = cardHeight + 66.dp
            val pinnedHeight = minOf(maxWidth, maxHeight * 0.43f, 380.dp)
            val progress = albumHeaderProgress
            val headerWidth = lerpDp(cardWidth, maxWidth, progress)
            val headerHeight = lerpDp(cardHeight, pinnedHeight, progress)
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(lerpDp(browseHeight, pinnedHeight, progress)).clipToBounds()
                    .draggable(headerDrag, Orientation.Vertical, enabled = active && !rolling,
                        onDragStopped = { settleHeader(it) })) {
                    if (progress < 1f) Column(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - progress }
                        .then(if (progress > 0f) Modifier.clearAndSetSemantics {} else Modifier)) {
                        CarouselHeader(enabled = progress == 0f && count > 1 && active && !rolling && !pager.isScrollInProgress, onRoll = ::roll)
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxWidth().clipToBounds().clearAndSetSemantics {
                                contentDescription = centered?.let {
                                    listOfNotNull(it.value ?: "Unknown album", it.artist ?: it.albumArtist).joinToString(", ")
                                } ?: "Albums"
                                stateDescription = "Album ${settledIndex + 1} of $count"
                                if (!rolling) customActions = buildList {
                                    if (count > 1) {
                                        add(CustomAccessibilityAction("Previous album") { moveTo(pager.settledPage - 1); true })
                                        add(CustomAccessibilityAction("Next album") { moveTo(pager.settledPage + 1); true })
                                    }
                                    if (active && centered != null && !pager.isScrollInProgress &&
                                        !LocalLibrary.scanning && !LocalLibrary.managingFolders) {
                                        add(CustomAccessibilityAction("Play album from first track") { playFocusedAlbum(pager.settledPage) })
                                    }
                                    add(CustomAccessibilityAction("Expand album artwork") {
                                        scope.launch { animateHeaderTo(1f) }
                                        true
                                    })
                                }
                            },
                            userScrollEnabled = progress == 0f && active && count > 1 && !rolling,
                            contentPadding = PaddingValues(horizontal = centerPadding, vertical = 9.dp),
                            pageSize = PageSize.Fixed(cardWidth),
                            pageSpacing = 8.dp,
                            flingBehavior = flingBehavior,
                            beyondViewportPageCount = 1,
                        ) { page ->
                            val group = albumAt(carouselAlbumIndex(page, count))
                            val pageOffset = remember(pager, page) {
                                { ((page - pager.currentPage) - pager.currentPageOffsetFraction).coerceIn(-3f, 3f) }
                            }
                            CarouselAlbumCard(group, group?.identity()?.let(covers::get)?.bitmap, cardWidth,
                                pageOffset = pageOffset, modifier = Modifier.graphicsLayer {
                                if (progress > 0f && page == pager.settledPage) alpha = 0f
                                if (!reducedMotion) {
                                    // Cover Flow: the covers either side turn to face the middle and step back.
                                    val offset = pageOffset()
                                    val distance = abs(offset)
                                    val scale = 1f - distance.coerceAtMost(2f) * 0.1f
                                    scaleX = scale
                                    scaleY = scale
                                    rotationY = -offset.coerceIn(-1f, 1f) * 38f
                                    cameraDistance = size.width * 4f
                                }
                            }, onClick = { if (progress == 0f) moveTo(page) },
                                onDoubleClick = if (page == pager.settledPage) ({ playFocusedAlbum(page) }) else null)
                        }
                        CarouselCaption(centered, captionHeight)
                    }
                    if (progress > 0f) {
                        ExpandingCarouselAlbum(
                            centered, centeredCover?.bitmap, progress, captionHeight,
                            coverHeight = lerpDp(cardWidth, pinnedHeight, progress),
                            modifier = Modifier.align(Alignment.TopCenter).offset(y = lerpDp(57.dp, 0.dp, progress))
                                .width(headerWidth).height(headerHeight).semantics {
                                    if (!trackList.canScrollBackward) customActions = listOf(
                                        CustomAccessibilityAction("Show carousel") {
                                            scope.launch { animateHeaderTo(0f) }
                                            true
                                        },
                                    )
                                },
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).background(Brush.verticalGradient(
                    0f to listColor.copy(alpha = progress),
                    0.34f to listColor.copy(alpha = 0.66f),
                    1f to listColor.copy(alpha = 0.88f),
                )).padding(horizontal = 16.dp)) {
                    if (metadataFailed) {
                        TextButton(onClick = { metadataRetry++ }) { Text("Retry loading albums") }
                    }
                    if (!LocalLibrary.statusIsIdle) {
                        Text(LocalLibrary.status, style = EvannaDesign.typography.bodySmall, color = theme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    when {
                        visibleSelection == null && centeredAlbum != null && failedAlbum == centeredAlbum ->
                            TextButton(onClick = { selectionRetry++ }) { Text("Retry loading tracks") }
                        visibleSelection == null -> Text("Loading tracks…", modifier = Modifier.padding(vertical = 12.dp))
                        else -> key(visibleSelection.album.key) {
                        // Play, Shuffle and the album line lead the list; the title stays under the covers above.
                            CarouselTracks(visibleSelection, nowPlaying, database, active, bottomInset, scope, trackList,
                                Modifier.nestedScroll(headerScroll), onSelect, onAdd,
                                onPlayNext, onPlaylistSong, { trackInfo = it }, { playbackFailed = true })
                        }
                    }
                }
            }
        }
        trackInfo?.let { song -> LibraryTrackInfo(song) { trackInfo = null } }
        if (playbackFailed) AlertDialog(onDismissRequest = { playbackFailed = false },
            title = { Text("Couldn't open song") },
            text = { Text("Check folder access and reconnect playback controls, then try again.") },
            confirmButton = { TextButton(onClick = { playbackFailed = false }) { Text("OK") } })
    }
}

@Composable
private fun CarouselArtworkBackdrop(
    pager: PagerState, backdrops: CarouselBackdrops, settled: AlbumIdentity?,
    albumAtPage: (Int) -> AlbumIdentity?, modifier: Modifier,
) {
    Canvas(modifier) {
        val fraction = pager.currentPageOffsetFraction
        val page = pager.currentPage
        val neighbor = if (fraction < 0f) page - 1 else page + 1
        val current = backdrops[albumAtPage(page)] ?: backdrops[settled]
        val incoming = if (fraction != 0f && neighbor in 0 until pager.pageCount) backdrops[albumAtPage(neighbor)] else null
        val base = current ?: incoming ?: return@Canvas
        val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
        drawImage(base, dstSize = target, filterQuality = FilterQuality.Medium)
        if (incoming != null && incoming !== base) {
            drawImage(incoming, dstSize = target, alpha = abs(fraction), filterQuality = FilterQuality.Medium)
        }
    }
}

@Composable
private fun CarouselHeader(enabled: Boolean, onRoll: () -> Unit) {
    val colors = EvannaDesign.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Carousel", modifier = Modifier.weight(1f).semantics { heading() }, style = EvannaDesign.typography.headlineSmall)
        IconButton(onClick = onRoll, enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(colors.onSurfaceVariant)) {
            Icon(PlayerIcons.Dice, "Random album", Modifier.size(18.dp))
        }
    }
}

/**
 * The centred album growing into the full-width cover at the top of its track list. It starts as the floating cover with
 * its caption under it, exactly as the carousel shows it, and ends edge to edge with the caption over the cover's foot.
 */
@Composable
private fun ExpandingCarouselAlbum(
    group: LibraryGroup?, cover: Bitmap?, progress: Float, captionHeight: Dp, coverHeight: Dp, modifier: Modifier,
) {
    val colors = EvannaDesign.colorScheme
    val listColor = colors.surfaceContainer
    val glass = 1f - progress
    val frame = RoundedCornerShape(14.dp * glass)
    val coverShape = RoundedCornerShape(14.dp * glass)
    val fade = Brush.verticalGradient(
        0f to Color.Transparent,
        0.24f to listColor.copy(alpha = progress * 0.16f),
        0.72f to listColor.copy(alpha = progress * 0.90f),
        1f to listColor.copy(alpha = progress),
    )
    Surface(modifier, shape = frame, color = lerp(Color.Transparent, listColor, progress)) {
        Box(Modifier.fillMaxSize()) {
            val coverModifier = Modifier.fillMaxWidth().height(coverHeight)
            if (cover == null) ArtworkImage(null, coverModifier, coverShape)
            else {
                val image = remember(cover) { cover.asImageBitmap() }
                Image(image, contentDescription = null, modifier = coverModifier.clip(coverShape), contentScale = ContentScale.Crop)
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(maxOf(coverHeight * 0.55f, captionHeight * 2f)).background(fade))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = lerpDp(0.dp, 12.dp, progress))
                .height(captionHeight),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CarouselAlbumLabel(group, heading = true)
            }
        }
    }
}

/**
 * One album in the carousel: the cover alone, floating on a soft shadow, with no card around it. Covers away from the
 * middle soften and darken; the centred album's title sits under the carousel ([CarouselCaption]).
 */
@Composable
private fun CarouselAlbumCard(
    group: LibraryGroup?, shown: Bitmap?, width: Dp,
    pageOffset: () -> Float, modifier: Modifier, onClick: () -> Unit, onDoubleClick: (() -> Unit)?,
) {
    Box(modifier.width(width).aspectRatio(1f).shadow(14.dp, CarouselCoverShape, clip = false)
        .combinedClickable(interactionSource = null, indication = null,
            enabled = group != null, onClick = onClick, onDoubleClick = onDoubleClick)) {
        run {
            val dim = Modifier.drawWithContent {
                drawContent()
                val away = abs(pageOffset()).coerceAtMost(1f)
                if (away > 0.01f) drawRect(Color.Black.copy(alpha = 0.42f * away))
            }
            val cover = Modifier.fillMaxSize()

            if (Build.VERSION.SDK_INT >= 31) {
                val density = LocalDensity.current
                val blurSteps = remember(density) {
                    List(8) { step ->
                        val radius = with(density) { (step * 0.25f).dp.toPx() }
                        if (step == 0) null else BlurEffect(radius, radius, TileMode.Clamp)
                    }
                }
                ArtworkImage(shown, cover.graphicsLayer {
                    val blurStep = ((abs(pageOffset()) - 0.15f) * 8f).roundToInt().coerceIn(0, blurSteps.lastIndex)
                    shape = CarouselCoverShape
                    clip = true
                    renderEffect = blurSteps[blurStep]
                }.then(dim), CarouselCoverShape)
            } else {
                val soften by remember(pageOffset) { derivedStateOf { abs(pageOffset()) >= 0.60f } }
                val softened = remember(shown) {
                    shown?.takeIf { maxOf(it.width, it.height) > 112 }?.let {
                        val scale = 112f / maxOf(it.width, it.height)
                        Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1),
                            (it.height * scale).toInt().coerceAtLeast(1), true)
                    }
                }
                ArtworkImage(if (soften) softened ?: shown else shown, cover.clip(CarouselCoverShape).then(dim), CarouselCoverShape)
            }
        }
    }
}

/** The centred album's title and artist, under the carousel; it fades to the next album as one settles. */
@Composable
private fun CarouselCaption(group: LibraryGroup?, height: Dp) {
    Crossfade(group?.identity(), Modifier.fillMaxWidth().height(height), animationSpec = tween(160), label = "carousel caption") { shown ->
        val album = if (shown == group?.identity()) group else null
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (album != null) CarouselAlbumLabel(album, heading = true)
        }
    }
}

/** Title, then the artist in the cover's colour, both centred. */
@Composable
private fun CarouselAlbumLabel(group: LibraryGroup?, heading: Boolean = false) {
    Text(group?.let { it.value ?: "Unknown album" }.orEmpty(), style = EvannaDesign.typography.titleMedium,
        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = if (heading) Modifier.semantics { heading() } else Modifier)
    Text((group?.artist ?: group?.albumArtist).orEmpty(), style = EvannaDesign.typography.bodyMedium,
        color = EvannaDesign.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CarouselTracks(
    selection: CarouselSelection, nowPlaying: NowPlayingMark?, database: LibraryDatabase, active: Boolean, bottomInset: Dp, scope: CoroutineScope,
    list: LazyListState, modifier: Modifier,
    onSelect: (LibraryContextRequest, (Boolean) -> Unit) -> Unit,
    onAdd: (String, (Boolean) -> Unit) -> Unit, onPlayNext: (String, (Boolean) -> Unit) -> Unit,
    onPlaylistSong: (String) -> Unit, onTrackInfo: (LibrarySong) -> Unit, onPlaybackFailed: () -> Unit,
) {
    var songs by remember(selection) { mutableStateOf(selection.tracks.songs) }
    var total by remember(selection) { mutableIntStateOf(selection.tracks.total) }
    var failed by remember(selection) { mutableStateOf(false) }
    var retry by remember(selection) { mutableIntStateOf(0) }
    val filter = remember(selection.album) {
        LibraryFilter(kind = LibraryGroupKind.ALBUM, value = selection.album.title, albumArtist = selection.album.albumArtist)
    }
    LaunchedEffect(active, songs.size, total, retry) {
        if (!active || songs.isEmpty() || songs.size >= total) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.first { it >= songs.lastIndex - 6 }
        readCarousel {
            withContext(Dispatchers.IO) { database.page(offset = songs.size, filter = filter) }
        }.onSuccess {
            songs = songs + it.songs
            total = if (it.songs.isEmpty()) songs.size else it.total
        }.onFailure { failed = true }
    }
    val colors = EvannaDesign.colorScheme
    val busy = LocalLibrary.scanning || LocalLibrary.managingFolders
    val fade = if (list.canScrollBackward) Modifier.graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithCache {
        val edge = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = 24.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawRect(edge, blendMode = BlendMode.DstIn)
        }
    } else Modifier
    val albumArtist = selection.album.albumArtist ?: selection.summary.displayArtist
    // The length shows once every track is loaded and has one.
    val lengthMs = if (songs.size >= total && songs.isNotEmpty() && songs.all { it.durationUs != null })
        songs.sumOf { it.durationUs ?: 0L } / 1000 else null
    val canPlay = active && !busy && songs.isNotEmpty()
    fun playAlbum(action: LibraryContextAction) = onSelect(LibraryContextRequest(filter, null, action)) { if (!it) onPlaybackFailed() }
    LazyColumn(modifier = modifier.then(fade), state = list, contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item(key = "album actions") {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(albumInfoLine(selection.summary.year, total, lengthMs), style = EvannaDesign.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Play is the one tinted control, in the cover's colour; Shuffle stays neutral.
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { playAlbum(LibraryContextAction.PLAY_FIRST) }, enabled = canPlay,
                        colors = ActionColors(colors.primary, colors.onPrimary), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    Button(onClick = { playAlbum(LibraryContextAction.SHUFFLE) }, enabled = canPlay,
                        colors = ActionColors(colors.onSurface.copy(alpha = 0.12f), colors.onSurface), modifier = Modifier.weight(1f)) {
                        Icon(PlayerIcons.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            albumDiscHeading(songs, index, selection.summary)?.let { label ->
                Text(label, style = EvannaDesign.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
            }
            val playingHere = nowPlaying?.matches(song) == true
            LibrarySongRow(song, enabled = active && !busy, scope = scope, onPlay = {
                onSelect(LibraryContextRequest(filter, song.id, LibraryContextAction.PLAY_FROM)) { if (!it) onPlaybackFailed() }
            }, onAdd = onAdd, onPlayNext = onPlayNext, onPlaylistSong = onPlaylistSong, onTrackInfo = { onTrackInfo(song) },
                queueButton = false, swipeToQueue = true) {
                // The track number, or moving bars on the song that is playing; the album cover is already above.
                Box(Modifier.width(28.dp).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                    if (playingHere) PlayingBars(nowPlaying?.playing == true, colors.primary,
                        Modifier.semantics { contentDescription = if (nowPlaying?.playing == true) "Now playing" else "Paused" })
                    else Text((song.musicTags.trackNumber ?: (index + 1)).toString(), style = EvannaType.playbackTime,
                        color = colors.onSurfaceVariant, maxLines = 1)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(song.title, style = EvannaDesign.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (playingHere) colors.primary else Color.Unspecified,
                        fontWeight = if (playingHere) FontWeight.SemiBold else null)
                    val guests = if (song.available) trackGuests(song.artist, albumArtist) else "Unavailable · reconnect or rescan"
                    if (guests != null) Text(guests, style = EvannaDesign.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(start = 40.dp), color = colors.outlineVariant.copy(alpha = 0.32f))
        }
        if (songs.isEmpty()) item { Text("No tracks in this album.", modifier = Modifier.padding(vertical = 12.dp)) }
        if (failed) item {
            TextButton(onClick = { failed = false; retry++ }) { Text("Retry loading more tracks") }
        }
    }
}

/** Three small bars in the cover's colour on the playing track: they move while it plays and rest while paused. */
@Composable
private fun PlayingBars(moving: Boolean, color: Color, modifier: Modifier = Modifier) {
    val rest = remember { listOf(mutableFloatStateOf(0.55f), mutableFloatStateOf(0.85f), mutableFloatStateOf(0.4f)) }
    // The clock only runs while the song plays, so a paused song costs no frames.
    val heights: List<State<Float>> = if (moving) {
        val beat = rememberInfiniteTransition(label = "playing bars")
        List(3) { i ->
            beat.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420 + i * 130, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar $i")
        }
    } else rest
    Canvas(modifier.size(16.dp, 14.dp)) {
        val bar = size.width / 5f
        for (i in 0..2) {
            val h = size.height * heights[i].value
            drawRoundRect(color, Offset(bar * 2 * i, size.height - h), androidx.compose.ui.geometry.Size(bar, h),
                androidx.compose.ui.geometry.CornerRadius(bar / 2))
        }
    }
}
