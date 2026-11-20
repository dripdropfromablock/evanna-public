package com.evanna.player

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.evanna.player.library.ArtworkImage
import com.evanna.player.library.FoundLyrics
import com.evanna.player.library.LibraryArtwork
import com.evanna.player.library.LibrarySong
import com.evanna.player.library.LrcLibBusy
import com.evanna.player.library.LyricLine
import com.evanna.player.library.Lyrics
import com.evanna.player.library.LyricsPlace
import com.evanna.player.library.LyricsQuery
import com.evanna.player.playback.SleepMode
import com.evanna.player.playback.nearest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The three pages of the Now Playing sheet; [heading] names the page for screen readers. */
internal enum class PlayerPage(val heading: String) { LYRICS("Lyrics"), SONG("Now Playing"), QUEUE("Queue") }

// White over the dark cover backdrop (UI spec §1.2). The two greys are raised from .45 and .18 so text keeps 4.5:1 (DESIGN.md).
private val Pv1 = Color.White.copy(alpha = 0.94f)
private val Pv2 = Color.White.copy(alpha = 0.64f)
private const val INACTIVE_LYRIC_OPACITY = 0.54f
private val Pv3 = Color.White.copy(alpha = 0.20f)
private val Pv4 = Color.White.copy(alpha = 0.10f)

private fun playerText(size: Int, line: Int, weight: FontWeight = FontWeight.Medium, tabular: Boolean = false) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    fontFeatureSettings = if (tabular) "tnum" else null)
private val SongTitle = playerText(20, 24)
private val SidewaysSongTitle = playerText(22, 27)
private val HeaderTitle = playerText(17, 22)
private val HeaderArtist = playerText(17, 22, FontWeight.Normal)
private val RowArtist = playerText(15, 20, FontWeight.Normal)
private val SectionTitle = playerText(17, 22, FontWeight.Bold)
private val PillLabel = playerText(15, 20)
private val Times = playerText(12, 16, tabular = true)
private val Badge = playerText(11, 13)
private val Route = playerText(13, 18)
private val VolumeLabel = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 15.sp, fontFeatureSettings = "tnum")
private val Status = playerText(13, 18, FontWeight.Normal)
private val LyricsLine = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.3).sp)
private val PlainLyrics = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 31.sp)
// Sideways Details style (Georgia mockup): the current line at 21 sp, the rest scaled to about 15 sp.
private val SmallLyricsLine = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)
private val SmallPlainLyrics = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
private const val SMALL_LYRIC_SCALE = 0.72f

private val CoverShape = RoundedCornerShape(10.dp)
private val SmallCoverShape = RoundedCornerShape(5.dp)

/**
 * Now Playing (plan tasks U3–U5, mockup `mockups/looks.html`): Lyrics, Song and Queue pages over the
 * moving cover, sharing one set of controls at the bottom.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingScreen(state: PlayerPresentation, cover: Bitmap?, actionError: String?, onBack: () -> Unit,
    onLibrary: () -> Unit, onOutput: (Rect?) -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (String?, Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onUsbVolume: (Int) -> Unit,
    loadLyrics: suspend (String) -> Lyrics?, onUsbVolumeSet: (Float, Long) -> Unit, onSleep: (SleepMode, Int) -> Unit,
    onFavorite: (String, Boolean) -> Unit, onSelect: (String) -> Unit, onMove: (String, String) -> Unit, onRemove: (String) -> Unit,
    onSavePlaylist: () -> Unit, loadArtwork: suspend (String) -> Bitmap?, motion: PlayerSheetMotion, flying: Boolean,
    wantedOpen: Boolean, onConvert: (() -> Unit)? = null,
    outputSheetOpen: Boolean = false, sidewaysStyle: SidewaysStyle = SidewaysStyle.DETAILS,
    loadSongInfo: suspend (String) -> LibrarySong? = { null }, coverStyle: CoverStyle = CoverStyle.CARD,
    findLyrics: suspend (String, LyricsQuery, Boolean) -> FoundLyrics = { _, _, _ -> FoundLyrics.NotFound },
    allowLyricsBeside: suspend (String) -> String? = { null }, autoFindLyrics: Boolean = false) {
    var page by rememberSaveable { mutableStateOf(PlayerPage.SONG) }
    var lyricsTrack by rememberSaveable { mutableStateOf<String?>(null) }
    var showSignal by rememberSaveable { mutableStateOf(false) }
    var signalOrigin by remember { mutableStateOf<Rect?>(null) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    if (showSleep) SleepDialog(state, onSleep) { showSleep = false }
    if (showSignal) SoundPathSheet(state.soundPath, { showSignal = false; onOutput(null) }, { showSignal = false }, signalOrigin)
    // Share cards (docs/mockups/share-cards.html): picked lyric lines or the album, over the cover's glow.
    var shareCard by remember { mutableStateOf<ShareCard?>(null) }
    val latest by rememberUpdatedState(state)
    val latestCover by rememberUpdatedState(cover)
    val lyricShare = remember(state.id) {
        LyricShare { lines, before, after ->
            shareCard = ShareCard.LyricCard(lines, latest.title.ifBlank { "Local audio file" },
                latest.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", before, after)
        }
    }
    LaunchedEffect(page) { if (page != PlayerPage.LYRICS) lyricShare.picked = null }
    shareCard?.let { card -> ShareSheet(card, { latestCover }) { shareCard = null } }
    BackHandler {
        when {
            lyricShare.picked != null -> lyricShare.picked = null
            wantedOpen && page != PlayerPage.SONG -> page = PlayerPage.SONG
            else -> onBack()
        }
    }
    // The preview and full view share one load, and never show the preceding track's words.
    val loaded by key(state.id) {
        produceState<LyricsLoad>(LyricsLoad.Loading) {
            val id = state.id ?: return@produceState
            value = try { loadLyrics(id)?.let { LyricsLoad.Ready(it) } ?: LyricsLoad.None }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LyricsLoad.Failed }
        }
    }
    // A song with no lyrics can look them up on LRCLIB: by hand from the Lyrics page, or by itself with the setting on.
    var online by remember(state.id) { mutableStateOf<OnlineLyrics>(OnlineLyrics.Idle) }
    var searchAsked by remember(state.id) { mutableStateOf<Boolean?>(null) }  // true by itself, false by hand
    var lyricsNote by remember(state.id) { mutableStateOf<String?>(null) }
    var askFolder by remember(state.id) { mutableStateOf<String?>(null) }
    val lyrics = (online as? OnlineLyrics.Found)?.let { LyricsLoad.Ready(it.lyrics) } ?: loaded
    LaunchedEffect(loaded, autoFindLyrics, wantedOpen) {
        if (autoFindLyrics && wantedOpen && loaded == LyricsLoad.None && online == OnlineLyrics.Idle && searchAsked == null) searchAsked = true
    }
    LaunchedEffect(state.id, searchAsked) {
        val auto = searchAsked ?: return@LaunchedEffect
        val id = state.id ?: return@LaunchedEffect
        val title = state.title.trim()
        online = if (title.isEmpty()) OnlineLyrics.Failed("This song has no title to search for.") else {
            online = OnlineLyrics.Searching
            try {
                when (val found = findLyrics(id, LyricsQuery(title, state.artist, state.album, state.durationMs), auto)) {
                    is FoundLyrics.Found -> {
                        askFolder = found.askFolder
                        if (found.askFolder == null) lyricsNote =
                            if (found.place == LyricsPlace.BESIDE_SONG) "Lyrics from LRCLIB, saved beside the song." else "Lyrics from LRCLIB, saved in Evanna."
                        OnlineLyrics.Found(found.lyrics)
                    }
                    FoundLyrics.NotFound -> OnlineLyrics.NotFound
                    FoundLyrics.Instrumental -> OnlineLyrics.Instrumental
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LrcLibBusy) { OnlineLyrics.Failed("LRCLIB is busy. Try again in a minute.") }
            catch (_: Exception) { OnlineLyrics.Failed("Couldn't reach LRCLIB. Check the internet connection.") }
        }
        searchAsked = null
    }
    LaunchedEffect(lyricsNote) { if (lyricsNote != null) { delay(4_000); lyricsNote = null } }
    val scope = rememberCoroutineScope()
    askFolder?.let { folder ->
        AlertDialog(onDismissRequest = { askFolder = null; lyricsNote = "Lyrics from LRCLIB, saved in Evanna." },
            title = { Text("Save beside the song?") },
            text = { Text("The lyrics are saved in Evanna for now. To save them as a .lrc file next to the song, Android will ask " +
                "you to let Evanna add files to “$folder”. Evanna never changes the songs themselves.") },
            confirmButton = {
                TextButton(onClick = {
                    askFolder = null
                    val id = state.id
                    if (id != null) scope.launch { lyricsNote = allowLyricsBeside(id) ?: "Saved beside the song." }
                }) { Text("Choose folder") }
            },
            dismissButton = {
                TextButton(onClick = { askFolder = null; lyricsNote = "Lyrics from LRCLIB, saved in Evanna." }) { Text("Keep in Evanna") }
            })
    }
    val lyricsSearch = LyricsSearch(online, lyricsNote) { if (online != OnlineLyrics.Searching) searchAsked = false }
    LaunchedEffect(wantedOpen) {
        if (wantedOpen && page == PlayerPage.LYRICS && lyricsTrack != state.id) page = PlayerPage.SONG
    }
    val more: @Composable () -> Unit = {
        MoreButton(buildList<Pair<String, () -> Unit>> {
            (lyrics as? LyricsLoad.Ready)?.lyrics?.let { words ->
                if (state.hasSong) add("Share lyrics" to {
                    lyricsTrack = state.id
                    page = PlayerPage.LYRICS
                    lyricShare.picked = setOfNotNull(firstShareLine(words, state.positionMs))
                })
            }
            if (state.hasSong) add("Share album" to {
                shareCard = ShareCard.AlbumCard(state.album?.takeIf { it.isNotBlank() } ?: state.title.ifBlank { "Local audio file" },
                    state.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist")
            })
            if(state.route!="DLNA")add("Sleep timer" to { showSleep = true })
            if (onConvert != null) add("Play converted once" to onConvert)
            if (page == PlayerPage.QUEUE && state.connected && state.queue.isNotEmpty()) add("Save queue as playlist" to onSavePlaylist)
        })
    }
    // Upright, the full cover fills the top of the Song page and fades into the backdrop above the title.
    val fullCover = coverStyle == CoverStyle.FULL && cover != null && state.hasSong && !sidewaysPhone()
    var screenBox by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var songCoverArea by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val contentAlpha = {
        if (motion.morphs) ((motion.progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
        else ((motion.progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
    }
    val onSong by animateFloatAsState(if (page == PlayerPage.SONG) 1f else 0f, tween(260), label = "full cover")
    val coverBottom = {
        val box = screenBox
        val area = songCoverArea
        if (box?.isAttached == true && area?.isAttached == true) box.localBoundingBoxOf(area).bottom else null
    }
    val coverOverlap = with(LocalDensity.current) { FullCoverOverlap.toPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { screenBox = it }) {
        PlayerBackdrop(cover, LocalLookStyle.current.look == EvannaLook.APPLE_LIGHT,
            // Growing out of the mini player, the page is solid almost at once and its words come in near the end.
            Modifier.matchParentSize().graphicsLayer { alpha = if (motion.morphs) (motion.progress / 0.2f).coerceAtMost(1f) else motion.progress },
            // Under the full cover the backdrop's glow fades out too, so the cover fades into one calm colour, not into it.
            // The calm holds through the first half of the cover's fade, so the glow comes back only near its end.
            calm = calm@{
                val box = screenBox?.takeIf { fullCover && it.isAttached } ?: return@calm null
                val height = fullCoverHeight(coverBottom(), box.size.width, box.size.height, coverOverlap).toFloat()
                val fadeTop = height * COVER_FADE_START
                BackdropCalm(fadeTop + (height - fadeTop) / 2, height, onSong * contentAlpha())
            })
        if (fullCover && cover != null) {
            FullCover(cover, bottom = coverBottom, modifier = Modifier.graphicsLayer { alpha = onSong * contentAlpha() }
                .dockingCover(motion, flying, corner = 0.dp, report = page == PlayerPage.SONG, fade = true))
        }
        val openPage: (PlayerPage) -> Unit = {
            if (it == PlayerPage.LYRICS) lyricsTrack = state.id
            page = it
        }
        // Upright Lyrics, as in Apple Music: left alone for 5 s, the controls slide away and the words take their room.
        // Any touch or key brings them back. They stay with TalkBack on, under a sheet or dialog, and while there is a problem.
        val context = LocalContext.current
        val talkBack = remember(context) { context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
        val canHideControls = page == PlayerPage.LYRICS && wantedOpen && !flying && state.hasSong && !sidewaysPhone() &&
            lyrics is LyricsLoad.Ready && !outputSheetOpen && !showSignal && !showSleep && !talkBack &&
            state.error == null && actionError == null
        val lyricsIdle = rememberIdleWatch(canHideControls)
        // While lyric lines are being ticked, their bar takes the controls' place.
        val hideControls = (lyricsIdle.idle && canHideControls) || (lyricShare.picked != null && page == PlayerPage.LYRICS && !sidewaysPhone())
        val controlsHidden by animateFloatAsState(if (hideControls) 1f else 0f,
            tween(if (hideControls) 420 else 260, easing = FastOutSlowInEasing), label = "lyrics controls hide")
        // Read each frame only while laying out and drawing; the page itself hears only when the controls start or finish moving.
        val controlsMoved by remember { derivedStateOf { controlsHidden > 0f } }
        CompositionLocalProvider(LocalContentColor provides Pv1) {
            if (sidewaysPhone() && state.hasSong && sidewaysStyle == SidewaysStyle.DETAILS) {
                val info by key(state.id) {
                    produceState<LibrarySong?>(null) { value = state.id?.let { runCatching { loadSongInfo(it) }.getOrNull() } }
                }
                DetailsPlayer(state, cover, info, page, openPage, lyrics, more, contentAlpha, motion, flying, onBack, onFavorite, onSeek,
                    onPlayPause, onPrevious, onNext, { signalOrigin = it; showSignal = true }, onOutput, outputSheetOpen, onSelect,
                    queue = { QueuePage(state, onShuffle, onRepeat, onSelect, onMove, onRemove, onLibrary, loadArtwork, it) }, search = lyricsSearch,
                    share = lyricShare)
            }
            else if (sidewaysPhone() && state.hasSong) SidewaysPlayer(state, cover, page, openPage, lyrics, more, contentAlpha, motion, flying,
                onBack, onFavorite, onSeek, onPlayPause, onPrevious, onNext, search = lyricsSearch, share = lyricShare,
                controls = {
                    PlayerControls(state, actionError, page, openPage, lyrics, onSeek, { signalOrigin = it; showSignal = true },
                        onPlayPause, onPrevious, onNext, onUsbVolume, onUsbVolumeSet, onSleep, onOutput, outputSheetOpen, sideways = true)
                },
                queue = { QueuePage(state, onShuffle, onRepeat, onSelect, onMove, onRemove, onLibrary, loadArtwork, it) })
            else Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha() }.wakesControls(lyricsIdle)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Grabber(page.heading, onBack)
                if (!state.hasSong) NoSong(state, onLibrary, Modifier.weight(1f))
                else {
                    SharedTransitionLayout(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(targetState = page, label = "player page", modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val enter = fadeIn(EvannaMotion.enter)
                                val exit = fadeOut(EvannaMotion.exit)
                                (if (targetState == PlayerPage.LYRICS) enter + slideInVertically { it / 4 } else enter) togetherWith
                                    (if (initialState == PlayerPage.LYRICS) exit + slideOutVertically { it / 4 } else exit)
                            }) { shown ->
                            val sharedCover = if (cover == null || flying) Modifier else Modifier.sharedElement(
                                rememberSharedContentState("player cover"),
                                animatedVisibilityScope = this@AnimatedContent,
                                boundsTransform = { _, _ -> tween(340, easing = FastOutSlowInEasing) },
                            )
                            // The leaving page stays composed for a moment; only the shown page may speak to screen readers.
                            Box(Modifier.fillMaxSize().then(if (shown == page) Modifier else Modifier.clearAndSetSemantics {})) {
                                when (shown) {
                                    PlayerPage.SONG -> SongPage(state, cover, onFavorite, more,
                                        Modifier.dockingCover(motion, flying, corner = 10.dp, report = shown == page && !fullCover).then(sharedCover),
                                        fullCover, onCoverArea = { songCoverArea = it })
                                    PlayerPage.LYRICS -> Column(Modifier.fillMaxSize()) {
                                        CompactHeader(state, cover, more,
                                            Modifier.dockingCover(motion, flying, report = shown == page).then(sharedCover)) { page = PlayerPage.SONG }
                                        LyricsPage(lyrics, state, onSeek, holdLine = controlsMoved, search = lyricsSearch, share = lyricShare, modifier = Modifier.weight(1f).padding(top = 24.dp).graphicsLayer {
                                            val expanded = ((motion.progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                            alpha = expanded
                                            scaleX = 0.65f + 0.35f * expanded
                                            scaleY = scaleX
                                            transformOrigin = TransformOrigin(1f, 1f)
                                        })
                                    }
                                    PlayerPage.QUEUE -> Column(Modifier.fillMaxSize()) {
                                        CompactHeader(state, cover, more,
                                            Modifier.dockingCover(motion, flying, report = shown == page).then(sharedCover)) { page = PlayerPage.SONG }
                                        QueuePage(state, onShuffle, onRepeat, onSelect, onMove, onRemove, onLibrary, loadArtwork, Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    // Hidden, the controls keep only the room above the home bar and slide down by their full height.
                    val navigationBars = WindowInsets.navigationBars
                    Box(Modifier.layout { measurable, constraints ->
                        val bar = measurable.measure(constraints)
                        val rest = navigationBars.getBottom(this) + 6.dp.roundToPx()
                        val height = bar.height - ((bar.height - rest).coerceAtLeast(0) * controlsHidden).roundToInt()
                        layout(bar.width, height) { bar.place(0, (rest * controlsHidden).roundToInt()) }
                    }.graphicsLayer { alpha = 1f - controlsHidden }.then(if (hideControls) Modifier.clearAndSetSemantics {} else Modifier)) {
                        PlayerControls(state, actionError, page, openPage, lyrics, onSeek, { signalOrigin = it; showSignal = true }, onPlayPause, onPrevious, onNext, onUsbVolume, onUsbVolumeSet, onSleep,
                            onOutput, outputSheetOpen, lyricsSearching = online == OnlineLyrics.Searching)
                    }
                }
            }
        }
    }
}

/** The pull-down grabber. Its row names the page for screen readers; the capsule closes the sheet. */
@Composable
private fun Grabber(title: String, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Box(Modifier.matchParentSize().semantics { heading(); text = AnnotatedString(title) })
        Box(Modifier.align(Alignment.TopCenter).size(120.dp, 48.dp)
            .controlFocus(interaction, RoundedCornerShape(12.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClose)
            .semantics { contentDescription = "Minimize player" }) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(36.dp, 5.dp)
                .clip(RoundedCornerShape(2.5.dp)).background(if (pressed) Pv2 else Pv3))
        }
    }
}

