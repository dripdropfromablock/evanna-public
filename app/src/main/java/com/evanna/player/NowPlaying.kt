package com.evanna.player

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.evanna.player.library.ArtworkImage
import com.evanna.player.library.LibraryArtwork
import com.evanna.player.library.LyricLine
import com.evanna.player.library.Lyrics
import com.evanna.player.playback.SleepMode
import com.evanna.player.playback.nearest
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

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

private val CoverShape = RoundedCornerShape(10.dp)
private val SmallCoverShape = RoundedCornerShape(5.dp)

/**
 * Now Playing (plan tasks U3–U5, mockup `mockups/looks.html`): Lyrics, Song and Queue pages over the
 * moving cover, sharing one set of controls at the bottom.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun NowPlayingScreen(state: PlayerPresentation, cover: Bitmap?, actionError: String?, onBack: () -> Unit,
    onLibrary: () -> Unit, onOutput: (Offset?) -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (String?, Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onUsbVolume: (Int) -> Unit,
    loadLyrics: suspend (String) -> Lyrics?, onUsbVolumeSet: (Float, Long) -> Unit, onSleep: (SleepMode, Int) -> Unit,
    onFavorite: (String, Boolean) -> Unit, onSelect: (String) -> Unit, onMove: (String, String) -> Unit, onRemove: (String) -> Unit,
    onSavePlaylist: () -> Unit, loadArtwork: suspend (String) -> Bitmap?, motion: PlayerSheetMotion, flying: Boolean,
    wantedOpen: Boolean, onConvert: (() -> Unit)? = null,
    outputSheetOpen: Boolean = false) {
    var page by rememberSaveable { mutableStateOf(PlayerPage.SONG) }
    var lyricsTrack by rememberSaveable { mutableStateOf<String?>(null) }
    var showSignal by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    if (showSleep) SleepDialog(state, onSleep) { showSleep = false }
    if (showSignal) SoundPathSheet(state.soundPath, { showSignal = false; onOutput(null) }, { showSignal = false })
    BackHandler { if (wantedOpen && page != PlayerPage.SONG) page = PlayerPage.SONG else onBack() }
    // The preview and full view share one load, and never show the preceding track's words.
    val lyrics by key(state.id) {
        produceState<LyricsLoad>(LyricsLoad.Loading) {
            val id = state.id ?: return@produceState
            value = try { loadLyrics(id)?.let { LyricsLoad.Ready(it) } ?: LyricsLoad.None }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LyricsLoad.Failed }
        }
    }
    LaunchedEffect(wantedOpen) {
        if (wantedOpen && page == PlayerPage.LYRICS && lyricsTrack != state.id) page = PlayerPage.SONG
    }
    val more: @Composable () -> Unit = {
        MoreButton(buildList<Pair<String, () -> Unit>> {
            add("Sleep timer" to { showSleep = true })
            if (onConvert != null) add("Play converted once" to onConvert)
            if (page == PlayerPage.QUEUE && state.connected && state.queue.isNotEmpty()) add("Save queue as playlist" to onSavePlaylist)
        })
    }
    Box(Modifier.fillMaxSize()) {
        PlayerBackdrop(cover, LocalLookStyle.current.look == EvannaLook.APPLE_LIGHT,
            Modifier.matchParentSize().graphicsLayer { alpha = motion.progress })
        CompositionLocalProvider(LocalContentColor provides Pv1) {
            Column(Modifier.fillMaxSize().graphicsLayer { alpha = ((motion.progress - 0.15f) / 0.85f).coerceIn(0f, 1f) }
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
                                        Modifier.dockingCover(motion, flying, corner = 10.dp, report = shown == page).then(sharedCover))
                                    PlayerPage.LYRICS -> Column(Modifier.fillMaxSize()) {
                                        CompactHeader(state, cover, more,
                                            Modifier.dockingCover(motion, flying, report = shown == page).then(sharedCover)) { page = PlayerPage.SONG }
                                        LyricsPage(lyrics, state, onSeek, Modifier.weight(1f).padding(top = 24.dp).graphicsLayer {
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
                    PlayerControls(state, actionError, page, {
                        if (it == PlayerPage.LYRICS) lyricsTrack = state.id
                        page = it
                    }, lyrics, onSeek, { showSignal = true }, onPlayPause, onPrevious, onNext, onUsbVolume, onUsbVolumeSet, onSleep,
                        onOutput, outputSheetOpen)
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

/** The 28 dp More circle with its menu. */
@Composable
private fun MoreButton(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        PlayerHeaderButton(PlayerIcons.More, "More options", 18.dp, showCircle = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, action) in items) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
        }
    }
}

