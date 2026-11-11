package com.evanna.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.evanna.player.diagnostics.CrashRecorder
import com.evanna.player.diagnostics.DiagnosticEvent
import com.evanna.player.user.*
import com.evanna.player.usb.DacIdentity
import com.evanna.player.usb.dacIdentity

/** A CUE song plays only its exact frame range of the file; ranges were measured at the scanned rate. */
internal fun libraryTrack(context:Context,song:com.evanna.player.library.LibrarySong):PcmTrack {
    val file=com.evanna.player.decoder.DecodedAudioTrack.local(context,android.net.Uri.parse(song.uri))
    val start=song.startFrame ?: return file
    return try {
        if(file.format.sampleRate!=song.rate)throw java.io.IOException("LIBRARY_SONG_CHANGED")
        RangePcmTrack(file,start,song.endFrame)
    }catch(failure:Throwable){file.close();throw failure}
}

/** Retains the sole USB owner across service recreation, including failed cleanup. Never holds an Activity. */
internal object PlaybackRuntime {
    @Volatile private var current: UsbSessionOwner? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var focus: AndroidPlaybackFocus? = null
    /** Read-only analysis gate; querying it must never create a service or playback owner. */
    internal fun isUsbPreparing()=current?.snapshot?.value?.let {it.route==PlaybackRoute.DIRECT_USB && it.state==UsbPlaybackState.PREPARING}==true