@Composable
private fun NoSong(state: PlayerPresentation, onLibrary: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (!state.connected) state.error ?: "Connecting playback controls…"
            else "No song selected. Choose music from your library, or open a local file in Settings.", style = HeaderArtist, color = Pv2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onLibrary, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open music library") }
    }
}

/** The More circle with its menu. */
@Composable
private fun MoreButton(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        PlayerHeaderButton(PlayerIcons.More, "More options") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, action) in items) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
        }
    }
}

/**
 * Favorite and More beside the title: an 18 dp glyph in a soft 32 dp circle with no rim, as in iOS 26 Apple Music.
 * [on] (a favorite) turns the circle white and the glyph dark.
 */
@Composable
private fun PlayerHeaderButton(icon: ImageVector, description: String,
    enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val glass = glassLiftOn()
    val contrast = increaseContrastOn()
    Box(Modifier.size(48.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (contrast) Box(Modifier.size(36.dp).contrastPlatter(CircleShape))
        else Box(Modifier.size(32.dp).background(Color.White.copy(alpha = if (on) 0.88f else 0.17f), CircleShape)
            .then(if (glass) Modifier.liftIntoGlass(interaction, CircleShape, outset = 3.dp) else Modifier))
        Icon(icon, contentDescription = null, tint = when {
            !enabled -> Pv3
            on && !contrast -> Color.Black.copy(alpha = 0.72f)
            else -> Pv1
        }, modifier = Modifier.size(18.dp).pressScale(interaction))
    }
}

@Composable
private fun SleepDialog(state: PlayerPresentation, onSleep: (SleepMode, Int) -> Unit, onClose: () -> Unit) {
    // The choices are the alert's own buttons, stacked as on iOS, with Cancel at the bottom.
    AlertDialog(onDismissRequest = onClose, title = { Text("Sleep timer") },
        text = { Text("Playback pauses; your queue stays. No fade-out.") },
        confirmButton = {
            for (minutes in listOf(15, 30, 45, 60, 90)) TextButton(onClick = { onSleep(SleepMode.MINUTES, minutes); onClose() }) {
                Text("$minutes minutes")
            }
            for (mode in listOf(SleepMode.SONG, SleepMode.ALBUM)) TextButton(onClick = { onSleep(mode, 0); onClose() }, enabled = state.hasSong) {
                Text(if (mode == SleepMode.SONG) "End of this song" else "End of this album")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }, prominent = false)
}

/** Page 1: the big cover, then title and artist with Favorite and More. The cover shrinks a little while paused. */
@Composable
private fun SongPage(state: PlayerPresentation, cover: Bitmap?, onFavorite: (String, Boolean) -> Unit,
    more: @Composable () -> Unit, artwork: Modifier, fullCover: Boolean = false, onCoverArea: (LayoutCoordinates) -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp)) {
        val scale by animateFloatAsState(if (state.playing) 1f else 0.86f, spring(dampingRatio = 0.78f, stiffness = 380f), label = "cover scale")
        val lift by animateDpAsState(if (state.playing) 15.dp else 8.dp, spring(dampingRatio = 0.78f, stiffness = 380f), label = "cover shadow")
        // With the full cover the screen draws the cover behind the page; this room only sets where it ends.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned(onCoverArea), contentAlignment = Alignment.Center) {
            if (!fullCover) {
                val side = minOf(maxWidth, maxHeight)
                Box(Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }) {
                    ArtworkImage(cover, Modifier.fillMaxSize().then(artwork).shadow(lift, CoverShape), CoverShape)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        SongTitleRow(state, onFavorite, more)
    }
}

/** How far the full cover runs past the cover room, under the title, while it fades out. */
private val FullCoverOverlap = 56.dp

/** The full cover's height in pixels: down to [bottom] plus [overlap], at least square, at most [maxHeight]. */
private fun fullCoverHeight(bottom: Float?, width: Int, maxHeight: Int, overlap: Float) =
    ((bottom ?: width.toFloat()) + overlap).roundToInt().coerceIn(width, maxOf(width, maxHeight))

/**
 * The full cover: the screen's width, from the top edge under the status bar to a little past where the card would end
 * ([bottom], in this box's pixels), cropped to fill. It fades out over its lower 45%, so the title sits on the backdrop.
 * A light shade at the top keeps the clock and the grabber readable on a bright cover.
 */
@Composable
private fun FullCover(cover: Bitmap, bottom: () -> Float?, modifier: Modifier) {
    val image = remember(cover) { cover.asImageBitmap() }
    val overlap = with(LocalDensity.current) { FullCoverOverlap.toPx() }
    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier
        .layout { measurable, constraints ->
            val width = constraints.maxWidth
            val height = fullCoverHeight(bottom(), width, constraints.maxHeight, overlap)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }
        .coverFade { 1f })
}

/** Title and artist, with Favorite and More at the end. */
@Composable
private fun SongTitleRow(state: PlayerPresentation, onFavorite: (String, Boolean) -> Unit, more: @Composable () -> Unit,
    modifier: Modifier = Modifier, titleStyle: TextStyle = SongTitle) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.title.ifBlank { "Local audio file" }, style = titleStyle, color = Pv1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", style = SongTitle, color = Pv2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.offset(x = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerHeaderButton(if (state.favorite == true) PlayerIcons.HeartFill else PlayerIcons.Heart,
                description = when (state.favorite) {
                    true -> "Remove from favorites"
                    false -> "Add to favorites"
                    null -> "Favorites unavailable"
                },
                enabled = state.connected && state.favorite != null,
                on = state.favorite == true,
            ) {
                state.id?.let { onFavorite(it, state.favorite != true) }
            }
            more()
        }
    }
}

/** A phone on its side: wider than tall, and too short for the upright layout. */
@Composable
internal fun sidewaysPhone(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp && config.screenHeightDp < 600
}

/**
 * Now Playing on its side: the upright cover card on the left and a panel on the right, both over the blurred cover
 * backdrop. The panel holds the controls; lyrics or the queue take its place, with a small Play row under them.
 */
