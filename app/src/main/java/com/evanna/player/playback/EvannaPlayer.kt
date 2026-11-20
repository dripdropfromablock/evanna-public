package com.evanna.player.playback

import android.os.Looper
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import com.evanna.player.user.DacProfile
import com.evanna.player.casting.DlnaRendererClient
import com.evanna.player.casting.RemotePlaybackSession
import com.evanna.player.casting.RendererPhase
import com.evanna.player.network.CancelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@UnstableApi
internal class EvannaPlayer(private val owner: UsbSessionOwner, private val remote: RemotePlaybackSession? = null) :
    SimpleBasePlayer(Looper.getMainLooper()) {
    private val artwork=SessionArtwork()
    private var favoriteId:String?=null
    private var favorite:Boolean?=null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val routeLane = remote?.commands ?: Mutex()
    private var choosingRenderer = false
    private var rendererSelection: CancelScope? = null
    private var released = false
    private var rendererIntent = 0L
    private val observer = scope.launch {
        combine(owner.snapshot, owner.queue,owner.sleepTimer,owner.sourcePreparing) { snapshot, _,_,_ -> snapshot }.collect {
            if(!owner.sourcePreparing.value && remote?.selected != true)owner.onPlaybackState(it)
            invalidateState()
        }
    }
    private val remoteObserver = remote?.let { output -> scope.launch {
        output.state.collect { invalidateState() }
    } }
    private val remotePoller = remote?.let { output -> scope.launch {
        output.state.map { it != null && it.phase != RendererPhase.UNREACHABLE }.distinctUntilChanged().collectLatest { active ->
            if (active) while (isActive) {
                delay(if (output.state.value?.phase in setOf(RendererPhase.PLAYING, RendererPhase.STARTING)) 1500 else 4000)
                routeLane.withLock {
                    if (released || !output.selected) return@withLock
                    val intent = rendererIntent
                    val ended = output.poll()
                    saveRemotePosition()
                    if (ended && intent == rendererIntent) {
                        val queue = owner.queue.value
                        val next = queue.navigationIndex(1, automatic = true)
                        if (next >= 0 && owner.selectQueue(queue.items[next].id))
                            playRemoteCurrent(reset = true, automaticIntent = intent)
                    }
                }
            }
        }
    } }

    override fun getState(): State {
        val snapshot = owner.snapshot.value
        val remoteNow = remote?.state?.value
        val track = owner.selectedTrack.takeIf { remoteNow == null }
        val queue = owner.queue.value
        artwork.select(queue.current?.id)
        val opened = (snapshot.route != null && snapshot.state != UsbPlaybackState.CLOSED) || remoteNow != null
        val route = if (remoteNow != null) DLNA_ROUTE else snapshot.route?.name ?: "NONE"
        val seekable = remoteNow?.canSeek ?: (track?.seekable == true && !owner.sourceSelectionFailed)
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_RELEASE, Player.COMMAND_STOP,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_REPEAT_MODE,Player.COMMAND_SET_SHUFFLE_MODE)
        val canStartOnAndroid = snapshot.route == null && !owner.sourceSelectionFailed && (track != null || queue.current != null)
        if (remoteNow == null && (opened || canStartOnAndroid) || remoteNow != null && queue.current != null &&
            (!remoteNow.playing || remoteNow.canPause)) commands.add(Player.COMMAND_PLAY_PAUSE)
        if (seekable) commands.addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS)
        if (queue.items.isNotEmpty()) commands.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        if (queue.navigationIndex(-1)>=0) commands.addAll(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS)
        if (queue.navigationIndex(1)>=0) commands.addAll(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT)
        val problem = when {
            remoteNow != null -> null
            owner.sourceSelectionFailed -> "The track could not be opened. Check its source connection or access, then select it again."
            owner.focusDenied -> "Audio focus was not granted. Try again with Evanna visible."
            snapshot.failure == UsbPlaybackFailure.VOLUME_UNVERIFIED -> "USB volume could not be verified. A controllable, unmuted DAC is required for music; analysis signals require measured calibration."
            // Crossfeed alone no longer stops Strict; this is a rate or precision change, or a compressed file.
            snapshot.failure == UsbPlaybackFailure.CONVERSION_REQUIRED ->
                "Strict playback is unavailable. Choose Compatibility USB, or Play converted once for this play."
            snapshot.failure == UsbPlaybackFailure.ROUTE_CHANGED -> "The audio output changed. Press Play to try again or choose an output."
            snapshot.failure != null -> "Playback stopped: ${snapshot.failure.name.lowercase().replace('_', ' ')}."
            else -> null
        }
        val signalStatus = remoteNow?.label ?: playbackSignalStatus(snapshot)
        val deviceVolume=usbDeviceVolume(snapshot).takeIf { remoteNow == null }
        if(deviceVolume!=null) {
            commands.add(Player.COMMAND_GET_DEVICE_VOLUME)
            if(deviceVolume.scale.positions>1)commands.addAll(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        }
        if (remoteNow?.volume != null) commands.addAll(Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        val queueStatus=when {
            queue.restoreFailed -> "The saved queue could not be restored. It was kept on disk; choose a new song or Close output to replace it."
            queue.saveFailed -> "Queue changes could not be saved. Free storage and try again; playback can continue in this session."
            queue.historySaveFailed -> "Play history could not be saved. Free storage and retry; playback can continue."
            remoteNow != null -> signalStatus
            queue.items.isNotEmpty() && track==null -> "Saved queue restored paused. Press Play to resume. Unavailable sources stay in the queue."
            track!=null && snapshot.route==null -> "Song selected. Press Play to listen through Android audio."
            else -> "Queue and estimated position are saved on this device."
        }
        val sleepLabel=owner.sleepTimer.value.label
        val currentFavorite = favorite.takeIf { favoriteId != null && favoriteId == queue.current?.id }
        val extras = Bundle().apply {
            putString(OUTPUT_ROUTE, route)
            putString(RENDERER_NAME, remoteNow?.name)
            putInt(USB_DEVICE_ID,if(opened && snapshot.state!=UsbPlaybackState.ERROR && snapshot.route==PlaybackRoute.DIRECT_USB)snapshot.deviceId ?: -1 else -1)
            putString(USB_POLICY, snapshot.usbPolicy.name)
            putBoolean(FOLLOW_SONG_RATE, snapshot.followSongRate)
            putInt(MAX_USB_RATE,snapshot.maxUsbRate ?: 0)
            putString(DAC_PROFILE_KEY,snapshot.dacProfileKey);putString(DAC_PROFILE_NAME,snapshot.dacProfileName)
            putFloat(USB_START_VOLUME, snapshot.startVolumeDb)
            putFloat(USB_VOLUME_LIMIT,snapshot.volumeLimitDb)
            putFloat(USB_KEY_STEP,snapshot.usbKeyStepDb)
            putString(LEVELING_MODE,snapshot.levelingMode.name)
            putFloat(LEVELING_OFFSET,snapshot.leveling.offsetDb)
            putString(LEVELING_EVIDENCE,snapshot.leveling.evidence)
            putBoolean(EQ_PROCESSED,snapshot.eqProcessed)
            putString(CROSSFEED,snapshot.crossfeed.name)
            putFloat(EQ_PREAMP,snapshot.eqPreampDb)
            snapshot.appliedGainDb?.let {putFloat(USB_APPLIED_GAIN,it)}
            if (snapshot.route == PlaybackRoute.DIRECT_USB) snapshot.stats?.let { putLong(USB_DROPPED, it.droppedSinceOpen) }
            putString(SIGNAL_STATUS, signalStatus)
            putString(SLEEP_LABEL,sleepLabel)
            if (currentFavorite != null) {
                putBoolean(FAVORITE_AVAILABLE, true)
                putBoolean(FAVORITE, currentFavorite)
            }
            val stages=if (remoteNow == null) signalSummary(snapshot)+signalStages(snapshot) else emptyList()
            putStringArrayList(SIGNAL_LABELS,ArrayList(stages.map {it.label}))
            putStringArrayList(SIGNAL_VALUES,ArrayList(stages.map {it.value}))
            putBooleanArray(SIGNAL_CHANGED,stages.map {it.changed}.toBooleanArray())
            putBooleanArray(SIGNAL_BRIEF,stages.map {it.brief}.toBooleanArray())
            val path=soundPath(snapshot)
            putString(SOUND_VERDICT,remoteNow?.let { "DLNA · ${it.name}" } ?: path.verdict)
            putString(SOUND_WHY,remoteNow?.let { "${it.label}. Phone EQ and direct USB are inactive." } ?: path.why)
            putBoolean(SOUND_PLAYING,remoteNow?.playing ?: path.playing)
            putBoolean(SOUND_OFFER_OUTPUT,remoteNow != null || path.offerOtherOutput)
            putStringArrayList(SOUND_STOPS,ArrayList(if (remoteNow == null) path.stops.map(::encodeStop) else emptyList()))
            putLong(USB_GENERATION,snapshot.generation)
            snapshot.usbVolume?.let {
                putFloat(USB_VOLUME,it.currentDb);putFloat(USB_VOLUME_MIN,it.minDb)
                putFloat(USB_VOLUME_MAX,it.maxDb);putFloat(USB_VOLUME_STEP,it.stepDb)
            }
            putString(QUEUE_STATUS,queueStatus)
            putBoolean(QUEUE_WARNING, queue.saveFailed || queue.restoreFailed || queue.historySaveFailed)
            putString(SOURCE_QUALITY, track?.format?.let {
                "${it.codec} · ${it.sampleRate} Hz · ${it.sourceBits?.let { bits -> "$bits-bit" } ?: "processed PCM"}"
            } ?: "")
        }
        val state = State.Builder().setAvailableCommands(commands.build())
            .setShuffleModeEnabled(queue.shuffleEnabled).setRepeatMode(queue.repeat.ordinal)
            // MediaMetadata equality ignores extras contents; semantic fields must change too.
            .setPlaylistMetadata(MediaMetadata.Builder().setTitle(remoteNow?.let { "DLNA · ${it.name}" }
                ?: "USB mode: ${snapshot.usbPolicy.name} · follow song rate: ${snapshot.followSongRate} · start volume: ${snapshot.startVolumeDb} dB · limit: ${snapshot.volumeLimitDb} dB · step: ${snapshot.usbKeyStepDb} dB")
                .setDescription("$signalStatus\n$queueStatus\nStream revision: ${snapshot.generation}\n$sleepLabel\nFavorite: $currentFavorite")
                .setExtras(extras).build())
            .setContentPositionMs(PositionSupplier { remote?.state?.value?.positionMs ?: owner.positionUs/1000 })
            .setPlayWhenReady(remoteNow?.playing ?: (snapshot.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)),
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when {
                remoteNow?.phase == RendererPhase.STARTING -> Player.STATE_BUFFERING
                remoteNow?.phase == RendererPhase.ENDED && queue.current != null -> Player.STATE_ENDED
                remoteNow != null -> if (queue.current == null) Player.STATE_IDLE else Player.STATE_READY
                owner.sourcePreparing.value -> Player.STATE_BUFFERING
                problem != null || !opened -> Player.STATE_IDLE
                snapshot.state in listOf(UsbPlaybackState.PREPARING, UsbPlaybackState.STOPPING, UsbPlaybackState.DRAINING) -> Player.STATE_BUFFERING
                snapshot.state == UsbPlaybackState.ENDED -> Player.STATE_ENDED
                else -> Player.STATE_READY
            })
            .setPlayerError(problem?.let { PlaybackException(it, null, PlaybackException.ERROR_CODE_UNSPECIFIED) })
        if(deviceVolume!=null)state.setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0).setMaxVolume(deviceVolume.scale.positions-1).build()).setDeviceVolume(deviceVolume.index)
        if (remoteNow != null) {
            val info = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            remoteNow.volume?.let {
                info.setMinVolume(remoteNow.volumeMin).setMaxVolume(remoteNow.volumeMax)
                state.setDeviceVolume(it)
            }
            state.setDeviceInfo(info.build())
        }
        if (queue.items.isNotEmpty()) {
            state.setPlaylist(queue.items.mapIndexed { index, queued ->
                val item = MediaItem.Builder().setMediaId(queued.id).setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(queued.title).setArtist(queued.artist).setAlbumTitle(queued.album)
                    .setSubtitle(remoteNow?.let { "DLNA · ${it.name}" })
                    .setAlbumArtist(queued.musicTags.albumArtist).setTrackNumber(queued.musicTags.trackNumber)
                    .setTotalTrackCount(queued.musicTags.totalTracks).setDiscNumber(queued.musicTags.discNumber)
                    .setTotalDiscCount(queued.musicTags.totalDiscs).setRecordingYear(queued.musicTags.year)
                    .setGenre(queued.musicTags.genre).setComposer(queued.musicTags.composer)
                    .apply {artwork.forItem(queued.id)?.let {setArtworkData(it,MediaMetadata.PICTURE_TYPE_FRONT_COVER)}}.build()).build()
                MediaItemData.Builder(queued.id).setMediaItem(item)
                    .setIsSeekable(index == queue.index && seekable)
                    .setDurationUs(if (index == queue.index) remoteNow?.durationMs?.times(1000)
                        ?: track?.format?.durationUs ?: queued.durationUs ?: C.TIME_UNSET else queued.durationUs ?: C.TIME_UNSET).build()
            }).setCurrentMediaItemIndex(queue.index)
            val playlist=state.build()
            val indices=queue.items.withIndex().associate{it.value.id to it.index}
            state.setPlaylist(QueueTimeline(playlist.timeline,queue.playOrder.map{indices.getValue(it)}),playlist.currentTracks,playlist.currentMetadata)
        } else if (remoteNow == null && (opened || track != null)) {
            val id = track?.id ?: if (snapshot.route == PlaybackRoute.ANDROID) "evanna:android-output" else "evanna:usb-readiness"
            val item = MediaItem.Builder().setMediaId(id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(if (track != null) "Local audio file" else if (snapshot.route == PlaybackRoute.ANDROID) "Select a local file" else "USB readiness check")
                    .setArtist(track?.format?.let { "${it.codec} · ${it.sampleRate} Hz · ${it.sourceBits?.let { bits -> "$bits-bit" } ?: "processed PCM"}" }
                        ?: if (snapshot.route == PlaybackRoute.ANDROID) "Android system volume · select a local file" else "48 kHz · 16-bit · stereo")
                    .setExtras(Bundle().apply { putString(OUTPUT_ROUTE, snapshot.route?.name ?: "NONE") }).build()).build()
            state.setPlaylist(listOf(MediaItemData.Builder(id).setMediaItem(item).setIsSeekable(track?.seekable == true)
                .setDurationUs(track?.format?.durationUs ?: C.TIME_UNSET).build()))
        }
        return state.build()
    }

    // Reflect engine evidence instead of optimistically advertising playback while a command is pending.
    override fun getPlaceholderState(suggestedPlaceholderState: State): State = state

    fun setCurrentArtwork(queueItemId:String,bytes:ByteArray?) {
        if(owner.queue.value.current?.id!=queueItemId)return
        artwork.select(queueItemId);artwork.update(queueItemId,bytes);invalidateState()
    }

    fun openDevice(deviceId:Int,onlyIfUnselected:Boolean=false,autoAttach:Boolean=false,
        profileLoader:suspend ()->DacProfile?={null}):ListenableFuture<Boolean> = command {
        usePhoneOutput(onlyIfUnselected || autoAttach) { owner.open(deviceId,onlyIfUnselected,profileLoader) }
    }
    fun openAndroid(bitPerfect: Boolean = false, onlyIfUnselected: Boolean = false): ListenableFuture<Boolean> =
        command { usePhoneOutput(onlyIfUnselected) { owner.openAndroid(bitPerfect, onlyIfUnselected) } }

    private suspend fun usePhoneOutput(automatic: Boolean, open: suspend () -> Boolean): Boolean {
        if (remote == null) return open()
        if (automatic && (choosingRenderer || remote.selected)) return false
        if (!automatic) {
            cancelRendererWork()
            owner.nextLibrarySelection()
        }
        return routeLane.withLock {
            if (automatic && (choosingRenderer || remote.selected)) return@withLock false
            if (released || !disconnectRenderer()) return@withLock false
            open()
        }
    }

    fun openRenderer(connect: (CancelScope) -> DlnaRendererClient): ListenableFuture<Boolean> {
        cancelRendererWork()
        val cancel = CancelScope()
        rendererSelection = cancel
        choosingRenderer = true
        return command {
            try {
                routeLane.withLock {
                    val output = remote ?: return@withLock false
                    if (released || cancel.cancelled) return@withLock false
                    val renderer = try { withContext(Dispatchers.IO) { connect(cancel) } }
                        catch (_: Exception) { return@withLock false }
                    if (cancel.cancelled || output.selected && !output.disconnect()) return@withLock false
                    if (!owner.enterRendererRoute()) return@withLock false
                    if (released || cancel.cancelled) {
                        owner.leaveRendererRoute()
                        return@withLock false
                    }
                    output.connect(renderer)
                    true
                }
            } finally {
                if (rendererSelection === cancel) {
                    rendererSelection = null
                    choosingRenderer = false
                }
            }
        }
    }

    fun stopRenderer(disconnect: Boolean): ListenableFuture<Boolean> = command {
        cancelRendererWork()
        routeLane.withLock { if (disconnect) disconnectRenderer() else remote?.stopItem() == true }
    }

    private fun cancelRendererWork() {
        rendererSelection?.cancel()
        rendererIntent++
        remote?.interrupt()
    }

    private suspend fun disconnectRenderer(): Boolean {
        if (remote?.selected != true) return true
        if (!remote.disconnect()) return false
        owner.leaveRendererRoute()
        return true
    }

    private suspend fun saveRemotePosition() {
        val now = remote?.state?.value ?: return
        val id = owner.queue.value.current?.id ?: return
        when (now.phase) {
            RendererPhase.PLAYING, RendererPhase.PAUSED -> owner.saveRendererPosition(id, now.positionMs)
            RendererPhase.ENDED -> owner.saveRendererPosition(id, 0)
            else -> Unit
        }
    }

    private suspend fun playRemoteCurrent(reset: Boolean = false, automaticIntent: Long? = null): Boolean {
        val output = remote ?: return false
        val item = owner.queue.value.current ?: return false
        val source = owner.queueSource(item.id) ?: return false
        if (automaticIntent != null && automaticIntent != rendererIntent) return false
        val started = output.play(source, if (reset) 0 else owner.positionUs / 1000, automatic = automaticIntent != null)
        if (started) saveRemotePosition() else owner.saveRendererPosition(item.id, 0)
        return started
    }

    private suspend fun editRemoteSelection(play: Boolean = false, change: suspend () -> Boolean): Boolean {
        if (remote?.selected != true && !choosingRenderer) return change()
        rendererIntent++
        remote?.interrupt()
        return routeLane.withLock {
            if (released) return@withLock false
            val output = remote?.takeIf { it.selected } ?: return@withLock change()
            if (!output.stopItem() || !change()) return@withLock false
            if (play) playRemoteCurrent(reset = true) else true
        }
    }
    fun adjustUsbVolume(direction:Int,generation:Long):ListenableFuture<Boolean> = command { owner.adjustUsbVolume(direction,generation) }
    fun setUsbVolume(db:Float,generation:Long):ListenableFuture<Boolean> = command { owner.setUsbVolume(db,generation) }
    fun setStartVolume(db: Float,expectedProfileKey:String?=null): ListenableFuture<Boolean> = command { owner.setStartVolume(db,expectedProfileKey) }
    fun setVolumeLimit(db:Float,expectedProfileKey:String?=null):ListenableFuture<Boolean> = command {owner.setVolumeLimit(db,expectedProfileKey)}
    fun setUsbKeyStep(db:Float,expectedProfileKey:String?=null):ListenableFuture<Boolean> = command {owner.setUsbKeyStep(db,expectedProfileKey)}
    fun setLevelingMode(mode:com.evanna.player.loudness.LevelingMode,expectedProfileKey:String?):ListenableFuture<Boolean> = command {owner.setLevelingMode(mode,expectedProfileKey)}
    fun setCrossfeed(level:com.evanna.player.equalizer.Crossfeed,expectedProfileKey:String?):ListenableFuture<Boolean> = command {owner.setCrossfeed(level,expectedProfileKey)}
    fun selectTrack(factory: () -> PcmTrack): ListenableFuture<Boolean> = command { editRemoteSelection { owner.selectTrack(factory) } }
    fun playConvertedOnce(): ListenableFuture<Boolean> = command { owner.play(allowConversion = true) }
    fun setUsbPolicy(policy: UsbPlaybackPolicy): ListenableFuture<Boolean> = command { owner.setUsbPolicy(policy) }
    fun setFollowSongRate(enabled: Boolean,expectedProfileKey:String?=null): ListenableFuture<Boolean> = command { owner.setFollowSongRate(enabled,expectedProfileKey) }
    fun setMaxUsbRate(rate:Int?,expectedProfileKey:String?=null):ListenableFuture<Boolean> = command {owner.setMaxUsbRate(rate,expectedProfileKey)}
    fun setSleepTimer(mode:SleepMode,minutes:Int=0):ListenableFuture<Boolean> = command {owner.setSleepTimer(mode,minutes)}
    fun setCurrentFavorite(id:String,value:Boolean?) {
        if(owner.queue.value.current?.id!=id)return
        favoriteId=id;favorite=value;invalidateState()
    }
    fun favoriteCurrent(id:String,save:(String)->Boolean):ListenableFuture<Boolean> = command {owner.favoriteCurrent(id,save)}
    fun enqueue(source: QueueSource): ListenableFuture<Boolean> = command { owner.enqueue(source) }
    fun replaceQueue(source: QueueSource): ListenableFuture<Boolean> = command { editRemoteSelection { owner.replaceQueue(source) } }
    fun moveQueue(id: String, target: Int): ListenableFuture<Boolean> = command { owner.moveQueue(id, target) }
    fun moveQueue(id: String, neighborId: String): ListenableFuture<Boolean> = command { owner.moveQueue(id, neighborId) }
    fun removeQueue(id: String): ListenableFuture<Boolean> = command {
        if (id == owner.queue.value.current?.id) editRemoteSelection { owner.removeQueue(id) }
        else owner.removeQueue(id)
    }
    fun selectQueue(id: String): ListenableFuture<Boolean> = command { editRemoteSelection { owner.selectQueue(id) } }
    fun libraryQueue(append: Boolean, next: Boolean = false, resolve: () -> QueueSource?): ListenableFuture<Boolean> = command {
        if (append) owner.libraryQueue(true,next,resolve) else editRemoteSelection { owner.libraryQueue(false,next,resolve) }
    }
    fun libraryContext(resolve: () -> ContextQueue?): ListenableFuture<Boolean> = playContext { resolve() }

    fun searchQueue(action: SearchQueueAction, resolve: () -> List<QueueSource>?): ListenableFuture<Boolean> =
        if (action == SearchQueueAction.PLAY) playContext { resolve()?.let { ContextQueue(it, 0) } }
        else command { owner.enqueueSelection(action == SearchQueueAction.NEXT, resolve) }

    fun playWebStream(resolve: () -> QueueSource?): ListenableFuture<Boolean> =
        playContext { resolve()?.let { ContextQueue(listOf(it),0,owner.queue.value.shuffleEnabled) } }

    private fun playContext(resolve: (Set<com.evanna.player.sources.SourceReference>) -> ContextQueue?): ListenableFuture<Boolean> {
        val request=owner.nextLibrarySelection()
        return command(cancelWithFuture=true) { editRemoteSelection(play = true) { owner.libraryContext(request,resolve) } }
    }
    fun queueSource(id: String): ListenableFuture<QueueSource?> = command { owner.queueSource(id) }
    fun saveQueueAsPlaylist(save:(List<QueueSource>)->Boolean):ListenableFuture<Boolean> = command {owner.saveQueueAsPlaylist(save)}

    override fun handleSetDeviceVolume(volume:Int,flags:Int):ListenableFuture<*> = command {
        if (remote?.selected == true) return@command routeLane.withLock { remote.setVolume(volume) }
        val now=owner.snapshot.value
        val scale=usbDeviceVolume(now)?.scale ?: return@command false
        owner.setUsbVolume(scale.dbAt(volume),now.generation)
    }
    override fun handleIncreaseDeviceVolume(flags:Int):ListenableFuture<*> = adjustDeviceVolume(1)
    override fun handleDecreaseDeviceVolume(flags:Int):ListenableFuture<*> = adjustDeviceVolume(-1)
    private fun adjustDeviceVolume(direction:Int):ListenableFuture<Boolean> = command {
        if (remote?.selected == true) return@command routeLane.withLock {
            val volume = remote.state.value?.volume ?: return@withLock false
            remote.setVolume(volume + direction)
        }
        val now=owner.snapshot.value
        if(usbDeviceVolume(now)==null)return@command false
        owner.adjustUsbVolume(direction,now.generation)
    }
    // The combined Media3 adjustment command also exposes mute; no verified hardware-mute path exists.
    override fun handleSetDeviceMuted(muted:Boolean,flags:Int):ListenableFuture<*> = com.google.common.util.concurrent.Futures.immediateFuture(false)

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = command {
        if (remote?.selected == true || choosingRenderer) {
            rendererIntent++
            if (!playWhenReady) remote?.interrupt()
            routeLane.withLock {
                if (released) return@withLock false
                if (remote?.selected != true) {
                    if (playWhenReady) owner.play() else owner.pause()
                } else if (playWhenReady) playRemoteCurrent() else {
                    remote.pause().also { if (it) saveRemotePosition() }
                }
            }
        } else if (playWhenReady) owner.play() else owner.pause()
    }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled:Boolean):ListenableFuture<*> = command { owner.setShuffle(shuffleModeEnabled) }
    override fun handleSetRepeatMode(repeatMode:Int):ListenableFuture<*> = command {
        require(repeatMode in 0..2)
        owner.setRepeat(QueueRepeat.entries[repeatMode])
    }
    override fun handleStop(): ListenableFuture<*> = command {
        if (remote?.selected == true || choosingRenderer) {
            cancelRendererWork()
            routeLane.withLock { if (remote?.selected == true) remote.stopItem() else owner.close() }
        } else owner.close()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = command {
        require(positionMs == C.TIME_UNSET || positionMs in 0..Long.MAX_VALUE / 1000)
        if (remote?.selected == true || choosingRenderer) {
            rendererIntent++
            return@command routeLane.withLock {
                if (remote?.selected == true) return@withLock seekRemote(mediaItemIndex, positionMs, seekCommand)
                seekLocal(mediaItemIndex, positionMs, seekCommand)
            }
        }
        seekLocal(mediaItemIndex, positionMs, seekCommand)
    }

    private suspend fun seekLocal(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): Boolean {
        val queue = owner.queue.value
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> owner.skipQueue(1)
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> owner.skipQueue(-1)
            else -> {
                val position = if (positionMs == C.TIME_UNSET) 0 else positionMs * 1000
                if (queue.items.isNotEmpty() && mediaItemIndex != queue.index) owner.seekQueue(mediaItemIndex, position)
                else { require(mediaItemIndex == queue.index || (queue.items.isEmpty() && mediaItemIndex == 0)); owner.seekTo(position) }
            }
        }
    }

    private suspend fun seekRemote(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): Boolean {
        val output = remote ?: return false
        val queue = owner.queue.value
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> queue.navigationIndex(1)
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> queue.navigationIndex(-1)
            else -> mediaItemIndex
        }
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (target !in queue.items.indices) return false
        val skip = seekCommand in setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (target == queue.index && !skip) return output.seek(position).also { if (it) saveRemotePosition() }
        val playing = output.state.value?.playing == true
        if (!output.stopItem() || !owner.seekQueue(target, position * 1000)) return false
        return if (playing) playRemoteCurrent() else true
    }

    override fun handleRelease(): ListenableFuture<*> {
        artwork.select(null)
        released = true
        observer.cancel()
        remoteObserver?.cancel()
        remotePoller?.cancel()
        cancelRendererWork()
        return command {
            routeLane.withLock {
                if (remote?.selected == true) {
                    if (!remote.release()) return@withLock false
                    owner.leaveRendererRoute()
                }
                owner.shutdown()
            }
        }.also { it.addListener({ scope.cancel() }, Runnable::run) }
    }

    private fun <T> command(cancelWithFuture:Boolean=false, action: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        // Enter the owner immediately so command receipt revokes older play intent before waiting for its lane.
        val job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try { val result = action(); invalidateState(); future.set(result) }
            catch (failure: Exception) { future.setException(failure) }
        }
        if(cancelWithFuture)future.addListener({if(future.isCancelled)job.cancel()},Runnable::run)
        return future
    }

    companion object {
        const val DLNA_ROUTE = "DLNA"
        const val RENDERER_NAME = "com.evanna.player.RENDERER_NAME"
        const val USB_GENERATION="com.evanna.player.USB_GENERATION"
        const val USB_DEVICE_ID="com.evanna.player.USB_DEVICE_ID"
        const val USB_VOLUME="com.evanna.player.USB_VOLUME"
        const val USB_VOLUME_MIN="com.evanna.player.USB_VOLUME_MIN"
        const val USB_VOLUME_MAX="com.evanna.player.USB_VOLUME_MAX"
        const val USB_VOLUME_STEP="com.evanna.player.USB_VOLUME_STEP"
        const val USB_START_VOLUME="com.evanna.player.USB_START_VOLUME"
        const val USB_VOLUME_LIMIT="com.evanna.player.USB_VOLUME_LIMIT"
        const val USB_KEY_STEP="com.evanna.player.USB_KEY_STEP"
        const val LEVELING_MODE="com.evanna.player.LEVELING_MODE"
        const val LEVELING_OFFSET="com.evanna.player.LEVELING_OFFSET"
        const val LEVELING_EVIDENCE="com.evanna.player.LEVELING_EVIDENCE"
        const val USB_APPLIED_GAIN="com.evanna.player.USB_APPLIED_GAIN"
        const val USB_DROPPED="com.evanna.player.USB_DROPPED"
        const val EQ_PROCESSED="com.evanna.player.EQ_PROCESSED"
        const val EQ_PREAMP="com.evanna.player.EQ_PREAMP"
        const val CROSSFEED="com.evanna.player.CROSSFEED"
        const val OUTPUT_ROUTE = "com.evanna.player.OUTPUT_ROUTE"
        const val USB_POLICY = "com.evanna.player.USB_POLICY"
        const val FOLLOW_SONG_RATE = "com.evanna.player.FOLLOW_SONG_RATE"
        const val MAX_USB_RATE="com.evanna.player.MAX_USB_RATE"
        const val DAC_PROFILE_KEY="com.evanna.player.DAC_PROFILE_KEY"
        const val DAC_PROFILE_NAME="com.evanna.player.DAC_PROFILE_NAME"
        const val SIGNAL_STATUS = "com.evanna.player.SIGNAL_STATUS"
        const val SLEEP_LABEL="com.evanna.player.SLEEP_LABEL"
        const val FAVORITE_AVAILABLE="com.evanna.player.FAVORITE_AVAILABLE"
        const val FAVORITE="com.evanna.player.FAVORITE"
        const val SOUND_VERDICT="com.evanna.player.SOUND_VERDICT"
        const val SOUND_WHY="com.evanna.player.SOUND_WHY"
        const val SOUND_PLAYING="com.evanna.player.SOUND_PLAYING"
        const val SOUND_OFFER_OUTPUT="com.evanna.player.SOUND_OFFER_OUTPUT"
        const val SOUND_STOPS="com.evanna.player.SOUND_STOPS"
        const val SIGNAL_LABELS="com.evanna.player.SIGNAL_LABELS"
        const val SIGNAL_VALUES="com.evanna.player.SIGNAL_VALUES"
        const val SIGNAL_CHANGED="com.evanna.player.SIGNAL_CHANGED"
        const val SIGNAL_BRIEF="com.evanna.player.SIGNAL_BRIEF"
        const val QUEUE_STATUS = "com.evanna.player.QUEUE_STATUS"
        const val QUEUE_WARNING = "com.evanna.player.QUEUE_WARNING"
        const val SOURCE_QUALITY = "com.evanna.player.SOURCE_QUALITY"
    }
}
