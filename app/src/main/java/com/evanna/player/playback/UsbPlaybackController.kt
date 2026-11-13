package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.evanna.player.user.DacProfile
import com.evanna.player.user.isValidMaxUsbRate
import com.evanna.player.loudness.*
import com.evanna.player.equalizer.*

internal enum class UsbPlaybackState { CLOSED, OPEN, PREPARING, PLAYING, DRAINING, STOPPING, PAUSED, ENDED, ERROR }
internal enum class UsbPlaybackFailure {
    NOT_OPEN, UNSUPPORTED_FORMAT, CONVERSION_REQUIRED, STREAM_MISMATCH, CLOCK_MISMATCH,
    CLOCK_UNVERIFIED, FEEDBACK_UNVERIFIED, VOLUME_UNVERIFIED, DISCONNECTED, TRANSFER_FAILED,
    WRITE_FAILED, STOP_FAILED, CLOSE_FAILED, OUTPUT_FAILED, SOURCE_OPEN_FAILED, SOURCE_READ_FAILED, SOURCE_CLOSE_FAILED, SOURCE_SEEK_FAILED, DRAIN_UNVERIFIED, ROUTE_CHANGED,
}

internal data class UsbPlaybackReadiness(
    val clockConfigured: Boolean = false,
    val feedbackReady: Boolean = false,
    val volumeVerified: Boolean = false,
)

internal data class UsbPlaybackSnapshot(
    val generation: Long = 0,
    val state: UsbPlaybackState = UsbPlaybackState.CLOSED,
    val deviceId: Int? = null,
    val description: String? = null,
    val source: PcmFormat? = null,
    val trackId: String? = null,
    val framesAccepted: Long = 0,
    val conversionAllowed: Boolean = false,
    val plan: PlaybackPlan? = null,
    val stream: StreamInfo? = null,
    val stats: StreamStats? = null,
    val readiness: UsbPlaybackReadiness = UsbPlaybackReadiness(),
    val failure: UsbPlaybackFailure? = null,
    val positionUs: Long = 0,
    val startPositionUs: Long = 0,
    val route: PlaybackRoute? = if (deviceId != null) PlaybackRoute.DIRECT_USB else null,
    val androidStream: AndroidOutputInfo? = null,
    val usbPolicy: UsbPlaybackPolicy = UsbPlaybackPolicy.STRICT,
    val followSongRate: Boolean = true,
    val maxUsbRate:Int?=if(followSongRate)null else FOLLOW_SONG_RATE_CAP,
    val dacProfileKey:String?=null,
    val dacProfileName:String?=null,
    val queueId:String?=null,
    val sourceStartFrame:Long=0,
    val usbVolume:VolumeRange?=null,
    val startVolumeDb: Float = USB_START_VOLUME_DEFAULT_DB,
    val volumeLimitDb: Float = USB_VOLUME_LIMIT_DEFAULT_DB,
    val usbKeyStepDb: Float = USB_KEY_STEP_DEFAULT_DB,
    val historyStream:Long=0,val historyPlayId:String?=null,val historyQueueId:String?=null,
    val levelingMode:LevelingMode=LevelingMode.OFF,val leveling:LevelingDecision=LevelingDecision(),val appliedGainDb:Float?=null,
    /** The built-in EQ changed this stream's samples, at [eqPreampDb] (preamp plus auto headroom). */
    val eqProcessed:Boolean=false,val eqPreampDb:Float=0f,
    val crossfeed:Crossfeed=Crossfeed.OFF,val crossfeedProcessed:Boolean=false,
)

/** Backend evidence must come from the transport, never from requested settings. */
internal interface UsbPlaybackOutput : PlaybackOutput {
    /** Real Android adapters capture the post-open device identity; synthetic outputs may omit it. */
    val profileIdentityKey:String? get()=null
    override val route get() = PlaybackRoute.DIRECT_USB
    override val description: String
    /** Synthetic outputs may ignore it; plan/stream processing evidence then disagrees and playback fails closed. */
    fun setCrossfeed(level:Crossfeed) {}
    fun plan(source: PcmFormat): PlaybackPlan?
    /** Plans ordinary music at no more than [maxRate] Hz; outputs without a rate choice plan normally. */
    fun plan(source: PcmFormat, maxRate: Int): PlaybackPlan? = plan(source)
    /** Authorize silent startup; gain readback must finish before any PCM is accepted. */
    fun prepareVolume(): Boolean
    val usbVolume:VolumeRange? get()=null
    val appliedGainDb:Float? get()=null
    /** Only an inactive stream may change its hardware offset; zero preserves existing adapters. */
    fun setLevelingOffsetDb(db:Float):Boolean=db==0f
    fun adjustVolume(direction:Int):Boolean=false
    /** Sets the music gain to the grid value at or below [db]; same live, verified-stream rules as [adjustVolume]. */
    fun setVolume(db: Float): Boolean = false
    /** Gain for the first stream of a connection; later streams keep the last music gain. */
    var startVolumeDb: Float get() = USB_START_VOLUME_DEFAULT_DB; set(_) {}
    /** Music gain must be at or below this ceiling before any PCM is accepted. */
    var volumeLimitDb: Float get() = USB_VOLUME_LIMIT_DEFAULT_DB; set(_) {}
    fun start(source: PcmFormat): StreamInfo
    override fun startOutput(source: PcmFormat): StartedOutput = StartedOutput.Usb(start(source))
    /** Cached current clock/feedback/volume evidence; no per-block control transfers. */
    fun readiness(): UsbPlaybackReadiness
    override fun ready() = readiness().let { it.clockConfigured && it.feedbackReady && it.volumeVerified }
    override fun stats(): StreamStats
    override fun write(buffer: ByteBuffer, frames: Int): Int
    /** True only after bounded, verified queued-PCM completion. Unsupported outputs fail closed. */
    override fun drain(): Boolean = false
    /** Bounded stop: signal blocked writes; return true only when callbacks are quiescent. */
    override fun stop(): Boolean
    override fun close()
}