@Composable
private fun SidewaysPlayer(state: PlayerPresentation, cover: Bitmap?, page: PlayerPage, onPage: (PlayerPage) -> Unit,
    lyrics: LyricsLoad, more: @Composable () -> Unit, contentAlpha: () -> Float, motion: PlayerSheetMotion, flying: Boolean,
    onBack: () -> Unit, onFavorite: (String, Boolean) -> Unit, onSeek: (String?, Long) -> Unit,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, search: LyricsSearch, share: LyricShare,
    controls: @Composable () -> Unit, queue: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panel = (maxWidth * 0.46f).coerceIn(320.dp, 460.dp)
        // The same cover card as upright, on the blurred cover backdrop behind the whole screen: rounded, with a
        // shadow, and a little smaller while paused.
        val scale by animateFloatAsState(if (state.playing) 1f else 0.86f, spring(dampingRatio = 0.78f, stiffness = 380f), label = "cover scale")
        val lift by animateDpAsState(if (state.playing) 15.dp else 8.dp, spring(dampingRatio = 0.78f, stiffness = 380f), label = "cover shadow")
        BoxWithConstraints(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(maxWidth - panel)
            .graphicsLayer { alpha = contentAlpha() }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.Bottom))
            .padding(horizontal = 32.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Box(Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }) {
                ArtworkImage(cover, Modifier.fillMaxSize().dockingCover(motion, flying, corner = 10.dp).shadow(lift, CoverShape), CoverShape)
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(panel)
            .graphicsLayer { alpha = contentAlpha() }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom))) {
            Grabber(page.heading, onBack)
            AnimatedContent(targetState = page, label = "sideways page", modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(EvannaMotion.enter) togetherWith fadeOut(EvannaMotion.exit) }) { shown ->
                Column(Modifier.fillMaxSize().then(if (shown == page) Modifier else Modifier.clearAndSetSemantics {})) {
                    when (shown) {
                        // The song and controls sit together on the cover's middle line
                        // (docs/mockups/cover-sideways-proportions.html, B). That line is half a grabber (24 dp) above
                        // this page's own middle; the block's last 22 dp are empty room under the icons, so it moves
                        // 11 dp less. When the page is too short it keeps inside it.
                        PlayerPage.SONG -> Column(Modifier.fillMaxSize().layout { measurable, constraints ->
                            val block = measurable.measure(constraints.copy(minHeight = 0))
                            val top = (constraints.maxHeight / 2 - 13.dp.roundToPx() - block.height / 2)
                                .coerceIn(0, (constraints.maxHeight - block.height).coerceAtLeast(0))
                            layout(constraints.maxWidth, constraints.maxHeight) { block.place(0, top) }
                        }) {
                            SongTitleRow(state, onFavorite, more, Modifier.padding(horizontal = 32.dp), titleStyle = SidewaysSongTitle)
                            controls()
                        }
                        PlayerPage.LYRICS -> {
                            LyricsPage(lyrics, state, onSeek, Modifier.weight(1f), search = search, share = share)
                            SidewaysPlayRow(state, page, onPage, lyrics, onPlayPause, onPrevious, onNext)
                        }
                        PlayerPage.QUEUE -> {
                            queue(Modifier.weight(1f))
                            SidewaysPlayRow(state, page, onPage, lyrics, onPlayPause, onPrevious, onNext)
                        }
                    }
                }
            }
        }
    }
}

/** Under lyrics or the queue on its side: a small Previous, Play and Next, then the Lyrics and Queue buttons. */
@Composable
private fun SidewaysPlayRow(state: PlayerPresentation, page: PlayerPage, onPage: (PlayerPage) -> Unit, lyrics: LyricsLoad,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val lyricsOn = page == PlayerPage.LYRICS
    val queueOn = page == PlayerPage.QUEUE
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TransportButton(PlayerIcons.Previous, 28.dp, "Previous", state.connected && state.canPrevious, onClick = onPrevious)
        TransportButton(if (state.playing) PlayerIcons.Pause else if (state.ended) PlayerIcons.Refresh else PlayerIcons.Play, 34.dp,
            if (state.playing) "Pause playback" else "Play playback", state.connected && state.canPlayPause, onClick = onPlayPause)
        TransportButton(PlayerIcons.Next, 28.dp, "Next", state.connected && state.canNext, onClick = onNext)
        Spacer(Modifier.weight(1f))
        StripButton(PlayerIcons.Lyrics, lyricsOn, if (lyricsOn) "Close lyrics" else "Open lyrics",
            enabled = lyricsOn || lyrics.opens) { onPage(if (lyricsOn) PlayerPage.SONG else PlayerPage.LYRICS) }
        StripButton(PlayerIcons.QueueList, queueOn, "Queue · ${counted(state.queue.size, "songs")}") {
            onPage(if (queueOn) PlayerPage.SONG else PlayerPage.QUEUE)
        }
    }
}

@Composable
private fun CompactHeader(state: PlayerPresentation, cover: Bitmap?, more: @Composable () -> Unit, artwork: Modifier, onShowSong: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkImage(cover, Modifier.size(54.dp).then(artwork).shadow(4.dp, SmallCoverShape)
            .controlFocus(interaction, SmallCoverShape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onShowSong)
            .semantics { contentDescription = "Return to Now Playing" }.pressScale(interaction), SmallCoverShape)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(state.title.ifBlank { "Local audio file" }, style = HeaderTitle, color = Pv1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", style = HeaderArtist, color = Pv2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        more()
    }
}

/** Shared playback controls keep their positions when the upper page changes. */
@Composable
private fun PlayerControls(state: PlayerPresentation, actionError: String?, page: PlayerPage, onPage: (PlayerPage) -> Unit,
    lyrics: LyricsLoad, onSeek: (String?, Long) -> Unit, onSignal: (Rect?) -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onUsbVolume: (Int) -> Unit, onUsbVolumeSet: (Float, Long) -> Unit, onSleep: (SleepMode, Int) -> Unit,
    onOutput: (Rect?) -> Unit, outputSheetOpen: Boolean, sideways: Boolean = false, lyricsSearching: Boolean = false) {
    val lyricsOn = page == PlayerPage.LYRICS
    val toggleLyrics = { onPage(if (lyricsOn) PlayerPage.SONG else PlayerPage.LYRICS) }
    val directUsb = state.route == "DIRECT_USB"
    val compact = LocalConfiguration.current.screenHeightDp < 560
    val androidVolume = state.route == "ANDROID" && !state.androidVolumeBypassed
    val volumeEnabled = androidVolume && state.connected && !outputSheetOpen
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 10.dp)) {
        // Sideways, the panel has no room for the line of lyrics; the lyrics button opens them in the panel.
        if (!lyricsOn && !sideways) LyricsPreview(lyrics, state, lyricsSearching, toggleLyrics)
        SeekBar(state, onSeek, onSignal)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(68.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TransportButton(PlayerIcons.Previous, 40.dp, "Previous", state.connected && state.canPrevious, onClick = onPrevious)
            TransportButton(if (state.playing) PlayerIcons.Pause else if (state.ended) PlayerIcons.Refresh else PlayerIcons.Play, 56.dp,
                if (state.playing) "Pause playback" else "Play playback", state.connected && state.canPlayPause, onClick = onPlayPause)
            TransportButton(PlayerIcons.Next, 40.dp, "Next", state.connected && state.canNext, onClick = onNext)
        }
        if (directUsb) UsbVolume(state, onUsbVolume, onUsbVolumeSet, onOpenOutput = onOutput)
        else if (!compact) AndroidMediaVolume(enabled = volumeEnabled,
            modifier = Modifier.padding(top = 8.dp), stepButtons = true, showLevel = androidVolume, onOpenOutput = onOutput)
        val problem = state.error ?: actionError
        val status = problem ?: when {
            !state.connected -> "Connecting playback controls…"
            state.route == "NONE" && state.canPlayPause -> "Press Play to listen through Android audio."
            state.buffering -> "Loading audio…"
            state.ended -> "Finished"
            else -> null
        }
        status?.let { Text(it, style = Status, color = if (problem == null) Pv2 else EvannaDesign.colorScheme.error, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        state.queueWarning?.let { Text(it, style = Status, color = EvannaDesign.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        if (state.route == "DIRECT_USB") state.signalStages.firstOrNull { it.label == "Leveling" && it.value != "Off" }?.let {
            Text("Leveling · ${it.value}", style = Status, color = Pv2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (state.sleepLabel.isNotEmpty()) TextButton(onClick = { onSleep(SleepMode.OFF, 0) }, colors = ButtonDefaults.textButtonColors(contentColor = Pv2),
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).semantics { contentDescription = "Cancel sleep timer" }) {
            Text("${state.sleepLabel} · Cancel", style = Status)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StripButton(PlayerIcons.Lyrics, lyricsOn, if (lyricsOn) "Close lyrics" else "Open lyrics",
                enabled = lyricsOn || lyrics.opens, onClick = toggleLyrics)
            // Audio output grows out of this button. On a short screen it sits after the volume.
            var outputBox by remember { mutableStateOf<Rect?>(null) }
            val output = @Composable {
                StripButton(PlayerIcons.Output, outputSheetOpen, "Sound output",
                    modifier = Modifier.onGloballyPositioned { outputBox = it.screenBox() }) { onOutput(outputBox) }
            }
            if (compact && !directUsb) {
                AndroidMediaVolume(enabled = volumeEnabled,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp), showLevel = androidVolume, onOpenOutput = onOutput)
                output()
            } else {
                Spacer(Modifier.weight(1f))
                output()
                Spacer(Modifier.weight(1f))
            }
            val queueOn = page == PlayerPage.QUEUE
            StripButton(PlayerIcons.QueueList, queueOn, "Queue · ${counted(state.queue.size, "songs")}") {
                onPage(if (queueOn) PlayerPage.SONG else PlayerPage.QUEUE)
            }
        }
    }
}

@Composable
private fun LyricsPreview(load: LyricsLoad, state: PlayerPresentation, searching: Boolean, onClick: () -> Unit) {
    val lyrics = (load as? LyricsLoad.Ready)?.lyrics
    val reducedMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
    val position = key(state.id, lyrics) {
        rememberLyricPosition(state.positionMs, lyrics?.timed == true && state.playing && !reducedMotion)
    }
    val index by remember(lyrics, position) { derivedStateOf { lyrics?.lineAt(position.value) ?: -1 } }
    val current = lyrics?.lines?.getOrNull(index)?.takeIf { it.text.isNotBlank() }
    val label = when (load) {
        LyricsLoad.Loading -> "Loading lyrics…"
        LyricsLoad.None -> if (searching) "Looking for lyrics online…" else "No lyrics"
        LyricsLoad.Failed -> "Lyrics unavailable"
        is LyricsLoad.Ready -> current?.text ?: "Lyrics"
    }
    // With no lyrics it still opens the Lyrics page, which can find them online.
    val action = if (load == LyricsLoad.None) "Find lyrics online" else "Show lyrics"
    val available = load.opens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).zIndex(1f)
            .then(if (available) Modifier.controlFocus(interaction, RoundedCornerShape(8.dp))
                .clickable(interaction, indication = null, role = Role.Button, onClickLabel = action, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = action
                    text = AnnotatedString(label)
                } else Modifier)
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        val color = if (pressed) EvannaDesign.colorScheme.primary else if (lyrics != null) Pv1 else Pv2
        val text = Modifier.weight(1f, fill = false)
        if (current != null && current.words.isNotEmpty() && !reducedMotion) {
            val next = lyrics?.lines?.getOrNull(index + 1)?.timeMs
            PreviewLyricWords(current, position, next, if (pressed) color else Color.White, text)
        } else {
            Text(label, style = Route, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = text)
        }
        if (available) Text(" ›", style = Route, color = Pv2)
    }
}

/** Mask the shaped text so the preview still ellipsizes as a single line. */
@Composable
private fun PreviewLyricWords(line: LyricLine, position: State<Long>, next: Long?, color: Color, modifier: Modifier) {
    val text = remember(line) { line.words.joinToString("") { it.text } }
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(text, style = Route.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it }, modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithCache {
            data class WordMask(val path: Path, val bounds: Rect, val rtl: Boolean, val start: Long, val end: Long)
            val masks = layout?.let { measured ->
                val visibleEnd = measured.getLineEnd(0, visibleEnd = true)
                var offset = 0
                buildList {
                    line.words.forEachIndexed { index, word ->
                        val from = offset
                        offset += word.text.length
                        val to = minOf(offset, visibleEnd)
                        if (from < to) {
                            val path = measured.getPathForRange(from, to)
                            val bounds = path.getBounds()
                            if (bounds.width > 0f) add(WordMask(path, bounds,
                                measured.getBidiRunDirection(from) == ResolvedTextDirection.Rtl,
                                word.timeMs, line.wordEnd(index, next)))
                        }
                    }
                }
            }.orEmpty()
            val feather = 8.dp.toPx()
            onDrawWithContent {
                drawContent()
                if (masks.isNotEmpty()) {
                    val now = position.value
                    for (word in masks) clipPath(word.path) {
                        val width = word.bounds.width
                        drawLyricFill(now, word.start, word.end, word.bounds.left, width, feather, word.rtl, resting = Pv2)
                    }
                }
            }
        })
}

internal fun shortSourceQuality(source: String): String {
    val fields = source.split(" · ")
    val hz = fields.getOrNull(1)?.removeSuffix(" Hz")?.toIntOrNull() ?: return source
    val rate = java.math.BigDecimal.valueOf(hz.toLong(), 3).stripTrailingZeros().toPlainString()
    return listOfNotNull(fields.first(), "$rate kHz", fields.getOrNull(2)?.takeUnless { it == "processed PCM" }).joinToString(" · ")
}

/**
 * A thumbless 7 dp pill (12 dp while dragging): played part white, the rest faint. It takes 7 dp in the layout, as in the
 * mockup; the slider under it stays 48 dp tall and reaches over its neighbours, which sit on top for their own taps.
 */