    fun owner(context: Context): UsbSessionOwner {
        current?.let { return it }
        val app = context.applicationContext
        val preferences = app.getSharedPreferences("playback-settings", Context.MODE_PRIVATE)
        val policy = UsbPlaybackPolicy.fromStored(preferences.all["usb_policy"] as? String)
        val followSongRate = preferences.all["follow_song_rate"] as? Boolean ?: true
        val startVolume = (preferences.all["usb_start_volume_db"] as? Float)?.let(::validStartVolume) ?: USB_START_VOLUME_DEFAULT_DB
        val volumeLimit = (preferences.all["usb_volume_limit_db"] as? Float)?.let(::validVolumeLimit) ?: USB_VOLUME_LIMIT_DEFAULT_DB
        val usbKeyStep = (preferences.all["usb_key_step_db"] as? Float)?.let(::validUsbKeyStep) ?: USB_KEY_STEP_DEFAULT_DB
        val wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Evanna:usb-playback").apply { setReferenceCounted(false) }
        val gate = AndroidPlaybackFocus(app.getSystemService(AudioManager::class.java))
        val queueStore=PlaybackQueueStore(app)
        val profiles=UserData.database(app)
        fun saveFloat(key:String,value:Float):Boolean {
            val previous=preferences.all[key] as? Float
            val saved=runCatching {preferences.edit().putFloat(key,value).commit()}.getOrDefault(false)
            if(!saved)runCatching {preferences.edit().apply {if(previous==null)remove(key)else putFloat(key,previous)}.commit()}
            return saved
        }
        fun saveFollow(enabled:Boolean):Boolean {
            val previous=preferences.all["follow_song_rate"] as? Boolean ?: true
            val saved=runCatching {preferences.edit().putBoolean("follow_song_rate",enabled).commit()}.getOrDefault(false)
            if(!saved)runCatching {preferences.edit().putBoolean("follow_song_rate",previous).commit()}
            return saved
        }
        fun saveProfile(change:(DacProfile)->DacProfile,legacy:()->Boolean):Boolean {
            val key=current?.snapshot?.value?.dacProfileKey
            return if(key==null)legacy() else profiles.updateProfile(key,change)
        }
        val owner = UsbSessionOwner(UsbPlaybackController(openAndroidOutput = { bitPerfect -> openAndroidAudioOutput(app, bitPerfect) },
            initialUsbPolicy = policy, saveUsbPolicy = {
                val previous = preferences.all["usb_policy"] as? String
                val saved = runCatching { preferences.edit().putString("usb_policy", it.name).commit() }.getOrDefault(false)
                // A failed commit can still update the in-memory SharedPreferences map.
                if (!saved) runCatching { preferences.edit().putString("usb_policy", previous).commit() }
                saved
            }, initialFollowSongRate=followSongRate,saveFollowSongRate=::saveFollow,
            initialStartVolumeDb=startVolume,saveStartVolumeDb={value ->
                saveProfile({it.copy(startVolumeDb=value)}) {saveFloat("usb_start_volume_db",value)}
            }, initialVolumeLimitDb=volumeLimit,saveVolumeLimitDb={value ->
                saveProfile({it.copy(volumeLimitDb=value)}) {saveFloat("usb_volume_limit_db",value)}
            }, initialUsbKeyStepDb=usbKeyStep,saveUsbKeyStepDb={value ->
                saveProfile({it.copy(keyStepDb=value)}) {saveFloat("usb_key_step_db",value)}
            },saveMaxUsbRate={rate ->
                saveProfile({it.copy(maxRate=rate)}) {if(rate==null || rate==FOLLOW_SONG_RATE_CAP)saveFollow(rate==null)else false}
            },saveLevelingMode={mode -> saveProfile({it.copy(levelingMode=mode)}){false}},
            saveCrossfeed={level -> saveProfile({it.copy(crossfeed=level)}){false}},
            resolveLeveling={mode,id,track -> com.evanna.player.loudness.resolveLeveling(app,mode,id,track)}) { id ->
            val manager=app.getSystemService(android.hardware.usb.UsbManager::class.java)
            val expected=current?.snapshot?.value?.dacProfileKey
            if(expected!=null)check(manager.deviceList.values.firstOrNull {it.deviceId==id}?.dacIdentity()?.key==expected){"USB profile identity changed"}
            openUsbPlaybackOutput(manager,id)
        }, gate, { CrashRecorder.event(it) }, persistence=queueStore,
            restoreSource={ saved ->
                QueueSource(saved.libraryId,saved.title,saved.artist,saved.album,saved.durationUs,saved.fileUri,saved.musicTags) {
                    if(saved.libraryId!=null)libraryTrack(app,com.evanna.player.library.LocalLibrary.database(app).song(saved.libraryId)?.takeIf { it.available }
                        ?: throw java.io.IOException("LIBRARY_SONG_UNAVAILABLE"))
                    else com.evanna.player.decoder.DecodedAudioTrack.local(app,android.net.Uri.parse(saved.fileUri ?: throw java.io.IOException("SOURCE_RESELECTION_REQUIRED")))
                }
            }, releaseSavedSource={ uri -> com.evanna.player.decoder.releaseLocalSourceLease(app,android.net.Uri.parse(uri)) },
            saveHistory={profiles.saveHistory(it)})
        gate.onLoss = { CrashRecorder.event(DiagnosticEvent.FOCUS_LOSS); scope.launch(start=CoroutineStart.UNDISPATCHED) { owner.pause() } }
        focus = gate
        current = owner
        scope.launch {
            owner.snapshot.map {snapshot ->
                if(snapshot.route==PlaybackRoute.DIRECT_USB && snapshot.state==UsbPlaybackState.PLAYING && snapshot.levelingMode!=com.evanna.player.loudness.LevelingMode.OFF)
                    (snapshot.queueId ?: owner.queue.value.current?.id)?.let {Triple(it,snapshot.levelingMode,snapshot.generation)}
                else null
            }.distinctUntilChanged().collectLatest {request ->
                if(request==null)return@collectLatest
                try {
                val source=owner.queueSource(request.first) ?: return@collectLatest
                val id=source.libraryId ?: return@collectLatest
                val song=withContext(Dispatchers.IO){com.evanna.player.library.LocalLibrary.database(app).song(id)} ?: return@collectLatest
                if(request.second==com.evanna.player.loudness.LevelingMode.ALBUM && song.album!=null)
                    com.evanna.player.loudness.LibraryLoudnessAnalysis.requestAlbum(app,com.evanna.player.library.AlbumIdentity(song.album,song.musicTags.albumArtist))
                else if(request.second==com.evanna.player.loudness.LevelingMode.TRACK)
                    com.evanna.player.loudness.LibraryLoudnessAnalysis.requestTrack(app,id)
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){/* Analysis availability never interrupts playback. */}
            }
        }
        // Sleep ticking only while minutes mode is on; checkpointing only while music plays or a save
        // needs a retry. An idle cached process then wakes 0 times from these loops instead of
        // ~129,600 times a day, which Android 11 punishes with an excessive-resource kill.
        scope.launch {
            owner.sleepTimer.map { it.mode == SleepMode.MINUTES }.distinctUntilChanged().collectLatest { active ->
                if (active) while (coroutineContext.isActive) { delay(1000); owner.tickSleepTimer() }
            }
        }
        scope.launch {
            owner.initialize()
            combine(owner.snapshot, owner.queue) { snapshot, queue ->
                snapshot.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING) || queue.saveFailed || queue.historySaveFailed
            }.distinctUntilChanged().collectLatest { active ->
                if (active) while (coroutineContext.isActive) {
                    delay(2000)
                    try { owner.checkpoint() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
            }
        }
        scope.launch {
            var observedFormatGeneration = Long.MIN_VALUE
            owner.snapshot.collect {
                if (it.stream != null && it.generation != observedFormatGeneration) {
                    observedFormatGeneration = it.generation
                    CrashRecorder.event(DiagnosticEvent.FORMAT_OBSERVED, it.stream.outputRate.toLong(), it.stream.format.bitResolution.toLong())
                }
                CrashRecorder.playbackFailure(it)
            }
        }
        scope.launch {
            owner.snapshot.map {
                it.state in listOf(UsbPlaybackState.PREPARING, UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING, UsbPlaybackState.STOPPING)
            }.distinctUntilChanged().collectLatest { active ->
                if (active) try {
                    // Renew while active; a stalled main loop cannot leave an unbounded wake lock.
                    while (true) { wakeLock.acquire(600_000); delay(300_000) }
                } finally { if (wakeLock.isHeld) wakeLock.release() }
            }
        }
        return owner
    }

    internal fun profile(context:Context,identity:DacIdentity):DacProfile {
        val preferences=context.applicationContext.getSharedPreferences("playback-settings",Context.MODE_PRIVATE)
        val values=preferences.all
        val hasLegacy=listOf("follow_song_rate","usb_start_volume_db","usb_volume_limit_db","usb_key_step_db").any {values.containsKey(it)}
        val legacy=if(hasLegacy)LegacyUsbSettings(values["follow_song_rate"] as? Boolean ?: !isKnownJd1(identity),
            values["usb_start_volume_db"] as? Float ?: USB_START_VOLUME_DEFAULT_DB,
            values["usb_volume_limit_db"] as? Float ?: USB_VOLUME_LIMIT_DEFAULT_DB,
            values["usb_key_step_db"] as? Float ?: USB_KEY_STEP_DEFAULT_DB) else null
        return UserData.database(context).profileFor(identity,legacy)
    }

    fun beforeFocusRequest(action: (() -> Unit)?) { focus?.beforeRequest = action }
    fun diagnosticSnapshot(): UsbPlaybackSnapshot? = current?.snapshot?.value
    fun diagnosticFocusHeld(): Boolean? = focus?.held

    private class AndroidPlaybackFocus(private val manager: AudioManager) : PlaybackFocus {
        var onLoss: () -> Unit = {}
        var beforeRequest: (() -> Unit)? = null
        private var wanted = false
        @Volatile override var held = false
            private set
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_GAIN) { if (wanted) held = true }
                else { held = false; wanted = false; onLoss() }
            }, Handler(Looper.getMainLooper())).build()

        override fun request(): Boolean {
            if (held) return true
            wanted = true
            held = try {
                beforeRequest?.invoke()
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }
            if (!held) wanted = false
            else CrashRecorder.event(DiagnosticEvent.FOCUS_GAIN)
            return held
        }
        override fun abandon() {
            if (wanted || held) manager.abandonAudioFocusRequest(request)
            wanted = false
            held = false
        }
    }
}