@Composable
private fun PlayerHeaderButton(icon: ImageVector, description: String, size: Dp,
    enabled: Boolean = true, showCircle: Boolean = false, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (showCircle) Box(Modifier.size(28.dp).background(Pv4, CircleShape))
        Icon(icon, contentDescription = null, tint = if (enabled) Pv1 else Pv3,
            modifier = Modifier.size(size).pressScale(interaction))
    }
}

@Composable
private fun SleepDialog(state: PlayerPresentation, onSleep: (SleepMode, Int) -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Sleep timer") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Playback pauses; your queue stays. No fade-out.")
            for (minutes in listOf(15, 30, 45, 60, 90)) TextButton(onClick = { onSleep(SleepMode.MINUTES, minutes); onClose() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$minutes minutes") }
            for (mode in listOf(SleepMode.SONG, SleepMode.ALBUM)) TextButton(onClick = { onSleep(mode, 0); onClose() }, enabled = state.hasSong,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (mode == SleepMode.SONG) "End of this song" else "End of this album") }
        } },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

/** Page 1: the big cover, then title and artist with Favorite and More. The cover shrinks a little while paused. */
@Composable
private fun SongPage(state: PlayerPresentation, cover: Bitmap?, onFavorite: (String, Boolean) -> Unit,
    more: @Composable () -> Unit, artwork: Modifier) {
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp)) {
        val scale by animateFloatAsState(if (state.playing) 1f else 0.86f, spring(dampingRatio = 0.78f, stiffness = 380f), label = "cover scale")
        val lift by animateDpAsState(if (state.playing) 15.dp else 8.dp, spring(dampingRatio = 0.78f, stiffness = 380f), label = "cover shadow")
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Box(Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale }) {
                ArtworkImage(cover, Modifier.fillMaxSize().then(artwork).shadow(lift, CoverShape), CoverShape)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title.ifBlank { "Local audio file" }, style = SongTitle, color = Pv1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", style = SongTitle, color = Pv2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.offset(x = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerHeaderButton(if (state.favorite == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    description = when (state.favorite) {
                        true -> "Remove from favorites"
                        false -> "Add to favorites"
                        null -> "Favorites unavailable"
                    },
                    size = 24.dp,
                    enabled = state.connected && state.favorite != null,
                ) {
                    state.id?.let { onFavorite(it, state.favorite != true) }
                }
                more()
            }
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
    lyrics: LyricsLoad, onSeek: (String?, Long) -> Unit, onSignal: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onUsbVolume: (Int) -> Unit, onUsbVolumeSet: (Float, Long) -> Unit, onSleep: (SleepMode, Int) -> Unit,
    onOutput: (Offset?) -> Unit, outputSheetOpen: Boolean) {
    val lyricsOn = page == PlayerPage.LYRICS
    val toggleLyrics = { onPage(if (lyricsOn) PlayerPage.SONG else PlayerPage.LYRICS) }
    val directUsb = state.route == "DIRECT_USB"
    val compact = LocalConfiguration.current.screenHeightDp < 560
    val androidVolume = state.route == "ANDROID" && !state.androidVolumeBypassed
    val volumeEnabled = androidVolume && state.connected && !outputSheetOpen
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 10.dp)) {
        if (!lyricsOn) LyricsPreview(lyrics, state.positionMs, toggleLyrics)
        SeekBar(state, onSeek, onSignal)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TransportButton(PlayerIcons.Previous, 36.dp, "Previous", state.connected && state.canPrevious, onPrevious)
            TransportButton(if (state.playing) PlayerIcons.Pause else if (state.ended) Icons.Filled.Refresh else Icons.Filled.PlayArrow, 52.dp,
                if (state.playing) "Pause playback" else "Play playback", state.connected && state.canPlayPause, onPlayPause)
            TransportButton(PlayerIcons.Next, 36.dp, "Next", state.connected && state.canNext, onNext)
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
                enabled = lyricsOn || lyrics is LyricsLoad.Ready, onClick = toggleLyrics)
            if (compact && !directUsb) AndroidMediaVolume(enabled = volumeEnabled,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp), showLevel = androidVolume, onOpenOutput = onOutput)
            else Spacer(Modifier.weight(1f))
            val queueOn = page == PlayerPage.QUEUE
            StripButton(PlayerIcons.Queue, queueOn, "Queue · ${state.queue.size} songs") {
                onPage(if (queueOn) PlayerPage.SONG else PlayerPage.QUEUE)
            }
        }
    }
}