@Composable
internal fun PillSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean, description: String, modifier: Modifier = Modifier,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() }, onOpenOutput: ((Rect?) -> Unit)? = null,
) {
    // Apple Music's scrubber: it thickens as soon as a finger lands, with a little bounce, and stretches a touch wider.
    val dragging by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val held = (dragging || pressed) && enabled
    val bounce = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    val height by animateDpAsState(if (held) 12.dp else 7.dp, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label = "pill height")
    val stretch by animateFloatAsState(if (held) 1f else 0f, bounce, label = "pill stretch")
    Box(modifier.height(7.dp).graphicsLayer { scaleX = 1f + 0.025f * stretch }, contentAlignment = Alignment.Center) {
        Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished, valueRange = valueRange,
            enabled = enabled, interactionSource = interaction, onOpenOutput = onOpenOutput, thumb = { Spacer(Modifier.size(0.dp)) },
            track = { slider ->
                val span = slider.valueRange.endInclusive - slider.valueRange.start
                val fraction = if (span > 0f) ((slider.value - slider.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                Canvas(Modifier.fillMaxWidth().height(height)) {
                    val corner = CornerRadius(size.height / 2)
                    drawRoundRect(Pv3, cornerRadius = corner)
                    if (fraction > 0f) drawRoundRect(if (enabled) Pv1 else Pv2, size = Size(size.width * fraction, size.height), cornerRadius = corner)
                }
            },
            modifier = Modifier.fillMaxWidth().requiredHeight(48.dp).semantics { contentDescription = description })
    }
}

/** Seek pill, then elapsed time, the honest quality badge and the time left. */
@Composable
private fun SeekBar(state: PlayerPresentation, onSeek: (String?, Long) -> Unit, onSignal: (Rect?) -> Unit) {
    key(state.id, state.durationMs) {
        val interaction = remember(state.id, state.durationMs) { MutableInteractionSource() }
        val dragging by interaction.collectIsDraggedAsState()
        var target by remember(state.id, state.durationMs) { mutableFloatStateOf(0f) }
        val duration = state.durationMs
        val position = if (dragging) target.toLong() else state.positionMs
        if (duration != null && duration > 0) PillSlider(if (dragging) target else state.positionMs.coerceIn(0, duration).toFloat(),
            { target = it }, { onSeek(state.id, target.toLong()) }, 0f..duration.toFloat(), state.connected && state.canSeek,
            "Estimated playback position", Modifier.fillMaxWidth(), interaction)
        else Spacer(Modifier.height(7.dp))
        // The times sit at the bar's ends and the badge in the middle, 10 dp below the bar. The badge is 20 dp tall;
        // Compose still widens its touch area to 48 dp (minimum touch target), so the row keeps the mockup's height.
        // Only large font sizes make the row taller, so the times are never cut off.
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 20.dp)) {
            Text(playbackTime(position), style = Times, color = Pv2, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart))
            Text(duration?.let { "−${playbackTime((it - position).coerceAtLeast(0))}" } ?: "--:--", style = Times, color = Pv2,
                maxLines = 1, modifier = Modifier.align(Alignment.CenterEnd))
            if (state.sourceQuality.isNotBlank()) {
                val badgePress = remember { MutableInteractionSource() }
                var badgeBox by remember { mutableStateOf<Rect?>(null) }
                Text(remember(state.sourceQuality) { shortSourceQuality(state.sourceQuality) }, style = Badge, color = Pv1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp)
                        .liftIntoGlass(badgePress, RoundedCornerShape(10.dp), outset = 5.dp).clip(RoundedCornerShape(5.dp)).background(Pv4)
                        .controlFocus(badgePress, RoundedCornerShape(5.dp))
                        .onGloballyPositioned {
                            val start = it.localToScreen(Offset.Zero)
                            val end = it.localToScreen(Offset(it.size.width.toFloat(), it.size.height.toFloat()))
                            badgeBox = if (start.isFinite && end.isFinite) Rect(start, end) else null
                        }
                        .clickable(badgePress, indication = null, role = Role.Button) { onSignal(badgeBox) }
                        .semantics { contentDescription = "Audio quality details" }
                        .padding(horizontal = 7.dp, vertical = 3.5.dp).pressScale(badgePress))
            }
        }
    }
}

/** A plain white transport icon that shrinks a little while pressed, over a glass circle that lifts in under the finger. */
@Composable
private fun TransportButton(icon: ImageVector, size: Dp, description: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(size + 12.dp).liftIntoGlass(interaction, CircleShape, outset = 4.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (enabled) Pv1 else Pv3, modifier = Modifier.size(size).pressScale(interaction))
    }
}

/**
 * Player strip buttons (Lyrics, Output, Queue): bare 26 dp glyphs on a 48 dp touch target, as in iOS 26 Apple Music.
 * The open one turns bright white; the rest stay grey. Increase contrast keeps a platter behind each.
 */
@Composable
private fun StripButton(icon: ImageVector, on: Boolean, description: String, enabled: Boolean = true,
    modifier: Modifier = Modifier, onTint: Color = Pv1, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val glass = glassLiftOn()
    val contrast = increaseContrastOn()
    Box(modifier.size(48.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; if (enabled) stateDescription = if (on) "On" else "Off" }, contentAlignment = Alignment.Center) {
        if (contrast) Box(Modifier.size(44.dp).contrastPlatter(CircleShape, on))
        else if (glass) Box(Modifier.size(44.dp).liftIntoGlass(interaction, CircleShape, outset = 2.dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp).pressScale(interaction),
            tint = if (!enabled) Pv3 else if (on) onTint else Pv2)
    }
}

/** USB hardware volume: the same pill as the seek bar, sent once on release, with step buttons on each side. */
@Composable
internal fun UsbVolume(state: PlayerPresentation, onAdjust: (Int) -> Unit, onSet: (Float, Long) -> Unit,
    onOpenOutput: ((Rect?) -> Unit)? = null,
) {
    val gain = state.usbVolume
    if (gain == null) {
        if (onOpenOutput != null) Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            PillSlider(0f, {}, {}, 0f..1f, false, "USB hardware volume", onOpenOutput = onOpenOutput)
        }
        Text("USB volume becomes available during verified music playback.", style = Status, color = Pv2, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        return
    }
    key(state.usbGeneration, gain.minDb, gain.maxDb, gain.stepDb) {
        val live = state.connected && state.playing
        val interaction = remember { MutableInteractionSource() }
        val dragging by interaction.collectIsDraggedAsState()
        var target by remember(gain.currentDb) { mutableStateOf<Float?>(null) }
        // A cancelled drag has no submitted value. A released one stays visible until the DAC confirms it.
        var submitted by remember(gain.currentDb) { mutableStateOf<Float?>(null) }
        val shown = (if (dragging) target else submitted) ?: gain.currentDb
        // The mockup's row: 20 dp speakers 12 dp from the pill, then the level 6 dp below.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VolumeStep(PlayerIcons.VolumeDown, "USB quieter", live && gain.currentDb > gain.minDb) { onAdjust(-1) }
            PillSlider(shown, { target = gain.nearest(it) }, {
                target?.let {
                    submitted = it
                    onSet(it, state.usbGeneration)
                }
            }, gain.minDb..gain.maxDb, live && gain.minDb < gain.maxDb,
                "USB hardware volume", Modifier.weight(1f), interaction, onOpenOutput = onOpenOutput)
            VolumeStep(PlayerIcons.VolumeUp, "USB louder", live && gain.currentDb < gain.maxDb) { onAdjust(1) }
        }
        Text("USB volume ${"%.1f".format(java.util.Locale.ROOT, shown).replace('-', '−')} dB", style = VolumeLabel, color = Pv2,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** A 20 dp speaker key. Compose widens its touch area to 48 dp (minimum touch target); a touch right on the pill stays the pill's. */
@Composable
private fun VolumeStep(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(20.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).pressScale(interaction), tint = if (enabled) Pv2 else Pv3)
    }
}

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data object None : LyricsLoad
    data object Failed : LyricsLoad
    data class Ready(val lyrics: Lyrics) : LyricsLoad
}

/** The Lyrics page has something to show: the words, or with none, the button that finds them online. */
private val LyricsLoad.opens get() = this is LyricsLoad.Ready || this == LyricsLoad.None

/** Where the LRCLIB search for a song with no lyrics stands. */
private sealed interface OnlineLyrics {
    data object Idle : OnlineLyrics
    data object Searching : OnlineLyrics
    data object NotFound : OnlineLyrics
    data object Instrumental : OnlineLyrics
    data class Failed(val message: String) : OnlineLyrics
    data class Found(val lyrics: Lyrics) : OnlineLyrics
}

/** The search as the Lyrics page sees it: its state, a short note to show for a moment, and how to start it. */
private class LyricsSearch(val state: OnlineLyrics, val note: String?, val start: () -> Unit)

/** A song with no lyrics: one button looks them up on LRCLIB, and the page says how it went. */
@Composable
private fun FindLyrics(search: LyricsSearch) {
    val state = search.state
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(when (state) {
            OnlineLyrics.Searching -> "Looking on LRCLIB…"
            OnlineLyrics.NotFound -> "LRCLIB has no lyrics for this song."
            OnlineLyrics.Instrumental -> "LRCLIB lists this song as instrumental."
            is OnlineLyrics.Failed -> state.message
            else -> "This song has no lyrics."
        }, style = HeaderArtist, color = Pv2, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state != OnlineLyrics.Instrumental) {
            FilledTonalButton(onClick = search.start, enabled = state != OnlineLyrics.Searching,
                modifier = Modifier.padding(top = 16.dp).heightIn(min = 48.dp)) {
                Text(when (state) {
                    OnlineLyrics.Searching -> "Searching…"
                    OnlineLyrics.NotFound, is OnlineLyrics.Failed -> "Search again"
                    else -> "Find lyrics online"
                })
            }
        }
        if (state == OnlineLyrics.Idle) Text("Sends the song's title, artist, album and length to lrclib.net.", style = Status, color = Pv2,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
    }
}


/** A break of at least seven seconds shows three dots that fill while it lasts; shorter breaks show nothing. */
private const val DOTS_BREAK_MS = 7_000L
private val DotFill = CubicBezierEasing(0f, 0.25f, 1f, 0.58f)

/** Follow playback samples without rewinding the wipe on small clock corrections. Seeks take effect immediately. */
internal fun lyricFramePosition(previous: Long, elapsedMs: Long, target: Long): Long {
    val predicted = previous + elapsedMs
    val drift = target - predicted
    if (abs(drift) > 280L) return target
    if (abs(drift) <= 20L) return predicted
    return predicted + (drift / 4).coerceIn(-minOf(elapsedMs, 14L), 14L)
}

@Composable
private fun rememberLyricPosition(positionMs: Long, running: Boolean): State<Long> {
    val sample by rememberUpdatedState(positionMs)
    return produceState(sample, running, if (running) null else sample) {
        value = sample
        if (!running) return@produceState
        var base = sample
        var sampledAt = 0L
        var lastFrame: Long? = null
        while (true) withFrameMillis { now ->
            if (lastFrame == null || base != sample) { base = sample; sampledAt = now }
            value = lyricFramePosition(value, now - (lastFrame ?: now), base + now - sampledAt)
            lastFrame = now
        }
    }
}

