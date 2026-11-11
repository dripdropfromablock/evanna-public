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

@UnstableApi
internal class EvannaPlayer(private val owner: UsbSessionOwner) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val artwork=SessionArtwork()
    private var favoriteId:String?=null
    private var favorite:Boolean?=null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val observer = scope.launch {
        combine(owner.snapshot, owner.queue,owner.sleepTimer) { snapshot, _,_ -> snapshot }.collect {
            owner.onPlaybackState(it)
            invalidateState()
        }
    }

    override fun getState(): State {
        val snapshot = owner.snapshot.value
        val track = owner.selectedTrack
        val queue = owner.queue.value
        artwork.select(queue.current?.id)
        val opened = snapshot.route != null && snapshot.state != UsbPlaybackState.CLOSED
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_RELEASE, Player.COMMAND_STOP,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_REPEAT_MODE,Player.COMMAND_SET_SHUFFLE_MODE)
        if (opened) commands.add(Player.COMMAND_PLAY_PAUSE)
        if (track?.seekable == true && !owner.sourceSelectionFailed) commands.addAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS)
        if (queue.items.isNotEmpty()) commands.add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
        if (queue.navigationIndex(-1)>=0) commands.addAll(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS)
        if (queue.navigationIndex(1)>=0) commands.addAll(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT)
        val problem = when {
            owner.sourceSelectionFailed -> "The local file could not be opened or released. Select it again, or close the output and retry."
            owner.focusDenied -> "Audio focus was not granted. Try again with Evanna visible."
            snapshot.failure == UsbPlaybackFailure.VOLUME_UNVERIFIED -> "USB volume could not be verified. A controllable, unmuted DAC is required for music; analysis signals require measured calibration."
            snapshot.failure == UsbPlaybackFailure.CONVERSION_REQUIRED -> when {
                snapshot.eqProcessed && snapshot.crossfeedProcessed -> "EQ and crossfeed change audio samples. Turn them off for Strict playback, or choose Compatibility USB or Play converted once."
                snapshot.eqProcessed -> "EQ changes audio samples. Turn EQ off for Strict playback, or choose Compatibility USB or Play converted once."
                snapshot.crossfeedProcessed -> "Crossfeed changes audio samples. Turn crossfeed off for Strict playback, or choose Compatibility USB or Play converted once."
                else -> "Strict playback is unavailable. Choose Compatibility USB, or Play converted once for this play."
            }
            snapshot.failure == UsbPlaybackFailure.EQ_HEADROOM_REQUIRED -> "EQ boosts need more headroom than the supported preamp range. Reduce the boosts or choose Flat."
            snapshot.failure == UsbPlaybackFailure.ROUTE_CHANGED -> "The audio output changed. Select an output again before playing."
            snapshot.failure != null -> "Playback stopped: ${snapshot.failure.name.lowercase().replace('_', ' ')}."
            else -> null
        }
        val signalStatus = playbackSignalStatus(snapshot)
        val deviceVolume=usbDeviceVolume(snapshot)
        if(deviceVolume!=null) {
            commands.add(Player.COMMAND_GET_DEVICE_VOLUME)
            if(deviceVolume.scale.positions>1)commands.addAll(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        }
        val queueStatus=when {
            queue.restoreFailed -> "The saved queue could not be restored. It was kept on disk; choose a new song or Close output to replace it."
            queue.saveFailed -> "Queue changes could not be saved. Free storage and try again; playback can continue in this session."
            queue.historySaveFailed -> "Play history could not be saved. Free storage and retry; playback can continue."
            queue.items.isNotEmpty() && track==null -> "Saved queue restored paused. Choose an output and press Play; unavailable sources stay in the queue."
            track!=null && snapshot.route==null -> "Song selected. Choose an output, then press Play."
            else -> "Queue and estimated position are saved on this device."
        }
        val sleepLabel=owner.sleepTimer.value.label
        val currentFavorite = favorite.takeIf { favoriteId != null && favoriteId == queue.current?.id }
        val extras = Bundle().apply {
            putString(OUTPUT_ROUTE, snapshot.route?.name ?: "NONE")
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
            putBoolean(EQ_ENABLED,snapshot.eqEnabled);putBoolean(EQ_PROCESSED,snapshot.eqProcessed)
            putString(EQ_NAME,snapshot.equalizer?.name ?: "Flat")
            putString(CROSSFEED,snapshot.crossfeed.name)
            putLong(EQ_PRESET_ID,snapshot.equalizer?.id ?: 0);putLong(EQ_REVISION,snapshot.equalizer?.revision ?: 1)
            putFloat(EQ_PREAMP,snapshot.eqPreampDb)
            snapshot.appliedGainDb?.let {putFloat(USB_APPLIED_GAIN,it)}
            putString(SIGNAL_STATUS, signalStatus)
            putString(SLEEP_LABEL,sleepLabel)
            if (currentFavorite != null) {
                putBoolean(FAVORITE_AVAILABLE, true)
                putBoolean(FAVORITE, currentFavorite)
            }
            val stages=signalSummary(snapshot)+signalStages(snapshot)
            putStringArrayList(SIGNAL_LABELS,ArrayList(stages.map {it.label}))
            putStringArrayList(SIGNAL_VALUES,ArrayList(stages.map {it.value}))
            putBooleanArray(SIGNAL_CHANGED,stages.map {it.changed}.toBooleanArray())
            putBooleanArray(SIGNAL_BRIEF,stages.map {it.brief}.toBooleanArray())
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
            .setPlaylistMetadata(MediaMetadata.Builder().setTitle("USB mode: ${snapshot.usbPolicy.name} · follow song rate: ${snapshot.followSongRate} · start volume: ${snapshot.startVolumeDb} dB · limit: ${snapshot.volumeLimitDb} dB · step: ${snapshot.usbKeyStepDb} dB")
                .setDescription("$signalStatus\n$queueStatus\nStream revision: ${snapshot.generation}\n$sleepLabel\nFavorite: $currentFavorite")
                .setExtras(extras).build())
            .setContentPositionMs(PositionSupplier { owner.positionUs/1000 })
            .setPlayWhenReady(snapshot.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when {
                problem != null || !opened -> Player.STATE_IDLE
                snapshot.state in listOf(UsbPlaybackState.PREPARING, UsbPlaybackState.STOPPING, UsbPlaybackState.DRAINING) -> Player.STATE_BUFFERING
                snapshot.state == UsbPlaybackState.ENDED -> Player.STATE_ENDED
                else -> Player.STATE_READY
            })
            .setPlayerError(problem?.let { PlaybackException(it, null, PlaybackException.ERROR_CODE_UNSPECIFIED) })
        if(deviceVolume!=null)state.setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMinVolume(0).setMaxVolume(deviceVolume.scale.positions-1).build()).setDeviceVolume(deviceVolume.index)
        if (queue.items.isNotEmpty()) {
            state.setPlaylist(queue.items.mapIndexed { index, queued ->
                val item = MediaItem.Builder().setMediaId(queued.id).setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(queued.title).setArtist(queued.artist).setAlbumTitle(queued.album)
                    .setAlbumArtist(queued.musicTags.albumArtist).setTrackNumber(queued.musicTags.trackNumber)
                    .setTotalTrackCount(queued.musicTags.totalTracks).setDiscNumber(queued.musicTags.discNumber)
                    .setTotalDiscCount(queued.musicTags.totalDiscs).setRecordingYear(queued.musicTags.year)
                    .setGenre(queued.musicTags.genre).setComposer(queued.musicTags.composer)
                    .apply {artwork.forItem(queued.id)?.let {setArtworkData(it,MediaMetadata.PICTURE_TYPE_FRONT_COVER)}}.build()).build()
                MediaItemData.Builder(queued.id).setMediaItem(item)
                    .setIsSeekable(index == queue.index && track?.seekable == true && !owner.sourceSelectionFailed)
                    .setDurationUs(if (index == queue.index) track?.format?.durationUs ?: queued.durationUs ?: C.TIME_UNSET else queued.durationUs ?: C.TIME_UNSET).build()
            }).setCurrentMediaItemIndex(queue.index)
            val playlist=state.build()
            val indices=queue.items.withIndex().associate{it.value.id to it.index}
            state.setPlaylist(QueueTimeline(playlist.timeline,queue.playOrder.map{indices.getValue(it)}),playlist.currentTracks,playlist.currentMetadata)
        } else if (opened || track != null) {
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

    fun openDevice(deviceId: Int, onlyIfUnselected: Boolean = false, profileLoader: suspend () -> DacProfile? = { null }): ListenableFuture<Boolean> =
        command { owner.open(deviceId, onlyIfUnselected, profileLoader) }
    fun openAndroid(bitPerfect: Boolean = false, onlyIfUnselected: Boolean = false): ListenableFuture<Boolean> =
        command { owner.openAndroid(bitPerfect, onlyIfUnselected) }
    fun adjustUsbVolume(direction:Int,generation:Long):ListenableFuture<Boolean> = command { owner.adjustUsbVolume(direction,generation) }
    fun setUsbVolume(db:Float,generation:Long):ListenableFuture<Boolean> = command { owner.setUsbVolume(db,generation) }
    fun setStartVolume(db: Float,expectedProfileKey:String?=null): ListenableFuture<Boolean> = command { owner.setStartVolume(db,expectedProfileKey) }
    fun setVolumeLimit(db:Float,expectedProfileKey:String?=null):ListenableFuture<Boolean> = command {owner.setVolumeLimit(db,expectedProfileKey)}
    fun setUsbKeyStep(db:Float,expectedProfileKey:String?=null):ListenableFuture<Boolean> = command {owner.setUsbKeyStep(db,expectedProfileKey)}
    fun setLevelingMode(mode:com.evanna.player.loudness.LevelingMode,expectedProfileKey:String?):ListenableFuture<Boolean> = command {owner.setLevelingMode(mode,expectedProfileKey)}
    fun setCrossfeed(level:com.evanna.player.equalizer.Crossfeed,expectedProfileKey:String?):ListenableFuture<Boolean> = command {owner.setCrossfeed(level,expectedProfileKey)}
    fun setEqualizer(expectedKey:String?,expectedId:Long?,expectedRevision:Long?,config:com.evanna.player.equalizer.EqualizerConfig,
        enabled:Boolean,persist:()->com.evanna.player.equalizer.EqualizerPreset?):ListenableFuture<Boolean> = command {
        owner.setEqualizer(expectedKey,expectedId,expectedRevision,config,enabled,persist)
    }
    fun selectTrack(factory: () -> PcmTrack): ListenableFuture<Boolean> = command { owner.selectTrack(factory) }
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
    fun replaceQueue(source: QueueSource): ListenableFuture<Boolean> = command { owner.replaceQueue(source) }
    fun moveQueue(id: String, target: Int): ListenableFuture<Boolean> = command { owner.moveQueue(id, target) }
    fun moveQueue(id: String, neighborId: String): ListenableFuture<Boolean> = command { owner.moveQueue(id, neighborId) }
    fun removeQueue(id: String): ListenableFuture<Boolean> = command { owner.removeQueue(id) }
    fun selectQueue(id: String): ListenableFuture<Boolean> = command { owner.selectQueue(id) }
    fun libraryQueue(append: Boolean, next: Boolean = false, resolve: () -> QueueSource?): ListenableFuture<Boolean> = command { owner.libraryQueue(append,next,resolve) }
    fun libraryContext(resolve: () -> ContextQueue?): ListenableFuture<Boolean> {
        val request=owner.nextLibrarySelection()
        return command(cancelWithFuture=true) { owner.libraryContext(request,resolve) }
    }
    fun queueSource(id: String): ListenableFuture<QueueSource?> = command { owner.queueSource(id) }
    fun saveQueueAsPlaylist(save:(List<QueueSource>)->Boolean):ListenableFuture<Boolean> = command {owner.saveQueueAsPlaylist(save)}

    override fun handleSetDeviceVolume(volume:Int,flags:Int):ListenableFuture<*> = command {
        val now=owner.snapshot.value
        val scale=usbDeviceVolume(now)?.scale ?: return@command false
        owner.setUsbVolume(scale.dbAt(volume),now.generation)
    }
    override fun handleIncreaseDeviceVolume(flags:Int):ListenableFuture<*> = adjustDeviceVolume(1)
    override fun handleDecreaseDeviceVolume(flags:Int):ListenableFuture<*> = adjustDeviceVolume(-1)
    private fun adjustDeviceVolume(direction:Int):ListenableFuture<Boolean> = command {
        val now=owner.snapshot.value
        if(usbDeviceVolume(now)==null)return@command false
        owner.adjustUsbVolume(direction,now.generation)
    }
    // The combined Media3 adjustment command also exposes mute; no verified hardware-mute path exists.
    override fun handleSetDeviceMuted(muted:Boolean,flags:Int):ListenableFuture<*> = com.google.common.util.concurrent.Futures.immediateFuture(false)

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = command {
        if (playWhenReady) owner.play() else owner.pause()
    }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled:Boolean):ListenableFuture<*> = command { owner.setShuffle(shuffleModeEnabled) }
    override fun handleSetRepeatMode(repeatMode:Int):ListenableFuture<*> = command {
        require(repeatMode in 0..2)
        owner.setRepeat(QueueRepeat.entries[repeatMode])
    }
    override fun handleStop(): ListenableFuture<*> = command { owner.close() }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = command {
        require(positionMs == C.TIME_UNSET || positionMs in 0..Long.MAX_VALUE / 1000)
        val queue = owner.queue.value
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> owner.skipQueue(1)
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> owner.skipQueue(-1)
            else -> {
                val position = if (positionMs == C.TIME_UNSET) 0 else positionMs * 1000
                if (queue.items.isNotEmpty() && mediaItemIndex != queue.index) owner.seekQueue(mediaItemIndex, position)
                else { require(mediaItemIndex == queue.index || (queue.items.isEmpty() && mediaItemIndex == 0)); owner.seekTo(position) }
            }
        }
    }
    override fun handleRelease(): ListenableFuture<*> {
        artwork.select(null)
        observer.cancel()
        return command { owner.shutdown() }.also { it.addListener({ scope.cancel() }, Runnable::run) }
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
        const val EQ_ENABLED="com.evanna.player.EQ_ENABLED"
        const val EQ_PROCESSED="com.evanna.player.EQ_PROCESSED"
        const val EQ_NAME="com.evanna.player.EQ_NAME"
        const val EQ_PRESET_ID="com.evanna.player.EQ_PRESET_ID"
        const val EQ_REVISION="com.evanna.player.EQ_REVISION"
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
        const val SIGNAL_LABELS="com.evanna.player.SIGNAL_LABELS"
        const val SIGNAL_VALUES="com.evanna.player.SIGNAL_VALUES"
        const val SIGNAL_CHANGED="com.evanna.player.SIGNAL_CHANGED"
        const val SIGNAL_BRIEF="com.evanna.player.SIGNAL_BRIEF"
        const val QUEUE_STATUS = "com.evanna.player.QUEUE_STATUS"
        const val QUEUE_WARNING = "com.evanna.player.QUEUE_WARNING"
        const val SOURCE_QUALITY = "com.evanna.player.SOURCE_QUALITY"
    }
}