@Composable
private fun LyricsPreview(load: LyricsLoad, positionMs: Long, onClick: () -> Unit) {
    val lyrics = (load as? LyricsLoad.Ready)?.lyrics
    val current = lyrics?.lines?.getOrNull(lyrics.lineAt(positionMs))?.text?.takeIf(String::isNotBlank)
    val label = when (load) {
        LyricsLoad.Loading -> "Loading lyrics…"
        LyricsLoad.None -> "No lyrics"
        LyricsLoad.Failed -> "Lyrics unavailable"
        is LyricsLoad.Ready -> current ?: "Lyrics"
    }
    val action = "Show lyrics"
    val available = lyrics != null
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
        Text(label, style = Route, color = if (pressed) EvannaDesign.colorScheme.primary else if (available) Pv1 else Pv2,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (available) Text(" ›", style = Route, color = Pv2)
    }
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
    interaction: MutableInteractionSource = remember { MutableInteractionSource() }, onOpenOutput: ((Offset?) -> Unit)? = null,
) {
    val dragging by interaction.collectIsDraggedAsState()
    val height by animateDpAsState(if (dragging) 12.dp else 7.dp, label = "pill height")
    Box(modifier.height(7.dp), contentAlignment = Alignment.Center) {
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
private fun SeekBar(state: PlayerPresentation, onSeek: (String?, Long) -> Unit, onSignal: () -> Unit) {
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
                Text(remember(state.sourceQuality) { shortSourceQuality(state.sourceQuality) }, style = Badge, color = Pv1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).clip(RoundedCornerShape(5.dp)).background(Pv4)
                        .controlFocus(badgePress, RoundedCornerShape(5.dp))
                        .clickable(badgePress, indication = null, role = Role.Button, onClick = onSignal)
                        .semantics { contentDescription = "Audio quality details" }
                        .padding(horizontal = 7.dp, vertical = 3.5.dp).pressScale(badgePress))
            }
        }
    }
}

/** A plain transport icon that shrinks a little while pressed. */
@Composable
private fun TransportButton(icon: ImageVector, size: Dp, description: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(size + 12.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (enabled) Pv1 else Pv3, modifier = Modifier.size(size).pressScale(interaction))
    }
}

/** Player strip buttons share a 48 dp touch target and highlight the open control. */
@Composable
private fun StripButton(icon: ImageVector, on: Boolean, description: String, enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val apple = LocalLookStyle.current.look.apple
    val platter = if (apple) Color.White.copy(alpha = 0.22f) else EvannaDesign.colorScheme.primary.copy(alpha = 0.30f)
    Box(Modifier.size(48.dp).controlFocus(interaction, RoundedCornerShape(12.dp))
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; if (enabled) stateDescription = if (on) "On" else "Off" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) platter else Color.Transparent), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).pressScale(interaction), tint = if (enabled) Pv1 else Pv3)
        }
    }
}