/**
 * Page 0: big lyrics. The current line is bright and sits 30 % down the page; the others are faint and a little smaller.
 * Word-timed lines fill word by word with a 30 dp soft edge, and sung words lift 2 dp. Tap a line to play from it.
 * After a hand scroll, Sync returns to the active line without changing playback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsPage(load: LyricsLoad, state: PlayerPresentation, onSeek: (String?, Long) -> Unit, modifier: Modifier,
    small: Boolean = false, accent: Color = Pv1, holdLine: Boolean = false, search: LyricsSearch? = null, share: LyricShare? = null) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        search?.note?.let { note ->
            Text(note, style = Status, color = Pv1, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.BottomCenter).zIndex(1f)
                .padding(horizontal = 24.dp, vertical = 8.dp).clip(RoundedCornerShape(50)).background(Pv4).padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (load == LyricsLoad.None && search != null) { FindLyrics(search); return@BoxWithConstraints }
        if (load !is LyricsLoad.Ready) {
            Text(when (load) { LyricsLoad.Loading -> "Loading lyrics…"; LyricsLoad.None -> "This song has no lyrics."; else -> "Lyrics could not be read." },
                style = HeaderArtist, color = Pv2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            return@BoxWithConstraints
        }
        val lyrics = load.lyrics
        // [holdLine]: the controls under the page are moving or away. The page grows into their room, but the current
        // line keeps its spot, so more lines show below it.
        var steady by remember { mutableStateOf(maxHeight) }
        val height = maxHeight
        SideEffect { if (!holdLine) steady = height }
        val viewport = if (holdLine) steady else maxHeight
        val reducedMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f
        // Keep overscroll in the lyrics. The header and surrounding player still accept a dismiss swipe.
        val lyricScroll = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero
            }
        }
        // New lyrics start fresh: the scroll spot, first jump and hand-scroll time belong to one song's text.
        key(state.id, lyrics) {
            val list = rememberLazyListState()
            val position = rememberLyricPosition(state.positionMs, lyrics.timed && state.playing && !reducedMotion)
            val current by remember(lyrics, position) { derivedStateOf { lyrics.lineAt(position.value) } }
            val intro = lyrics.timed && (lyrics.lines.firstOrNull()?.timeMs ?: 0) >= DOTS_BREAK_MS
            val offset = if (intro) 1 else 0
            val dragged by list.interactionSource.collectIsDraggedAsState()
            var following by remember { mutableStateOf(true) }
            var placed by remember { mutableStateOf(false) }
            LaunchedEffect(dragged) { if (dragged) following = false }
            // While lines are being ticked the page holds still, so a line does not move away from the finger.
            val picked = share?.picked
            LaunchedEffect(current, dragged, following, reducedMotion, picked != null) {
                if (!lyrics.timed || dragged || !following || picked != null) return@LaunchedEffect
                val target = if (current < 0) 0 else current + offset
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                when {
                    !placed || reducedMotion -> list.scrollToItem(target)
                    item != null -> list.animateScrollBy(item.offset.toFloat(), tween(340, easing = FastOutSlowInEasing))
                    else -> list.animateScrollToItem(target)
                }
                placed = true
            }
            val seekable = lyrics.timed && state.connected && state.canSeek
            // The padding puts the current line 30 % down; the edges fade (top 8 %, bottom 15 %).
            LazyColumn(Modifier.fillMaxSize().nestedScroll(lyricScroll).semantics {
                if (lyrics.timed) {
                    stateDescription = if (following) "Following playback" else "Browsing lyrics"
                    customActions = listOf(CustomAccessibilityAction(
                        if (following) "Pause lyric following" else "Resume lyric following",
                    ) {
                        following = !following
                        true
                    })
                }
            }.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.85f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn)
            }, state = list, verticalArrangement = Arrangement.spacedBy(if (!lyrics.timed) 8.dp else if (small) 4.dp else 20.dp),
                // Small (sideways Details style): the current line sits near the middle, as in the Georgia mockup.
                contentPadding = PaddingValues(top = if (lyrics.timed) viewport * (if (small) 0.36f else 0.3f) else 24.dp,
                    bottom = if (lyrics.timed) viewport * (if (small) 0.64f else 0.7f) else 24.dp)) {
                if (intro) item(key = "intro") {
                    BreakDots(current < 0, position, 0L, lyrics.lines.first().timeMs!!, reducedMotion, small)
                }
                itemsIndexed(lyrics.lines) { index, line ->
                    val next = lyrics.lines.getOrNull(index + 1)?.timeMs
                    if (lyrics.timed && line.text.isEmpty()) {
                        val start = line.timeMs!!
                        if (next != null && next - start >= DOTS_BREAK_MS) {
                            BreakDots(index == current, position, start, next, reducedMotion, small)
                        } else Spacer(Modifier.height(8.dp))
                    } else LyricRow(line, lyrics.timed, index == current, !following || dragged || picked != null, position, next,
                        seekable, reducedMotion, small, accent, picked = picked?.let { index in it },
                        onPick = {
                            val now = share?.picked
                            if (share != null && now != null) toggledPick(now, index)?.let { share.picked = it } ?: share.refused++
                        },
                        onLongPress = if (share != null && line.text.isNotBlank()) ({ share.picked = setOf(index) }) else null) {
                        following = true
                        onSeek(state.id, line.timeMs!!)
                    }
                }
            }
            if (lyrics.timed && !following && !dragged && picked == null) {
                FilledTonalButton(onClick = { following = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 6.dp)) {
                    Text("Sync")
                }
            }
            if (share != null && picked != null) PickBar(picked.size, share.refused, onCancel = { share.picked = null }, onShare = {
                val lines = lyrics.lines.map { it.text }
                val words = pickedWords(lines, picked)
                val (before, after) = contextAround(lines, picked)
                if (words.isNotEmpty()) { share.onShare(words, before, after); share.picked = null }
            }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * One lyric line. Tap plays from it. A long press ([onLongPress]) starts picking lines for a share card; while picking,
 * [picked] is whether this line is ticked: a tap ticks or unticks it ([onPick]) and only ticked lines are bright.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricRow(line: LyricLine, timed: Boolean, current: Boolean, browsing: Boolean,
    position: State<Long>, next: Long?, seekable: Boolean, reducedMotion: Boolean, small: Boolean, accent: Color,
    picked: Boolean? = null, onPick: () -> Unit = {}, onLongPress: (() -> Unit)? = null, onSeek: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val picking = picked != null
    val active = if (picking) picked == true else !timed || current || browsing
    // Small: the other lines are drawn at about 15 sp beside the 21 sp current one, by scale so the text does not re-wrap.
    val resting = if (small) SMALL_LYRIC_SCALE else 0.98f
    val blur by animateFloatAsState(if (!active && Build.VERSION.SDK_INT >= 31) 3f else 0f,
        EvannaMotion.enter, label = "lyric focus blur")
    val scale by animateFloatAsState(when {
        picking -> if (small && picked != true) resting else 1f
        !timed || current -> 1f
        active && !small -> 1f
        else -> resting
    }, EvannaMotion.enter, label = "line scale")
    val opacity by animateFloatAsState(if (active) 1f else INACTIVE_LYRIC_OPACITY, EvannaMotion.enter, label = "line opacity")
    val style = if (!small) LyricsLine else SmallLyricsLine
    val lit = if (small && timed && current && !picking) accent else Pv1
    val touchable = picking || seekable || onLongPress != null
    Box(Modifier.fillMaxWidth().padding(horizontal = if (small) 4.dp else 20.dp).clip(RoundedCornerShape(16.dp))
        .then(if (touchable) Modifier.controlFocus(interaction, RoundedCornerShape(16.dp))
            .combinedClickable(interaction, indication = null,
                onClickLabel = when { picking -> if (picked == true) "Untick line" else "Tick line"; seekable -> "Play from this line"; else -> null },
                onLongClickLabel = if (!picking && onLongPress != null) "Share lyrics" else null,
                onLongClick = if (!picking) onLongPress else null,
                onClick = { if (picking) onPick() else if (seekable) onSeek() }) else Modifier)
        .then(if (picking) Modifier.semantics { selected = picked == true }
            else if (timed && current) Modifier.semantics { stateDescription = "Current lyric" } else Modifier)
        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = if (small) 4.dp else 10.dp)
        .graphicsLayer {
            scaleX = scale; scaleY = scale; alpha = opacity; transformOrigin = TransformOrigin(if (small) 0.5f else 0f, 0.5f)
            val radius = blur.dp.toPx()
            renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
        }.pressScale(interaction), contentAlignment = if (small) Alignment.Center else Alignment.CenterStart) {
        if (picking) Row(verticalAlignment = Alignment.Top) {
            PickCircle(picked == true, Modifier.padding(top = if (small) 2.dp else 8.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f, fill = false)) { LyricWords(line, timed, current, true, position, next, reducedMotion, small, style, lit) }
        } else LyricWords(line, timed, current, browsing, position, next, reducedMotion, small, style, lit)
    }
}

/** A line's words: plain, sung word by word, or still. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricWords(line: LyricLine, timed: Boolean, current: Boolean, browsing: Boolean, position: State<Long>, next: Long?,
    reducedMotion: Boolean, small: Boolean, style: TextStyle, lit: Color) {
        val align = if (small) TextAlign.Center else TextAlign.Start
        when {
            !timed -> Text(line.text.ifEmpty { " " }, style = if (small) SmallPlainLyrics else PlainLyrics, color = Pv1, textAlign = align)
            line.words.isNotEmpty() && !reducedMotion -> FlowRow(Modifier.clearAndSetSemantics {
                text = AnnotatedString(line.text)
            }, horizontalArrangement = if (small) Arrangement.Center else Arrangement.Start) {
                line.words.forEachIndexed { i, word ->
                    if (current && !browsing) {
                        SungWord(word.text, position, word.timeMs, line.wordEnd(i, next), style, lit)
                    } else Text(word.text, style = style, color = lit)
                }
            }
            else -> Text(line.text, style = style, color = lit, textAlign = align)
        }
}

/** The tick beside a lyric line while picking: an empty ring, or a blue disc with a white check. */
@Composable
private fun PickCircle(on: Boolean, modifier: Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape)
        .then(if (on) Modifier.background(PickBlue) else Modifier.border(2.dp, Pv3, CircleShape)), contentAlignment = Alignment.Center) {
        if (on) Canvas(Modifier.size(14.dp)) {
            val s = size.width / 14f
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(2.5f * s, 7.5f * s); lineTo(5.8f * s, 10.8f * s); lineTo(11.5f * s, 3.5f * s) },
                Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private val PickBlue = Color(0xFF0A84FF)

/** The line "Share lyrics" ticks first: the one being sung, else the first line with words. */
private fun firstShareLine(lyrics: Lyrics, positionMs: Long): Int? =
    lyrics.lineAt(positionMs).takeIf { it >= 0 && lyrics.lines[it].text.isNotBlank() }
        ?: lyrics.lines.indexOfFirst { it.text.isNotBlank() }.takeIf { it >= 0 }

/** Ticking lyric lines for a share card: [picked] is null when not picking, else the ticked lines (their indexes). */
private class LyricShare(val onShare: (List<String>, String?, String?) -> Unit) {
    var picked by mutableStateOf<Set<Int>?>(null)
    /** Counts refused ticks (a fifth line), so the bar can say why. */
    var refused by mutableIntStateOf(0)
}

/** Under the lyrics while picking: Cancel, how many lines are ticked, and Share. */
@Composable
private fun PickBar(count: Int, refused: Int, onCancel: () -> Unit, onShare: () -> Unit, modifier: Modifier) {
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(refused) { if (refused > 0) { full = true; delay(2_000); full = false } }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(50))
        .background(Color(0xE61C1C1E)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, colors = ButtonDefaults.textButtonColors(contentColor = Pv1),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        Text(if (full) "$SHARE_MAX_LINES lines at most" else "$count of $SHARE_MAX_LINES lines", style = Status,
            color = if (full) Pv1 else Pv2, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Button(onClick = onShare, enabled = count > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
    }
}

private fun LyricLine.wordEnd(index: Int, next: Long?): Long =
    words.getOrNull(index + 1)?.timeMs ?: next ?: (words[index].timeMs + 1_000)

/** One word: text masked from bright (sung) to faint (to come), with a 30 dp soft edge, lifted 2 dp once reached. */
@Composable
private fun SungWord(text: String, position: State<Long>, start: Long, end: Long, style: TextStyle = LyricsLine, sung: Color = Pv1) {
    val reached by remember(position, start) { derivedStateOf { position.value >= start } }
    val lift by animateDpAsState(if (reached) (-2).dp else 0.dp, spring(dampingRatio = 0.93f, stiffness = 25f), label = "word lift")
    // The mask below only uses alpha, so the text colour is the colour of the sung part.
    Text(text, style = style, color = if (sung == Pv1) Color.White else sung, modifier = Modifier.graphicsLayer {
        translationY = lift.toPx(); compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        drawLyricFill(position.value, start, end, 0f, size.width, 30.dp.toPx())
    })
}

private fun DrawScope.drawLyricFill(now: Long, start: Long, end: Long, left: Float, width: Float, feather: Float,
    rtl: Boolean = false, resting: Color = Pv3) {
    if (width <= 0f) return
    val progress = ((now - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    val brush = when {
        progress <= 0f -> SolidColor(resting)
        progress >= 1f -> SolidColor(Pv1)
        else -> {
            val edge = progress * (width + feather) - feather / 2
            val right = left + width
            Brush.horizontalGradient(0f to Pv1, ((edge - feather / 2) / width).coerceIn(0f, 1f) to Pv1,
                ((edge + feather / 2) / width).coerceIn(0f, 1f) to resting, 1f to resting,
                startX = if (rtl) right else left, endX = if (rtl) left else right)
        }
    }
    drawRect(brush, topLeft = Offset(left, 0f), size = Size(width, size.height), blendMode = BlendMode.DstIn)
}

/**
 * How lit [dot] (0, 1 or 2) is during a break from [startMs] to [endMs]: the dots fill one after another, each over a
 * third of the break minus its last 0.8 s, so all three are full just before the next line.
 */
internal fun breakDotFill(positionMs: Long, startMs: Long, endMs: Long, dot: Int): Float {
    val span = (endMs - startMs - 800).coerceAtLeast(1)
    val fraction = ((positionMs - startMs).toFloat() / span).coerceIn(0f, 1f)
    return DotFill.transform((fraction * 3 - dot).coerceIn(0f, 1f))
}

/** Three 10 dp dots at the text inset. They stay faint until their break plays, then fill (see [breakDotFill]). */
@Composable
private fun BreakDots(active: Boolean, position: State<Long>, start: Long, end: Long, reducedMotion: Boolean, centered: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = if (centered) 0.dp else 32.dp, top = 6.dp, bottom = 6.dp)
        .semantics { contentDescription = "Instrumental break" },
        horizontalArrangement = if (centered) Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(6.dp)) {
        repeat(3) { dot ->
            Canvas(Modifier.size(10.dp)) {
                val fill = when {
                    !active -> 0f
                    reducedMotion -> 1f
                    else -> breakDotFill(position.value, start, end, dot)
                }
                drawCircle(lerp(Pv3, Pv1, fill))
            }
        }
    }
}

/** Page 2: Shuffle and Repeat, songs already played ("History") and the songs still to come ("Playing Next"). */
@Composable
private fun QueuePage(state: PlayerPresentation, onShuffle: () -> Unit, onRepeat: () -> Unit, onSelect: (String) -> Unit,
    onMove: (String, String) -> Unit, onRemove: (String) -> Unit, onLibrary: () -> Unit, loadArtwork: suspend (String) -> Bitmap?, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (LocalConfiguration.current.screenHeightDp >= 560) Text("Queue", style = SectionTitle, color = Pv1,
            modifier = Modifier.padding(start = 24.dp, top = 10.dp).semantics { heading() })
        // The pills are 36 dp inside 48 dp touch rows, so 10 dp here and 6 dp below give the mockup's 16 dp and 12 dp gaps.
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QueuePill("Shuffle", PlayerIcons.Shuffle, state.shuffle, if (state.shuffle) "Shuffle: On" else "Shuffle: Off",
                state.connected && state.canShuffle, Modifier.weight(1f), onShuffle)
            val repeat = when (state.repeat) { 1 -> "Song"; 2 -> "Queue"; else -> "Off" }
            QueuePill(when (state.repeat) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Repeat" },
                if (state.repeat == 1) PlayerIcons.RepeatOne else PlayerIcons.Repeat, state.repeat != 0, "Repeat: $repeat",
                state.connected && state.canRepeat, Modifier.weight(1f), onRepeat)
        }
        val current = state.queue.indexOfFirst { it.id == state.id }.takeIf { it >= 0 } ?: state.queueIndex.coerceIn(-1, state.queue.lastIndex)
        val upcoming = state.queue.drop(current + 1)
        val ids = upcoming.map { it.id }
        val list = rememberLazyListState()
        val drag = remember { QueueDrag() }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        val latestIds by rememberUpdatedState(ids)
        val latestMove by rememberUpdatedState(onMove)
        // The queue that comes back after a drop shows the new order. A queue that changes mid-drag ends the drag.
        LaunchedEffect(ids) {
            if (drag.held == null) drag.order = null
            else if (drag.order?.toSet() != ids.toSet()) drag.cancel()
        }
        // A move that never lands puts the rows back.
        LaunchedEffect(drag.drops) { if (drag.drops > 0) { delay(2000); if (drag.held == null) drag.order = null } }
        // Near the list's top or bottom, the held row scrolls the list, faster the deeper it goes.
        LaunchedEffect(drag.held) {
            if (drag.held == null) return@LaunchedEffect
            val zone = with(density) { 56.dp.toPx() }
            val most = with(density) { 12.dp.toPx() }
            while (true) {
                withFrameNanos { }
                val over = drag.overEdge(list, zone)
                if (over != 0f) {
                    list.scrollBy((over / zone).coerceIn(-1f, 1f) * most)
                    if (drag.settle(list)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
        }
        val shown = drag.order?.let { order -> upcoming.associateBy { it.id }.let { byId -> order.mapNotNull { byId[it] } } }
            ?.takeIf { it.size == upcoming.size } ?: upcoming
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0.8f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }, state = list, contentPadding = PaddingValues(start = 24.dp, end = 14.dp, bottom = 24.dp)) {
            if (current > 0) {
                item(key = "history") { Text("History", style = SectionTitle, color = Pv2, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() }) }
                itemsIndexed(state.queue.take(current), key = { _, item -> item.id }) { _, item ->
                    QueueRow(item, played = true, state, loadArtwork, onSelect, edit = null)
                }
            }
            item(key = "next") { Text("Playing Next", style = SectionTitle, color = Pv1, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() }) }
            // An empty "Playing Next" offers the library; a full one ends with its last song, as in the mockup.
            if (upcoming.isEmpty()) item(key = "empty") {
                Column {
                    Text("Nothing is queued after this song.", style = RowArtist, color = Pv2, modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = onLibrary, colors = ButtonDefaults.textButtonColors(contentColor = Pv2), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Open music library")
                    }
                }
            }
            itemsIndexed(shown, key = { _, item -> item.id }) { offset, item ->
                val held = drag.held == item.id
                val handle = Modifier.pointerInput(item.id) {
                    detectDragGestures(
                        onDragStart = { if (drag.pick(item.id, latestIds, list)) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragEnd = { drag.drop(list, latestIds, latestMove, scope) },
                        onDragCancel = { drag.drop(list, latestIds, latestMove, scope) },
                    ) { change, amount ->
                        change.consume()
                        if (drag.dragBy(amount.y, list)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
                val edit = QueueEdit(
                    up = shown.getOrNull(offset - 1)?.let { above -> { onMove(item.id, above.id) } },
                    down = shown.getOrNull(offset + 1)?.let { below -> { onMove(item.id, below.id) } },
                    remove = { onRemove(item.id) }, handle = handle, held = held)
                // The held row follows the finger above the others; the rest slide to make room.
                QueueRow(item, played = false, state, loadArtwork, onSelect, edit, Modifier.zIndex(if (held || drag.landed == item.id) 1f else 0f)
                    .then(when {
                        held -> Modifier.graphicsLayer { translationY = drag.offset(list) }
                        drag.landed == item.id -> Modifier.graphicsLayer { translationY = drag.landing.value }
                        else -> Modifier.animateItem()
                    }))
            }
        }
    }
}

/**
 * The queue row held by its handle, after the Compose drag-and-drop list sample: it follows the finger, the rows it
 * passes make room, and letting go asks the player for a single move.
 */
private class QueueDrag {
    var held by mutableStateOf<String?>(null)
        private set
    /** The songs still to come in the order shown while a row is held, and until the moved queue comes back. */
    var order by mutableStateOf<List<String>?>(null)
    var drops by mutableIntStateOf(0)
        private set
    /** The row easing into its slot after a drop. */
    var landed by mutableStateOf<String?>(null)
        private set
    val landing = Animatable(0f)
    private var startTop = 0
    private var moved by mutableFloatStateOf(0f)

    private fun row(list: LazyListState, id: String?) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }

    /** How far the held row is drawn from where the list lays it out. */
    fun offset(list: LazyListState): Float = row(list, held)?.let { startTop + moved - it.offset } ?: 0f

    fun pick(id: String, ids: List<String>, list: LazyListState): Boolean {
        val row = row(list, id) ?: return false
        startTop = row.offset; moved = 0f; order = ids; landed = null; held = id
        return true
    }

    /** Moves the held row with the finger; true when it took another row's place. */
    fun dragBy(dy: Float, list: LazyListState): Boolean {
        if (held == null) return false
        moved += dy
        return settle(list)
    }

    /** Puts the held row in the place of the row under its middle, once its middle has passed that row's middle. */
    fun settle(list: LazyListState): Boolean {
        val id = held ?: return false
        val now = order ?: return false
        val size = row(list, id)?.size ?: return false
        val middle = startTop + moved + size / 2f
        val from = now.indexOf(id)
        for (other in list.layoutInfo.visibleItemsInfo) {
            val key = other.key as? String ?: continue
            val to = now.indexOf(key)
            if (key == id || to < 0 || middle < other.offset || middle > other.offset + other.size) continue
            // Rows not yet laid out in their new places still pass this test the wrong way round, so they never swap back.
            val centre = other.offset + other.size / 2f
            if (to > from && middle > centre || to < from && middle < centre) {
                order = now.toMutableList().apply { add(to, removeAt(from)) }
                return true
            }
        }
        return false
    }

    /**
     * How far the held row reaches into the [zone] at the list's top (negative) or bottom (positive). Only the edge it is
     * being dragged toward counts: a row picked up near the bottom and dragged up must not scroll the list down.
     */
    fun overEdge(list: LazyListState, zone: Float): Float {
        val size = row(list, held)?.size ?: return 0f
        val info = list.layoutInfo
        val top = startTop + moved
        val over = when {
            top < info.viewportStartOffset + zone -> top - (info.viewportStartOffset + zone)
            top + size > info.viewportEndOffset - zone -> top + size - (info.viewportEndOffset - zone)
            else -> 0f
        }
        return if (over * moved > 0f) over else 0f
    }

    /** Lets go: one move into the new place, then the row eases into its slot. */
    fun drop(list: LazyListState, ids: List<String>, onMove: (String, String) -> Unit, scope: CoroutineScope) {
        val id = held ?: return
        val left = offset(list)
        held = null
        val now = order
        val to = now?.indexOf(id) ?: -1
        if (now == null || to < 0 || now == ids || now.size != ids.size) order = null
        else { onMove(id, ids[to]); drops++ }
        landed = id
        scope.launch {
            landing.snapTo(left)
            landing.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 600f))
            if (landed == id) landed = null
        }
    }

    fun cancel() { held = null; order = null; landed = null }
}

