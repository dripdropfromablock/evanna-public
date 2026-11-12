package com.evanna.player.library

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
private val CarouselCardShape = RoundedCornerShape(20.dp)
private val CarouselCoverShape = RoundedCornerShape(14.dp)
private val CarouselCoverInset = 7.dp
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
    val cover: Bitmap?,
    val accent: Color?,
)

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
                bottomInset, onSelect, onAdd, onPlayNext, onPlaylistSong)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
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
    onPlaylistSong: (String) -> Unit,
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
    LaunchedEffect(active, centeredAlbum, selectionRetry) {
        val album = centeredAlbum ?: return@LaunchedEffect
        if (!active || selection?.album == album) return@LaunchedEffect
        failedAlbum = null
        readCarousel {
            coroutineScope {
                val tracks = async(Dispatchers.IO) {
                    database.page(filter = LibraryFilter(kind = LibraryGroupKind.ALBUM, value = album.title,
                        albumArtist = album.albumArtist)) to database.albumSummary(album)
                }
                val cover = readCarousel { artwork.album(album, ArtworkSize.MEDIUM) }.getOrNull()
                val accent = cover?.let { withContext(Dispatchers.Default) { sampleArtworkAccent(it) } }
                val (page, summary) = tracks.await()
                CarouselSelection(album, page, summary, cover, accent)
            }
        }.onSuccess { selection = it }.onFailure { failedAlbum = album }
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
    val accent = selection?.accent
    val theme = remember(look, accent) { look.colors.withArtwork(accent, look.dark, look.apple, surfaceTint = 0.12f) }
    val heroColor by animateColorAsState(
        accent?.let { lerp(theme.background, it, if (look.dark) 0.21f else 0.12f) } ?: theme.background,
        EvannaMotion.ambient, label = "Carousel backdrop",
    )
    val cardColor by animateColorAsState(theme.surfaceContainerHigh, EvannaMotion.ambient, label = "Carousel cards")
    val listColor by animateColorAsState(theme.surfaceContainer, EvannaMotion.ambient, label = "Carousel track list")
    val visibleSelection = selection?.takeIf { it.album == centeredAlbum }
    LaunchedEffect(selection) {
        selection?.let { selected -> selected.cover?.let { cover -> backdrops.prepare(selected.album, cover) } }
    }
    ProvideEvannaColors(theme.copy(surfaceContainer = listColor, surfaceContainerHigh = cardColor)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(heroColor, listColor)))) {
            CarouselArtworkBackdrop(pager, backdrops, selection?.album,
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
            Column(Modifier.fillMaxSize()) {
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
                            CarouselAlbumCard(group, artwork, backdrops, revision, cardWidth, captionHeight, pageOffset, Modifier.graphicsLayer {
                                if (progress > 0f && page == pager.settledPage) alpha = 0f
                                if (!reducedMotion) {
                                    val offset = pageOffset()
                                    val distance = abs(offset)
                                    val scale = 1f - distance.coerceAtMost(2f) * 0.075f
                                    scaleX = scale
                                    scaleY = scale
                                    rotationY = -offset.coerceIn(-1.25f, 1.25f) * 14f
                                    cameraDistance = size.width * 4f
                                }
                            }, onClick = { if (progress == 0f) moveTo(page) })
                        }
                    }
                    if (progress > 0f) {
                        ExpandingCarouselAlbum(
                            centered, visibleSelection?.cover, progress, captionHeight,
                            coverHeight = lerpDp(cardWidth - CarouselCoverInset * 2, pinnedHeight, progress),
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
                            CarouselTracks(visibleSelection, database, active, bottomInset, scope, trackList,
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

private fun carouselGlassRim(strength: Float) = Brush.linearGradient(listOf(
    Color.White.copy(alpha = 0.54f * strength),
    Color.White.copy(alpha = 0.16f * strength),
    Color.White.copy(alpha = 0.10f * strength),
    Color.White.copy(alpha = 0.20f * strength),
))

@Composable
private fun ExpandingCarouselAlbum(
    group: LibraryGroup?, cover: Bitmap?, progress: Float, captionHeight: Dp, coverHeight: Dp, modifier: Modifier,
) {
    val colors = EvannaDesign.colorScheme
    val listColor = colors.surfaceContainer
    val glass = 1f - progress
    val inset = CarouselCoverInset * glass
    val frame = RoundedCornerShape(20.dp * glass)
    val coverShape = RoundedCornerShape(14.dp * glass)
    val fade = Brush.verticalGradient(
        0f to Color.Transparent,
        0.24f to listColor.copy(alpha = progress * 0.16f),
        0.72f to listColor.copy(alpha = progress * 0.90f),
        1f to listColor.copy(alpha = progress),
    )
    Surface(modifier, shape = frame,
        color = lerp(colors.surfaceContainerHigh.copy(alpha = 0.62f), listColor, progress),
        border = if (glass > 0f) BorderStroke(1.dp, carouselGlassRim(glass)) else null) {
        Box(Modifier.fillMaxSize()) {
            val coverModifier = Modifier.padding(start = inset, end = inset, top = inset)
                .fillMaxWidth().height(coverHeight)
            if (cover == null) ArtworkImage(null, coverModifier, coverShape)
            else {
                val image = remember(cover) { cover.asImageBitmap() }
                Image(image, contentDescription = null, modifier = coverModifier.clip(coverShape), contentScale = ContentScale.Crop)
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(maxOf(coverHeight * 0.55f, captionHeight * 2f)).background(fade))
            Column(Modifier.align(Alignment.BottomStart)
                .padding(horizontal = lerpDp(13.dp, 16.dp, progress), vertical = lerpDp(7.dp, 12.dp, progress))
                .height(captionHeight).padding(top = 6.dp * (1f - progress)),
                verticalArrangement = Arrangement.Center) {
                CarouselAlbumLabel(group, heading = true)
            }
        }
    }
}

@Composable
private fun CarouselAlbumCard(
    group: LibraryGroup?, artwork: EmbeddedArtwork, backdrops: CarouselBackdrops, revision: Int, width: Dp, captionHeight: Dp,
    pageOffset: () -> Float, modifier: Modifier, onClick: () -> Unit,
) {
    val colors = EvannaDesign.colorScheme
    Surface(modifier.width(width).clickable(interactionSource = null, indication = null, enabled = group != null, onClick = onClick),
        shape = CarouselCardShape, color = colors.surfaceContainerHigh.copy(alpha = 0.62f),
        border = BorderStroke(1.dp, carouselGlassRim(1f))) {
        Column(Modifier.padding(CarouselCoverInset)) {
            val cover = Modifier.fillMaxWidth().aspectRatio(1f)
            val album = group?.identity()
            var bitmap by remember(album, revision) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(album, revision) {
                if (album != null) {
                    bitmap = readCarousel { artwork.album(album, ArtworkSize.MEDIUM) }.getOrNull()
                    bitmap?.let { backdrops.prepare(album, it) }
                }
            }
            val shown = bitmap
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
                }, CarouselCoverShape)
            } else {
                val soften by remember(pageOffset) { derivedStateOf { abs(pageOffset()) >= 0.60f } }
                val softened = remember(shown) {
                    shown?.takeIf { maxOf(it.width, it.height) > 112 }?.let {
                        val scale = 112f / maxOf(it.width, it.height)
                        Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1),
                            (it.height * scale).toInt().coerceAtLeast(1), true)
                    }
                }
                ArtworkImage(if (soften) softened ?: shown else shown, cover, CarouselCoverShape)
            }
            Column(Modifier.fillMaxWidth().height(captionHeight).padding(start = 6.dp, end = 6.dp, top = 6.dp),
                verticalArrangement = Arrangement.Center) {
                CarouselAlbumLabel(group)
            }
        }
    }
}

