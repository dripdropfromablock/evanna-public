package com.evanna.player.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.evanna.player.diagnostics.DiagnosticEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import com.evanna.player.user.DacProfile
import com.evanna.player.user.PlayedSong

internal interface PlaybackFocus {
    val held: Boolean
    fun request(): Boolean
    fun abandon()
}

internal fun releaseTerminalFocus(state: UsbPlaybackState, focus: PlaybackFocus) {
    // Explicit Pause abandons focus itself; an active seek also passes through PAUSED.
    if (state in listOf(UsbPlaybackState.ERROR, UsbPlaybackState.CLOSED, UsbPlaybackState.ENDED)) focus.abandon()
}

/** One command lane above source/output ownership, including position anchors for the selected file. */
internal class UsbSessionOwner(val controller: UsbPlaybackController, private val focus: PlaybackFocus,
    private val record: (DiagnosticEvent) -> Unit = {},
    private val persistence: QueuePersistence? = null,
    private val restoreSource: (SavedQueueEntry) -> QueueSource = { saved ->
        QueueSource(saved.libraryId,saved.title,saved.artist,saved.album,saved.durationUs,saved.fileUri,saved.musicTags) { error("SOURCE_RESELECTION_REQUIRED") }
    },
    private val releaseSavedSource: (String) -> Unit = {},
    private val shuffleOrder: (List<String>) -> List<String> = {it.shuffled()},
    private val nowMs:()->Long={System.nanoTime()/1_000_000},
    private val saveHistory:(List<PlayedSong>)->Boolean={true},
) {
    val snapshot = controller.snapshot
    private val commands = Mutex()
    private val librarySelection = java.util.concurrent.atomic.AtomicLong()
    fun nextLibrarySelection(): Long = librarySelection.incrementAndGet()
    private val entries = mutableListOf<OwnedQueueEntry>()
    private val mutableQueue = MutableStateFlow(PlaybackQueue())
    val queue = mutableQueue.asStateFlow()
    private val mutableSleepTimer=MutableStateFlow(SleepTimerState())
    val sleepTimer=mutableSleepTimer.asStateFlow()
    private var queueIndex = -1
    private var shuffleEnabled=false
    private var repeat=QueueRepeat.OFF
    private val playOrder=mutableListOf<String>()
    private val continuousQueue=java.util.concurrent.atomic.AtomicReference(GaplessQueue(PlaybackQueue(),emptyMap()))
    private var initialized=false
    private var pendingPositionUs=0L
    private var lastSaved:SavedQueue?=null
    private var restoreBlocked=false
    private var saveFailed=false
    private var historySaveFailed=false
    private var historyPlayId=UUID.randomUUID().toString()
    private val sourcePositionUs:Long? get()=snapshot.value.takeIf{selectedTrack!=null && it.trackId==selectedTrack?.id}?.positionUs
    val positionUs:Long get()=sourcePositionUs ?: pendingPositionUs
    var focusDenied = false
        private set
    @Volatile var selectedTrack: PcmTrack? = null
        private set
    var sourceSelectionFailed = false
        private set
    private var fileMode = false

    suspend fun initialize() = command(save=false) { }
    suspend fun checkpoint() = command { }
    suspend fun setSleepTimer(mode:SleepMode,minutes:Int=0):Boolean=command(save=false) {
        if(mode==SleepMode.MINUTES && minutes !in 1..180 || mode!=SleepMode.MINUTES && minutes!=0)return@command false
        if(mode==SleepMode.OFF) {mutableSleepTimer.value=SleepTimerState();publishQueue();return@command true}
        if(mode==SleepMode.MINUTES) {
            mutableSleepTimer.value=SleepTimerState(mode,Math.addExact(nowMs(),minutes*60000L),minutes*60L)
            publishQueue()
            return@command true
        }
        if(queue.value.current==null && selectedTrack==null)return@command false
        val playing=snapshot.value
        val resume=pauseForEdit() ?: return@command false
        val current=queue.value.current
        mutableSleepTimer.value=SleepTimerState(mode,targetId=current?.id ?: selectedTrack?.id,
            albumKey=if(mode==SleepMode.ALBUM)current?.let(::sleepAlbumKey) else null)
        publishQueue()
        if(resume)playLocked(playing.conversionAllowed && playing.queueId==current?.id) else true
    }
    suspend fun tickSleepTimer()=command(save=false) {
        val timer=mutableSleepTimer.value
        if(timer.mode==SleepMode.MINUTES) {
            val remaining=timer.remaining(nowMs())
            if(remaining>0)mutableSleepTimer.value=timer.copy(remainingSeconds=remaining)
            else {
                pauseLocked();persistLocked()
                // Publishing OFF cancels the runtime's ticking coroutine.
                mutableSleepTimer.value=SleepTimerState()
            }
        }
    }
    suspend fun queueSource(id: String): QueueSource? = command(save=false) { entries.firstOrNull { it.item.id == id }?.source }
    suspend fun favoriteCurrent(id:String,save:(String)->Boolean):Boolean=command(save=false) {
        if(queue.value.current?.id!=id)return@command false
        val key=entries.firstOrNull {it.item.id==id}?.source?.libraryId?.takeIf {com.evanna.player.user.validLibraryId(it)}
            ?: return@command false
        withContext(NonCancellable+Dispatchers.IO){save(key)}
    }
    suspend fun saveQueueAsPlaylist(save:(List<QueueSource>)->Boolean):Boolean=command(save=false) {
        if(entries.isEmpty())false else withContext(Dispatchers.IO){save(entries.map {it.source})}
    }

    private suspend fun <T> command(save:Boolean=true, interruptSelection:Boolean=false, action:suspend ()->T):T {
        if (interruptSelection) nextLibrarySelection()
        return commands.withLock {
        if(!initialized) {
            withContext(NonCancellable+Dispatchers.IO) {
                try {
                    val saved=persistence?.load()
                    if(saved!=null) {
                        val restored=saved.items.map { item -> OwnedQueueEntry(restoreSource(item),QueueItem(item.id,item.title,item.artist,item.album,item.durationUs,item.musicTags)) }
                        entries.addAll(restored)
                        queueIndex=saved.index;pendingPositionUs=saved.positionUs;fileMode=entries.isNotEmpty();lastSaved=saved
                        shuffleEnabled=saved.shuffleEnabled;repeat=saved.repeat;playOrder.addAll(saved.playOrder)
                    }
                } catch(_:Exception){restoreBlocked=true}
                initialized=true;publishQueue()
            }
        }
        syncCompleted()
        persistHistoryLocked()
        try { action() } finally { syncCompleted();persistHistoryLocked();if(save) persistLocked() }
        }
    }

    private fun syncCompleted() {
        val now=snapshot.value
        val index=entries.indexOfFirst{it.item.id==now.queueId}
        val track=controller.completedTrack
        if(index>=0 && track!=null && track.id==now.trackId && (index!=queueIndex || selectedTrack?.id!=track.id)) {
            queueIndex=index;selectedTrack=track;pendingPositionUs=now.positionUs
            now.historyPlayId?.let {historyPlayId=it};publishQueue()
        }
        if(now.stats!=null && now.trackId==selectedTrack?.id)now.historyPlayId?.let {historyPlayId=it}
    }
    private suspend fun persistHistoryLocked() {
        val pending=controller.pendingHistory()
        if(pending.isEmpty())return
        val batch=pending.map {PlayedSong(it.eventId,it.trackId,it.playedAt)}
        val saved=try {withContext(NonCancellable+Dispatchers.IO){saveHistory(batch)}}catch(_:Exception){false}
        if(saved)controller.acknowledgeHistory(pending.last().sequence)
        if(historySaveFailed==saved){historySaveFailed=!saved;publishQueue()}
    }
    private fun continuation()=entries.getOrNull(queueIndex)?.let {
        GaplessPlayback(it.item.id,{continuousQueue.get()},{focus.held},entries.mapNotNull {entry ->
            entry.source.libraryId?.takeIf {id->com.evanna.player.user.validLibraryId(id)}?.let {id->entry.item.id to id}
        }.toMap())
    }
    private suspend fun pauseForEdit():Boolean? {
        if(snapshot.value.queueId==null || snapshot.value.state !in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING))return false
        val active=focus.held
        if(!controller.pause())return null
        syncCompleted()
        return active
    }

    private suspend fun persistLocked(positionOverride:Long?=null) {
        val store=persistence ?: return
        if(restoreBlocked)return
        try {
            val saved=SavedQueue(entries.map { entry ->
                SavedQueueEntry(entry.item.id,entry.source.libraryId,entry.source.fileUri,entry.item.title,entry.item.artist,entry.item.album,
                    entry.track?.format?.durationUs ?: entry.item.durationUs,entry.item.musicTags)
            },queueIndex,if(entries.isEmpty() || snapshot.value.state==UsbPlaybackState.ENDED) 0 else positionOverride ?: positionUs,
                shuffleEnabled,repeat,playOrder.toList())
            if(saved!=lastSaved) withContext(NonCancellable+Dispatchers.IO){store.save(saved)}
            lastSaved=saved
            if(saveFailed){saveFailed=false;publishQueue()}
        } catch(_:Exception){saveFailed=true;publishQueue()}
    }

    suspend fun selectTrack(factory: () -> PcmTrack): Boolean = command(interruptSelection=true) {
        fileMode = true
        if (!controller.pause()) return@command false
        focus.abandon()
        focusDenied = false
        var acquired: PcmTrack? = null
        try {
            withContext(Dispatchers.IO) {
                closeSources()
                currentCoroutineContext().ensureActive()
                acquired = factory()
            }
            selectedTrack = checkNotNull(acquired)
            historyPlayId=UUID.randomUUID().toString()
            controller.setPausedPosition(selectedTrack!!, 0)
            sourceSelectionFailed = false
            record(DiagnosticEvent.SOURCE_SELECTED)
            true
        } catch (cancelled: CancellationException) {
            sourceSelectionFailed = true
            withContext(NonCancellable + Dispatchers.IO) {
                try { acquired?.close() }
                catch (failure: Exception) { selectedTrack = acquired; throw failure }
            }
            throw cancelled
        } catch (_: Exception) {
            sourceSelectionFailed = true
            record(DiagnosticEvent.SOURCE_SELECTION_FAILED)
            false
        }
    }

    suspend fun open(deviceId: Int, onlyIfUnselected: Boolean = false, profileLoader: suspend () -> DacProfile? = { null }): Boolean =
        command(interruptSelection = !onlyIfUnselected, save = !onlyIfUnselected) {
        if (onlyIfUnselected && snapshot.value.route != null) return@command false
        record(DiagnosticEvent.OPEN)
        focusDenied = false
        try {
            val profile=withContext(Dispatchers.IO){profileLoader()}
            controller.open(deviceId,profile)
        }catch(failure:Throwable) {
            withContext(NonCancellable) {
                if(snapshot.value.state in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING,UsbPlaybackState.PREPARING))controller.pause()
            }
            throw failure
        }finally {focus.abandon()}
    }

    suspend fun openAndroid(bitPerfect: Boolean = false, onlyIfUnselected: Boolean = false): Boolean =
        command(interruptSelection = !onlyIfUnselected, save = !onlyIfUnselected) {
        if (onlyIfUnselected && snapshot.value.route != null) return@command false
        record(DiagnosticEvent.OPEN)
        focusDenied = false
        try { controller.openAndroid(bitPerfect) } finally { focus.abandon() }
    }

    suspend fun adjustUsbVolume(direction:Int,generation:Long):Boolean = volumeChange { controller.adjustUsbVolume(direction,generation) }

    suspend fun setUsbVolume(db:Float,generation:Long):Boolean = volumeChange { controller.setUsbVolume(db,generation) }
    suspend fun setEqualizer(expectedKey:String?,expectedId:Long?,expectedRevision:Long?,config:com.evanna.player.equalizer.EqualizerConfig,
        enabled:Boolean,persist:()->com.evanna.player.equalizer.EqualizerPreset?):Boolean=volumeChange {
        controller.setEqualizer(expectedKey,expectedId,expectedRevision,config,enabled,persist)
    }
    suspend fun setVolumeLimit(db:Float,expectedProfileKey:String?=null):Boolean = volumeChange {controller.setVolumeLimit(db,expectedProfileKey)}
    suspend fun setUsbKeyStep(db:Float,expectedProfileKey:String?=null):Boolean = command {controller.setUsbKeyStep(db,expectedProfileKey)}
    suspend fun setLevelingMode(mode:com.evanna.player.loudness.LevelingMode,expectedProfileKey:String?):Boolean=command(interruptSelection=true) {
        val usb=snapshot.value.route==PlaybackRoute.DIRECT_USB
        try {controller.setLevelingMode(mode,expectedProfileKey)}finally {
            if(usb && snapshot.value.state !in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING))focus.abandon()
        }
    }
    suspend fun setCrossfeed(level:com.evanna.player.equalizer.Crossfeed,expectedProfileKey:String?):Boolean=command(interruptSelection=true) {
        val usb=snapshot.value.route==PlaybackRoute.DIRECT_USB
        try {controller.setCrossfeed(level,expectedProfileKey)}finally {
            if(usb && snapshot.value.state !in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING))focus.abandon()
        }
    }

    /** Only the next new USB connection uses it, so playback is not paused. */
    suspend fun setStartVolume(db: Float,expectedProfileKey:String?=null): Boolean = command { controller.setStartVolume(db,expectedProfileKey) }

    private suspend fun volumeChange(change: suspend () -> Boolean):Boolean {
        // A volume request waiting for PCM must not hold the lane needed by Pause/Stop.
        try {return change()}
        finally {withContext(NonCancellable){commands.withLock {
            // Natural EOF retains focus until onPlaybackState decides whether the queue continues.
            if(snapshot.value.state !in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING,UsbPlaybackState.PREPARING,UsbPlaybackState.STOPPING,UsbPlaybackState.ENDED))focus.abandon()
        }}}
    }

    suspend fun setUsbPolicy(policy: UsbPlaybackPolicy): Boolean = command(interruptSelection=true) {
        if (snapshot.value.usbPolicy == policy) return@command true
        val usb = snapshot.value.route == PlaybackRoute.DIRECT_USB
        try { controller.setUsbPolicy(policy) } finally {
            if (usb && snapshot.value.state !in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)) focus.abandon()
        }
    }

    suspend fun setFollowSongRate(enabled:Boolean,expectedProfileKey:String?=null):Boolean=setMaxUsbRate(if(enabled)null else FOLLOW_SONG_RATE_CAP,expectedProfileKey)

    suspend fun setMaxUsbRate(rate:Int?,expectedProfileKey:String?=null):Boolean = command(interruptSelection=true) {
        if(snapshot.value.dacProfileKey!=expectedProfileKey)return@command false
        if (snapshot.value.maxUsbRate == rate) return@command true
        val usb = snapshot.value.route == PlaybackRoute.DIRECT_USB
        try { controller.setMaxUsbRate(rate,expectedProfileKey) } finally {
            if (usb && snapshot.value.state !in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)) focus.abandon()
        }
    }

    suspend fun setShuffle(enabled:Boolean):Boolean = command {
        if(shuffleEnabled==enabled)return@command true
        if(snapshot.value.state==UsbPlaybackState.ENDED)focus.abandon()
        val ids=entries.map{it.item.id}
        val current=entries.getOrNull(queueIndex)?.item?.id
        val order=if(enabled) listOfNotNull(current)+shuffleOrder(ids.filter{it!=current}) else ids
        check(order.size==ids.size && order.toSet()==ids.toSet())
        shuffleEnabled=enabled;playOrder.clear();playOrder.addAll(order);publishQueue();true
    }

    suspend fun setRepeat(mode:QueueRepeat):Boolean = command {
        if(repeat==mode)return@command true
        if(snapshot.value.state==UsbPlaybackState.ENDED)focus.abandon()
        repeat=mode;publishQueue();true
    }

    suspend fun play(allowConversion: Boolean = false): Boolean = command(interruptSelection=true) { playLocked(allowConversion) }

    private suspend fun playLocked(allowConversion: Boolean = false): Boolean {
        record(DiagnosticEvent.PLAY)
        if(selectedTrack==null && queueIndex in entries.indices && snapshot.value.route!=null) {
            if(!selectQueueLocked(queueIndex,false,pendingPositionUs)) return false
        }
        if (snapshot.value.route == null || sourceSelectionFailed || (fileMode && selectedTrack == null) ||
            (snapshot.value.route == PlaybackRoute.ANDROID && selectedTrack == null)) return false
        if (snapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)) {
            if (focus.held) return true
            if (!controller.pause()) return false
        }
        focusDenied = !focus.request()
        if (focusDenied) { record(DiagnosticEvent.FOCUS_DENIED); return false }
        return try {
            val started = selectedTrack?.let {
                val now = snapshot.value
                if(now.state==UsbPlaybackState.ENDED)historyPlayId=UUID.randomUUID().toString()
                val position = if (now.state != UsbPlaybackState.ENDED && it.seekable) positionUs else 0
                controller.start(it, allowConversion, position,continuation(),historyPlayId)
            }
                ?: controller.start(PcmFixture(48000, 16, 2), allowConversion)
            if (!started || !focus.held) {
                val ended = snapshot.value.state == UsbPlaybackState.ENDED
                if (started && snapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING, UsbPlaybackState.PREPARING)) controller.pause()
                focus.abandon()
                started && ended
            } else true
        } catch (failure: Exception) {
            focus.abandon()
            throw failure
        }
    }

    suspend fun enqueue(source: QueueSource): Boolean = command { enqueueLocked(source) }

    suspend fun libraryQueue(append: Boolean, next: Boolean = false, resolve: () -> QueueSource?): Boolean = command(interruptSelection=!append) {
        val source = withContext(Dispatchers.IO) { resolve() } ?: return@command false
        if (append) enqueueLocked(source,next) else replaceQueueLocked(source)
    }

    suspend fun libraryContext(request: Long = nextLibrarySelection(), resolve: () -> ContextQueue?): Boolean = command {
        if (request != librarySelection.get()) return@command false
        val selection = withContext(Dispatchers.IO) { resolve() } ?: return@command false
        currentCoroutineContext().ensureActive()
        if (request != librarySelection.get()) return@command false
        replaceQueueLocked(selection.sources,selection.startIndex,selection.shuffle,true,true) { request == librarySelection.get() }
    }

    private suspend fun enqueueLocked(source: QueueSource, next: Boolean = false): Boolean {
        if (entries.size >= 1000 || source.title.length !in 1..512) return false
        val playing=snapshot.value
        val resume=pauseForEdit() ?: return false
        if (snapshot.value.state == UsbPlaybackState.ENDED) focus.abandon()
        if (entries.isEmpty() && selectedTrack != null) {
            val track = selectedTrack!!
            addEntry(QueueSource(null, "Local audio file", null, null, track.format.durationUs) { error("Source ownership lost") }, track)
            queueIndex = 0
        }
        addEntry(source)
        if (next && queueIndex >= 0) {
            val inserted = entries.removeAt(entries.lastIndex)
            entries.add(queueIndex+1,inserted)
            playOrder.remove(inserted.item.id)
            playOrder.add(playOrder.indexOf(entries[queueIndex].item.id)+1,inserted.item.id)
        }
        return if (queueIndex < 0) { queueIndex = 0; publishQueue(); selectQueueLocked(0, false) }
        else { publishQueue();if(resume)playLocked(playing.conversionAllowed && playing.queueId==queue.value.current?.id) else true }
    }

    suspend fun replaceQueue(source: QueueSource): Boolean = command(interruptSelection=true) { replaceQueueLocked(source) }

    private suspend fun replaceQueueLocked(source: QueueSource): Boolean = replaceQueueLocked(listOf(source),0,shuffleEnabled,false,false)

    private suspend fun replaceQueueLocked(sources: List<QueueSource>, start: Int, shuffle: Boolean, play: Boolean,
        acceptedOnly: Boolean, current: () -> Boolean = {true}): Boolean {
        if (sources.size !in 1..1000 || start !in sources.indices || sources.any {
                it.title.length !in 1..512 || (it.artist?.length ?: 0)>512 || (it.album?.length ?: 0)>512 || (it.durationUs ?: 0)<0
            } || !controller.pause()) return false
        focus.abandon()
        try { withContext(NonCancellable) {
            withContext(Dispatchers.IO) { closeSources() }
            sources.forEach {addEntry(it)};queueIndex=start;shuffleEnabled=shuffle
            if (shuffle) {
                val id=entries[start].item.id
                val order=listOf(id)+shuffleOrder(entries.map {it.item.id}.filter {it!=id})
                check(order.size==entries.size && order.toSet()==entries.map {it.item.id}.toSet())
                playOrder.clear();playOrder.addAll(order)
            }
            fileMode=true;sourceSelectionFailed=false;publishQueue()
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { sourceSelectionFailed = true; return false }
        currentCoroutineContext().ensureActive()
        val selected=selectQueueLocked(start,false)
        if (selected && play && current()) playLocked()
        return acceptedOnly || selected
    }

    suspend fun selectQueue(id: String): Boolean = command(interruptSelection=true) {
        val index = entries.indexOfFirst { it.item.id == id }
        if (index < 0) return@command false
        selectQueueLocked(index, false)
    }

    suspend fun skipQueue(direction: Int): Boolean = command(interruptSelection=true) {
        if (direction !in listOf(-1, 1)) return@command false
        val next = mutableQueue.value.navigationIndex(direction)
        if (next !in entries.indices) return@command false
        selectQueueLocked(next, snapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING) && focus.held)
    }

    suspend fun seekQueue(index: Int, positionUs: Long): Boolean = command(interruptSelection=true) {
        if (index !in entries.indices || positionUs < 0) return@command false
        val active = snapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING) && focus.held
        selectQueueLocked(index, active, positionUs)
    }

    suspend fun moveQueue(id: String, target: Int): Boolean = command(interruptSelection=true) {
        moveQueueLocked(entries.indexOfFirst { it.item.id == id }, target)
    }

    suspend fun moveQueue(id: String, neighborId: String): Boolean = command(interruptSelection=true) {
        moveQueueLocked(playOrder.indexOf(id), playOrder.indexOf(neighborId), upcomingOnly = true)
    }

    private suspend fun moveQueueLocked(from: Int, target: Int, upcomingOnly: Boolean = false): Boolean {
        val moveInShuffleOrder = upcomingOnly && shuffleEnabled
        fun canMove(): Boolean {
            if (!upcomingOnly) return true
            val current = if (moveInShuffleOrder) entries.getOrNull(queueIndex)?.item?.id?.let(playOrder::indexOf) ?: -1
                else queueIndex
            return current >= 0 && from > current && target > current && kotlin.math.abs(from - target) == 1
        }
        if (from !in entries.indices || target !in entries.indices || !canMove()) return false
        val playing=snapshot.value
        val resume=pauseForEdit() ?: return false
        // Pausing can settle a gapless transition that advanced the current song.
        val moved = canMove()
        if (moved) {
            if (moveInShuffleOrder) {
                playOrder.add(target, playOrder.removeAt(from))
            } else {
                val current = entries.getOrNull(queueIndex)
                entries.add(target, entries.removeAt(from))
                queueIndex = entries.indexOf(current)
                if (!shuffleEnabled) {
                    playOrder.clear()
                    playOrder.addAll(entries.map { it.item.id })
                }
            }
            publishQueue()
        }
        val resumed = !resume || playLocked(playing.conversionAllowed && playing.queueId == queue.value.current?.id)
        return moved && resumed
    }

    suspend fun removeQueue(id: String): Boolean = command(interruptSelection=true) {
        val index = entries.indexOfFirst { it.item.id == id }
        if (index < 0) return@command false
        val playing=snapshot.value
        val resume=pauseForEdit() ?: return@command false
        val current = entries.getOrNull(queueIndex)
        val next=mutableQueue.value.navigationIndex(1).takeIf{it>=0} ?: mutableQueue.value.navigationIndex(-1)
        val successor=entries.getOrNull(next)?.item?.id
        val active = index == queueIndex && (resume || (snapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING) && focus.held))
        if (index == queueIndex && !controller.pause()) return@command false
        try { withContext(NonCancellable + Dispatchers.IO) {
            entries[index].track?.close() ?: entries[index].source.fileUri?.let(releaseSavedSource)
            entries[index].track = null
        } }
        catch (_: Exception) {
            if (index == queueIndex) { sourceSelectionFailed = true; focus.abandon() }
            return@command false
        }
        entries.removeAt(index)
        playOrder.remove(id)
        if (entries.isEmpty()) {
            selectedTrack = null; queueIndex = -1; publishQueue(); fileMode = false; sourceSelectionFailed = false
            focus.abandon(); return@command controller.close()
        }
        if (current?.item?.id == id) {
            selectedTrack = null
            queueIndex = entries.indexOfFirst{it.item.id==successor}.takeIf{it>=0} ?: index.coerceAtMost(entries.lastIndex)
            publishQueue()
            selectQueueLocked(queueIndex, active)
        } else { queueIndex = entries.indexOf(current); publishQueue();if(resume)playLocked(playing.conversionAllowed && playing.queueId==queue.value.current?.id) else true }
    }

    /** Called on the Media3 observation lane; stale terminal events cannot revive a replaced/paused play. */
    suspend fun onPlaybackState(observed: UsbPlaybackSnapshot): Boolean = command(save=false) {
        if (snapshot.value.generation != observed.generation || snapshot.value.state != observed.state) return@command false
        val timer=mutableSleepTimer.value
        if(observed.state==UsbPlaybackState.ENDED && timer.mode in listOf(SleepMode.SONG,SleepMode.ALBUM) &&
            (timer.blocks(queue.value,queue.value.current?.id.orEmpty()) || queue.value.current==null && timer.targetId==observed.trackId)) {
            mutableSleepTimer.value=SleepTimerState();publishQueue()
            pauseLocked();persistLocked();return@command false
        }
        val next=mutableQueue.value.navigationIndex(1,automatic=true)
        if (observed.state == UsbPlaybackState.ENDED && observed.trackId == selectedTrack?.id && focus.held && next>=0) {
            val result=selectQueueLocked(next, true);persistLocked();return@command result
        }
        releaseTerminalFocus(observed.state, focus)
        if(observed.state in listOf(UsbPlaybackState.ENDED,UsbPlaybackState.ERROR))persistLocked()
        false
    }

    private fun addEntry(source: QueueSource, track: PcmTrack? = null) {
        restoreBlocked=false
        val item=QueueItem("evanna:local:queue:${UUID.randomUUID()}", source.title, source.artist, source.album, source.durationUs,source.musicTags)
        entries += OwnedQueueEntry(source,item,track)
        playOrder+=item.id
    }
    private fun publishQueue() {
        val state=PlaybackQueue(entries.map { it.item },queueIndex,saveFailed,restoreBlocked,shuffleEnabled,repeat,playOrder.toList(),historySaveFailed)
        val timer=mutableSleepTimer.value
        if(timer.mode in listOf(SleepMode.SONG,SleepMode.ALBUM) &&
            (if(timer.mode==SleepMode.SONG || timer.albumKey==null)(state.current?.id ?: selectedTrack?.id)!=timer.targetId else state.current?.let(::sleepAlbumKey)!=timer.albumKey))
            mutableSleepTimer.value=SleepTimerState()
        continuousQueue.set(GaplessQueue(state,entries.associate{it.item.id to it.source.create},
            state.items.filter {mutableSleepTimer.value.blocks(state,it.id)}.map {it.id}.toSet()))
        mutableQueue.value=state
    }

    private suspend fun selectQueueLocked(index: Int, continuePlaying: Boolean, positionUs: Long = 0): Boolean {
        if (!controller.pause()) return false
        if (!continuePlaying) focus.abandon()
        focusDenied = false; fileMode = true; queueIndex = index; selectedTrack = null; historyPlayId=UUID.randomUUID().toString();publishQueue()
        pendingPositionUs=positionUs
        val entry = entries[index]
        var acquired: PcmTrack? = null
        try {
            withContext(Dispatchers.IO) { acquired = entry.track ?: entry.source.create() }
            entry.track = checkNotNull(acquired); selectedTrack = acquired
            if (!controller.setPausedPosition(selectedTrack!!, minOf(positionUs, selectedTrack!!.format.durationUs ?: Long.MAX_VALUE))) return false
            sourceSelectionFailed = false
            record(DiagnosticEvent.SOURCE_SELECTED)
            return if (continuePlaying && focus.held) playLocked() else true
        } catch (cancelled: CancellationException) {
            // Retain a prepared source even when dispatch discards its result; Stop owns release.
            if (acquired != null) entry.track = acquired
            sourceSelectionFailed = true; focus.abandon(); throw cancelled
        } catch (_: Exception) {
            if (acquired != null) entry.track = acquired
            sourceSelectionFailed = true; focus.abandon(); record(DiagnosticEvent.SOURCE_SELECTION_FAILED); return false
        }
    }

    private fun closeSources() {
        for (entry in entries) {
            entry.track?.close() ?: entry.source.fileUri?.let(releaseSavedSource)
            if (entry.track === selectedTrack) selectedTrack = null
            entry.track = null
        }
        selectedTrack?.close(); selectedTrack = null
        entries.clear();playOrder.clear();queueIndex = -1;pendingPositionUs=0;restoreBlocked=false;publishQueue()
    }

    suspend fun pause(): Boolean = command(interruptSelection=true) {pauseLocked()}

    private suspend fun pauseLocked():Boolean {
        record(DiagnosticEvent.PAUSE)
        val completed=snapshot.value.state==UsbPlaybackState.ENDED
        val anchor=positionUs
        return try {
            val paused=controller.pause()
            if(paused)pendingPositionUs=if(completed)0 else sourcePositionUs ?: anchor
            if(paused && completed) {
                historyPlayId=UUID.randomUUID().toString()
                selectedTrack?.let { return controller.setPausedPosition(it,0) }
            }
            paused
        } finally { focus.abandon(); focusDenied = false }
    }

    suspend fun seekTo(positionUs: Long): Boolean = command(interruptSelection=true) {
        val track = selectedTrack?.takeIf { it.seekable && !sourceSelectionFailed } ?: return@command false
        require(positionUs >= 0)
        val position = minOf(positionUs, track.format.durationUs ?: Long.MAX_VALUE)
        val wasPlaying = snapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)
        val conversion = snapshot.value.conversionAllowed
        val wasEnded=snapshot.value.state==UsbPlaybackState.ENDED
        if (!controller.pause()) return@command false
        if (!controller.setPausedPosition(track, position)) return@command false
        if(wasEnded)historyPlayId=UUID.randomUUID().toString()
        if (!wasPlaying) return@command true
        if (!focus.held) return@command false
        try {
            val started = controller.start(track, conversion, position,continuation(),historyPlayId)
            if (!started || !focus.held) {
                val ended = snapshot.value.state == UsbPlaybackState.ENDED
                if (started && snapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING, UsbPlaybackState.PREPARING)) controller.pause()
                focus.abandon()
                started && ended
            } else true
        } catch (failure: Exception) { focus.abandon(); throw failure }
    }

    suspend fun close(): Boolean = command(interruptSelection=true) {
        mutableSleepTimer.value=SleepTimerState();publishQueue()
        record(DiagnosticEvent.CLOSE)
        try {
            pendingPositionUs=positionUs
            if (!controller.close()) return@command false
            try { withContext(NonCancellable + Dispatchers.IO) { closeSources() } }
            catch (_: Exception) { sourceSelectionFailed = true; return@command false }
            fileMode = false
            sourceSelectionFailed = false
            true
        } finally { focus.abandon(); focusDenied = false }
    }

    suspend fun shutdown():Boolean = command(save=false,interruptSelection=true) {
        mutableSleepTimer.value=SleepTimerState();publishQueue()
        val completed=snapshot.value.state==UsbPlaybackState.ENDED
        val anchor=positionUs
        if(!controller.pause())return@command false
        syncCompleted()
        pendingPositionUs=if(completed) 0 else sourcePositionUs ?: anchor
        persistLocked(pendingPositionUs)
        if(!controller.close())return@command false
        try {
            withContext(NonCancellable+Dispatchers.IO) {
                for(entry in entries){entry.track?.closeForRestore();entry.track=null}
                selectedTrack?.takeIf{entries.isEmpty()}?.closeForRestore();selectedTrack=null
            }
            focus.abandon();focusDenied=false;publishQueue();!saveFailed
        } catch(_:Exception){sourceSelectionFailed=true;focus.abandon();false}
    }
}