/** A capsule toggle fills with the current accent when selected. */
@Composable
private fun QueuePill(label: String, icon: ImageVector, on: Boolean, description: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = EvannaDesign.colorScheme
    val fill = if (on) colors.primary else Pv4
    val ink = if (on) colors.onPrimary else Pv1
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.heightIn(min = 48.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        // The fade layer always stays (an alpha layer that comes and goes can lose the fill drawn before it); large fonts may grow the pill.
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(CircleShape).background(fill).graphicsLayer { alpha = if (enabled) 1f else 0.5f },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).pressScale(interaction), tint = ink)
            Spacer(Modifier.width(8.dp))
            Text(label, style = PillLabel, color = ink, maxLines = 1)
        }
    }
}

/** What a song still to come can do: move one place (for screen readers), leave the queue, or be dragged by [handle]. */
private class QueueEdit(val up: (() -> Unit)?, val down: (() -> Unit)?, val remove: () -> Unit, val handle: Modifier, val held: Boolean)

/** How far a queue row slides toward the start before letting go removes it. */
private val RemoveReach = 96.dp
private val QueueRowShape = RoundedCornerShape(10.dp)
/** The held row's own fill, so the rows it passes over don't show through it. */
private val HeldRowFill = Color(0xF23A3A3C)

/**
 * A 64 dp queue row: 48 dp cover, title and artist. A song still to come has a handle to drag it to another place and
 * slides toward the start to leave the queue (docs/mockups/dock-fade-queue-rows.html, A). Screen readers get Move up,
 * Move down and Remove as actions on the row instead.
 */
@Composable
private fun QueueRow(item: PlayerQueueItem, played: Boolean, state: PlayerPresentation, loadArtwork: suspend (String) -> Bitmap?,
    onSelect: (String) -> Unit, edit: QueueEdit?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val canEdit = edit != null && state.connected
    val held = edit?.held == true
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reach = with(LocalDensity.current) { RemoveReach.toPx() }
    val latestEdit by rememberUpdatedState(edit)
    var slide by remember(item.id) { mutableFloatStateOf(0f) }
    // Only a slide toward the start is taken; the other way is left for the sideways page swipe.
    val swipe = if (!canEdit) Modifier else Modifier.pointerInput(item.id, rtl) {
        val toward = if (rtl) 1f else -1f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var start = 0f
            val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                if (over * toward > 0f) { change.consume(); start = over }
            } ?: return@awaitEachGesture
            val width = size.width.toFloat()
            fun clamp(x: Float) = if (toward < 0f) x.coerceIn(-width, 0f) else x.coerceIn(0f, width)
            slide = clamp(slide + start)
            horizontalDrag(first.id) { change ->
                val before = slide
                slide = clamp(slide + change.positionChange().x)
                change.consume()
                // A tick as the row passes the point where letting go removes it.
                if ((abs(before) < reach) != (abs(slide) < reach)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            val gone = abs(slide) >= reach
            scope.launch {
                if (gone) {
                    animate(slide, toward * width, animationSpec = tween(160)) { value, _ -> slide = value }
                    latestEdit?.remove?.invoke()
                    // Still here after a while: the queue did not change, so the row comes back.
                    delay(1500)
                }
                animate(slide, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { value, _ -> slide = value }
            }
        }
    }
    val actions = if (!canEdit) emptyList() else listOfNotNull(
        edit!!.up?.let { up -> CustomAccessibilityAction("Move up") { up(); true } },
        edit.down?.let { down -> CustomAccessibilityAction("Move down") { down(); true } },
        CustomAccessibilityAction("Remove") { edit.remove(); true })
    Box(modifier.fillMaxWidth().then(swipe)) {
        if (slide != 0f) RemoveStrip(slide, reach)
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .graphicsLayer {
                translationX = slide
                if (held) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 12.dp.toPx(); shape = QueueRowShape; clip = true }
            }
            .then(if (held) Modifier.background(HeldRowFill, QueueRowShape) else Modifier)
            .alpha(if (played) 0.55f else 1f).clip(QueueRowShape)
            .controlFocus(interaction, QueueRowShape)
            .clickable(interaction, indication = null, enabled = state.connected, onClickLabel = "Play this song") { onSelect(item.id) }
            .semantics { if (actions.isNotEmpty()) customActions = actions },
            verticalAlignment = Alignment.CenterVertically) {
            LibraryArtwork(item.id, 0, Modifier.size(48.dp), SmallCoverShape) { loadArtwork(item.id) }
            Column(Modifier.weight(1f).padding(start = 12.dp).pressScale(interaction)) {
                Text(item.title, style = HeaderTitle, color = Pv1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", style = RowArtist, color = Pv2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (edit != null) Box(Modifier.size(width = 44.dp, height = 48.dp).then(if (canEdit) edit.handle else Modifier).clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                Icon(PlayerIcons.Grip, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (canEdit) Pv2 else Pv3)
            }
        }
    }
}

/** What a queue row shows at its end as it slides toward the start: red, with Remove once there is room. */
@Composable
private fun BoxScope.RemoveStrip(slide: Float, reach: Float) {
    val width = with(LocalDensity.current) { abs(slide).toDp() }
    val ready = abs(slide) >= reach
    Box(Modifier.matchParentSize()) {
        Box(Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight().padding(vertical = 6.dp)
            .clip(QueueRowShape).background(AppleColors.red(true)), contentAlignment = Alignment.Center) {
            if (width > 72.dp) Text("Remove", style = PillLabel, color = Color.White, maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = if (ready) 1f else 0.7f })
        }
    }
}