/** USB hardware volume: the same pill as the seek bar, sent once on release, with step buttons on each side. */
@Composable
internal fun UsbVolume(state: PlayerPresentation, onAdjust: (Int) -> Unit, onSet: (Float, Long) -> Unit,
    onOpenOutput: ((Offset?) -> Unit)? = null,
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

/**
 * Page 0: big lyrics. The current line is bright and sits 30 % down the page; the others are faint and a little smaller.
 * Word-timed lines fill word by word with a 30 dp soft edge, and sung words lift 2 dp. Tap a line to play from it.
 * After a hand scroll, Sync returns to the active line without changing playback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsPage(load: LyricsLoad, state: PlayerPresentation, onSeek: (String?, Long) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (load !is LyricsLoad.Ready) {
            Text(when (load) { LyricsLoad.Loading -> "Loading lyrics…"; LyricsLoad.None -> "This song has no lyrics."; else -> "Lyrics could not be read." },
                style = HeaderArtist, color = Pv2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            return@BoxWithConstraints
        }
        val lyrics = load.lyrics
        val viewport = maxHeight
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
            val running = lyrics.timed && state.playing && !reducedMotion
            val sample by rememberUpdatedState(state.positionMs)
            val position = produceState(sample, running, if (running) null else sample) {
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
            val current by remember(lyrics, position) { derivedStateOf { lyrics.lineAt(position.value) } }
            val intro = lyrics.timed && (lyrics.lines.firstOrNull()?.timeMs ?: 0) >= DOTS_BREAK_MS
            val offset = if (intro) 1 else 0
            val dragged by list.interactionSource.collectIsDraggedAsState()
            var following by remember { mutableStateOf(true) }
            var placed by remember { mutableStateOf(false) }
            LaunchedEffect(dragged) { if (dragged) following = false }
            LaunchedEffect(current, dragged, following, reducedMotion) {
                if (!lyrics.timed || dragged || !following) return@LaunchedEffect
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
            }, state = list, verticalArrangement = Arrangement.spacedBy(if (lyrics.timed) 20.dp else 8.dp),
                contentPadding = PaddingValues(top = if (lyrics.timed) viewport * 0.3f else 24.dp, bottom = if (lyrics.timed) viewport * 0.7f else 24.dp)) {
                if (intro) item(key = "intro") {
                    BreakDots(current < 0, position, 0L, lyrics.lines.first().timeMs!!, reducedMotion)
                }
                itemsIndexed(lyrics.lines) { index, line ->
                    val next = lyrics.lines.getOrNull(index + 1)?.timeMs
                    if (lyrics.timed && line.text.isEmpty()) {
                        val start = line.timeMs!!
                        if (next != null && next - start >= DOTS_BREAK_MS) {
                            BreakDots(index == current, position, start, next, reducedMotion)
                        } else Spacer(Modifier.height(8.dp))
                    } else LyricRow(line, lyrics.timed, index == current, !following || dragged, position, next, seekable, reducedMotion) {
                        following = true
                        onSeek(state.id, line.timeMs!!)
                    }
                }
            }
            if (lyrics.timed && !following && !dragged) {
                FilledTonalButton(onClick = { following = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 6.dp)) {
                    Text("Sync")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricRow(line: LyricLine, timed: Boolean, current: Boolean, browsing: Boolean,
    position: State<Long>, next: Long?, seekable: Boolean, reducedMotion: Boolean, onSeek: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val active = !timed || current || browsing
    val blur by animateFloatAsState(if (!active && Build.VERSION.SDK_INT >= 31) 3f else 0f,
        EvannaMotion.enter, label = "lyric focus blur")
    val scale by animateFloatAsState(if (active) 1f else 0.98f, EvannaMotion.enter, label = "line scale")
    val opacity by animateFloatAsState(if (active) 1f else INACTIVE_LYRIC_OPACITY, EvannaMotion.enter, label = "line opacity")
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(16.dp))
        .then(if (seekable) Modifier.controlFocus(interaction, RoundedCornerShape(16.dp))
            .clickable(interaction, indication = null, onClickLabel = "Play from this line", onClick = onSeek) else Modifier)
        .then(if (timed && current) Modifier.semantics { stateDescription = "Current lyric" } else Modifier)
        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
        .graphicsLayer {
            scaleX = scale; scaleY = scale; alpha = opacity; transformOrigin = TransformOrigin(0f, 0.5f)
            val radius = blur.dp.toPx()
            renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Decal) else null
        }.pressScale(interaction)) {
        when {
            !timed -> Text(line.text.ifEmpty { " " }, style = PlainLyrics, color = Pv1)
            line.words.isNotEmpty() && !reducedMotion -> FlowRow(Modifier.clearAndSetSemantics {
                text = AnnotatedString(line.text)
            }) {
                line.words.forEachIndexed { i, word ->
                    if (current && !browsing) {
                        val end = line.words.getOrNull(i + 1)?.timeMs ?: next ?: (word.timeMs + 1_000)
                        SungWord(word.text, position, word.timeMs, end)
                    } else Text(word.text, style = LyricsLine, color = Pv1)
                }
            }
            else -> Text(line.text, style = LyricsLine, color = Pv1)
        }
    }
}

/** One word: white text masked from bright (sung) to faint (to come), with a 30 dp soft edge, lifted 2 dp once reached. */
@Composable
private fun SungWord(text: String, position: State<Long>, start: Long, end: Long) {
    val reached by remember(position, start) { derivedStateOf { position.value >= start } }
    val lift by animateDpAsState(if (reached) (-2).dp else 0.dp, spring(dampingRatio = 0.93f, stiffness = 25f), label = "word lift")
    Text(text, style = LyricsLine, color = Color.White, modifier = Modifier.graphicsLayer {
        translationY = lift.toPx(); compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        val progress = ((position.value - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
        val feather = 30.dp.toPx()
        val brush = when {
            progress <= 0f -> SolidColor(Pv3)
            progress >= 1f -> SolidColor(Pv1)
            else -> {
                val edge = progress * (size.width + feather) - feather / 2
                Brush.horizontalGradient(0f to Pv1, ((edge - feather / 2) / size.width).coerceIn(0f, 1f) to Pv1,
                    ((edge + feather / 2) / size.width).coerceIn(0f, 1f) to Pv3, 1f to Pv3)
            }
        }
        drawRect(brush, blendMode = BlendMode.DstIn)
    })
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
private fun BreakDots(active: Boolean, position: State<Long>, start: Long, end: Long, reducedMotion: Boolean) {
    Row(Modifier.padding(start = 32.dp, top = 6.dp, bottom = 6.dp).semantics { contentDescription = "Instrumental break" },
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0.8f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }, contentPadding = PaddingValues(start = 24.dp, end = 14.dp, bottom = 24.dp)) {
            if (current > 0) {
                item(key = "history") { Text("History", style = SectionTitle, color = Pv2, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() }) }
                itemsIndexed(state.queue.take(current), key = { _, item -> item.id }) { _, item ->
                    QueueRow(item, played = true, state, loadArtwork, onSelect, null, null, null)
                }
            }
            item(key = "next") { Text("Playing Next", style = SectionTitle, color = Pv1, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() }) }
            val upcoming = state.queue.drop(current + 1)
            // An empty "Playing Next" offers the library; a full one ends with its last song, as in the mockup.
            if (upcoming.isEmpty()) item(key = "empty") {
                Column {
                    Text("Nothing is queued after this song.", style = RowArtist, color = Pv2, modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = onLibrary, colors = ButtonDefaults.textButtonColors(contentColor = Pv2), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Open music library")
                    }
                }
            }
            itemsIndexed(upcoming, key = { _, item -> item.id }) { offset, item ->
                QueueRow(item, played = false, state, loadArtwork, onSelect,
                    upcoming.getOrNull(offset - 1)?.let { neighbor -> { onMove(item.id, neighbor.id) } },
                    upcoming.getOrNull(offset + 1)?.let { neighbor -> { onMove(item.id, neighbor.id) } },
                    { onRemove(item.id) })
            }
        }
    }
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

/** A 64 dp queue row: 48 dp cover, title and artist; songs still to come can move up or down or leave the queue. */
@Composable
private fun QueueRow(item: PlayerQueueItem, played: Boolean, state: PlayerPresentation, loadArtwork: suspend (String) -> Bitmap?,
    onSelect: (String) -> Unit, up: (() -> Unit)?, down: (() -> Unit)?, remove: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (played) 0.55f else 1f).clip(RoundedCornerShape(10.dp))
        .controlFocus(interaction, RoundedCornerShape(10.dp))
        .clickable(interaction, indication = null, enabled = state.connected, onClickLabel = "Play this song") { onSelect(item.id) },
        verticalAlignment = Alignment.CenterVertically) {
        LibraryArtwork(item.id, 0, Modifier.size(48.dp), SmallCoverShape) { loadArtwork(item.id) }
        Column(Modifier.weight(1f).padding(start = 12.dp).pressScale(interaction)) {
            Text(item.title, style = HeaderTitle, color = Pv1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist", style = RowArtist, color = Pv2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!played) {
            RowButton(Icons.Filled.KeyboardArrowUp, "Move ${item.title} up", state.connected && up != null) { up?.invoke() }
            RowButton(Icons.Filled.KeyboardArrowDown, "Move ${item.title} down", state.connected && down != null) { down?.invoke() }
            RowButton(Icons.Filled.Close, "Remove ${item.title} from queue", state.connected && remove != null) { remove?.invoke() }
        }
    }
}

@Composable
private fun RowButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(width = 40.dp, height = 48.dp).controlFocus(interaction, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).pressScale(interaction), tint = if (enabled) Pv2 else Pv3)
    }
}