@Composable
private fun CarouselAlbumLabel(group: LibraryGroup?, heading: Boolean = false) {
    Text(group?.let { it.value ?: "Unknown album" }.orEmpty(), style = EvannaDesign.typography.titleMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = if (heading) Modifier.semantics { heading() } else Modifier)
    Text((group?.artist ?: group?.albumArtist).orEmpty(), style = EvannaDesign.typography.bodySmall,
        color = EvannaDesign.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CarouselTracks(
    selection: CarouselSelection, database: LibraryDatabase, active: Boolean, bottomInset: Dp, scope: CoroutineScope,
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
    LazyColumn(modifier = modifier.then(fade), state = list, contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            albumDiscHeading(songs, index, selection.summary)?.let { label ->
                Text(label, style = EvannaDesign.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
            }
            LibrarySongRow(song, enabled = active && !busy, scope = scope, onPlay = {
                onSelect(LibraryContextRequest(filter, song.id, LibraryContextAction.PLAY_FROM)) { if (!it) onPlaybackFailed() }
            }, onAdd = onAdd, onPlayNext = onPlayNext, onPlaylistSong = onPlaylistSong, onTrackInfo = { onTrackInfo(song) }) {
                ArtworkImage(selection.cover, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(song.title, style = EvannaDesign.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val artist = song.artist ?: selection.summary.displayArtist
                    if (artist != null || !song.available) Text(
                        if (song.available) artist.orEmpty() else "Unavailable · reconnect or rescan",
                        style = EvannaDesign.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(start = 56.dp), color = colors.outlineVariant.copy(alpha = 0.32f))
        }
        if (songs.isEmpty()) item { Text("No tracks in this album.", modifier = Modifier.padding(vertical = 12.dp)) }
        if (failed) item {
            TextButton(onClick = { failed = false; retry++ }) { Text("Retry loading more tracks") }
        }
    }
}