/** Which layout Now Playing uses on a phone on its side (Settings → Appearance). */
internal enum class SidewaysStyle(val label: String) {
    DETAILS("Details and disc"), COVER("Cover and controls");
    companion object { fun stored(value: Any?) = entries.firstOrNull { it.name == value } ?: DETAILS }
}
internal const val SIDEWAYS_STYLE_PREFERENCE = "sideways-player"

/** How upright Now Playing shows the cover (Settings → Appearance; docs/mockups/now-playing-full-cover.html). */
internal enum class CoverStyle(val label: String) {
    CARD("Card"), FULL("Full cover");
    companion object { fun stored(value: Any?) = entries.firstOrNull { it.name == value } ?: CARD }
}
internal const val COVER_STYLE_PREFERENCE = "now-playing-cover"
/** UI_SETTINGS key: songs with no lyrics are looked up on LRCLIB by themselves (default off). */
internal const val FIND_LYRICS_ONLINE_PREFERENCE = "find-lyrics-online"
/** UI_SETTINGS key: on its side, Now Playing and Carousel hide the phone's status and navigation bars (default on). */
internal const val FULL_SCREEN_SIDEWAYS_PREFERENCE = "sideways-full-screen"

private val DetailsGap = 24.dp
private val SideTitle = playerText(28, 33, FontWeight.Bold)
private val SideArtist = playerText(18, 23)
private val SideLine = playerText(13, 17, FontWeight.Normal)
private val SideCaption = playerText(11, 14, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp)
private val SideNext = playerText(14, 18, FontWeight.Normal)
private val SideBadge = playerText(12, 15)
private val SideSmallTitle = playerText(15, 19, FontWeight.SemiBold)

/**
 * Now Playing on its side after the foobar2000 Georgia-ReBORN theme, drawn the iOS way ("Song first",
 * docs/mockups/details-sideways-options.html, B). Details: the cover on the left with a disc printed with the cover half
 * out of it, turning while music plays, and beside them the song's name, its artist, the album line, the quality as the
 * theme's badges and the next two songs. Lyrics and Queue: the disc rolls back into the cover, the song's name moves under
 * it and the page comes in on the right. A glass bar along the bottom holds the transport, the seek bar and the page buttons.
 * The codec, channel and disc pictures are the theme's own (MIT, assets/licenses/Georgia-ReBORN-MIT.txt).
 */
@Composable
private fun DetailsPlayer(state: PlayerPresentation, cover: Bitmap?, info: LibrarySong?, page: PlayerPage, onPage: (PlayerPage) -> Unit,
    lyrics: LyricsLoad, more: @Composable () -> Unit, contentAlpha: () -> Float, motion: PlayerSheetMotion, flying: Boolean,
    onBack: () -> Unit, onFavorite: (String, Boolean) -> Unit, onSeek: (String?, Long) -> Unit,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSignal: (Rect?) -> Unit, onOutput: (Rect?) -> Unit,
    outputSheetOpen: Boolean, onSelect: (String) -> Unit, queue: @Composable (Modifier) -> Unit, search: LyricsSearch,
    share: LyricShare) {
    val accent = EvannaDesign.colorScheme.primary
    // The page on the right while Details shows stays the last one opened, so it can fade out as the disc comes back.
    var lastPane by remember { mutableStateOf(PlayerPage.LYRICS) }
    val pane = if (page == PlayerPage.SONG) lastPane else page
    SideEffect { lastPane = pane }
    // A swipe sideways moves one page: Details, Lyrics (when the song has them, or can look them up), Queue.
    val pages = listOfNotNull(PlayerPage.SONG, PlayerPage.LYRICS.takeIf { page == it || lyrics.opens }, PlayerPage.QUEUE)
    val turn by rememberUpdatedState({ step: Int -> pages.getOrNull(pages.indexOf(page) + step)?.let(onPage) })
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shift by animateFloatAsState(if (page == PlayerPage.SONG) 0f else 1f, tween(420, easing = FastOutSlowInEasing), label = "details shift")
    // Left alone, playing or paused, the bar slides down off the screen and the cover and pages grow into its room.
    // A single tap on the page brings it back, or sends it away at once. A double tap plays or pauses, and a swipe turns
    // the page (or, on the cover, the song); neither moves the bar. Touches while it shows keep it there, and so do keys.
    // It stays while Audio output is open and with TalkBack on.
    val context = LocalContext.current
    val talkBack = remember(context) { context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
    val canHide = !outputSheetOpen && !talkBack
    val lastTouch = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(canHide) {
        idle = false
        if (!canHide) return@LaunchedEffect
        lastTouch.longValue = SystemClock.uptimeMillis()
        while (true) {
            val wait = lastTouch.longValue + BAR_IDLE_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            else {
                idle = true
                snapshotFlow { idle }.first { !it }
            }
        }
    }
    fun wake() {
        lastTouch.longValue = SystemClock.uptimeMillis()
        if (idle) idle = false
    }
    val tap by rememberUpdatedState({ if (idle) wake() else if (canHide) idle = true })
    val doubleTap by rememberUpdatedState({ if (state.connected && state.canPlayPause) onPlayPause() })
    val changeSong by rememberUpdatedState({ forward: Boolean ->
        if (forward) { if (state.connected && state.canNext) onNext() } else if (state.connected && state.canPrevious) onPrevious()
    })
    // Where the cover (and the disc beside it) sits, for the swipe that changes the song.
    val coverZone = remember { CoverZone() }
    val hidden by animateFloatAsState(if (idle) 1f else 0f, tween(if (idle) 420 else 260, easing = FastOutSlowInEasing), label = "bar hide")
    val navigationBars = WindowInsets.navigationBars
    Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha() }
        // Watched before anything below acts on it, and never consumed, so the touch still does what it touched.
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); if (!idle) lastTouch.longValue = SystemClock.uptimeMillis() } }
        }
        .onPreviewKeyEvent { wake(); false }
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
        // The top buttons and the dots go with the bar: they slide up out of sight and the song takes their room.
        Box(Modifier.layout { measurable, constraints ->
            val row = measurable.measure(constraints)
            val gone = (row.height * hidden).roundToInt()
            layout(row.width, row.height - gone) { row.place(0, -gone) }
        }.graphicsLayer { alpha = 1f - hidden }.then(if (idle) Modifier.clearAndSetSemantics {} else Modifier)
            .fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlayerHeaderButton(PlayerIcons.ChevronDown, "Minimize player", onClick = onBack)
                Spacer(Modifier.weight(1f))
                PlayerHeaderButton(if (state.favorite == true) PlayerIcons.HeartFill else PlayerIcons.Heart,
                    description = if (state.favorite == true) "Remove from favorites" else "Add to favorites",
                    enabled = state.connected && state.favorite != null, on = state.favorite == true) {
                    state.id?.let { onFavorite(it, state.favorite != true) }
                }
                Spacer(Modifier.width(8.dp))
                more()
            }
            PageDots(pages, page, Modifier)
        }
        // Taps that no button, row or line took: one shows or hides the bar, two play or pause.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap() }, onTap = { tap() }) }
            .padding(horizontal = 26.dp, vertical = 6.dp)
            .pointerInput(rtl) {
                val threshold = 48.dp.toPx()
                var distance = 0f
                var turned = false
                var onCover = false
                // One page (or one song, on the cover) per swipe, as soon as the finger has gone far enough; up and down
                // still scroll the page.
                detectHorizontalDragGestures(onDragStart = { at -> distance = 0f; turned = false; onCover = coverZone.rect.contains(at) }) { _, delta ->
                    distance += delta
                    if (!turned && abs(distance) >= threshold) {
                        turned = true
                        val forward = (distance < 0) != rtl
                        if (onCover) changeSong(forward) else turn(if (forward) 1 else -1)
                    }
                }
            }) {
            val t = shift
            // Details: the cover on the left, the disc half out of it (it reaches 1.47 covers), then the song.
            // Lyrics and Queue: the disc is in, the song's name sits under the cover and the page fills the rest.
            val nameRoom = 46.dp * LocalDensity.current.fontScale.coerceAtMost(1.3f)
            // The song keeps at least 320 dp, so a cover grown while idle never squeezes the badges onto new lines.
            val start = minOf(maxHeight, (maxWidth - DetailsGap - 320.dp) / 1.47f)
            val end = minOf(maxHeight - nameRoom, maxWidth * 0.42f)
            val side = lerp(start, end, t)
            val top = lerp((maxHeight - start) / 2, ((maxHeight - end - nameRoom) / 2).coerceAtLeast(0.dp), t)
            val rolled = (t * 1.6f).coerceAtMost(1f)
            val songLeft = start * 1.47f + DetailsGap
            with(LocalDensity.current) {
                // The disc's far edge while it is out, else the cover's; mirrored when the cover sits on the right.
                val width = maxOf(side, if (t < 1f) side * 0.5f * (1f - rolled) + side * 0.97f else side).toPx()
                val left = if (rtl) maxWidth.toPx() - width else 0f
                coverZone.rect = Rect(left, top.toPx(), left + width, (top + side).toPx())
            }
            if (t < 1f) {
                Disc(cover, state.playing && page == PlayerPage.SONG, Modifier
                    .offset(side * 0.5f * (1f - rolled) + side * 0.03f, top + side * 0.03f).size(side * 0.94f)
                    .graphicsLayer { alpha = 1f - ((t - 0.55f) / 0.2f).coerceIn(0f, 1f) })
                // As tall as the cover: the song starts at its top edge and Up next ends at its bottom edge.
                SongDetails(state, info, accent, onSignal, onSelect, Modifier.offset(songLeft, (maxHeight - start) / 2)
                    .width(maxWidth - songLeft).height(start).graphicsLayer { alpha = 1f - (t / 0.45f).coerceAtMost(1f); translationX = 24.dp.toPx() * t }
                    .then(if (page == PlayerPage.SONG) Modifier else Modifier.clearAndSetSemantics {}))
            }
            ArtworkImage(cover, Modifier.offset(0.dp, top).size(side).dockingCover(motion, flying, corner = 10.dp)
                .shadow(15.dp, CoverShape), CoverShape)
            if (t > 0f) Column(Modifier.offset(0.dp, top + side + 8.dp).width(side)
                .graphicsLayer { alpha = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f) }
                .then(if (page == PlayerPage.SONG) Modifier.clearAndSetSemantics {} else Modifier)) {
                Text(state.title.ifBlank { "Local audio file" }, style = SideSmallTitle, color = Pv1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", style = SideLine, color = accent, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (t > 0f) Box(Modifier.offset(end + DetailsGap, 0.dp).width(maxWidth - end - DetailsGap).fillMaxHeight()
                .graphicsLayer { alpha = ((t - 0.45f) / 0.45f).coerceIn(0f, 1f); translationX = 24.dp.toPx() * (1f - t) }
                .then(if (page == PlayerPage.SONG) Modifier.clearAndSetSemantics {} else Modifier)) {
                // Lyrics and Queue slide past each other the way the swipe went; coming from Details the shift above is enough.
                AnimatedContent(pane, Modifier.fillMaxSize(), transitionSpec = {
                    if (shift < 1f) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val way = (if (targetState == PlayerPage.QUEUE) 1 else -1) * (if (rtl) -1 else 1)
                        (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 5 * way } + fadeIn(tween(320))) togetherWith
                            (slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 5 * way } + fadeOut(tween(200)))
                    }
                }, label = "details pane") { shown ->
                    if (shown == PlayerPage.QUEUE) queue(Modifier.fillMaxSize())
                    else LyricsPage(lyrics, state, onSeek, Modifier.fillMaxSize(), small = true, accent = accent, search = search, share = share)
                }
            }
        }
        // Hidden, the bar keeps only the room above the home bar; it slides by its full height, so it leaves the screen.
        Box(Modifier.layout { measurable, constraints ->
            val bar = measurable.measure(constraints)
            val rest = navigationBars.getBottom(this) + 6.dp.roundToPx()
            val height = bar.height - ((bar.height - rest).coerceAtLeast(0) * hidden).roundToInt()
            layout(bar.width, height) { bar.place(0, (rest * hidden).roundToInt()) }
        }.graphicsLayer { alpha = 1f - hidden }.then(if (idle) Modifier.clearAndSetSemantics {} else Modifier)) {
            DetailsBar(state, page, onPage, lyrics, accent, onPlayPause, onPrevious, onNext, onOutput, outputSheetOpen, onSeek)
        }
    }
}

/** How long the sideways Details bar, or the upright Lyrics controls, wait with nothing touched before they slide away. */
private const val BAR_IDLE_MS = 5_000L

/** The idle count for the upright Lyrics controls: [idle] turns true after [BAR_IDLE_MS] with no touch or key. */
private class IdleWatch {
    var idle by mutableStateOf(false)
    var touching by mutableStateOf(false)
    var lastTouch = SystemClock.uptimeMillis()

    fun wake() {
        lastTouch = SystemClock.uptimeMillis()
        if (idle) idle = false
    }
}