/** Permission handling stays outside; opening a connection never starts its writer. */
internal class UsbPlaybackController(
    /** The argument requests the explicit Android 14+ bit-perfect route. */
    private val openAndroidOutput: (Boolean) -> PlaybackOutput = { error("Android output is unavailable") },
    initialUsbPolicy: UsbPlaybackPolicy = UsbPlaybackPolicy.STRICT,
    private val saveUsbPolicy: (UsbPlaybackPolicy) -> Boolean = { true },
    initialFollowSongRate: Boolean = true,
    private val saveFollowSongRate: (Boolean) -> Boolean = { true },
    initialStartVolumeDb: Float = USB_START_VOLUME_DEFAULT_DB,
    private val saveStartVolumeDb: (Float) -> Boolean = { true },
    initialVolumeLimitDb: Float = USB_VOLUME_LIMIT_DEFAULT_DB,
    private val saveVolumeLimitDb: (Float) -> Boolean = { true },
    initialUsbKeyStepDb: Float = USB_KEY_STEP_DEFAULT_DB,
    private val saveUsbKeyStepDb: (Float) -> Boolean = { true },
    private val saveMaxUsbRate:(Int?)->Boolean={saveFollowSongRate(it==null)},
    private val saveLevelingMode:(LevelingMode)->Boolean={true},
    private val saveCrossfeed:(Crossfeed)->Boolean={true},
    private val resolveLeveling:suspend (LevelingMode,String?,PcmTrack)->LevelingDecision={mode,_,track -> levelingDecision(mode,track.loudnessTags)},
    private val openOutput: (Int) -> PlaybackOutput,
) {
    private val commands = Mutex()
    // Gain verification temporarily revokes native write permission; stop must remain independent.
    private val pcmBlocks = Mutex()
    private val listeningHistory=ListeningHistory()
    private var historyStream=0L
    private var historyLibraryIds:Map<String,String> = emptyMap()
    fun pendingHistory()=listeningHistory.pending()
    fun acknowledgeHistory(sequence:Long)=listeningHistory.acknowledge(sequence)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var usbPolicy = initialUsbPolicy
    private var maxUsbRate:Int?=if(initialFollowSongRate)null else FOLLOW_SONG_RATE_CAP
    private val followSongRate get()=maxUsbRate==null
    private var dacProfileKey:String?=null
    private var dacProfileName:String?=null
    private var startVolumeDb = validStartVolume(initialStartVolumeDb) ?: USB_START_VOLUME_DEFAULT_DB
    private var volumeLimitDb = validVolumeLimit(initialVolumeLimitDb) ?: USB_VOLUME_LIMIT_DEFAULT_DB
    private var usbKeyStepDb = validUsbKeyStep(initialUsbKeyStepDb) ?: USB_KEY_STEP_DEFAULT_DB
    private var levelingMode=LevelingMode.OFF
    private var crossfeed=Crossfeed.OFF
    private val mutableSnapshot = MutableStateFlow(UsbPlaybackSnapshot(usbPolicy = initialUsbPolicy, followSongRate = initialFollowSongRate,
        startVolumeDb = startVolumeDb, volumeLimitDb = volumeLimitDb, usbKeyStepDb = usbKeyStepDb))
    val snapshot = mutableSnapshot.asStateFlow()
    private var generation = 0L
    private var output: PlaybackOutput? = null
    private var writer: Job? = null
    private var reader: PcmReader? = null
    private var sequence:GaplessReader?=null
    val completedTrack:PcmTrack? get()=sequence?.completed(mutableSnapshot.value.stats?.framesCompleted ?: 0)?.track
    private var transportStarted = false
    private var quarantined = false

    suspend fun open(deviceId: Int,profile:DacProfile?=null): Boolean {
        require(deviceId >= 0)
        return openRoute(deviceId, PlaybackRoute.DIRECT_USB,profile) { openOutput(deviceId) }
    }

    suspend fun openAndroid(bitPerfect:Boolean=false): Boolean = openRoute(null, PlaybackRoute.ANDROID) { openAndroidOutput(bitPerfect) }

    private suspend fun openRoute(deviceId: Int?, route: PlaybackRoute,profile:DacProfile?=null,create: () -> PlaybackOutput): Boolean = prepareCommand(releaseOnCancellation = true) {
        val replay = mutableSnapshot.value.state == UsbPlaybackState.ENDED
        invalidate()
        if (!withContext(NonCancellable) { releaseOutput() }) return@prepareCommand false
        if(profile!=null) {
            maxUsbRate=profile.maxRate;startVolumeDb=profile.startVolumeDb;volumeLimitDb=profile.volumeLimitDb;usbKeyStepDb=profile.keyStepDb
            dacProfileKey=profile.identity.key;dacProfileName=profile.identity.displayName
            levelingMode=profile.levelingMode
            crossfeed=profile.crossfeed
        }
        val previous = mutableSnapshot.value
        val position = if (replay) 0 else previous.positionUs
        mutableSnapshot.value = UsbPlaybackSnapshot(generation, UsbPlaybackState.PREPARING, deviceId,
            source = previous.source, trackId = previous.trackId, positionUs = position, startPositionUs = position, route = route, usbPolicy = usbPolicy,
            followSongRate = followSongRate, queueId=previous.queueId, startVolumeDb = startVolumeDb, volumeLimitDb = volumeLimitDb, usbKeyStepDb = usbKeyStepDb,
            maxUsbRate=maxUsbRate,dacProfileKey=dacProfileKey,dacProfileName=dacProfileName,levelingMode=levelingMode,crossfeed=crossfeed)
        try {
            currentCoroutineContext().ensureActive()
            output = create()
            check(output!!.route == route)
            if(profile!=null)(output as? UsbPlaybackOutput)?.profileIdentityKey?.let {check(it==profile.identity.key){"USB profile identity changed"}}
            currentCoroutineContext().ensureActive()
            mutableSnapshot.value = mutableSnapshot.value.copy(state = UsbPlaybackState.OPEN, description = output!!.description)
            true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (releaseOutput()) mutableSnapshot.value = closedSnapshot()
            }
            throw cancelled
        } catch (_: Exception) {
            withContext(NonCancellable) { if (releaseOutput()) fail(UsbPlaybackFailure.OUTPUT_FAILED) }
            false
        }
    }

    suspend fun start(source: PcmFixture, allowConversion: Boolean = false): Boolean =
        startSource(source, null, { FixtureReader(source) }, allowConversion)

    suspend fun start(track: PcmTrack, allowConversion: Boolean = false, positionUs: Long = 0,continuation:GaplessPlayback?=null,
        historyPlayId:String=java.util.UUID.randomUUID().toString()): Boolean {
        require(positionUs >= 0 && (positionUs == 0L || track.seekable))
        return startSource(track.format, track.id, track::open, allowConversion, positionUs,track,continuation,historyPlayId)
    }

    private suspend fun startSource(source: PcmFormat, trackId: String?, openReader: () -> PcmReader,
        allowConversion: Boolean, positionUs: Long = 0,track:PcmTrack?=null,continuation:GaplessPlayback?=null,
        historyPlayId:String=java.util.UUID.randomUUID().toString()): Boolean = prepareCommand(releaseOnCancellation = false) {
        if (quarantined) return@prepareCommand false
        val owned = output ?: return@prepareCommand fail(UsbPlaybackFailure.NOT_OPEN)
        invalidate()
        if (!withContext(NonCancellable) { stopWriter() }) return@prepareCommand false
        sequence=null
        val historyIds=continuation?.libraryIds.orEmpty()
        historyLibraryIds=historyIds
        mutableSnapshot.value = mutableSnapshot.value.copy(state = UsbPlaybackState.PREPARING,
            source = source, trackId = trackId, framesAccepted = 0,
            conversionAllowed = allowConversion && owned.route == PlaybackRoute.DIRECT_USB && usbPolicy == UsbPlaybackPolicy.STRICT,
            positionUs = positionUs, startPositionUs = positionUs,
            plan = null, stream = null, androidStream = null, stats = null,
            readiness = UsbPlaybackReadiness(), failure = null,queueId=null,sourceStartFrame=0,usbVolume=null,
            historyStream=++historyStream,historyPlayId=historyPlayId,historyQueueId=continuation?.queueId,leveling=LevelingDecision(),appliedGainDb=null,eqProcessed=false,eqPreampDb=0f,
            crossfeedProcessed=false)
        try {
            currentCoroutineContext().ensureActive()
            val usb = owned as? UsbPlaybackOutput
            val streamLeveling=if(usb!=null && track!=null)resolveLeveling(levelingMode,continuation?.libraryIds?.get(continuation.queueId),track) else LevelingDecision()
            check(streamLeveling.mode==if(usb!=null && track!=null)levelingMode else LevelingMode.OFF)
            mutableSnapshot.update {it.copy(leveling=streamLeveling)}
            var plan: PlaybackPlan? = null
            if (owned.route == PlaybackRoute.DIRECT_USB) {
                check(usb != null)
                if(!usb.setLevelingOffsetDb(streamLeveling.offsetDb))return@prepareCommand fail(UsbPlaybackFailure.VOLUME_UNVERIFIED)
                val feed=if(source is TrackPcmFormat)crossfeed else Crossfeed.OFF
                usb.setCrossfeed(feed)
                // Its sine gain never exceeds 0 dB (native tests), and the EQ's limiter runs before it.
                val crossfed=feed!=Crossfeed.OFF && source.channels==2
                val cap=maxUsbRate
                plan = if (cap==null) usb.plan(source) else usb.plan(source,cap)
                mutableSnapshot.update {it.copy(plan=plan,crossfeedProcessed=plan!=null && crossfed)}
                planFailure(source,plan,true,maxUsbRate)?.let {return@prepareCommand fail(it)}
                if(plan!=null && plan.processed!=crossfed)return@prepareCommand fail(UsbPlaybackFailure.STREAM_MISMATCH)
                mutableSnapshot.value = mutableSnapshot.value.copy(plan = plan)
                planFailure(source, plan, allowConversion || usbPolicy == UsbPlaybackPolicy.COMPATIBILITY,maxUsbRate)?.let { return@prepareCommand fail(it) }
                usb.startVolumeDb = startVolumeDb
                usb.volumeLimitDb = volumeLimitDb
                if (!usb.prepareVolume()) return@prepareCommand fail(UsbPlaybackFailure.VOLUME_UNVERIFIED)
            }
            // The explicit bit-perfect Android route never converts: other sources need the ordinary Android route.
            val androidBitPerfect=(owned as? AndroidAudioOutput)?.bitPerfect==true
            if(androidBitPerfect && !source.strictEligible)return@prepareCommand fail(UsbPlaybackFailure.UNSUPPORTED_FORMAT)
            currentCoroutineContext().ensureActive()
            try {
                reader = openReader()
                val actual = reader!!.format
                check(actual.sampleRate == source.sampleRate && actual.significantBits == source.significantBits &&
                    actual.channels == source.channels && actual.sourceBits == source.sourceBits &&
                    actual.codec == source.codec && actual.strictEligible == source.strictEligible)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@prepareCommand abortStart(UsbPlaybackFailure.SOURCE_OPEN_FAILED) }
            try { if (positionUs > 0) reader!!.seekTo(positionUs) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@prepareCommand abortStart(UsbPlaybackFailure.SOURCE_SEEK_FAILED) }
            if(track?.gaplessEligible==true && continuation!=null && (usb==null || plan?.resampled==false)) {
                val decisions=java.util.concurrent.ConcurrentHashMap<String,Pair<LoudnessTags,LevelingDecision>>().apply {put(continuation.queueId,track.loudnessTags to streamLeveling)}
                val continuous=GaplessReader(continuation.queueId,track,checkNotNull(reader),continuation.queue,continuation.focused,
                    { next -> if(usb==null)!androidBitPerfect || next.strictEligible else usbPolicy==UsbPlaybackPolicy.COMPATIBILITY || (next.strictEligible && plan?.bitPerfect==true) },
                    {owned.stats().framesCompleted},positionUs,historyPlayId,successorAllowed={id,next ->
                        if(usb==null || levelingMode==LevelingMode.OFF)true else {
                            val cached=decisions[id]
                            if(cached!=null && cached.first!=next.loudnessTags)false else {
                                val decision=cached?.second ?: resolveLeveling(levelingMode,historyIds[id],next).also {decisions[id]=next.loudnessTags to it}
                                decision.mode==streamLeveling.mode && decision.offsetDb==streamLeveling.offsetDb
                            }
                        }
                    }) {segment,end ->
                        listeningHistory.progress(segment.playId,historyIds[segment.queueId],mutableSnapshot.value.historyStream,
                            (end-segment.startFrame).coerceAtLeast(0),source.sampleRate,segment.track.format.durationUs,segment.startFrame)
                    }
                sequence=continuous;reader=continuous
                mutableSnapshot.value=mutableSnapshot.value.copy(queueId=continuation.queueId)
            }
            currentCoroutineContext().ensureActive()
            // A throwing start may still own partially submitted native transfers.
            transportStarted = true
            when (val started = owned.startOutput(source)) {
                is StartedOutput.Usb -> {
                    if (owned.route != PlaybackRoute.DIRECT_USB || plan == null) return@prepareCommand abortStart(UsbPlaybackFailure.STREAM_MISMATCH)
                    mutableSnapshot.value = mutableSnapshot.value.copy(stream = started.info)
                    streamFailure(source, plan, started.info)?.let { return@prepareCommand abortStart(it) }
                }
                is StartedOutput.Android -> {
                    if (owned.route != PlaybackRoute.ANDROID || started.info.sampleRate != source.sampleRate || started.info.channels != source.channels)
                        return@prepareCommand abortStart(UsbPlaybackFailure.STREAM_MISMATCH)
                    mutableSnapshot.value = mutableSnapshot.value.copy(androidStream = started.info)
                }
            }
            currentCoroutineContext().ensureActive()
            healthFailure(owned)?.let { return@prepareCommand abortStart(it) }
            currentCoroutineContext().ensureActive()
            mutableSnapshot.value = mutableSnapshot.value.copy(state = UsbPlaybackState.PLAYING)
            val session = generation
            val input = checkNotNull(reader)
            writer = scope.launch(start = CoroutineStart.LAZY) { writeSource(owned, input, session) }
            writer!!.start()
            true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (stopWriter()) mutableSnapshot.value = mutableSnapshot.value.copy(state = UsbPlaybackState.PAUSED)
            }
            throw cancelled
        } catch (_: Exception) {
            abortStart(UsbPlaybackFailure.OUTPUT_FAILED)
        }
    }

    private suspend fun prepareCommand(releaseOnCancellation: Boolean, prepare: suspend () -> Boolean): Boolean {
        var completedSession: Long? = null
        try {
            return withContext(Dispatchers.IO) {
                commands.withLock {
                    prepare().also { if (it) completedSession = generation }
                }
            }
        } catch (cancelled: CancellationException) {
            // Cancellation can discard a successful result when dispatching back to the caller.
            if (completedSession != null) withContext(NonCancellable + Dispatchers.IO) {
                commands.withLock {
                    if (completedSession == generation) {
                        invalidate()
                        if (releaseOnCancellation) {
                            if (releaseOutput()) mutableSnapshot.value = closedSnapshot()
                        } else if (stopWriter()) {
                            mutableSnapshot.value = mutableSnapshot.value.copy(state = UsbPlaybackState.PAUSED, failure = null)
                        }
                    }
                }
            }
            throw cancelled
        }
    }

    suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
        commands.withLock {
            if (quarantined) return@withLock false
            withContext(NonCancellable) {
                invalidate()
                if (!stopWriter()) return@withContext false
                mutableSnapshot.value = if (output == null) closedSnapshot()
                    else mutableSnapshot.value.copy(state = UsbPlaybackState.PAUSED, failure = null)
                true
            }
        }
    }

    suspend fun adjustUsbVolume(direction:Int,expectedGeneration:Long):Boolean =
        direction in listOf(-1,1) && changeUsbVolume(expectedGeneration) { usb ->
            usb.usbVolume?.adjusted(direction,usbKeyStepDb)?.let {setBoundedVolume(usb,it)} ?: false
        }

    /** Slider target: the output rounds down to its gain grid, never above 0 dB. */
    suspend fun setUsbVolume(db:Float,expectedGeneration:Long):Boolean =
        db.isFinite() && changeUsbVolume(expectedGeneration) { setBoundedVolume(it,db) }

    private fun setBoundedVolume(usb:UsbPlaybackOutput,db:Float):Boolean {
        val gain=usb.usbVolume ?: return false
        if(volumeLimitDb<gain.minDb)return false
        return usb.setVolume(gain.snapDown(minOf(db,volumeLimitDb)) ?: return false)
    }

    suspend fun setUsbKeyStep(db:Float,expectedProfileKey:String?=null):Boolean=withContext(Dispatchers.IO) {
        commands.withLock {
            val value=validUsbKeyStep(db) ?: return@withLock false
            if(dacProfileKey!=expectedProfileKey)return@withLock false
            if(quarantined)return@withLock false
            if(usbKeyStepDb==value)return@withLock true
            withContext(NonCancellable) {
                if(!runCatching {saveUsbKeyStepDb(value)}.getOrDefault(false))return@withContext false
                usbKeyStepDb=value;mutableSnapshot.update {it.copy(usbKeyStepDb=value)};true
            }
        }
    }

    suspend fun setVolumeLimit(db:Float,expectedProfileKey:String?=null):Boolean=withContext(Dispatchers.IO) {
        pcmBlocks.withLock {commands.withLock {
            val value=validVolumeLimit(db) ?: return@withLock false
            if(dacProfileKey!=expectedProfileKey)return@withLock false
            if(quarantined)return@withLock false
            if(volumeLimitDb==value)return@withLock true
            // Once saved, cancellation must not leave a live stream above the new ceiling.
            withContext(NonCancellable) {
                if(!runCatching {saveVolumeLimitDb(value)}.getOrDefault(false))return@withContext false
                volumeLimitDb=value
                val usb=output as? UsbPlaybackOutput
                usb?.volumeLimitDb=value
                val before=mutableSnapshot.value
                val live=usb!=null && before.state in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING)
                mutableSnapshot.update {it.copy(volumeLimitDb=value,usbVolume=if(live)it.usbVolume else null)}
                if(live) {
                    val gain=before.usbVolume
                    if(gain!=null)return@withContext applyVolumeChange(usb!!) {setBoundedVolume(it,gain.currentDb)}
                    // Analysis fixtures cannot have their calibrated gain changed live.
                    invalidate();if(!stopWriter())return@withContext false
                    mutableSnapshot.update {it.copy(state=UsbPlaybackState.PAUSED,failure=null)}
                }
                true
            }
        }}
    }

    /** Saved gain for the first stream of each new USB connection; streams already open keep their gain. */
    suspend fun setStartVolume(db: Float,expectedProfileKey:String?=null): Boolean = withContext(Dispatchers.IO) {
        commands.withLock {
            val value = validStartVolume(db) ?: return@withLock false
            if(dacProfileKey!=expectedProfileKey)return@withLock false
            if (quarantined) return@withLock false
            if (startVolumeDb == value) return@withLock true
            withContext(NonCancellable) {
                val saved = try { saveStartVolumeDb(value) } catch (_: Exception) { false }
                if (!saved) return@withContext false
                startVolumeDb = value
                mutableSnapshot.update { it.copy(startVolumeDb = value) }
                true
            }
        }
    }

    private suspend fun changeUsbVolume(expectedGeneration:Long, change:(UsbPlaybackOutput)->Boolean):Boolean {
        var touched:Long?=null
        try {
            return withContext(Dispatchers.IO) {
                pcmBlocks.withLock {
                    commands.withLock command@{
                        val usb=output as? UsbPlaybackOutput ?: return@command false
                        val now=mutableSnapshot.value
                        if(now.generation!=expectedGeneration ||
                            now.state !in listOf(UsbPlaybackState.PLAYING,UsbPlaybackState.DRAINING) || now.usbVolume==null)return@command false
                        touched=now.generation
                        applyVolumeChange(usb,change)
                    }
                }
            }
        }catch(cancelled:CancellationException) {
            withContext(NonCancellable+Dispatchers.IO){commands.withLock {
                if(touched!=null && touched==generation) {
                    invalidate()
                    if(stopWriter())mutableSnapshot.value=mutableSnapshot.value.copy(state=UsbPlaybackState.PAUSED,failure=null)
                }
            }}
            throw cancelled
        }
    }

    /** Called with PCM and control ownership; failure must stop PCM before returning. */
    private suspend fun applyVolumeChange(usb:UsbPlaybackOutput,change:(UsbPlaybackOutput)->Boolean):Boolean {
        val failure=try {if(change(usb))healthFailure(usb) else UsbPlaybackFailure.VOLUME_UNVERIFIED}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){UsbPlaybackFailure.VOLUME_UNVERIFIED}
        if(failure==null)return true
        invalidate()
        return withContext(NonCancellable) {if(!stopWriter())false else fail(failure)}
    }

    suspend fun setPausedPosition(track: PcmTrack, positionUs: Long): Boolean = withContext(Dispatchers.IO) {
        commands.withLock {
            require(positionUs >= 0 && (track.seekable || positionUs == 0L))
            if (quarantined || mutableSnapshot.value.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)) return@withLock false
            sequence=null
            mutableSnapshot.value = mutableSnapshot.value.copy(trackId = track.id, source = track.format,
                state = if (output == null) UsbPlaybackState.CLOSED else UsbPlaybackState.PAUSED,
                positionUs = positionUs, startPositionUs = positionUs, framesAccepted = 0, plan = null, stream = null, androidStream = null, stats = null, failure = null,
                conversionAllowed = false,queueId=null,sourceStartFrame=0)
            true
        }
    }

    suspend fun setUsbPolicy(policy: UsbPlaybackPolicy): Boolean = withContext(Dispatchers.IO) {
        commands.withLock {
            if (quarantined) return@withLock false
            if (usbPolicy == policy) return@withLock true
            // Finish save/publication atomically even if the caller leaves while disk IO is in flight.
            withContext(NonCancellable) {
                if (!pauseDirectUsbForSetting()) return@withContext false
                val saved = try { saveUsbPolicy(policy) } catch (_: Exception) { false }
                if (!saved) return@withContext false
                usbPolicy = policy
                mutableSnapshot.update { it.copy(usbPolicy = policy, conversionAllowed = false) }
                true
            }
        }
    }

    /** Like a mode change, this pauses direct USB so the next Play plans the stream again. */
    suspend fun setFollowSongRate(enabled:Boolean,expectedProfileKey:String?=null):Boolean=setMaxUsbRate(if(enabled)null else FOLLOW_SONG_RATE_CAP,expectedProfileKey)

    suspend fun setMaxUsbRate(rate:Int?,expectedProfileKey:String?=null):Boolean = withContext(Dispatchers.IO) {
        if(!isValidMaxUsbRate(rate))return@withContext false
        commands.withLock {
            if(dacProfileKey!=expectedProfileKey)return@withLock false
            if (quarantined) return@withLock false
            if (maxUsbRate == rate) return@withLock true
            withContext(NonCancellable) {
                if (!pauseDirectUsbForSetting()) return@withContext false
                val saved = try { saveMaxUsbRate(rate) } catch (_: Exception) { false }
                if (!saved) return@withContext false
                maxUsbRate = rate
                mutableSnapshot.update { it.copy(followSongRate = rate==null,maxUsbRate=rate,conversionAllowed = false) }
                true
            }
        }
    }

    suspend fun setLevelingMode(mode:LevelingMode,expectedProfileKey:String?):Boolean=withContext(Dispatchers.IO) {
        commands.withLock {
            if(dacProfileKey==null || dacProfileKey!=expectedProfileKey || quarantined)return@withLock false
            if(levelingMode==mode)return@withLock true
            withContext(NonCancellable) {
                if(!pauseDirectUsbForSetting())return@withContext false
                if(!runCatching {saveLevelingMode(mode)}.getOrDefault(false))return@withContext false
                levelingMode=mode;mutableSnapshot.update {it.copy(levelingMode=mode,leveling=LevelingDecision(),appliedGainDb=null,conversionAllowed=false)};true
            }
        }
    }

    /** Like leveling, a change pauses Direct USB; the next Play plans the stream again. */
    suspend fun setCrossfeed(level:Crossfeed,expectedProfileKey:String?):Boolean=withContext(Dispatchers.IO) {
        commands.withLock {
            if(dacProfileKey==null || dacProfileKey!=expectedProfileKey || quarantined)return@withLock false
            if(crossfeed==level)return@withLock true
            withContext(NonCancellable) {
                if(!pauseDirectUsbForSetting())return@withContext false
                if(!runCatching {saveCrossfeed(level)}.getOrDefault(false))return@withContext false
                crossfeed=level;mutableSnapshot.update {it.copy(crossfeed=level,crossfeedProcessed=false,conversionAllowed=false)};true
            }
        }
    }

    private suspend fun pauseDirectUsbForSetting(): Boolean {
        if (mutableSnapshot.value.route != PlaybackRoute.DIRECT_USB) return true
        val ended = mutableSnapshot.value.state == UsbPlaybackState.ENDED
        invalidate()
        if (!stopWriter()) return false
        val stopped = mutableSnapshot.value.copy(state = if (ended) UsbPlaybackState.ENDED else UsbPlaybackState.PAUSED,
            plan = null, stream = null, stats = null, failure = null, conversionAllowed = false)
        mutableSnapshot.value = if (output == null)
            stopped.copy(state = UsbPlaybackState.CLOSED, deviceId = null, description = null, route = null)
            else stopped
        return true
    }

    suspend fun close(): Boolean = withContext(Dispatchers.IO) {
        commands.withLock {
            withContext(NonCancellable) {
                invalidate()
                if (!releaseOutput()) return@withContext false
                sequence=null
                mutableSnapshot.value = closedSnapshot()
                true
            }
        }
    }

    private fun closedSnapshot() = UsbPlaybackSnapshot(generation, usbPolicy = usbPolicy, followSongRate = followSongRate,
        startVolumeDb = startVolumeDb, volumeLimitDb = volumeLimitDb, usbKeyStepDb = usbKeyStepDb, maxUsbRate = maxUsbRate,
        dacProfileKey = dacProfileKey, dacProfileName = dacProfileName, levelingMode = levelingMode, crossfeed = crossfeed)

    private fun invalidate() {
        mutableSnapshot.value = mutableSnapshot.value.copy(generation = ++generation,
            state = UsbPlaybackState.STOPPING, readiness = UsbPlaybackReadiness())
    }

    private fun fail(reason: UsbPlaybackFailure): Boolean {
        mutableSnapshot.value = mutableSnapshot.value.copy(state = UsbPlaybackState.ERROR, failure = reason, conversionAllowed = false)
        return false
    }

    private suspend fun abortStart(reason: UsbPlaybackFailure): Boolean = withContext(NonCancellable) {
        if (stopWriter()) fail(reason) else false
    }

    private suspend fun stopWriter(): Boolean {
        val task = writer
        try { reader?.requestCancel() } catch (_: Exception) { /* Join still guards reader ownership. */ }
        task?.cancel()
        val quiet = try {
            if (transportStarted || task != null || quarantined) output?.stop() == true else true
        } catch (_: Exception) { false }
        // Joining must not require the command mutex or run before native stop.
        val joined = withTimeoutOrNull(2000) { task?.join(); true } == true
        var readerClosed = true
        if (joined) try { reader?.close(); reader = null } catch (_: Exception) { readerClosed = false }
        if (!quiet || !joined) {
            quarantined = true
            return fail(UsbPlaybackFailure.STOP_FAILED)
        }
        writer = null
        transportStarted = false
        if (!readerClosed) { quarantined = true; return fail(UsbPlaybackFailure.SOURCE_CLOSE_FAILED) }
        if (quiet && task != null) try {
            val stats = output?.stats()
            mutableSnapshot.value = withPosition(mutableSnapshot.value.copy(stats = stats))
        } catch (_: Exception) { /* Preserve the last verified position if statistics are unavailable. */ }
        mutableSnapshot.value = mutableSnapshot.value.copy(readiness = UsbPlaybackReadiness(), conversionAllowed = false,usbVolume=null,appliedGainDb=null)
        return true
    }

    private suspend fun releaseOutput(): Boolean {
        if (!stopWriter()) return false
        try {
            output?.close()
            output = null
            quarantined = false
            return true
        } catch (_: Exception) {
            quarantined = true
            return fail(UsbPlaybackFailure.CLOSE_FAILED)
        }
    }

    private fun healthFailure(owned: PlaybackOutput, session: Long = generation): UsbPlaybackFailure? {
        val readiness = (owned as? UsbPlaybackOutput)?.readiness() ?: UsbPlaybackReadiness()
        val stats = owned.stats()
        mutableSnapshot.update {
            if (it.generation == session) withPosition(it.copy(readiness = readiness, stats = stats,usbVolume=(owned as? UsbPlaybackOutput)?.usbVolume,appliedGainDb=(owned as? UsbPlaybackOutput)?.appliedGainDb)) else it
        }
        return if (owned.route == PlaybackRoute.ANDROID) when {
            stats.disconnected -> UsbPlaybackFailure.ROUTE_CHANGED
            stats.transferErrors > 0 || !owned.ready() -> UsbPlaybackFailure.OUTPUT_FAILED
            else -> null
        } else healthFailure(readiness, stats)
    }

    private fun withPosition(snapshot: UsbPlaybackSnapshot): UsbPlaybackSnapshot {
        val rate = snapshot.stream?.outputRate ?: snapshot.androidStream?.sampleRate ?: return snapshot
        if (rate <= 0) return snapshot
        val delta = ((snapshot.stats?.framesCompleted ?: 0).coerceAtLeast(0).toDouble() * 1_000_000 / rate).toLong()
        val completed=snapshot.stats?.framesCompleted ?: 0
        val segment=sequence?.completed(completed)
        val anchor=segment?.startPositionUs ?: snapshot.startPositionUs
        val relative=if(segment!=null)((completed-segment.startFrame).coerceAtLeast(0).toDouble()*1_000_000/rate).toLong() else delta
        val position = anchor + relative.coerceAtMost(Long.MAX_VALUE-anchor)
        val source=segment?.track?.format ?: snapshot.source
        val playId=segment?.playId ?: snapshot.historyPlayId
        if(playId!=null)listeningHistory.progress(playId,historyLibraryIds[segment?.queueId ?: snapshot.historyQueueId],snapshot.historyStream,
            (completed-(segment?.startFrame ?: 0)).coerceAtLeast(0),rate,source?.durationUs,segment?.startFrame ?: 0)
        return snapshot.copy(positionUs=minOf(position,source?.durationUs ?: Long.MAX_VALUE),
            startPositionUs=anchor,source=source,trackId=segment?.track?.id ?: snapshot.trackId,
            queueId=segment?.queueId ?: snapshot.queueId,sourceStartFrame=segment?.startFrame ?: snapshot.sourceStartFrame,
            historyPlayId=playId,historyQueueId=segment?.queueId ?: snapshot.historyQueueId,
            conversionAllowed=snapshot.conversionAllowed && (segment==null || segment.startFrame==0L))
    }

    private fun healthFailure(readiness: UsbPlaybackReadiness, stats: StreamStats): UsbPlaybackFailure? = when {
        stats.disconnected -> UsbPlaybackFailure.DISCONNECTED
        stats.transferErrors > 0 || stats.underruns > 0 -> UsbPlaybackFailure.TRANSFER_FAILED
        !readiness.clockConfigured -> UsbPlaybackFailure.CLOCK_UNVERIFIED
        !readiness.feedbackReady -> UsbPlaybackFailure.FEEDBACK_UNVERIFIED
        !readiness.volumeVerified -> UsbPlaybackFailure.VOLUME_UNVERIFIED
        else -> null
    }

    private suspend fun writeSource(owned: PlaybackOutput, input: PcmReader, session: Long) {
        var failure: UsbPlaybackFailure? = null
        var ended = false
        val sound = SoundEngine()
        var lastEqProcessed = false
        var lastEqPreampDb = 0f
        try {
            val frames = (input.format.sampleRate / 100).coerceIn(1, 4096)
            val buffer = ByteBuffer.allocateDirect(frames * input.format.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
            var accepted = 0L
            while (currentCoroutineContext().isActive) {
                val count = try { input.read(buffer, frames) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failure = UsbPlaybackFailure.SOURCE_READ_FAILED; break }
                currentCoroutineContext().ensureActive()
                if (count !in 0..frames) { failure = UsbPlaybackFailure.SOURCE_READ_FAILED; break }
                if (count == 0) {
                    pcmBlocks.withLock {
                        currentCoroutineContext().ensureActive()
                        mutableSnapshot.update { if (it.generation == session) it.copy(state = UsbPlaybackState.DRAINING) else it }
                        val drained = owned.drain()
                        currentCoroutineContext().ensureActive()
                        failure = healthFailure(owned, session) ?: if (drained) null else UsbPlaybackFailure.DRAIN_UNVERIFIED
                        currentCoroutineContext().ensureActive()
                        ended = failure == null
                    }
                    break
                }
                val submitted=pcmBlocks.withLock {
                    failure = healthFailure(owned, session)
                    currentCoroutineContext().ensureActive()
                    if(failure!=null)return@withLock false
                    val pcm = buffer.duplicate().apply { limit(count * input.format.channels * 4) }.slice().order(ByteOrder.LITTLE_ENDIAN)
                    // Every output gets the same EQ here, before the Android/Direct USB split.
                    if (input.format is TrackPcmFormat) {
                        sound.process(pcm, count, input.format.channels, input.format.sampleRate, SoundSettings.config.value)
                        if (sound.active != lastEqProcessed || sound.gainDb != lastEqPreampDb) {
                            lastEqProcessed = sound.active; lastEqPreampDb = sound.gainDb
                            mutableSnapshot.update { if (it.generation == session) it.copy(eqProcessed = sound.active, eqPreampDb = sound.gainDb) else it }
                        }
                    }
                    val written = owned.write(pcm, count)
                    currentCoroutineContext().ensureActive()
                    if (written != count) {
                        failure = healthFailure(owned, session) ?: UsbPlaybackFailure.WRITE_FAILED
                        false
                    }else {
                        accepted = Math.addExact(accepted, written.toLong())
                        mutableSnapshot.update { if (it.generation == session) it.copy(framesAccepted = accepted) else it }
                        true
                    }
                }
                if(!submitted)break
            }
        } catch (_: CancellationException) {
            // The command lane already owns stop/join and the next state.
        } catch (_: Exception) {
            failure = UsbPlaybackFailure.OUTPUT_FAILED
        }
        if (failure == null && !ended) return
        // Cleanup cannot be part of the writer job: pause/close joins it while holding commands.
        scope.launch {
            commands.withLock {
                if (session == generation && output === owned) {
                    invalidate()
                    withContext(NonCancellable) {
                        if (stopWriter()) {
                            if (failure != null) fail(failure!!)
                            else {
                                val now = mutableSnapshot.value
                                val end = now.startPositionUs + ((now.framesAccepted-now.sourceStartFrame).coerceAtLeast(0).toDouble() * 1_000_000 / input.format.sampleRate).toLong()
                                mutableSnapshot.value = now.copy(state = UsbPlaybackState.ENDED, failure = null,
                                    positionUs = minOf(end, now.source?.durationUs ?: Long.MAX_VALUE))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun planFailure(source: PcmFormat, plan: PlaybackPlan?, allowConversion: Boolean, maxRate:Int?=null): UsbPlaybackFailure? {
        if (plan == null || plan.outputRate <= 0 || plan.dacBits !in 1..32) return UsbPlaybackFailure.UNSUPPORTED_FORMAT
        if(source is TrackPcmFormat && maxRate!=null && plan.outputRate>maxRate)return UsbPlaybackFailure.UNSUPPORTED_FORMAT
        val resampled = plan.outputRate != source.sampleRate
        val unchanged = !resampled && plan.dacBits >= source.significantBits && !plan.processed
        if (plan.resampled != resampled || plan.bitPerfect != unchanged) return UsbPlaybackFailure.STREAM_MISMATCH
        // The saved "Follow song rate: off" choice authorizes only the rate reduction; other conversions still need permission.
        val chosenRate = maxRate!=null && source is TrackPcmFormat && source.sampleRate>maxRate && plan.outputRate < source.sampleRate &&
            plan.outputRate <= maxRate && plan.dacBits >= source.significantBits && !plan.processed
        return if (!allowConversion && (!(unchanged || chosenRate) || !source.strictEligible)) UsbPlaybackFailure.CONVERSION_REQUIRED else null
    }

    private fun streamFailure(source: PcmFormat, plan: PlaybackPlan, stream: StreamInfo): UsbPlaybackFailure? {
        val format = stream.format
        val supportedRate = format.sampleRates?.contains(stream.outputRate)
            ?: (stream.outputRate in format.minRate..format.maxRate)
        if (stream.sampleRate != source.sampleRate || stream.bitsPerSample != source.significantBits ||
            stream.channels != source.channels || format.channels != source.channels ||
            stream.outputRate != plan.outputRate || format.bitResolution != plan.dacBits ||
            stream.resampled != plan.resampled || stream.bitPerfect != plan.bitPerfect || stream.processed!=plan.processed ||
            format.subslotBytes !in 1..4 || format.bitResolution !in 1..(8 * format.subslotBytes) ||
            format.endpointAddress !in 1..15 || format.maxPacketBytes < format.channels * format.subslotBytes ||
            !supportedRate) return UsbPlaybackFailure.STREAM_MISMATCH
        if (stream.deviceRate < 0 || (stream.deviceRate != 0 && stream.deviceRate != stream.outputRate)) {
            return UsbPlaybackFailure.CLOCK_MISMATCH
        }
        return null
    }

    private class FixtureReader(override val format: PcmFixture) : PcmReader {
        private var frame = 0L
        override fun read(target: ByteBuffer, maxFrames: Int): Int {
            target.duplicate().put(format.block(frame, maxFrames))
            frame = Math.addExact(frame, maxFrames.toLong())
            return maxFrames
        }
        override fun requestCancel() = Unit
        override fun close() = Unit
    }
}
