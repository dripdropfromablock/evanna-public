package com.evanna.player

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.evanna.player.playback.PlaybackService
import com.evanna.player.playback.EvannaPlayer
import com.evanna.player.playback.UsbPlaybackPolicy
import com.evanna.player.playback.USB_START_VOLUME_DEFAULT_DB
import com.evanna.player.playback.USB_START_VOLUME_MIN_DB
import com.evanna.player.playback.USB_VOLUME_LIMIT_DEFAULT_DB
import com.evanna.player.playback.USB_KEY_STEP_DEFAULT_DB
import com.evanna.player.playback.UsbKeyContext
import com.evanna.player.playback.UsbKeyRequest
import com.evanna.player.playback.UsbVolumeKeys
import com.evanna.player.playback.validVolumeLimit
import com.truesample.usbaudio.VolumeRange
import kotlin.math.roundToInt
import com.evanna.player.diagnostics.CrashRecorder
import com.evanna.player.diagnostics.CrashReportsScreen
import com.evanna.player.diagnostics.DiagnosticEvent
import com.evanna.player.decoder.LocalDecodeScreen
import com.evanna.player.library.LibraryScreen
import com.evanna.player.library.MusicFoldersDialog
import com.evanna.player.library.ArtworkSize
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.Lyrics
import com.evanna.player.library.musicNote
import com.evanna.player.usb.UsbPermissionSession
import com.evanna.player.usb.DacIdentity
import com.evanna.player.usb.DacConnection
import com.evanna.player.usb.AUTO_CONNECT_DAC_PREFERENCE
import com.evanna.player.ui.glass.GLASS_PREFERENCE
import com.evanna.player.ui.glass.GlassContext
import com.evanna.player.ui.glass.GlassTier
import com.evanna.player.ui.glass.INCREASE_CONTRAST_PREFERENCE
import com.evanna.player.ui.glass.LocalGlass
import com.evanna.player.ui.glass.REDUCE_TRANSPARENCY_PREFERENCE
import com.evanna.player.ui.glass.backdrop.backdrops.layerBackdrop
import com.evanna.player.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.evanna.player.ui.glass.floorBrush
import com.evanna.player.ui.glass.glassTier
import com.evanna.player.ui.glass.samplesBackdrop
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import com.evanna.player.usb.KEEP_DIRECT_USB_PREFERENCE
import com.evanna.player.usb.latestUsbDrops
import com.evanna.player.usb.suggestAndroidAudio
import com.evanna.player.usb.LAST_DAC_PREFERENCE
import com.evanna.player.usb.shouldAutoConnect
import com.evanna.player.usb.dacIdentity
import com.evanna.player.usb.storedDacIdentity
import com.evanna.player.usb.sameDac
import com.evanna.player.usb.preferredDacId
import com.google.common.util.concurrent.ListenableFuture
import com.truesample.usbaudio.UsbAudioOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun shouldHandleAttachIntent(restored: Boolean, flags: Int): Boolean =
    !restored && (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

@UnstableApi
class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private val permissions = UsbPermissionSession()
    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private var devices by mutableStateOf(emptyList<DeviceState>())
    private var selectedId by mutableStateOf<Int?>(null)
    private var lastDac by mutableStateOf<DacIdentity?>(null)
    private var activeDacId:Int?=null
    /** Settings switch: connect and play when a DAC used before is plugged in. */
    private var autoConnectEnabled by mutableStateOf(true)
    /** The saved layout and brightness. The selected song supplies the accent. */
    private var look by mutableStateOf(EvannaLook.APPLE_DARK)
    /** Liquid glass on the floating controls, in the Apple looks; Reduce transparency keeps it frosted and dense. */
    private var liquidGlass by mutableStateOf(true)
    private var reduceTransparency by mutableStateOf(false)
    /** Increase contrast: solid controls with clear edges instead of glass (phase 6). */
    private var increaseContrast by mutableStateOf(false)
    private var foreground by mutableStateOf(false)
    private var startPage by mutableStateOf(PlayerScreen.LIBRARY)
    private val motionScale = AppMotionScale()
    /** A just-plugged-in DAC waiting for the playback controls; [connectPluggedDac] then connects it. Cleared when the screen stops. */
    private var autoConnectId:Int?=null
    private var autoConnectingId:Int?=null
    /** Until this time (elapsedRealtime), start playing as soon as the opened output allows it; 0 when nothing is waiting. */
    private var playAfterOpenUntil=0L
    private var pending by mutableStateOf<UsbPermissionSession.Request?>(null)
    private var message by mutableStateOf("Connect a USB DAC to check access.")
    private var mediaController by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playbackMessage by mutableStateOf("Connecting playback controls…")
    private var playerUi by mutableStateOf(PlayerPresentation())
    private var playlistAddIds by mutableStateOf<List<String>>(emptyList())
    private var playlistAddFilter by mutableStateOf<com.evanna.player.library.LibraryFilter?>(null)
    private var playlistSaveQueue by mutableStateOf(false)
    private var navigation by mutableStateOf(listOf(PlayerScreen.LIBRARY))
    private var actionError by mutableStateOf<String?>(null)
    private var selectionSequence = 0L
    private val playing get() = playerUi.playing
    private val fileSelected get() = playerUi.hasSong
    private var outputRoute by mutableStateOf("NONE")
    private var usbPolicy by mutableStateOf(UsbPlaybackPolicy.STRICT)
    private var followSongRate by mutableStateOf(true)
    private var maxUsbRate by mutableStateOf<Int?>(null)
    private var dacProfileKey by mutableStateOf<String?>(null)
    private var dacProfileName by mutableStateOf<String?>(null)
    private var startVolumeDb by mutableFloatStateOf(USB_START_VOLUME_DEFAULT_DB)
    private var volumeLimitDb by mutableFloatStateOf(USB_VOLUME_LIMIT_DEFAULT_DB)
    private var usbKeyStepDb by mutableFloatStateOf(USB_KEY_STEP_DEFAULT_DB)
    private var levelingMode by mutableStateOf(com.evanna.player.loudness.LevelingMode.OFF)
    private var crossfeed by mutableStateOf(com.evanna.player.equalizer.Crossfeed.OFF)
    private var androidBitPerfect by mutableStateOf(false)
    private var usbDrops by mutableStateOf<Pair<String,Long>?>(null)
    private var keptDirectUsb by mutableStateOf<Set<String>>(emptySet())
    private val usbKeys=UsbVolumeKeys()
    private val usbKeyDowns=mutableMapOf<Int,Pair<MediaController,Long>>()
    private var usbKeyIndicator by mutableStateOf<Float?>(null)
    private var usbKeyIndicatorRevision by mutableIntStateOf(0)
    private var modeChanging by mutableStateOf(false)
    private var signalStatus by mutableStateOf("")
    private var pendingFile: Uri? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::selectLocalFile)
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { if (mediaController === player) refreshPlayback(player) }
    }

    private fun refreshPlayback(player: Player) {
        val previousKeys=usbKeyContext()
        val extras = player.playlistMetadata.extras
        outputRoute = extras?.getString(EvannaPlayer.OUTPUT_ROUTE) ?: "NONE"
        activeDacId=extras?.getInt(EvannaPlayer.USB_DEVICE_ID,-1)?.takeIf {it>=0}
        usbPolicy = UsbPlaybackPolicy.fromStored(extras?.getString(EvannaPlayer.USB_POLICY))
        followSongRate = extras?.getBoolean(EvannaPlayer.FOLLOW_SONG_RATE, true) ?: true
        maxUsbRate=extras?.getInt(EvannaPlayer.MAX_USB_RATE,if(followSongRate)0 else 48000)?.takeIf {it>0}
        dacProfileKey=extras?.getString(EvannaPlayer.DAC_PROFILE_KEY);dacProfileName=extras?.getString(EvannaPlayer.DAC_PROFILE_NAME)
        usbDrops=latestUsbDrops(usbDrops,dacProfileKey,extras?.getLong(EvannaPlayer.USB_DROPPED,0L) ?: 0L)
        startVolumeDb = extras?.getFloat(EvannaPlayer.USB_START_VOLUME, USB_START_VOLUME_DEFAULT_DB) ?: USB_START_VOLUME_DEFAULT_DB
        volumeLimitDb = extras?.getFloat(EvannaPlayer.USB_VOLUME_LIMIT,USB_VOLUME_LIMIT_DEFAULT_DB) ?: USB_VOLUME_LIMIT_DEFAULT_DB
        usbKeyStepDb = extras?.getFloat(EvannaPlayer.USB_KEY_STEP,USB_KEY_STEP_DEFAULT_DB) ?: USB_KEY_STEP_DEFAULT_DB
        levelingMode=com.evanna.player.loudness.storedLevelingMode(extras?.getString(EvannaPlayer.LEVELING_MODE))
        crossfeed=com.evanna.player.equalizer.storedCrossfeed(extras?.getString(EvannaPlayer.CROSSFEED))
        signalStatus = extras?.getString(EvannaPlayer.SIGNAL_STATUS) ?: ""
        playerUi = playerPresentation(player)
        val keyContext=usbKeyContext()
        if(keyContext!=previousKeys)usbKeys.observe(keyContext)?.let(::sendUsbKeyVolume)
        if(keyContext==null)usbKeyIndicator=null
        else if(usbKeyIndicator!=null && keyContext!=previousKeys)usbKeyIndicator=keyContext.gain.currentDb
        playbackMessage = player.playerError?.message ?: when {
            fileSelected && player.playbackState == Player.STATE_ENDED -> "The selected file completed after output drain."
            fileSelected && playing -> "Local file playback is active."
            fileSelected -> "Local file selected. Press Play to start or resume."
            outputRoute == "ANDROID" -> "Android audio selected. Choose a local file; selecting an output does not start audio."
            playing -> "USB check running"
            player.currentMediaItem != null -> "USB output opened. Select music and press Play; hardware volume is checked before audio."
            else -> "Select music to listen through Android audio or a connected USB DAC."
        }
        if (playAfterOpenUntil != 0L) {
            if (SystemClock.elapsedRealtime() > playAfterOpenUntil) playAfterOpenUntil = 0
            else if (playerUi.hasSong && outputRoute == "DIRECT_USB" && player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                playAfterOpenUntil = 0
                actionError = null
                if (!player.playWhenReady) player.play()
                message = "${dacProfileName ?: "USB DAC"} connected. Playing."
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                val parts = intent.data?.pathSegments ?: return
                if (intent.data?.scheme != "evanna" || intent.data?.host != "usb-permission" || parts.size != 2) return
                val id = parts[0].toIntOrNull() ?: return
                val request = UsbPermissionSession.Request(id, parts[1])
                val device = usbManager.deviceList.values.firstOrNull { it.deviceId == id }
                val permitted = try { device?.let(usbManager::hasPermission) == true }
                    catch (_: SecurityException) { false }
                val result = permissions.finish(request, device != null, permitted)
                if (result != null) {
                    CrashRecorder.event(if (result == UsbPermissionSession.Result.GRANTED) DiagnosticEvent.PERMISSION_GRANTED else DiagnosticEvent.PERMISSION_DENIED)
                    pending = permissions.pending
                    message = when (result) {
                        UsbPermissionSession.Result.GRANTED -> "USB access allowed."
                        UsbPermissionSession.Result.DENIED -> "USB access was denied. You can try again."
                        UsbPermissionSession.Result.DISCONNECTED -> "The DAC was disconnected. Connect it and try again."
                    }

                }
            }
            refreshDevices()
            if(intent.action==UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                @Suppress("DEPRECATION") val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                device?.let {autoConnectId=it.deviceId;connectPluggedDac()}
            }
            if(intent.action==UsbManager.ACTION_USB_DEVICE_DETACHED) {
                @Suppress("DEPRECATION") val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if(device!=null && UsbAudioOutput.isUsbAudioOutput(device)) {
                    if(activeDacId==device.deviceId)activeDacId=null
                    message="${device.dacIdentity().displayName} unplugged. Your queue and position are kept."

                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.evanna.player.equalizer.SoundSettings.load(this)
        LocalLibrary.syncOnLaunch(this)
        playlistAddIds=savedInstanceState?.getStringArrayList("playlistAddIds")?.filter {it.matches(Regex("[0-9a-f]{64}"))}?.take(1000).orEmpty()
        playlistSaveQueue=savedInstanceState?.getBoolean("playlistSaveQueue")==true
        playlistAddFilter=savedInstanceState?.getBundle("playlistAddFilter")?.let {bundle ->
            com.evanna.player.library.libraryContextRequest(bundle.getString("kind"),bundle.getString("value"),bundle.getString("artist"),
                bundle.getString("search"),null,"PLAY_FIRST")?.filter
        }
        lastDac=storedDacIdentity(getSharedPreferences("playback-settings",MODE_PRIVATE).all[LAST_DAC_PREFERENCE] as? String)
        autoConnectEnabled=getSharedPreferences("playback-settings",MODE_PRIVATE).all[AUTO_CONNECT_DAC_PREFERENCE] as? Boolean ?: true
        keptDirectUsb=(getSharedPreferences("playback-settings",MODE_PRIVATE).all[KEEP_DIRECT_USB_PREFERENCE] as? Set<*>)
            ?.filterIsInstance<String>()?.toSet().orEmpty()
        // A recreated screen or a launch restored from Recents must not act on the old plug-in again.
        if(shouldHandleAttachIntent(savedInstanceState!=null,intent.flags))handleAttachIntent(intent)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val uiSettings = getSharedPreferences(UI_SETTINGS, MODE_PRIVATE)
        val uiPreferences = uiSettings.all
        @Suppress("DEPRECATION")
        val installedVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val updatesEnabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
        startPage = storedStartPage(uiPreferences[START_PAGE_PREFERENCE])
        navigation = savedInstanceState?.getStringArrayList("playerScreens")?.mapNotNull { value -> PlayerScreen.entries.firstOrNull { it.name == value } }
            ?.takeIf { it.firstOrNull() in startPages && it.size == it.distinct().size } ?: listOf(startPage)
        look = storedLook(uiPreferences[LOOK_PREFERENCE])
        liquidGlass = uiPreferences[GLASS_PREFERENCE] as? Boolean ?: true
        reduceTransparency = uiPreferences[REDUCE_TRANSPARENCY_PREFERENCE] as? Boolean ?: false
        increaseContrast = uiPreferences[INCREASE_CONTRAST_PREFERENCE] as? Boolean ?: false
        motionScale.speed = storedAnimationSpeed(uiPreferences[ANIMATION_SPEED_PREFERENCE])
        fun readSystemMotionScale() = AndroidSettings.Global.getFloat(
            contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f)
        motionScale.systemScale = readSystemMotionScale()
        applySystemBars(look)

        val composeView = ComposeView(this)
        composeView.setParentCompositionContext(composeView.createLifecycleAwareWindowRecomposer(motionScale, lifecycle))
        composeView.setContent {
            DisposableEffect(Unit) {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        motionScale.systemScale = readSystemMotionScale()
                    }
                }
                contentResolver.registerContentObserver(
                    AndroidSettings.Global.getUriFor(AndroidSettings.Global.ANIMATOR_DURATION_SCALE), false, observer)
                motionScale.systemScale = readSystemMotionScale()
                onDispose { contentResolver.unregisterContentObserver(observer) }
            }
            val controller = mediaController
            val songId = playerUi.id?.takeIf { playerUi.hasSong }
            val scansFinished = LocalLibrary.scansFinished
            // Clear the old palette with the old cover as soon as the selected song changes.
            var artwork by remember(controller, songId, scansFinished) { mutableStateOf<Pair<Bitmap, Color?>?>(null) }
            LaunchedEffect(controller, songId, scansFinished) {
                if (controller == null || songId == null) return@LaunchedEffect
                val decoded = try { currentArtwork(controller, songId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null } ?: return@LaunchedEffect
                val accent = withContext(Dispatchers.Default) { sampleArtworkAccent(decoded) }
                artwork = decoded to accent
            }
            val cover = artwork?.first
            val accent = artwork?.second
            val playerColors = remember(accent) { playerColorScheme(accent) }
            EvannaTheme(look, accent) {
                var licenseIndex by rememberSaveable { mutableStateOf<Int?>(null) }
                var showOutput by rememberSaveable { mutableStateOf(false) }
                var outputOrigin by remember { mutableStateOf<Offset?>(null) }
                val openOutput: (Offset?) -> Unit = { origin ->
                    outputOrigin = origin
                    refreshDevices()
                    showOutput = true
                }
                val screen = navigation.last()
                val savedScreens = rememberSaveableStateHolder()
                // Poll only while music plays; player events refresh the position when paused
                // (a paused app then wakes 0 times from this loop).
                LaunchedEffect(mediaController, playerUi.playing) {
                    val controller = mediaController ?: return@LaunchedEffect
                    fun sync() { playerUi = playerUi.copy(positionMs = controller.currentPosition.coerceAtLeast(0), durationMs = controller.duration.takeIf { it >= 0 }) }
                    sync()
                    while (mediaController === controller && playerUi.playing) { delay(250); sync() }
                }
                LaunchedEffect(usbKeyIndicatorRevision) {delay(1500);usbKeyIndicator=null}
                BackHandler(navigation.size > 1) { if (screen == PlayerScreen.NOTICES && licenseIndex != null) licenseIndex = null else back() }
                // Cancelling "Add to playlist" with system Back must not leak stale add arguments.
                LaunchedEffect(PlayerScreen.PLAYLISTS in navigation) {
                    if (PlayerScreen.PLAYLISTS !in navigation) { playlistAddIds = emptyList(); playlistAddFilter = null; playlistSaveQueue = false }
                }
                val keyboard = WindowInsets.isImeVisible
                var dockHeight by remember { mutableIntStateOf(0) }
                val dockInset = if (keyboard) 0.dp else with(LocalDensity.current) { dockHeight.toDp() }
                val openNotices = { licenseIndex = null; navigate(PlayerScreen.NOTICES) }
                // Tabs stay alive side by side, so a tab keeps its scroll spot and loaded lists.
                val tabContent: @Composable (PlayerScreen) -> Unit = { tab ->
                    if (tab == PlayerScreen.OUTPUT) SettingsScreen(tab, null, dockInset, openNotices)
                    else if (tab == PlayerScreen.CAROUSEL) com.evanna.player.library.CarouselScreen(
                        bottomInset = dockInset,
                        onLibrary = { selectTab(PlayerScreen.LIBRARY) },
                        onSelect = { request, complete -> libraryContext(request) { success -> complete(success); if (success) navigate(PlayerScreen.NOW_PLAYING) } },
                        onAdd = { id, result -> librarySong(id, true, result) },
                        onPlayNext = { id, result -> librarySong(id, true, result, next = true) },
                        onPlaylistSong = { openPlaylists(listOf(it)) },
                        nowPlaying = playerUi.takeIf { it.hasSong }?.let {
                            com.evanna.player.library.NowPlayingMark(it.title, it.album, it.playing)
                        },
                    )
                    else LibraryScreen(
                        bottomInset = dockInset,
                        onSelect = { request, complete -> libraryContext(request) { success -> complete(success); if (success) navigate(PlayerScreen.NOW_PLAYING) } },
                        onAdd = { id, result -> librarySong(id, true, result) },
                        onPlayNext = { id, result -> librarySong(id, true, result, next=true) },
                        searchMode = tab == PlayerScreen.SEARCH,onPlaylists={openPlaylists()},
                        onPlaylistSong={openPlaylists(listOf(it))},onPlaylistAlbum={openPlaylists(filter=it)},onFavorites={navigate(PlayerScreen.FAVORITES)},
                        onRecent={navigate(PlayerScreen.RECENT)},onMostPlayed={navigate(PlayerScreen.MOST)})
                }
                // Screens pushed over the tabs; Now Playing is the sheet above everything.
                val pushedContent: @Composable (PlayerScreen) -> Unit = { shown -> when (shown) {
                    PlayerScreen.CAROUSEL, PlayerScreen.LIBRARY, PlayerScreen.SEARCH, PlayerScreen.NOW_PLAYING -> Unit
                    PlayerScreen.PLAYLISTS -> com.evanna.player.user.PlaylistsScreen(::back,::playPlaylist,::saveQueuePlaylist,
                        playlistAddIds,playlistAddFilter,playlistSaveQueue,onAdded={playlistAddIds=emptyList();playlistAddFilter=null;playlistSaveQueue=false;back()})
                    PlayerScreen.FAVORITES,PlayerScreen.RECENT,PlayerScreen.MOST -> {
                        val view=com.evanna.player.user.PersonalView.valueOf(shown.name)
                        key(view){com.evanna.player.user.PersonalSongsScreen(view,::back){id,shuffle,complete->playPersonal(view,id,shuffle,complete)}}
                    }
                    PlayerScreen.OUTPUT, PlayerScreen.AUDIO_OUTPUT, PlayerScreen.LIBRARY_SETTINGS,
                    PlayerScreen.APPEARANCE, PlayerScreen.SETTINGS_TOOLS -> SettingsScreen(shown, ::back, dockInset, openNotices)
                    PlayerScreen.DECODE -> LocalDecodeScreen(onBack = ::back)
                    PlayerScreen.EQUALIZER -> com.evanna.player.equalizer.EqualizerScreen(::back,dockInset)
                    PlayerScreen.DIAGNOSTICS -> CrashReportsScreen(onBack = ::back)
                    PlayerScreen.NOTICES -> Column(Modifier.fillMaxSize()) {
                        val entry = licenseIndex?.let(licenseFiles::get)
                        // Hoisted so Back from a license restores the list's exact scroll spot.
                        val noticesScroll = rememberLazyListState()
                        ScreenHeader(entry?.title ?: "Open-source notices") { if (licenseIndex != null) licenseIndex = null else back() }
                        if (entry == null) LazyColumn(state = noticesScroll) {
                            items(licenseFiles) { file -> SettingsLink(file.title) { licenseIndex = licenseFiles.indexOf(file) } }
                        } else LicenseText(entry)
                    }
                } }
                val wantedOpen = screen == PlayerScreen.NOW_PLAYING
                val motion = remember { PlayerSheetMotion(wantedOpen) }
                val track by rememberUpdatedState(playerUi.id)
                val movingOrOpen by remember(motion) { derivedStateOf { motion.dragging || motion.progress > 0f } }
                val settledOpen by remember(motion) { derivedStateOf { !motion.dragging && motion.progress == 1f } }
                val flying by remember(motion) { derivedStateOf { motion.isFlying(track) } }
                val playerVisible = wantedOpen || movingOrOpen
                val modalOpen = showOutput || pending != null
                val focus = LocalFocusManager.current
                val softwareKeyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(wantedOpen, motion.dragging, motion.releases) { if (!motion.dragging) motion.settle(wantedOpen, track) }
                LaunchedEffect(look, playerVisible) { applySystemBars(look, playerVisible) }
                LaunchedEffect(wantedOpen, playerVisible) {
                    if (playerVisible) focus.clearFocus(force = true)
                    if (wantedOpen) softwareKeyboard?.hide()
                }
                val setPlayerOpen: (Boolean) -> Unit = { open ->
                    if (open) navigate(PlayerScreen.NOW_PLAYING)
                    else navigation = playerSheetDismissed(navigation)
                }
                val pushed = pushedScreen(navigation)
                val dockTab = selectedTab(navigation)
                // Scrolling a tab down folds the dock into one row; scrolling up, a new tab or a new screen opens it again.
                var dockFolded by remember(dockTab, pushed, playerUi.hasSong) { mutableStateOf(false) }
                val dockThreshold = with(LocalDensity.current) { 32.dp.toPx() }
                val dockScroll = remember(dockTab, pushed, playerUi.hasSong, dockThreshold) {
                    object : NestedScrollConnection {
                        private var distance = 0f

                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (source != NestedScrollSource.UserInput) return Offset.Zero
                            val dy = consumed.y.takeUnless { it == 0f } ?: available.y.coerceAtLeast(0f)
                            if (dy == 0f) return Offset.Zero
                            if (distance * dy < 0f) distance = 0f
                            distance += dy
                            when {
                                distance <= -dockThreshold -> { dockFolded = true; distance = 0f }
                                distance >= dockThreshold -> { dockFolded = false; distance = 0f }
                            }
                            return Offset.Zero
                        }
                    }
                }
                // The page content is recorded for the floating dock's glass to blur and bend; only the live tiers read it.
                val glassTier = glassTier(Build.VERSION.SDK_INT, liquidGlass, reduceTransparency)
                val pageColor = EvannaDesign.colorScheme.background
                // The recording starts from the page colour: a transparent gap would blur to black.
                val recordPage = remember(pageColor) { val draw: ContentDrawScope.() -> Unit = { drawRect(pageColor); drawContent() }; draw }
                val pageBackdrop = rememberLayerBackdrop(onDraw = recordPage)
                val glass = remember(glassTier, pageBackdrop, reduceTransparency, increaseContrast) {
                    GlassContext(glassTier, pageBackdrop.takeIf { glassTier.samplesBackdrop && !increaseContrast }, reduceTransparency,
                        increaseContrast)
                }
                // Controls everywhere read the tier, so pressed controls only lift into glass while glass is on.
                // Only the dock gets the recorded page to sample.
                val glassControls = remember(glassTier, reduceTransparency, increaseContrast) {
                    GlassContext(glassTier, null, reduceTransparency, increaseContrast)
                }
                CompositionLocalProvider(LocalGlass provides glassControls) {
                Surface(Modifier.fillMaxSize(), color = EvannaDesign.colorScheme.background) {
                Box(Modifier.fillMaxSize().onSizeChanged { motion.travelPx = it.height }) {
                    // The app under the sheet is never taken apart, so closing Now Playing finds the tab exactly as it was.
                    // While the sheet is open its contents are hidden from screen readers.
                    Box(Modifier.fillMaxSize().playerFocusScope(blocked = playerVisible)) {
                        Box(Modifier.fillMaxSize()
                            .then(if (glassTier.samplesBackdrop && !increaseContrast) Modifier.layerBackdrop(pageBackdrop) else Modifier)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .imePadding()) {
                            val covered = playerVisible || pushed != null
                            CompositionLocalProvider(LocalCovered provides covered) {
                                // Covered tabs stay silent to screen readers and Back, and never receive touches:
                                // empty gutters on a pushed screen or the sheet must not tap through to the tabs.
                                Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {}.blockTouches()
                                    else Modifier.nestedScroll(dockScroll))) {
                                    // The Carousel tab owns its horizontal drags.
                                    TabPager(baseTab(navigation), ::selectTab) { tab ->
                                        val insets = if (tab == PlayerScreen.OUTPUT)
                                            Modifier.statusBarsPadding().padding(horizontal = 16.dp) else Modifier
                                        Box(Modifier.fillMaxSize().background(screenBackground(tab)).then(insets)) { tabContent(tab) }
                                    }
                                }
                            }
                            // The pushed screen choreographs in and out; while it transitions, the exiting
                            // screen stays composed ~140 ms, so it is silenced (semantics) and made inert (touch),
                            // and only the visible top screen answers (only shown == pushed is interactive).
                            AnimatedContent(targetState = pushed, label = "pushed screen",
                                transitionSpec = { fadeIn(EvannaMotion.enter) togetherWith fadeOut(EvannaMotion.exit) },
                                modifier = Modifier.fillMaxSize()) { shown ->
                                if (shown == null) {
                                    // Returning to the tabs: nothing may cover or intercept them.
                                    Spacer(Modifier)
                                } else {
                                    val screenCovered = playerVisible || shown != pushed
                                    val blockTouches = playerVisible || pushed != null && shown != pushed
                                    CompositionLocalProvider(LocalCovered provides screenCovered) {
                                        Surface(Modifier.fillMaxSize()
                                            .then(if (screenCovered) Modifier.clearAndSetSemantics {} else Modifier)
                                            .then(if (blockTouches) Modifier.blockTouches() else Modifier),
                                            color = screenBackground(shown)) {
                                            // EQ and Settings reserve their own scroll space below the dock.
                                            val bottom = if (shown in settingsPages || shown == PlayerScreen.EQUALIZER) 0.dp else dockInset
                                            Box(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(bottom = bottom)) {
                                                savedScreens.SaveableStateProvider(shown.name) { pushedContent(shown) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Under see-through controls, lists fade into the page colour instead of running out from under them.
                        if (!keyboard && glassTier != GlassTier.SOLID && dockHeight > 0)
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(dockInset + 28.dp).background(floorBrush(pageColor)))
                        if (!keyboard) CompositionLocalProvider(LocalGlass provides glass) { PlayerDock(dockTab, ::selectTab,
                            listOf(Triple(PlayerScreen.CAROUSEL, "Carousel", PlayerIcons.AlbumCarousel),
                                Triple(PlayerScreen.LIBRARY, "Library", musicNote),
                                Triple(PlayerScreen.OUTPUT, "Settings", Icons.Filled.Settings)),
                            Icons.Filled.Search, playerUi.hasSong, dockFolded, { dockFolded = false },
                            Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight = it.height }
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { compact ->
                            MiniPlayer(playerUi, cover, { setPlayerOpen(true) }, ::togglePlayback,
                                onPrevious = { mediaController?.seekToPreviousMediaItem() },
                                onNext = { mediaController?.seekToNextMediaItem() },
                                onOutput = { openOutput(null) }, motion = motion, flying = flying, onOpenChange = setPlayerOpen, compact = compact)
                        } }
                    }
                    if (playerVisible) {
                        PlayerSheet(motion, cover, flying, wantedOpen && settledOpen, track, setPlayerOpen) {
                            savedScreens.SaveableStateProvider(PlayerScreen.NOW_PLAYING.name) { ProvideEvannaColors(playerColors) {
                                NowPlayingScreen(playerUi, cover, actionError, { motion.endPull(); setPlayerOpen(false) },
                                    { selectTab(PlayerScreen.LIBRARY) }, openOutput,
                                    ::togglePlayback, { mediaController?.seekToPreviousMediaItem() }, { mediaController?.seekToNextMediaItem() }, ::seekFromUi,
                                    { mediaController?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                                    { mediaController?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }, ::adjustUsbVolume, ::currentLyrics,
                                    ::setUsbVolume, ::chooseSleepTimer, ::chooseFavorite,
                                    onSelect = { editQueue(PlaybackService.SELECT_QUEUE, it) },
                                    onMove = { id, target -> editQueue(PlaybackService.MOVE_QUEUE, id, target) },
                                    onRemove = { editQueue(PlaybackService.REMOVE_QUEUE, it) },
                                    onSavePlaylist = { openPlaylists(saveQueue = true) }, loadArtwork = ::queueArtwork,
                                    motion = motion, flying = flying, wantedOpen = wantedOpen,
                                    outputSheetOpen = showOutput,
                                    onConvert = if (outputRoute == "DIRECT_USB" && usbPolicy == UsbPlaybackPolicy.STRICT &&
                                        playerUi.canPlayPause && !playing) ::playConvertedOnce else null)
                            } }
                        }
                    }
                    if (updatesEnabled) AppUpdatePrompt(uiSettings, installedVersion, foreground,
                        !modalOpen && !keyboard && !motion.dragging && !playerVisible &&
                            pushed == null && !LocalLibrary.managingFolders, ::openSourceLink)
                    UsbVolumeIndicator(usbKeyIndicator)
                    if (showOutput) ProvideEvannaColors(playerColors) {
                        AudioOutputSheet(
                            state = playerUi,
                            origin = outputOrigin,
                            onAndroid = ::selectAndroidOutput,
                            onUsbVolume = ::adjustUsbVolume,
                            onUsbVolumeSet = ::setUsbVolume,
                            onVolumeKey = { event ->
                                when (event.action) {
                                    KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                                    KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
                                    else -> false
                                }
                            },
                            onDismiss = { showOutput = false },
                        ) {
                            for (device in devices) {
                                val id = device.device.deviceId
                                val active = playerUi.route == "DIRECT_USB" && activeDacId == id
                                OutputRow(device.device.dacIdentity().displayName, when {
                                    active -> "Direct USB"
                                    pending?.deviceId == id -> "Waiting for USB access…"
                                    !device.permitted -> "Allow USB access"
                                    playing -> "Pause to switch to Direct USB"
                                    else -> "Direct USB"
                                }, PlayerIcons.Usb, selected = active,
                                    enabled = mediaController != null && !active && pending == null && !playing) {
                                    selectedId = id
                                    if (device.permitted) checkPlaybackReadiness() else requestAccess()
                                }
                            }
                        }
                    }
                }
                }
                }
            }
        }
        setContentView(composeView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAttachIntent(intent)
    }

    /**
     * Android's "open Evanna for this device" popup (or an earlier "always") starts the app with the DAC that was just plugged in.
     * The id only picks a device from Android's own list; a connection still needs access, a DAC used before and the switch.
     */
    private fun handleAttachIntent(received: Intent?) {
        val attach = received ?: return
        if (attach.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        @Suppress("DEPRECATION") val device = runCatching { attach.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) }.getOrNull() ?: return
        autoConnectId = device.deviceId
        // Starting or stopped: the id waits for onStart and the playback controls (see connectPlayback).
        if (mediaController != null) { refreshDevices(); connectPluggedDac() }
    }

    /** A DAC was just plugged in. Connect it and play when the switch is on and it is a DAC used before; otherwise leave a line in Settings and the tap. */
    private fun connectPluggedDac() {
        val id = autoConnectId ?: return
        if (mediaController == null) return
        autoConnectId = null
        val state = devices.firstOrNull { it.device.deviceId == id } ?: return
        if (id == activeDacId || id == autoConnectingId) return
        val eligible = shouldAutoConnect(autoConnectEnabled, sameDac(lastDac, state.device.dacIdentity()), state.permitted,
            alreadyOpen = false, busy = autoConnectingId != null || pending != null || playerUi.playing)
        if (!eligible) { message = "${state.device.dacIdentity().displayName} connected. Choose an output to use it."; return }
        selectedId = id
        autoConnectingId = id
        checkPlaybackReadiness(play = true)
    }

    private fun chooseAutoConnect(enabled: Boolean) {
        val saved = runCatching {
            getSharedPreferences("playback-settings", MODE_PRIVATE).edit().putBoolean(AUTO_CONNECT_DAC_PREFERENCE, enabled).commit()
        }.getOrDefault(false)
        if (saved) autoConnectEnabled = enabled else message = "The setting could not be saved. Check free storage and try again."
    }

    private fun keepDirectUsb() {
        val key = dacProfileKey ?: return
        val kept = keptDirectUsb + key
        val saved = runCatching {
            getSharedPreferences("playback-settings", MODE_PRIVATE).edit().putStringSet(KEEP_DIRECT_USB_PREFERENCE, kept).commit()
        }.getOrDefault(false)
        if (saved) keptDirectUsb = kept else message = "The setting could not be saved. Check free storage and try again."
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Background music keeps the process alive; large decoded covers should not stay with it.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) LocalLibrary.artwork(applicationContext).trimMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("playerScreens", ArrayList(navigation.map { it.name }))
        outState.putStringArrayList("playlistAddIds",ArrayList(playlistAddIds));outState.putBoolean("playlistSaveQueue",playlistSaveQueue)
        playlistAddFilter?.let {filter ->outState.putBundle("playlistAddFilter",Bundle().apply {
            putString("kind",filter.kind?.name);putString("value",filter.value);putString("artist",filter.albumArtist);putString("search",filter.search)
        })}
        super.onSaveInstanceState(outState)
    }

    private fun navigate(screen: PlayerScreen) { navigation = playerDestination(navigation, screen) }
    private fun selectTab(tab: PlayerScreen) { navigation = playerTab(tab, navigation.first()) }
    private fun back() { if (navigation.size > 1) navigation = navigation.dropLast(1) else finish() }
    private fun togglePlayback() {
        usbKeys.reset();usbKeyIndicator=null
        val controller = mediaController ?: return
        if (!controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return
        actionError = null
        if (controller.playWhenReady) controller.pause() else controller.play()
    }
    private fun seekFromUi(id: String?, positionMs: Long) {
        val controller = mediaController ?: return
        playbackSeekTarget(id, controller.currentMediaItem?.mediaId, positionMs, controller.duration.takeIf { it >= 0 },
            controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))?.let(controller::seekTo)
    }
    /** The private library/file reference behind a queue occurrence, from the app-only ARTWORK_SOURCE command. */
    private suspend fun sourceOf(controller: MediaController, id: String): Bundle? {
        if (mediaController !== controller) return null
        val future = controller.sendCustomCommand(SessionCommand(PlaybackService.ARTWORK_SOURCE, Bundle.EMPTY), Bundle().apply { putString(PlaybackService.QUEUE_ID, id) })
        val result = suspendCancellableCoroutine<SessionResult> { continuation ->
            future.addListener({ try { continuation.resume(future.get()) } catch (failure: Exception) { continuation.resumeWithException(failure) } }, Runnable::run)
            continuation.invokeOnCancellation { future.cancel(false) }
        }
        if (mediaController !== controller || result.resultCode != SessionResult.RESULT_SUCCESS || result.extras.getString(PlaybackService.QUEUE_ID) != id) return null
        return result.extras
    }
    private suspend fun currentArtwork(controller: MediaController, id: String): Bitmap? {
        val source = sourceOf(controller, id) ?: return null
        val cache = LocalLibrary.artwork(applicationContext)
        // One large cover serves both Now Playing and the mini player.
        return source.getString(PlaybackService.LIBRARY_ID)?.let { cache.song(it, ArtworkSize.LARGE) }
            ?: source.getString(PlaybackService.FILE_URI)?.let { cache.file(id, it, ArtworkSize.LARGE) }
    }
    /** A small cover for a queue row; null when there is none or the controls are gone. */
    private suspend fun queueArtwork(id: String): Bitmap? {
        val controller = mediaController ?: return null
        val source = sourceOf(controller, id) ?: return null
        val cache = LocalLibrary.artwork(applicationContext)
        return source.getString(PlaybackService.LIBRARY_ID)?.let { cache.song(it, ArtworkSize.SMALL) }
            ?: source.getString(PlaybackService.FILE_URI)?.let { cache.file(id, it, ArtworkSize.SMALL) }
    }
    /** Null when the song has no lyrics; throws when its file cannot be read or controls are disconnected. */
    private suspend fun currentLyrics(id: String): Lyrics? {
        val controller = mediaController ?: throw java.io.IOException("PLAYBACK_DISCONNECTED")
        val source = sourceOf(controller, id) ?: throw java.io.IOException("LYRICS_SOURCE_UNAVAILABLE")
        val lyrics = LocalLibrary.lyrics(applicationContext)
        return source.getString(PlaybackService.LIBRARY_ID)?.let { lyrics.song(applicationContext, it) }
            ?: source.getString(PlaybackService.FILE_URI)?.let { lyrics.file(applicationContext, id, it) }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        connectPlayback(SessionToken(this, ComponentName(this, PlaybackService::class.java)), forStartup = true)
        val permissionFilter = IntentFilter(permissionAction).apply { addDataScheme("evanna") }
        val deviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Permission replies carry a URI; system attach/detach broadcasts do not.
        for (filter in listOf(permissionFilter, deviceFilter)) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
        }
        refreshDevices()
    }

    internal fun connectPlayback(token: SessionToken, forStartup: Boolean = false) {
        releaseController()
        val future = MediaController.Builder(this, token).setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (mediaController !== controller) return
                releaseController()
                playbackMessage = "Playback controls disconnected. Reopen Evanna to reconnect."
                playerUi = playerUi.copy(error = playbackMessage)
            }
        }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture === future) {
                try {
                    val connected = future.get()
                    mediaController = connected
                    connected.addListener(playerListener)
                    refreshPlayback(connected)
                    dispatchLocalFile()
                    connectPluggedDac()
                    if (forStartup && outputRoute == "NONE" && autoConnectingId == null) {
                        selectAndroidOutput(false, onlyIfUnselected = true)
                    }
                } catch (_: Exception) {
                    releaseController()
                    playbackMessage = "Playback controls could not connect. Reopen Evanna to retry."
                    playerUi = playerUi.copy(error = playbackMessage)
                }
            }
        }, mainExecutor)
    }

    private fun releaseController() {
        usbKeys.reset();usbKeyIndicator=null
        activeDacId=null
        playAfterOpenUntil=0;autoConnectingId=null
        val previous = mediaController
        val future = controllerFuture
        controllerFuture = null
        mediaController = null
        selectionSequence++
        actionError = null
        playerUi = playerUi.copy(connected = false, playing = false, error = null)
        previous?.removeListener(playerListener)
        future?.let(MediaController::releaseFuture)
    }

    override fun onStop() {
        releaseController()
        autoConnectId = null
        usbKeyDowns.clear()
        unregisterReceiver(receiver)
        pending?.let {
            permissions.detach(it.deviceId)
            message = "USB access request was interrupted. Check the device status below, or try again."
        }
        pending = null
        super.onStop()
    }

    /**
     * Opens the selected DAC, paused. With [play] (a DAC plugged in that was used before) it carries on into playback as soon as
     * the opened output allows it, exactly as if Play were pressed: focus, Strict rules and the DAC's volume are still checked first.
     */
    private fun checkPlaybackReadiness(play: Boolean = false) {
        usbKeys.reset()
        usbKeyIndicator = null
        val controller = mediaController ?: return
        val id = selectedId ?: return
        val name = devices.firstOrNull { it.device.deviceId == id }?.device?.dacIdentity()?.displayName ?: "USB DAC"
        playbackMessage = "Opening the selected USB output…"
        val result = controller.sendCustomCommand(SessionCommand(PlaybackService.OPEN_USB, Bundle.EMPTY),
            Bundle().apply {
                putInt(PlaybackService.DEVICE_ID, id)
            })
        result.addListener({
            autoConnectingId = null
            if (mediaController === controller) {
                try {
                    val reply=result.get()
                    if (reply.resultCode == SessionResult.RESULT_SUCCESS) {
                        refreshDevices()
                        val remembered=reply.extras.getBoolean(PlaybackService.LAST_DAC_SAVED,true)
                        if(!remembered)message="The DAC opened, but its reconnect preference could not be saved."
                        // Playing is asked for after the refresh: it needs the opened output's play command, which can arrive a moment later.
                        if (play) playAfterOpenUntil = SystemClock.elapsedRealtime() + 8000
                        refreshPlayback(controller)
                        if (play && playAfterOpenUntil != 0L && !playerUi.hasSong) {
                            playAfterOpenUntil = 0
                            if (remembered) message = "$name connected. Choose a song to play."
                        }
                    } else {
                        playbackMessage = "The USB output could not be opened. Check access and reconnect if needed."
                        if (play) message = "$name could not be opened. Check access below, then press Use DAC."
                    }
                } catch (_: Exception) { playbackMessage = "The playback command failed. Reopen Evanna to retry." }
            }
        }, mainExecutor)
    }

    private fun selectLocalFile(uri: Uri) {
        pendingFile = uri
        dispatchLocalFile()
    }

    private fun dispatchLocalFile() {
        val uri = pendingFile ?: return
        val controller = mediaController ?: run {
            playbackMessage = "Waiting for playback controls to prepare the selected file…"
            return
        }
        pendingFile = null
        val selection = ++selectionSequence
        playbackMessage = "Preparing the selected local file…"
        val result = controller.sendCustomCommand(SessionCommand(PlaybackService.SELECT_FILE, Bundle.EMPTY),
            Bundle().apply { putString(PlaybackService.FILE_URI, uri.toString()) })
        result.addListener({
            if (mediaController === controller && selection == selectionSequence) try {
                if (result.get().resultCode != SessionResult.RESULT_SUCCESS)
                    playbackMessage = "The local file could not be selected. Check local storage access and try again."
                else { actionError = null; refreshPlayback(controller); navigate(PlayerScreen.NOW_PLAYING) }
            } catch (_: Exception) { playbackMessage = "File selection could not finish. Reopen Evanna and select it again." }
        }, mainExecutor)
    }

    private fun playConvertedOnce() {
        mediaController?.sendCustomCommand(SessionCommand(PlaybackService.PLAY_CONVERTED, Bundle.EMPTY), Bundle.EMPTY)
    }

    private fun adjustUsbVolume(direction:Int) = sendUsbVolume(PlaybackService.ADJUST_USB_VOLUME) { putInt(PlaybackService.VOLUME_DIRECTION,direction) }
    private fun chooseSleepTimer(mode:com.evanna.player.playback.SleepMode,minutes:Int) {
        val controller=mediaController ?: return
        val reply=controller.sendCustomCommand(SessionCommand(PlaybackService.SET_SLEEP_TIMER,Bundle.EMPTY),Bundle().apply {
            putString(PlaybackService.SLEEP_MODE,mode.name);putInt(PlaybackService.SLEEP_MINUTES,minutes)
        })
        reply.addListener({if(mediaController===controller) {
            if(runCatching {reply.get().resultCode}.getOrNull()!=SessionResult.RESULT_SUCCESS)actionError="Sleep timer could not be changed."
            refreshPlayback(controller)
        }},mainExecutor)
    }
    private fun openPlaylists(ids:List<String> = emptyList(),filter:com.evanna.player.library.LibraryFilter?=null,saveQueue:Boolean=false) {
        playlistAddIds=ids;playlistAddFilter=filter;playlistSaveQueue=saveQueue;navigate(PlayerScreen.PLAYLISTS)
    }
    private fun chooseFavorite(id:String,selected:Boolean) {
        val controller=mediaController ?: return
        val reply=controller.sendCustomCommand(SessionCommand(PlaybackService.SET_FAVORITE,Bundle.EMPTY),Bundle().apply {
            putString(PlaybackService.QUEUE_ID,id);putBoolean(EvannaPlayer.FAVORITE,selected)
        })
        reply.addListener({if(mediaController===controller && runCatching {reply.get().resultCode}.getOrNull()!=SessionResult.RESULT_SUCCESS)actionError="The favorite could not be changed."},mainExecutor)
    }
    private fun playPersonal(view:com.evanna.player.user.PersonalView,id:String?,shuffle:Boolean,complete:(Boolean)->Unit) {
        val controller=mediaController ?: run {complete(false);return}
        val reply=controller.sendCustomCommand(SessionCommand(PlaybackService.SELECT_LIBRARY_CONTEXT,Bundle.EMPTY),Bundle().apply {
            putString(PlaybackService.CONTEXT_KIND,view.name);putString(PlaybackService.START_ID,id)
            putString(PlaybackService.CONTEXT_ACTION,if(shuffle)"SHUFFLE" else if(id==null)"PLAY_FIRST" else "PLAY_FROM")
        })
        reply.addListener({val success=mediaController===controller && runCatching {reply.get().resultCode}.getOrNull()==SessionResult.RESULT_SUCCESS
            complete(success);if(success){refreshPlayback(controller);navigate(PlayerScreen.NOW_PLAYING)}},mainExecutor)
    }
    private fun playPlaylist(id:Long,revision:Long,shuffle:Boolean,index:Int,complete:(Boolean)->Unit) {
        val controller=mediaController ?: run {complete(false);return}
        val reply=controller.sendCustomCommand(SessionCommand(PlaybackService.SELECT_LIBRARY_CONTEXT,Bundle.EMPTY),Bundle().apply {
            putString(PlaybackService.CONTEXT_KIND,"PLAYLIST");putString(PlaybackService.CONTEXT_VALUE,id.toString())
            putLong(PlaybackService.PLAYLIST_REVISION,revision)
            putString(PlaybackService.CONTEXT_ACTION,if(shuffle)"SHUFFLE" else "PLAY_FIRST");putInt(PlaybackService.PLAYLIST_INDEX,index)
        })
        reply.addListener({val success=mediaController===controller && runCatching {reply.get().resultCode}.getOrNull()==SessionResult.RESULT_SUCCESS
            complete(success);if(success){refreshPlayback(controller);navigate(PlayerScreen.NOW_PLAYING)}},mainExecutor)
    }
    private fun saveQueuePlaylist(name:String,complete:(Boolean)->Unit) {
        val controller=mediaController ?: run {complete(false);return}
        val reply=controller.sendCustomCommand(SessionCommand(PlaybackService.SAVE_QUEUE_PLAYLIST,Bundle.EMPTY),Bundle().apply {putString(PlaybackService.PLAYLIST_NAME,name)})
        reply.addListener({complete(mediaController===controller && runCatching {reply.get().resultCode}.getOrNull()==SessionResult.RESULT_SUCCESS)},mainExecutor)
    }

    private fun setUsbVolume(db:Float,generation:Long) = sendUsbVolume(PlaybackService.SET_USB_VOLUME,generation) { putFloat(PlaybackService.VOLUME_DB,db) }

    private fun sendUsbVolume(action:String,generation:Long=playerUi.usbGeneration,args:Bundle.()->Unit) {
        usbKeys.reset()
        val controller=mediaController ?: return
        val result=controller.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),Bundle().apply {
            args();putLong(PlaybackService.VOLUME_GENERATION,generation)
        })
        result.addListener({if(mediaController===controller) {
            val success=try{result.get().resultCode==SessionResult.RESULT_SUCCESS}catch(_:Exception){false}
            actionError=if(success)null else "USB volume change could not be verified. Check the output before retrying."
            refreshPlayback(controller)
        }},mainExecutor)
    }

    private fun usbKeyContext():UsbKeyContext? {
        if(outputRoute!="DIRECT_USB" || !playerUi.playing || playerUi.error!=null || validVolumeLimit(volumeLimitDb)==null)return null
        val gain=playerUi.usbVolume ?: return null
        if(gain.maxDb>volumeLimitDb)return null
        return UsbKeyContext(playerUi.usbGeneration,gain,usbKeyStepDb).takeIf {it.valid()}
    }

    override fun onKeyUp(keyCode:Int,event:KeyEvent):Boolean =
        if(usbKeyDowns.remove(keyCode)!=null)true else super.onKeyUp(keyCode,event)

    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        val direction=when(keyCode) {KeyEvent.KEYCODE_VOLUME_UP->1;KeyEvent.KEYCODE_VOLUME_DOWN->-1;else->return super.onKeyDown(keyCode,event)}
        val context=usbKeyContext()
        val held=usbKeyDowns[keyCode]
        if(held!=null && (held.first!==mediaController || held.second!=context?.generation))return true
        if(context==null)return super.onKeyDown(keyCode,event)
        val controller=mediaController ?: return super.onKeyDown(keyCode,event)
        usbKeyDowns[keyCode]=controller to context.generation
        showUsbKeyVolume(usbKeys.confirmedGain(context))
        usbKeys.press(context,direction)?.let(::sendUsbKeyVolume)
        return true
    }

    private fun showUsbKeyVolume(db:Float) {usbKeyIndicator=db;usbKeyIndicatorRevision++}
    private fun sendUsbKeyVolume(request:UsbKeyRequest) {
        val controller=mediaController ?: run {usbKeys.reset();return}
        val future=controller.sendCustomCommand(SessionCommand(PlaybackService.SET_USB_VOLUME,Bundle.EMPTY),Bundle().apply {
            putLong(PlaybackService.VOLUME_GENERATION,request.generation);putFloat(PlaybackService.VOLUME_DB,request.db)
        })
        future.addListener({
            if(!usbKeys.isCurrent(request))return@addListener
            if(mediaController!==controller){usbKeys.reset();return@addListener}
            val result=runCatching {future.get()}.getOrNull()
            val extras=result?.extras
            val ack=if(result?.resultCode==SessionResult.RESULT_SUCCESS && extras?.getString(EvannaPlayer.OUTPUT_ROUTE)=="DIRECT_USB") {
                val gain=VolumeRange(extras.getFloat(EvannaPlayer.USB_VOLUME_MIN,Float.NaN),extras.getFloat(EvannaPlayer.USB_VOLUME_MAX,Float.NaN),
                    extras.getFloat(EvannaPlayer.USB_VOLUME_STEP,Float.NaN),extras.getFloat(EvannaPlayer.USB_VOLUME,Float.NaN))
                UsbKeyContext(extras.getLong(EvannaPlayer.USB_GENERATION,-1),gain,extras.getFloat(EvannaPlayer.USB_KEY_STEP,Float.NaN)).takeIf {it.valid()}
            } else null
            if(!usbKeys.isCurrent(request))return@addListener
            val confirmed=ack?.takeIf {usbKeyContext()?.controlsMatch(it)==true}
            if(confirmed!=null){actionError=null;showUsbKeyVolume(confirmed.gain.currentDb)} else usbKeyIndicator=null
            usbKeys.complete(request,confirmed)?.let(::sendUsbKeyVolume)
        },mainExecutor)
    }

    private fun libraryContext(request:com.evanna.player.library.LibraryContextRequest,complete:(Boolean)->Unit) {
        usbKeys.reset();usbKeyIndicator=null
        val controller=mediaController ?: run {complete(false);return}
        val selection=++selectionSequence
        val result=controller.sendCustomCommand(SessionCommand(PlaybackService.SELECT_LIBRARY_CONTEXT,Bundle.EMPTY),Bundle().apply {
            putString(PlaybackService.CONTEXT_KIND,request.filter.kind?.name ?: "SONGS")
            putString(PlaybackService.CONTEXT_VALUE,request.filter.value);putString(PlaybackService.ALBUM_ARTIST,request.filter.albumArtist)
            putString(PlaybackService.SEARCH,request.filter.search);putString(PlaybackService.START_ID,request.startId)
            putString(PlaybackService.CONTEXT_ACTION,request.action.name)
            putString(PlaybackService.SORT,request.filter.sort.name)
        })
        result.addListener({
            if(mediaController===controller && selection==selectionSequence) {
                val response=runCatching {result.get()}.getOrNull()
                val success=response?.resultCode==SessionResult.RESULT_SUCCESS
                if(success) {
                    actionError=null;refreshPlayback(controller)
                    val count=response!!.extras.getInt(PlaybackService.QUEUE_COUNT)
                    com.evanna.player.library.LocalLibrary.status=if(response.extras.getBoolean(PlaybackService.QUEUE_TRUNCATED))
                        "Queue limited to $count songs from this part of the list." else "Queued $count songs."
                    if(response.extras.getBoolean(PlaybackService.QUEUE_TRUNCATED))android.widget.Toast.makeText(this,
                        com.evanna.player.library.LocalLibrary.status,android.widget.Toast.LENGTH_LONG).show()
                }
                complete(success)
            }
        },mainExecutor)
    }

    private fun librarySong(id: String, append: Boolean, complete: (Boolean) -> Unit = {},next:Boolean=false) {
        val controller = mediaController ?: run { complete(false); return }
        val selection = if (append) null else ++selectionSequence
        val result = controller.sendCustomCommand(SessionCommand(if(next)PlaybackService.PLAY_NEXT_LIBRARY else if (append) PlaybackService.ENQUEUE_LIBRARY else PlaybackService.SELECT_LIBRARY, Bundle.EMPTY),
            Bundle().apply { putString(PlaybackService.LIBRARY_ID, id) })
        result.addListener({
            val success = try { result.get().resultCode == SessionResult.RESULT_SUCCESS } catch (_: Exception) { false }
            if (mediaController === controller && (selection == null || selection == selectionSequence)) {
                if (success) { actionError = null; refreshPlayback(controller) } else playbackMessage = "The library song could not be prepared. Check folder access and the queue."
                complete(success)
            }
        }, mainExecutor)
    }

    private fun editQueue(action: String, id: String, neighborId: String? = null) {
        val controller = mediaController ?: return
        val result = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle().apply {
            putString(PlaybackService.QUEUE_ID, id)
            if (neighborId != null) putString(PlaybackService.QUEUE_NEIGHBOR_ID, neighborId)
        })
        result.addListener({
            if (mediaController === controller) try {
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) { actionError = null; refreshPlayback(controller) }
                else { actionError = "The queue change could not finish. Check the selected song or close the output and retry."; playbackMessage = actionError!! }
            } catch (_: Exception) { actionError = "The queue command failed."; playbackMessage = actionError!! }
        }, mainExecutor)
    }

    private fun chooseUsbPolicy(policy: UsbPlaybackPolicy) {
        usbKeys.reset();usbKeyIndicator=null
        val controller = mediaController ?: return
        modeChanging = true
        val result = controller.sendCustomCommand(SessionCommand(PlaybackService.SET_USB_POLICY, Bundle.EMPTY),
            Bundle().apply { putString(PlaybackService.USB_POLICY, policy.name) })
        result.addListener({
            modeChanging = false
            if (mediaController === controller) try {
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) refreshPlayback(controller)
                else playbackMessage = "USB mode could not be saved. Close the output and retry."
            } catch (_: Exception) { playbackMessage = "The USB mode command failed. Reopen Evanna to check the saved mode." }
        }, mainExecutor)
    }

    /** Reread on refusal too: a saved ceiling may have stopped playback when USB application failed. */
    private fun chooseUsbVolumeSetting(action:String,db: Float,expectedProfileKey:String?,failed: () -> Unit) {
        usbKeys.reset()
        val controller = mediaController ?: return failed()
        val result = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY),
            Bundle().apply {putFloat(PlaybackService.VOLUME_DB,db);putString(EvannaPlayer.DAC_PROFILE_KEY,expectedProfileKey)})
        result.addListener({
            if (mediaController !== controller) failed()
            else try {
                val success=result.get().resultCode == SessionResult.RESULT_SUCCESS
                refreshPlayback(controller)
                if(!success){playbackMessage="The USB volume setting could not be saved or applied. Check the displayed value and output.";failed()}
            } catch (_: Exception) { playbackMessage = "The USB volume command failed. Reopen Evanna to check the setting."; failed() }
        }, mainExecutor)
    }

    private fun chooseMaxUsbRate(rate:Int?,expectedProfileKey:String?) {
        if(expectedProfileKey==null)return
        usbKeys.reset();usbKeyIndicator=null
        val controller = mediaController ?: return
        modeChanging = true
        val result = controller.sendCustomCommand(SessionCommand(PlaybackService.SET_MAX_USB_RATE, Bundle.EMPTY),
            Bundle().apply {putInt(PlaybackService.MAX_USB_RATE,rate ?: 0);putString(EvannaPlayer.DAC_PROFILE_KEY,expectedProfileKey)})
        result.addListener({
            modeChanging = false
            if (mediaController === controller) try {
                val success=result.get().resultCode==SessionResult.RESULT_SUCCESS
                refreshPlayback(controller)
                if(!success)playbackMessage="The DAC rate setting could not be saved. Check the displayed value and retry."
            } catch (_: Exception) { playbackMessage = "The song rate command failed. Reopen Evanna to check the setting." }
        }, mainExecutor)
    }

    private fun chooseLevelingMode(mode:com.evanna.player.loudness.LevelingMode,expectedProfileKey:String?) {
        if(expectedProfileKey==null)return
        val controller=mediaController ?: return
        modeChanging=true;usbKeys.reset();usbKeyIndicator=null
        val result=controller.sendCustomCommand(SessionCommand(PlaybackService.SET_LEVELING_MODE,Bundle.EMPTY),Bundle().apply {
            putString(EvannaPlayer.LEVELING_MODE,mode.name);putString(EvannaPlayer.DAC_PROFILE_KEY,expectedProfileKey)
        })
        result.addListener({
            modeChanging=false
            if(mediaController===controller)try {
                refreshPlayback(controller)
                if(result.get().resultCode==SessionResult.RESULT_SUCCESS) {
                    if(mode!=com.evanna.player.loudness.LevelingMode.OFF)com.evanna.player.loudness.LibraryLoudnessAnalysis.allowAutomatic()
                }else playbackMessage="The leveling setting could not be saved. Check the displayed mode and retry."
            }catch(_:Exception){playbackMessage="The leveling command failed. Reopen Evanna to check the setting."}
        },mainExecutor)
    }

    private fun chooseCrossfeed(level:com.evanna.player.equalizer.Crossfeed,expectedProfileKey:String?) {
        if(expectedProfileKey==null)return
        val controller=mediaController ?: return
        modeChanging=true
        val result=controller.sendCustomCommand(SessionCommand(PlaybackService.SET_CROSSFEED,Bundle.EMPTY),Bundle().apply {
            putString(EvannaPlayer.CROSSFEED,level.name);putString(EvannaPlayer.DAC_PROFILE_KEY,expectedProfileKey)
        })
        result.addListener({
            modeChanging=false
            if(mediaController===controller)try {
                refreshPlayback(controller)
                if(result.get().resultCode!=SessionResult.RESULT_SUCCESS)playbackMessage="The crossfeed setting could not be saved. Check the displayed choice and retry."
            }catch(_:Exception){playbackMessage="The crossfeed command failed. Reopen Evanna to check the setting."}
        },mainExecutor)
    }

    private fun selectAndroidOutput() = selectAndroidOutput(false)

    private fun selectAndroidOutput(bitPerfect: Boolean, onlyIfUnselected: Boolean = false) {
        if (!onlyIfUnselected) { usbKeys.reset(); usbKeyIndicator = null }
        val controller = mediaController ?: return
        val result = controller.sendCustomCommand(SessionCommand(PlaybackService.OPEN_ANDROID, Bundle.EMPTY),
            Bundle().apply {
                putBoolean(PlaybackService.ANDROID_BIT_PERFECT, bitPerfect)
                putBoolean(PlaybackService.ONLY_IF_UNSELECTED, onlyIfUnselected)
            })
        result.addListener({
            if (mediaController === controller) try {
                if (result.get().resultCode == SessionResult.RESULT_SUCCESS) refreshPlayback(controller)
                else if (!onlyIfUnselected || outputRoute == "NONE")
                    playbackMessage = "Android output could not be selected. Close the current output and retry."
            } catch (_: Exception) { playbackMessage = "The output command failed." }
        }, mainExecutor)
    }

    private fun refreshDevices() {
        try {
            lastDac=storedDacIdentity(getSharedPreferences("playback-settings",MODE_PRIVATE).all[LAST_DAC_PREFERENCE] as? String)
            devices = usbManager.deviceList.values
                .filter(UsbAudioOutput::isUsbAudioOutput)
                .sortedBy { it.deviceId }
                .map { DeviceState(it, usbManager.hasPermission(it)) }
            pending?.takeIf { request -> devices.none { it.device.deviceId == request.deviceId } }?.let {
                permissions.detach(it.deviceId)
                pending = null
                message = "The DAC was disconnected. Connect it and try again."
            }
            selectedId=preferredDacId(selectedId,devices.map {DacConnection(it.device.deviceId,it.device.dacIdentity(),it.permitted)},lastDac)
            androidBitPerfect=com.evanna.player.playback.androidBitPerfectAvailable(this)
        } catch (_: SecurityException) {
            devices = emptyList()
            selectedId = null
            pending?.let { permissions.detach(it.deviceId) }
            pending = null
            message = "Android could not list USB devices. Refresh to try again."
        }
    }

    private fun requestAccess() {
        val device = devices.firstOrNull { it.device.deviceId == selectedId }?.device ?: return
        try {
            if (usbManager.hasPermission(device)) {
                message = "USB access is already allowed."
                return
            }
            val request = permissions.begin(device.deviceId)
            CrashRecorder.event(DiagnosticEvent.PERMISSION_REQUEST, device.vendorId.toLong(), device.productId.toLong())
            pending = request
            val intent = Intent(permissionAction).setPackage(packageName)
                .setData(Uri.parse("evanna://usb-permission/${request.deviceId}/${request.token}"))
            val callback = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            message = "Waiting for USB access."
            usbManager.requestPermission(device, callback)
        } catch (_: SecurityException) {
            permissions.detach(device.deviceId)
            pending = null
            message = "Android refused the USB request. Refresh and try again."
        } catch (_: IllegalArgumentException) {
            permissions.detach(device.deviceId)
            pending = null
            message = "The DAC is no longer available. Refresh and try again."
        }

    }

    private val settingsPages = setOf(PlayerScreen.OUTPUT, PlayerScreen.AUDIO_OUTPUT, PlayerScreen.LIBRARY_SETTINGS,
        PlayerScreen.APPEARANCE, PlayerScreen.SETTINGS_TOOLS)

    /** Settings and EQ use the grouped background. Lists sit on the plain one. */
    @Composable
    private fun screenBackground(screen: PlayerScreen): Color =
        if (screen in settingsPages || screen in listOf(PlayerScreen.EQUALIZER, PlayerScreen.DIAGNOSTICS, PlayerScreen.DECODE, PlayerScreen.NOTICES))
            LocalLookStyle.current.groupedBackground else EvannaDesign.colorScheme.background

    /** Dark looks need light status and navigation bar icons; the light look needs dark ones. */
    private fun applySystemBars(look: EvannaLook, playerVisible: Boolean = false) {
        val bars = if (look.dark || playerVisible) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    private fun chooseLook(choice: EvannaLook) {
        look = choice
        getSharedPreferences(UI_SETTINGS, MODE_PRIVATE).edit().putString(LOOK_PREFERENCE, choice.name).apply()
    }

    private fun openSourceLink(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No browser is installed to open the source link.", Toast.LENGTH_LONG).show()
        false
    }

    @Composable
    private fun SettingsScreen(page: PlayerScreen, onBack: (() -> Unit)?, bottomInset: Dp, onNotices: () -> Unit) {
        val muted = EvannaDesign.colorScheme.onSurfaceVariant
        var settingsQuery by rememberSaveable { mutableStateOf("") }
        var showMusicFolders by rememberSaveable { mutableStateOf(false) }
        val profile=dacProfileKey
        val title = when (page) {
            PlayerScreen.AUDIO_OUTPUT -> "Audio output and USB"
            PlayerScreen.LIBRARY_SETTINGS -> "Library settings"
            PlayerScreen.APPEARANCE -> "Appearance"
            PlayerScreen.SETTINGS_TOOLS -> "Diagnostics and notices"
            else -> "Settings"
        }
        // iOS inset-grouped Settings: each item is one group with its header above and its notes below.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + bottomInset)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onBack != null) ScreenHeader(title, onBack)
                    else Text("Settings", style = EvannaDesign.typography.headlineSmall, fontSize = 34.sp,
                        lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 24.dp).semantics { heading() })
                    if (page == PlayerScreen.OUTPUT) SearchField(settingsQuery, { settingsQuery = it.take(120) }, "Search settings",
                        Modifier.fillMaxWidth(), clearLabel = "Clear settings search")
                }
            }
            if (page == PlayerScreen.OUTPUT) {
                val query = settingsQuery.trim()
                val sections = listOf(
                    "Audio" to listOf(
                        SettingsEntry("Audio output and USB", null, routeLabel(outputRoute), PlayerScreen.AUDIO_OUTPUT, PlayerIcons.VolumeUp, TilePink),
                        SettingsEntry("Equalizer", "Graphic and parametric, on every output", null, PlayerScreen.EQUALIZER, PlayerIcons.Pipeline, TileOrange),
                    ),
                    "App" to listOf(
                        SettingsEntry("Library settings", "Manage folders and open files", null, PlayerScreen.LIBRARY_SETTINGS, musicNote, TilePurple),
                        SettingsEntry("Appearance", null, look.label, PlayerScreen.APPEARANCE, PlayerIcons.Appearance, TileBlue),
                        SettingsEntry("Diagnostics and notices", "Signal status, file checks and licenses", null, PlayerScreen.SETTINGS_TOOLS,
                            PlayerIcons.Pulse, TileGray),
                    ),
                ).map { (heading, rows) -> heading to rows.filter { it.matches(query) } }.filter { it.second.isNotEmpty() }
                if (sections.isEmpty()) item { Text("No matching settings", color = muted, modifier = Modifier.padding(horizontal = 16.dp)) }
                for ((heading, rows) in sections) item(key = heading) {
                    SettingsSection(heading, separatorInset = SettingsTileInset) {
                        for (entry in rows) row(entry.target) {
                            SettingsLink(entry.label, entry.note, entry.icon, entry.tile, entry.value) { navigate(entry.target) }
                        }
                    }
                }
                item(key = "public-repository") {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = { openSourceLink(EVANNA_REPOSITORY) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(muted),
                        ) { Text("Evanna on GitHub") }
                    }
                }
            }
            if (page == PlayerScreen.AUDIO_OUTPUT) {
                item(key = "dac") {
                    SettingsSection("Check your DAC", footer = "Direct USB sends music to your DAC and checks its volume before playback.",
                        choices = true) {
                        row("status") { SettingsCell { Text(message, style = RowTitleStyle) } }
                        if (suggestAndroidAudio(outputRoute, usbDrops, dacProfileKey, keptDirectUsb)) {
                            row("drops") { SettingsCell {
                                Text("This phone's USB lost ${usbDrops?.second} packets of sound, which can sound like slight distortion. " +
                                    "Android audio may sound cleaner on this phone${if (androidBitPerfect) "." else ", but it converts the audio."}",
                                    style = EvannaDesign.typography.bodyMedium)
                            } }
                            row("drops-android") {
                                ActionRow("Use Android audio", enabled = mediaController != null) { selectAndroidOutput(androidBitPerfect) }
                            }
                            row("drops-keep") { ActionRow("Keep Direct USB", onClick = ::keepDirectUsb) }
                        }
                        row("refresh") { ActionRow("Refresh devices", onClick = ::refreshDevices) }
                        if (devices.isEmpty()) row("none") { SettingsCell {
                            Text("No USB audio device found. Connect your DAC, then refresh.", style = RowNoteStyle, color = muted)
                        } }
                        for (state in devices) row(state.device.deviceId) {
                            CheckRow(state.device.dacIdentity().displayName, selectedId == state.device.deviceId, enabled = pending == null,
                                note = "VID %04X · PID %04X".format(state.device.vendorId, state.device.productId),
                                extra = listOf("Connection ${state.device.deviceId}", if (state.permitted) "Access allowed" else "Access needed"),
                            ) { selectedId = state.device.deviceId }
                        }
                        row("access") {
                            ActionRow(if (pending != null) "Waiting for access…" else "Allow USB access",
                                enabled = selectedId != null && pending == null, onClick = ::requestAccess)
                        }
                        row("use") {
                            ActionRow(if (activeDacId != null && activeDacId == selectedId) "DAC selected" else "Use DAC",
                                enabled = mediaController != null && !playing && devices.any { it.device.deviceId == selectedId && it.permitted } &&
                                    activeDacId != selectedId, onClick = ::checkPlaybackReadiness)
                        }
                    }
                }
                item(key = "auto-connect") {
                    SettingsSection(footer = "Resume your queue at the saved starting volume on a known DAC. New DACs ask first.") {
                        row { SwitchRow("Connect and play when plugged in", checked = autoConnectEnabled, onChange = ::chooseAutoConnect) }
                    }
                }
                item(key = "route") {
                    val notes = listOfNotNull(
                        when (outputRoute) {
                            "ANDROID" -> "Android audio uses system volume and may change the audio."
                            "DIRECT_USB" -> "Direct USB uses DAC volume and bypasses Android's mixer."
                            else -> "No output selected"
                        },
                        // Shown only on Android 14+ when a USB output lists a bit-perfect mixer; never chosen automatically.
                        if (androidBitPerfect) "Android bit-perfect plays supported integer audio unchanged through Android's USB mixer. " +
                            "System volume leaves samples unchanged. Hardware verification is pending." else null,
                        "Close output clears the saved queue.",
                    )
                    SettingsSection("Playback route", footer = notes.joinToString("\n\n")) {
                        row("message") { SettingsCell { Text(playbackMessage, style = RowTitleStyle) } }
                        row("android") { ActionRow("Use Android audio", enabled = mediaController != null) { selectAndroidOutput() } }
                        if (androidBitPerfect) row("bit-perfect") {
                            ActionRow("Use Android bit-perfect (Android 14+)", enabled = mediaController != null) { selectAndroidOutput(true) }
                        }
                        row("close") { ActionRow("Close output", enabled = mediaController != null, destructive = true) { mediaController?.stop() } }
                    }
                }
                item(key = "mode") {
                    SettingsSection("USB playback mode", choices = true, footer = "Strict USB plays audio only when it can stay unchanged. " +
                        "Compatibility USB converts audio when needed. After changing modes, press Play to resume.") {
                        for (policy in UsbPlaybackPolicy.entries) row(policy) {
                            CheckRow(if (policy == UsbPlaybackPolicy.STRICT) "Strict USB" else "Compatibility USB", usbPolicy == policy,
                                enabled = mediaController != null && !modeChanging) { chooseUsbPolicy(policy) }
                        }
                    }
                }
                item(key = "rate:$profile") {
                    SettingsSection("Max output rate", choices = true,
                        footer = (dacProfileName?.let { "Saved DAC: $it. " } ?: "Select a DAC to set its volume and rate. ") +
                            "Limits the sample rate for this DAC. In Compatibility USB, higher rates can be converted. Press Play after changing the limit.") {
                        for (rate in listOf<Int?>(null, 48000, 96000, 192000)) row(rate ?: 0) {
                            CheckRow(if (rate == null) "Song rate" else "${rate / 1000} kHz", maxUsbRate == rate,
                                enabled = mediaController != null && profile != null && !modeChanging) { chooseMaxUsbRate(rate, profile) }
                        }
                    }
                }
                item(key = "start:$profile") {
                    var draft by remember(startVolumeDb) { mutableFloatStateOf(startVolumeDb) }
                    SettingsSection("USB starting volume", footer = "Sets the DAC volume when you connect, up to the Max USB volume.") {
                        row { SettingsSliderCell(draft, USB_START_VOLUME_MIN_DB..0f, "${draft.toInt()} dB", "USB starting volume",
                            enabled = mediaController != null && profile != null, onChange = { draft = it.roundToInt().toFloat() }) {
                            chooseUsbVolumeSetting(PlaybackService.SET_USB_START_VOLUME, draft, profile) { draft = startVolumeDb }
                        } }
                    }
                }
                item(key = "limit:$profile") {
                    var draft by remember(volumeLimitDb) { mutableFloatStateOf(volumeLimitDb) }
                    SettingsSection("Max USB volume", footer = "Caps the starting volume and USB volume controls. " +
                        "Playback stops if the DAC can't lower its volume to the limit.") {
                        row { SettingsSliderCell(draft, USB_START_VOLUME_MIN_DB..0f, "${draft.toInt()} dB", "Max USB volume",
                            enabled = mediaController != null && profile != null, onChange = { draft = it.roundToInt().toFloat() }) {
                            chooseUsbVolumeSetting(PlaybackService.SET_USB_VOLUME_LIMIT, draft, profile) { draft = volumeLimitDb }
                        } }
                    }
                }
                item(key = "step:$profile") {
                    val steps = listOf(0.5f, 1f, 2f, 3f)
                    SettingsSection("USB volume step", footer = "Sets how much the USB volume changes per press. Your DAC determines the exact step.") {
                        row { SettingsCell(vertical = 4.dp) {
                            SingleChoiceSegmentedButtonRow(steps.map { "${it.toString().removeSuffix(".0")} dB" }, steps.indexOf(usbKeyStepDb),
                                { chooseUsbVolumeSetting(PlaybackService.SET_USB_KEY_STEP, steps[it], profile) {} },
                                enabled = mediaController != null && profile != null)
                        } }
                    }
                }
                item(key = "leveling:$profile") {
                    val modes = com.evanna.player.loudness.LevelingMode.entries
                    SettingsSection("Volume leveling", footer = "Evens out loudness in Direct USB using DAC volume, up to the Max USB volume. " +
                        "Press Play after changing modes.\n\nEvanna measures untagged albums offline. New measurements apply the next time you start playback.") {
                        row { SettingsCell(vertical = 4.dp) {
                            SingleChoiceSegmentedButtonRow(modes.map { mode -> mode.name.lowercase().replaceFirstChar { it.uppercase() } },
                                modes.indexOf(levelingMode), { chooseLevelingMode(modes[it], profile) },
                                enabled = mediaController != null && profile != null && !modeChanging)
                        } }
                    }
                }
                item(key = "loudness") { com.evanna.player.loudness.LoudnessAnalysisControls() }
                item(key = "crossfeed:$profile") {
                    val levels = com.evanna.player.equalizer.Crossfeed.entries
                    SettingsSection("Crossfeed", footer = "Blends left and right slightly for a softer stereo effect in headphones. Direct USB only. " +
                        "Strict USB asks before changing the audio. Press Play after changing the setting.") {
                        row { SettingsCell(vertical = 4.dp) {
                            SingleChoiceSegmentedButtonRow(levels.map { it.label }, levels.indexOf(crossfeed), { chooseCrossfeed(levels[it], profile) },
                                enabled = mediaController != null && profile != null && !modeChanging)
                        } }
                    }
                }
            }
            if (page == PlayerScreen.LIBRARY_SETTINGS) item {
                SettingsSection("Local music") {
                    row { SettingsLink("Manage music folders") { showMusicFolders = true } }
                    row { SettingsLink("Open music library") { selectTab(PlayerScreen.LIBRARY) } }
                    row { ActionRow("Choose local file", enabled = mediaController != null) {
                        filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        })
                    } }
                }
            }
            if (page == PlayerScreen.APPEARANCE) {
                item(key = "look") {
                    SettingsSection("Look", footer = "Sets the app's colours and shapes.", choices = true) {
                        for (choice in EvannaLook.entries) row(choice) { CheckRow(choice.label, look == choice) { chooseLook(choice) } }
                    }
                }
                item(key = "glass") {
                    val tier = glassTier(Build.VERSION.SDK_INT, liquidGlass, reduceTransparency)
                    SettingsSection(footer = when {
                        increaseContrast -> "Controls are drawn solid, with a clear edge."
                        tier == GlassTier.FULL -> "This phone shows full glass: blur and a bent edge."
                        tier == GlassTier.BLUR -> "This phone shows blurred glass. The bent edge needs Android 13."
                        tier == GlassTier.FROSTED && !reduceTransparency && liquidGlass -> "This phone shows frosted glass. Live blur needs Android 12."
                        else -> "Controls are drawn solid."
                    }) {
                        row { SwitchRow("Liquid glass", "See-through tab bar and mini player.", liquidGlass) { on ->
                            liquidGlass = on
                            getSharedPreferences(UI_SETTINGS, MODE_PRIVATE).edit().putBoolean(GLASS_PREFERENCE, on).apply()
                        } }
                        row { SwitchRow("Reduce transparency", "Keeps the glass frosted and solid behind text.", reduceTransparency,
                            enabled = liquidGlass) { on ->
                            reduceTransparency = on
                            getSharedPreferences(UI_SETTINGS, MODE_PRIVATE).edit().putBoolean(REDUCE_TRANSPARENCY_PREFERENCE, on).apply()
                        } }
                        row { SwitchRow("Increase contrast", "Solid black or white controls with a clear edge.", increaseContrast) { on ->
                            increaseContrast = on
                            getSharedPreferences(UI_SETTINGS, MODE_PRIVATE).edit().putBoolean(INCREASE_CONTRAST_PREFERENCE, on).apply()
                        } }
                    }
                }
                item(key = "start") {
                    SettingsSection("Start page", footer = "Opens on the next app launch.", choices = true) {
                        for (choice in startPages) row(choice) {
                            CheckRow(if (choice == PlayerScreen.CAROUSEL) "Carousel" else "Library", startPage == choice) {
                                startPage = choice
                                getSharedPreferences(UI_SETTINGS, MODE_PRIVATE).edit().putString(START_PAGE_PREFERENCE, choice.name).apply()
                            }
                        }
                    }
                }
                item(key = "speed") {
                    SettingsSection("Animation speed", choices = true) {
                        for (choice in AnimationSpeed.entries) row(choice) {
                            CheckRow(choice.label, motionScale.speed == choice) {
                                motionScale.speed = choice
                                getSharedPreferences(UI_SETTINGS, MODE_PRIVATE).edit().putString(ANIMATION_SPEED_PREFERENCE, choice.name).apply()
                            }
                        }
                    }
                }
            }
            if (page == PlayerScreen.SETTINGS_TOOLS) {
                item(key = "signal") {
                    SettingsSection("Signal status") { row { SettingsCell { Text(signalStatus, style = EvannaDesign.typography.bodyMedium) } } }
                }
                item(key = "tools") {
                    SettingsSection("App tools") {
                        row { SettingsLink("Diagnostics and crash reports") { navigate(PlayerScreen.DIAGNOSTICS) } }
                        row { SettingsLink("Check a local audio file") { navigate(PlayerScreen.DECODE) } }
                        row { SettingsLink("Open-source notices", onClick = onNotices) }
                    }
                }
            }
        }
        if (page == PlayerScreen.LIBRARY_SETTINGS && showMusicFolders) MusicFoldersDialog { showMusicFolders = false }
    }

    @Composable
    private fun ColumnScope.LicenseText(file: LicenseFile) {
        val text by produceState("Loading…", file.asset) {
            value = withContext(Dispatchers.IO) {
                try { assets.open(file.asset).bufferedReader().use { it.readText() } }
                catch (_: java.io.IOException) { "This notice could not be read. Please reinstall this build." }
            }
        }
        file.source?.let { source ->
            TextButton(onClick = {
                openSourceLink(source)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open component source") }
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(text, style = EvannaDesign.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

private data class DeviceState(val device: UsbDevice, val permitted: Boolean)
/** A row on the main Settings page: a [note] under the label or a [value] at the end, and its icon tile. */
private class SettingsEntry(val label: String, val note: String?, val value: String?, val target: PlayerScreen,
    val icon: androidx.compose.ui.graphics.vector.ImageVector, val tile: Color) {
    fun matches(query: String) = listOfNotNull(label, note, value).any { it.contains(query, ignoreCase = true) }
}
private data class LicenseFile(val title: String, val asset: String, val source: String? = null)
private val licenseFiles = listOf(
    LicenseFile("Evanna · GPL 3.0", "licenses/Evanna-GPL-3.0.txt"),
    LicenseFile("libebur128 · MIT", "licenses/libebur128-MIT.txt", "https://github.com/jiixyj/libebur128/tree/67b33abe1558160ed76ada1322329b0e9e058b02"),
    LicenseFile("libebur128 queue · BSD 3-Clause", "licenses/libebur128-queue-BSD-3-Clause.txt"),
    LicenseFile("C++ runtime · LLVM notices", "licenses/NDK-libcxx.txt", "https://developer.android.com/ndk/downloads"),
    LicenseFile("Components and attribution", "third-party-notices.txt"),
    LicenseFile("Apache License 2.0", "licenses/Apache-2.0.txt", "https://github.com/devang-altf4/truesample-player/tree/cf23a00030cd2d27c91f667737f0ca9aa30256a4/usbaudio"),
    LicenseFile("libusb · LGPL 2.1", "licenses/LGPL-2.1.txt", "https://github.com/libusb/libusb/tree/v1.0.29"),
    LicenseFile("libsamplerate · BSD 2-Clause", "licenses/libsamplerate-BSD-2-Clause.txt", "https://github.com/libsndfile/libsamplerate/tree/0.2.2"),
    LicenseFile("FFmpeg 9.0.2 · LGPL 2.1 or later", "licenses/FFmpeg.txt", "https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz"),
)