/** Counts while [armed]; disarmed, it is never idle. A finger held down keeps it awake. */
@Composable
private fun rememberIdleWatch(armed: Boolean): IdleWatch {
    val watch = remember { IdleWatch() }
    LaunchedEffect(armed) {
        watch.idle = false
        if (!armed) return@LaunchedEffect
        watch.lastTouch = SystemClock.uptimeMillis()
        while (true) {
            val wait = watch.lastTouch + BAR_IDLE_MS - SystemClock.uptimeMillis()
            when {
                wait > 0 -> delay(wait)
                watch.touching -> snapshotFlow { watch.touching }.first { !it }
                else -> {
                    watch.idle = true
                    snapshotFlow { watch.idle }.first { !it }
                }
            }
        }
    }
    return watch
}

/** Every touch and key under this wakes [watch]. It looks before anything below acts and consumes nothing. */
private fun Modifier.wakesControls(watch: IdleWatch): Modifier = pointerInput(watch) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            watch.touching = event.changes.any { it.pressed }
            watch.wake()
        }
    }
}.onPreviewKeyEvent { watch.wake(); false }

/** The cover's place in the Details page, in the page's pixels; set while laying out, read by the swipe. */
private class CoverZone { var rect = Rect.Zero }

/**
 * Which page shows, as page dots (docs/mockups/details-page-dots.html, B). On a change the bright one stretches over to
 * the new dot, its far end first, then its near end follows and it settles there.
 */
@Composable
private fun PageDots(pages: List<PlayerPage>, page: PlayerPage, modifier: Modifier) {
    val index = pages.indexOf(page).coerceAtLeast(0)
    val low = remember { Animatable(index.toFloat()) }
    val high = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) {
        val to = index.toFloat()
        val (lead, follow) = if (to > high.value) high to low else low to high
        launch { lead.animateTo(to, tween(240, easing = FastOutSlowInEasing)) }
        follow.animateTo(to, tween(240, delayMillis = 200, easing = FastOutSlowInEasing))
    }
    val name = when (page) { PlayerPage.SONG -> "Details"; PlayerPage.LYRICS -> "Lyrics"; PlayerPage.QUEUE -> "Queue" }
    Box(modifier.size(width = DotStep * pages.size, height = 24.dp)
        .semantics { contentDescription = "Page ${index + 1} of ${pages.size}, $name" }
        .drawBehind {
            val step = DotStep.toPx()
            fun x(at: Float) = (step / 2 + step * at).let { if (layoutDirection == LayoutDirection.Rtl) size.width - it else it }
            for (i in pages.indices) drawCircle(DotColor, 3.dp.toPx(), Offset(x(i.toFloat()), center.y))
            val left = minOf(x(low.value), x(high.value)) - 5.dp.toPx()
            val right = maxOf(x(low.value), x(high.value)) + 5.dp.toPx()
            drawRoundRect(Pv1, Offset(left, center.y - 4.dp.toPx()), Size(right - left, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
        })
}

private val DotStep = 18.dp
private val DotColor = Color.White.copy(alpha = 0.35f)

/** A disc printed with the cover, under the theme's clear CD so its hub and rim show; it turns while music plays. */
@Composable
private fun Disc(cover: Bitmap?, turning: Boolean, modifier: Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    LaunchedEffect(turning, durationScale) {
        // Reduced motion (scale 0) keeps it still. One turn every ten seconds.
        if (!turning || durationScale == 0f) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            angle = (angle + (now - last) / 1_000_000_000f * 36f / durationScale) % 360f
            last = now
        }
    }
    Box(modifier.shadow(10.dp, CircleShape).graphicsLayer { rotationZ = angle }) {
        Image(painterResource(R.drawable.georgia_cd), contentDescription = null, modifier = Modifier.fillMaxSize())
        ArtworkImage(cover, Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = 0.94f }
            .drawWithContent {
                drawContent()
                drawRect(Brush.radialGradient(0f to Color.Transparent, 0.37f to Color.Transparent, 0.38f to Color.Black,
                    0.965f to Color.Black, 0.975f to Color.Transparent), blendMode = BlendMode.DstIn)
            }, CircleShape)
    }
}

/**
 * The song beside the cover: its name, its artist, the album line, the quality as the theme's badges (tap for the sound
 * path) and the next two songs in the queue (tap to play one).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongDetails(state: PlayerPresentation, info: LibrarySong?, accent: Color, onSignal: (Rect?) -> Unit,
    onSelect: (String) -> Unit, modifier: Modifier) {
    val tags = info?.musicTags
    val fields = state.sourceQuality.split(" · ")
    // Until the decoder reports the format (nothing played yet), the library's scan gives the codec and rate.
    val codec = fields.firstOrNull()?.takeIf { it.isNotBlank() } ?: info?.codec?.takeIf { it.isNotBlank() }
    val rate = fields.getOrNull(1)?.removeSuffix(" Hz")?.toIntOrNull() ?: info?.rate?.takeIf { it > 0 }
    val bits = fields.getOrNull(2)?.removeSuffix("-bit")?.toIntOrNull()
    val channels = state.signalStages.firstOrNull { it.label == "Source" }?.value
        ?.let { Regex("""(\d+) channels""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    // The song at the top, Up next at the bottom (docs/mockups/details-proportions.html, A). Large text can outgrow the
    // room beside the cover; then the two meet and the column scrolls.
    BoxWithConstraints(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(state.title.ifBlank { "Local audio file" }, style = SideTitle, color = Pv1, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Text(state.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", style = SideArtist, color = accent, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                val added = info?.addedAt?.takeIf { it > 0 }
                    ?.let { "added " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) }
                val line = listOfNotNull(state.album?.takeIf { it.isNotBlank() }, tags?.year?.toString(), tags?.genre?.takeIf { it.isNotBlank() }, added)
                    .joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = SideLine, color = Pv2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
                if (codec != null || rate != null) {
                    var box by remember { mutableStateOf<Rect?>(null) }
                    val press = remember { MutableInteractionSource() }
                    // One height for every badge; the pictures are 18 dp so the words in them match the 12 sp quality.
                    @Composable fun pill(content: @Composable () -> Unit) = Box(Modifier.height(26.dp).clip(RoundedCornerShape(6.dp))
                        .background(Pv4).padding(horizontal = 9.dp), contentAlignment = Alignment.Center) { content() }
                    FlowRow(Modifier.padding(top = 12.dp).onGloballyPositioned { box = it.screenBox() }
                        .clickable(press, indication = null, role = Role.Button) { onSignal(box) }
                        .semantics { contentDescription = "Audio quality details" },
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        codec?.let { name -> pill { ThemeBadge(codecBadge(name), name, Modifier.height(18.dp)) } }
                        val quality = listOfNotNull(bits?.let { "$it-bit" },
                            rate?.let { shortSourceQuality("${codec ?: "?"} · $it Hz").split(" · ").getOrNull(1) }).joinToString(" · ")
                        if (quality.isNotEmpty()) pill { Text(quality, style = SideBadge, color = Pv1, maxLines = 1) }
                        channels?.let { n ->
                            pill { ThemeBadge(channelBadge(n), if (n == 1) "Mono" else if (n == 2) "Stereo" else "$n channels", Modifier.height(18.dp)) }
                        }
                    }
                }
            }
            // "UP NEXT · TRACK 1 OF 22", then the next two songs of the queue.
            Column(Modifier.padding(top = 12.dp)) {
                val current = state.queue.indexOfFirst { it.id == state.id }.takeIf { it >= 0 } ?: state.queueIndex.coerceIn(-1, state.queue.lastIndex)
                val upcoming = state.queue.drop(current + 1).take(2)
                val discs = tags?.totalDiscs
                val caption = listOfNotNull("Up next", tags?.trackNumber?.let { n -> "Track $n" + (tags.totalTracks?.let { " of $it" } ?: "") },
                    tags?.discNumber?.takeIf { discs != null && discs > 1 }?.let { "Disc $it of $discs" })
                    .joinToString(" · ").uppercase(java.util.Locale.getDefault())
                Text(caption, style = SideCaption, color = Pv2, modifier = Modifier.padding(bottom = 2.dp).semantics { heading() })
                if (upcoming.isEmpty()) Text("Nothing is queued after this song.", style = SideLine, color = Pv2, modifier = Modifier.padding(vertical = 8.dp))
                for (item in upcoming) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp)
                        .clickable(enabled = state.connected, onClickLabel = "Play", role = Role.Button) { onSelect(item.id) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title, style = SideNext, color = Pv1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        item.artist?.takeIf { it.isNotBlank() }?.let {
                            Text(" · $it", style = SideNext, color = Pv2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Pv4))
                }
            }
        }
    }
}

/** One of the theme's white badges, or its name in words when there is no picture for it. */
@Composable
private fun ThemeBadge(picture: Int?, name: String, modifier: Modifier = Modifier.height(13.dp)) {
    if (picture == null) Text(name, style = SideBadge, color = Pv1)
    else Image(painterResource(picture), contentDescription = name, modifier = modifier, contentScale = ContentScale.Fit)
}

/** The theme's codec picture for an FFmpeg codec name as the decoder reports it (FLAC, ALAC, PCM_S24LE, DSD64…). */
internal fun codecBadge(codec: String): Int? {
    val name = codec.uppercase(java.util.Locale.ROOT)
    return when {
        name.startsWith("PCM") -> R.drawable.georgia_codec_pcm
        name.startsWith("MP3") -> R.drawable.georgia_codec_mp3
        name.startsWith("AAC") -> R.drawable.georgia_codec_aac
        name.startsWith("DSD") -> R.drawable.georgia_codec_dsd
        name == "VORBIS" -> R.drawable.georgia_codec_ogg
        name == "FLAC" -> R.drawable.georgia_codec_flac
        name == "ALAC" -> R.drawable.georgia_codec_alac
        name == "OPUS" -> R.drawable.georgia_codec_opus
        name == "WAVPACK" -> R.drawable.georgia_codec_wavpack
        name == "APE" -> R.drawable.georgia_codec_ape
        name == "TTA" -> R.drawable.georgia_codec_tta
        else -> null
    }
}

internal fun channelBadge(channels: Int): Int? = when (channels) {
    1 -> R.drawable.georgia_channels_1
    2 -> R.drawable.georgia_channels_2
    6 -> R.drawable.georgia_channels_6
    8 -> R.drawable.georgia_channels_8
    else -> null
}

/** The glass bar along the bottom: the transport, the time gone, the seek bar, the time left and the page buttons. */
@Composable
private fun DetailsBar(state: PlayerPresentation, page: PlayerPage, onPage: (PlayerPage) -> Unit, lyrics: LyricsLoad, accent: Color,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onOutput: (Rect?) -> Unit,
    outputSheetOpen: Boolean, onSeek: (String?, Long) -> Unit) {
    val lyricsOn = page == PlayerPage.LYRICS
    val queueOn = page == PlayerPage.QUEUE
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 10.dp).heightIn(min = 52.dp)
        .clip(RoundedCornerShape(50)).background(Pv4).padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TransportButton(PlayerIcons.Previous, 26.dp, "Previous", state.connected && state.canPrevious, onClick = onPrevious)
        TransportButton(if (state.playing) PlayerIcons.Pause else if (state.ended) PlayerIcons.Refresh else PlayerIcons.Play, 32.dp,
            if (state.playing) "Pause playback" else "Play playback", state.connected && state.canPlayPause, onClick = onPlayPause)
        TransportButton(PlayerIcons.Next, 26.dp, "Next", state.connected && state.canNext, onClick = onNext)
        // The same seek pill as upright: it thickens under a finger, and its touch area is 48 dp tall.
        key(state.id, state.durationMs) {
            val interaction = remember { MutableInteractionSource() }
            val dragging by interaction.collectIsDraggedAsState()
            var target by remember { mutableFloatStateOf(0f) }
            val duration = state.durationMs?.takeIf { it > 0 }
            val position = if (dragging) target.toLong() else state.positionMs
            Text(playbackTime(position), style = Times, color = Pv2, maxLines = 1, modifier = Modifier.padding(start = 8.dp, end = 12.dp))
            if (duration != null) PillSlider(position.coerceIn(0, duration).toFloat(), { target = it }, { onSeek(state.id, target.toLong()) },
                0f..duration.toFloat(), state.connected && state.canSeek, "Estimated playback position", Modifier.weight(1f), interaction)
            else Spacer(Modifier.weight(1f))
            Text(duration?.let { "−${playbackTime((it - position).coerceAtLeast(0))}" } ?: "--:--", style = Times, color = Pv2,
                maxLines = 1, modifier = Modifier.padding(start = 12.dp, end = 6.dp))
        }
        StripButton(PlayerIcons.Lyrics, lyricsOn, if (lyricsOn) "Close lyrics" else "Open lyrics",
            enabled = lyricsOn || lyrics.opens, onTint = accent) {
            onPage(if (lyricsOn) PlayerPage.SONG else PlayerPage.LYRICS)
        }
        var outputBox by remember { mutableStateOf<Rect?>(null) }
        StripButton(PlayerIcons.Output, outputSheetOpen, "Sound output", onTint = accent,
            modifier = Modifier.onGloballyPositioned { outputBox = it.screenBox() }) { onOutput(outputBox) }
        StripButton(PlayerIcons.QueueList, queueOn, "Queue · ${counted(state.queue.size, "songs")}", onTint = accent) {
            onPage(if (queueOn) PlayerPage.SONG else PlayerPage.QUEUE)
        }
    }
}
