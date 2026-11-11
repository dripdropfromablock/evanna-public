package com.evanna.player.playback

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.MainActivity
import com.evanna.player.usb.dacIdentity
import com.evanna.player.usb.rememberDac
import com.truesample.usbaudio.UsbAudioOutput
import com.evanna.player.decoder.DecodedAudioTrack
import com.evanna.player.decoder.inspectLocalQueueSource
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.LibraryContextAction
import com.evanna.player.library.LibrarySong
import com.evanna.player.library.libraryContextRequest
import com.evanna.player.library.hasTreeReadGrant
import com.evanna.player.user.*
import com.evanna.player.equalizer.*
import com.google.common.util.concurrent.*
import kotlinx.coroutines.*

@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: EvannaPlayer
    private lateinit var owner: UsbSessionOwner
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { scope.launch(start=CoroutineStart.UNDISPATCHED) { owner.pause() } }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                @Suppress("DEPRECATION") val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (device?.deviceId == owner.snapshot.value.deviceId) { scope.launch(start=CoroutineStart.UNDISPATCHED) { owner.shutdown() } }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        owner = PlaybackRuntime.owner(this)
        player = EvannaPlayer(owner)
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.uid != applicationInfo.uid && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                if (controller.uid == applicationInfo.uid) {
                    commands.add(SessionCommand(OPEN_USB, Bundle.EMPTY))
                    commands.add(SessionCommand(ADJUST_USB_VOLUME, Bundle.EMPTY))
                    commands.add(SessionCommand(SET_USB_VOLUME, Bundle.EMPTY))
                    commands.add(SessionCommand(SET_USB_START_VOLUME, Bundle.EMPTY))
                    commands.add(SessionCommand(SET_USB_VOLUME_LIMIT, Bundle.EMPTY))
                    commands.add(SessionCommand(SET_USB_KEY_STEP, Bundle.EMPTY))
                    commands.add(SessionCommand(OPEN_ANDROID, Bundle.EMPTY))
                    commands.add(SessionCommand(SELECT_FILE, Bundle.EMPTY))
                    commands.add(SessionCommand(PLAY_CONVERTED, Bundle.EMPTY))
                    commands.add(SessionCommand(SET_USB_POLICY, Bundle.EMPTY))
                    commands.add(SessionCommand(SET_FOLLOW_SONG_RATE, Bundle.EMPTY))
                    commands.add(SessionCommand(SET_MAX_USB_RATE,Bundle.EMPTY))
                    commands.add(SessionCommand(SET_LEVELING_MODE,Bundle.EMPTY))
                    commands.add(SessionCommand(SET_CROSSFEED,Bundle.EMPTY))
                    commands.add(SessionCommand(SET_EQUALIZER,Bundle.EMPTY))
                    commands.add(SessionCommand(SET_SLEEP_TIMER,Bundle.EMPTY))
                    commands.add(SessionCommand(SAVE_QUEUE_PLAYLIST,Bundle.EMPTY))
                    commands.add(SessionCommand(SET_FAVORITE,Bundle.EMPTY))
                    commands.add(SessionCommand(ARTWORK_SOURCE, Bundle.EMPTY))
                    for (action in listOf(ENQUEUE_LIBRARY, PLAY_NEXT_LIBRARY, SELECT_LIBRARY, SELECT_LIBRARY_CONTEXT, MOVE_QUEUE, REMOVE_QUEUE, SELECT_QUEUE)) commands.add(SessionCommand(action, Bundle.EMPTY))
                }
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands.build()).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.uid != applicationInfo.uid)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val profileKey=args.getString(EvannaPlayer.DAC_PROFILE_KEY)
                if(customCommand.customAction==SET_EQUALIZER)return equalizerCommand(args,profileKey)
                if(customCommand.customAction==SET_FAVORITE) {
                    val id=args.getString(QUEUE_ID)?.takeIf {it.length in 1..100}
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    if(!args.containsKey(EvannaPlayer.FAVORITE))return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    val selected=args.getBoolean(EvannaPlayer.FAVORITE)
                    return result(player.favoriteCurrent(id){UserData.database(applicationContext).setFavorite(it,selected)})
                }
                if(customCommand.customAction==SAVE_QUEUE_PLAYLIST) {
                    val name=args.getString(PLAYLIST_NAME)?.let(::validPlaylistName)
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.saveQueueAsPlaylist {sources ->
                        val items=sources.map {source ->source.libraryId?.let {playlistFile(applicationContext,it)?.entry}
                            ?: PlaylistEntry(null,source.title.take(512),source.artist?.take(512),source.album?.take(512),source.durationUs?.takeIf {it in 0..604_800_000_000L})}
                        UserData.database(applicationContext).savePlaylist(null,name,items)!=null
                    })
                }
                if(customCommand.customAction==SET_SLEEP_TIMER) {
                    val mode=SleepMode.entries.firstOrNull {it.name==args.getString(SLEEP_MODE)}
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setSleepTimer(mode,args.getInt(SLEEP_MINUTES,0)))
                }
                if(customCommand.customAction in listOf(SET_MAX_USB_RATE,SET_FOLLOW_SONG_RATE,SET_USB_START_VOLUME,SET_USB_VOLUME_LIMIT,SET_USB_KEY_STEP,SET_LEVELING_MODE,SET_CROSSFEED) &&
                    profileKey!=owner.snapshot.value.dacProfileKey)return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                if(customCommand.customAction==ADJUST_USB_VOLUME) {
                    val direction=args.getInt(VOLUME_DIRECTION,0)
                    val generation=args.getLong(VOLUME_GENERATION,-1)
                    if(direction !in listOf(-1,1) || generation<0)return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return volumeResult(player.adjustUsbVolume(direction,generation))
                }
                if(customCommand.customAction==SET_USB_VOLUME) {
                    val db=args.getFloat(VOLUME_DB,Float.NaN)
                    val generation=args.getLong(VOLUME_GENERATION,-1)
                    if(!db.isFinite() || db !in -128f..0f || generation<0)return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return volumeResult(player.setUsbVolume(db,generation))
                }
                if(customCommand.customAction==SET_USB_START_VOLUME) {
                    val db=validStartVolume(args.getFloat(VOLUME_DB,Float.NaN))
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setStartVolume(db,profileKey))
                }
                if(customCommand.customAction==SET_USB_VOLUME_LIMIT) {
                    val db=validVolumeLimit(args.getFloat(VOLUME_DB,Float.NaN))
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setVolumeLimit(db,profileKey))
                }
                if(customCommand.customAction==SET_USB_KEY_STEP) {
                    val db=validUsbKeyStep(args.getFloat(VOLUME_DB,Float.NaN))
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setUsbKeyStep(db,profileKey))
                }
                if(customCommand.customAction==SET_LEVELING_MODE) {
                    val mode=com.evanna.player.loudness.LevelingMode.entries.firstOrNull {it.name==args.getString(EvannaPlayer.LEVELING_MODE)}
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setLevelingMode(mode,profileKey))
                }
                if(customCommand.customAction==SET_CROSSFEED) {
                    val level=com.evanna.player.equalizer.Crossfeed.entries.firstOrNull {it.name==args.getString(EvannaPlayer.CROSSFEED)}
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setCrossfeed(level,profileKey))
                }
                if (customCommand.customAction == ARTWORK_SOURCE) {
                    val id = args.getString(QUEUE_ID)?.takeIf { it.length in 1..100 }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return Futures.transform(player.queueSource(id), { source ->
                        if (source == null) SessionResult(SessionError.ERROR_BAD_VALUE)
                        else SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                            putString(QUEUE_ID, id); putString(LIBRARY_ID, source.libraryId); putString(FILE_URI, source.fileUri)
                        })
                    }, Runnable::run)
                }
                if (customCommand.customAction == SELECT_LIBRARY_CONTEXT) {
                    val personal=PersonalView.entries.firstOrNull {it.name==args.getString(CONTEXT_KIND)}
                    if(personal!=null) {
                        val start=args.getString(START_ID)
                        if(start!=null && !validLibraryId(start))return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        val action=args.getString(CONTEXT_ACTION)
                        if(action !in listOf("PLAY_FIRST","SHUFFLE","PLAY_FROM"))return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        return result(player.libraryContext {
                            val songs=UserData.database(applicationContext).personalWindow(personal,start).mapNotNull {id ->
                                val file=playlistFile(applicationContext,id)
                                if(file?.available==true && hasTreeReadGrant(applicationContext,android.net.Uri.parse(file.root)))LocalLibrary.database(applicationContext).song(id)?.takeIf {it.available} else null
                            }
                            if(songs.isEmpty() || start!=null && songs.first().id!=start)return@libraryContext null
                            ContextQueue(songs.map(::librarySource),if(action=="SHUFFLE")songs.indices.random()else 0,action=="SHUFFLE")
                        })
                    }
                    if(args.getString(CONTEXT_KIND)=="PLAYLIST") {
                        val id=args.getString(CONTEXT_VALUE)?.toLongOrNull()?.takeIf {it>0}
                            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        val action=args.getString(CONTEXT_ACTION)
                        val start=args.getInt(PLAYLIST_INDEX,0)
                        val revision=args.getLong(PLAYLIST_REVISION,-1)
                        if(action !in listOf("PLAY_FIRST","SHUFFLE") || start !in 0 until PLAYLIST_LIMIT || revision<1)
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        return result(player.libraryContext {
                            val playlist=UserData.database(applicationContext).playlist(id,revision) ?: return@libraryContext null
                            val available=playlist.items.mapIndexedNotNull {index,item ->item.trackId?.let {key ->
                                val song=LocalLibrary.database(applicationContext).song(key)?.takeIf {it.available}
                                val file=playlistFile(applicationContext,key)
                                if(song!=null && file!=null && file.available && hasTreeReadGrant(applicationContext,android.net.Uri.parse(file.root)))index to song else null
                            }}
                            if(available.isEmpty())return@libraryContext null
                            val first=if(action=="SHUFFLE")available.indices.random()else available.indexOfFirst {it.first>=start}.takeIf {it>=0} ?: return@libraryContext null
                            ContextQueue(available.map {librarySource(it.second)},first,action=="SHUFFLE")
                        })
                    }
                    val request=libraryContextRequest(args.getString(CONTEXT_KIND),args.getString(CONTEXT_VALUE),args.getString(ALBUM_ARTIST),
                        args.getString(SEARCH),args.getString(START_ID),args.getString(CONTEXT_ACTION),args.getString(SORT))
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    val extras=Bundle()
                    return Futures.transform(player.libraryContext {
                        val window=LocalLibrary.database(applicationContext).queueWindow(request.filter,request.startId)
                            ?: return@libraryContext null
                        extras.putBoolean(QUEUE_TRUNCATED,window.truncated);extras.putInt(QUEUE_COUNT,window.songs.size)
                        val shuffle=request.action==LibraryContextAction.SHUFFLE
                        val start=if(shuffle)window.songs.indices.filter {window.songs[it].available}.random() else window.startIndex
                        ContextQueue(window.songs.map(::librarySource),start,shuffle)
                    }, { accepted -> SessionResult(if(accepted==true)SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN,extras) },Runnable::run)
                }
                if (customCommand.customAction in listOf(ENQUEUE_LIBRARY, SELECT_LIBRARY, PLAY_NEXT_LIBRARY)) {
                    val id = args.getString(LIBRARY_ID)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.libraryQueue(customCommand.customAction != SELECT_LIBRARY,customCommand.customAction == PLAY_NEXT_LIBRARY) {
                            val song = LocalLibrary.database(applicationContext).song(id)?.takeIf { it.available }
                                ?: return@libraryQueue null
                            librarySource(song)
                    })
                }
                if (customCommand.customAction in listOf(MOVE_QUEUE, REMOVE_QUEUE, SELECT_QUEUE)) {
                    val id = args.getString(QUEUE_ID)?.takeIf { it.length in 1..100 }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return when (customCommand.customAction) {
                        MOVE_QUEUE -> {
                            val neighborId = args.getString(QUEUE_NEIGHBOR_ID)
                            val target = args.getInt(QUEUE_INDEX, -1)
                            when {
                                neighborId != null && neighborId.length in 1..100 -> result(player.moveQueue(id, neighborId))
                                neighborId == null && target in 0..999 -> result(player.moveQueue(id, target))
                                else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                            }
                        }
                        REMOVE_QUEUE -> result(player.removeQueue(id))
                        else -> result(player.selectQueue(id))
                    }
                }
                if (customCommand.customAction == SET_USB_POLICY) {
                    val value = args.getString(USB_POLICY)
                    val policy = UsbPlaybackPolicy.entries.firstOrNull { it.name == value }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setUsbPolicy(policy))
                }
                if (customCommand.customAction == SET_FOLLOW_SONG_RATE) {
                    if (!args.containsKey(FOLLOW_SONG_RATE)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setFollowSongRate(args.getBoolean(FOLLOW_SONG_RATE),profileKey))
                }
                if(customCommand.customAction==SET_MAX_USB_RATE) {
                    val rate=args.getInt(MAX_USB_RATE,-1)
                    if(profileKey==null || rate !in listOf(0,48000,96000,192000))return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.setMaxUsbRate(rate.takeIf {it>0},profileKey))
                }
                if (customCommand.customAction == PLAY_CONVERTED) return result(player.playConvertedOnce())
                if (customCommand.customAction == OPEN_ANDROID)
                    return result(player.openAndroid(args.getBoolean(ANDROID_BIT_PERFECT), args.getBoolean(ONLY_IF_UNSELECTED)))
                if (customCommand.customAction == SELECT_FILE) {
                    val value = args.getString(FILE_URI)?.takeIf { it.length in 1..8192 }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    return result(player.libraryQueue(false) { inspectLocalQueueSource(applicationContext, android.net.Uri.parse(value)) })
                }
                if (customCommand.customAction != OPEN_USB || !args.containsKey(DEVICE_ID))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val id = args.getInt(DEVICE_ID, -1)
                if (id < 0) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val manager=getSystemService(UsbManager::class.java)
                val device=runCatching {manager.deviceList.values.firstOrNull {it.deviceId==id}
                    ?.takeIf {manager.hasPermission(it) && UsbAudioOutput.isUsbAudioOutput(it)}}.getOrNull()
                    ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                val identity=device.dacIdentity()
                return Futures.transform(player.openDevice(id, args.getBoolean(ONLY_IF_UNSELECTED)) {PlaybackRuntime.profile(applicationContext,identity)},{success ->
                    val saved=success!=true || rememberDac(applicationContext,identity)
                    SessionResult(if(success==true)SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN,
                        Bundle().apply {putBoolean(LAST_DAC_SAVED,saved)})
                },Runnable::run)
            }
        }).setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        PlaybackRuntime.beforeFocusRequest { session?.let { onUpdateNotification(it, true) } }
        scope.launch {loadSessionArtwork(applicationContext,owner,player)}
        scope.launch {loadSessionFavorite(applicationContext,owner,player)}
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun librarySource(song:LibrarySong)=QueueSource(song.id,song.title,song.artist,song.album,song.durationUs,musicTags=song.musicTags) {
        val current=LocalLibrary.database(applicationContext).song(song.id)?.takeIf {it.available}
            ?: throw java.io.IOException("LIBRARY_SONG_UNAVAILABLE")
        libraryTrack(applicationContext,current)
    }

    private fun result(future: ListenableFuture<Boolean>): ListenableFuture<SessionResult> = Futures.transform(future,
        { success -> SessionResult(if (success == true) SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN) }, Runnable::run)
    private fun volumeResult(future:ListenableFuture<Boolean>):ListenableFuture<SessionResult> = Futures.transform(future,
        {success->SessionResult(if(success==true)SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN,volumeReadback(owner.snapshot.value))},Runnable::run)

    private fun equalizerCommand(args:Bundle,key:String?):ListenableFuture<SessionResult> {
        fun bad()=Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        if(key==null || key!=owner.snapshot.value.dacProfileKey)return bad()
        val expectedId=args.getLong("eqExpectedId",-2)
        val expectedRevision=args.getLong("eqExpectedRevision",-1)
        if(!(expectedId==-1L && expectedRevision==0L || expectedId>=0 && expectedRevision>=1))return bad()
        val action=args.getString("eqAction")
        if(action !in listOf("SELECT","SAVE","COPY","DELETE") || !args.containsKey(EvannaPlayer.EQ_ENABLED))return bad()
        val enabled=args.getBoolean(EvannaPlayer.EQ_ENABLED)
        val config=runCatching {equalizerConfigFromValues(args.getFloatArray("eqBands") ?: error("Missing filters"),
            args.getFloat("eqRequestedPreamp",Float.NaN),args.getBoolean("eqGraphic"))}.getOrNull() ?: return bad()
        val targetId=args.getLong("eqTargetId",-1);val targetRevision=args.getLong("eqTargetRevision",0)
        val name=args.getString("eqPresetName")?.let(::validEqualizerName)
        if(action=="SELECT" && (targetId<0 || targetRevision<1) || action in listOf("SAVE","COPY") && name==null ||
            action in listOf("SAVE","DELETE") && expectedId<=0 || action=="DELETE" && (enabled || config!=EqualizerConfig()))return bad()
        return result(player.setEqualizer(key,expectedId.takeIf {it>=0},expectedRevision.takeIf {it>=1},config,enabled) {
            val db=UserData.database(applicationContext)
            db.commitEqualizer(key,expectedId.takeIf {it>=0},expectedRevision.takeIf {it>=1},enabled) {
                when(action) {
                    "SELECT" -> db.equalizerPreset(targetId,targetRevision)?.takeIf {it.config==config}
                    "SAVE" -> db.saveEqualizerPreset(expectedId,name!!,config,expectedRevision)
                    "COPY" -> db.saveEqualizerPreset(null,name!!,config)
                    "DELETE" -> if(db.deleteEqualizerPreset(expectedId,expectedRevision))db.equalizerPreset(0)else null
                    else -> null
                }
            }
        })
    }
    override fun onDestroy() {
        unregisterReceiver(receiver)
        PlaybackRuntime.beforeFocusRequest(null)
        session?.release()
        player.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal fun volumeReadback(snapshot:UsbPlaybackSnapshot):Bundle {
            if(usbDeviceVolume(snapshot)==null)return Bundle.EMPTY
            val gain=checkNotNull(snapshot.usbVolume)
            return Bundle().apply {
                putString(EvannaPlayer.OUTPUT_ROUTE,PlaybackRoute.DIRECT_USB.name)
                putLong(EvannaPlayer.USB_GENERATION,snapshot.generation)
                putFloat(EvannaPlayer.USB_KEY_STEP,snapshot.usbKeyStepDb)
                putFloat(EvannaPlayer.USB_VOLUME_LIMIT,snapshot.volumeLimitDb)
                putFloat(EvannaPlayer.USB_VOLUME,gain.currentDb);putFloat(EvannaPlayer.USB_VOLUME_MIN,gain.minDb)
                putFloat(EvannaPlayer.USB_VOLUME_MAX,gain.maxDb);putFloat(EvannaPlayer.USB_VOLUME_STEP,gain.stepDb)
            }
        }
        const val ADJUST_USB_VOLUME="com.evanna.player.ADJUST_USB_VOLUME"
        const val SET_USB_VOLUME="com.evanna.player.SET_USB_VOLUME"
        const val SET_USB_START_VOLUME="com.evanna.player.SET_USB_START_VOLUME"
        const val SET_USB_VOLUME_LIMIT="com.evanna.player.SET_USB_VOLUME_LIMIT"
        const val SET_USB_KEY_STEP="com.evanna.player.SET_USB_KEY_STEP"
        const val SET_LEVELING_MODE="com.evanna.player.SET_LEVELING_MODE"
        const val SET_CROSSFEED="com.evanna.player.SET_CROSSFEED"
        const val SET_EQUALIZER="com.evanna.player.SET_EQUALIZER"
        const val VOLUME_DB="volumeDb"
        const val VOLUME_DIRECTION="volumeDirection"
        const val VOLUME_GENERATION="volumeGeneration"
        const val OPEN_USB = "com.evanna.player.OPEN_USB"
        const val OPEN_ANDROID = "com.evanna.player.OPEN_ANDROID"
        const val ONLY_IF_UNSELECTED = "onlyIfUnselected"
        const val ANDROID_BIT_PERFECT = "androidBitPerfect"
        const val DEVICE_ID = "deviceId"
        const val SELECT_FILE = "com.evanna.player.SELECT_FILE"
        const val FILE_URI = "fileUri"
        const val PLAY_CONVERTED = "com.evanna.player.PLAY_CONVERTED_ONCE"
        const val SET_USB_POLICY = "com.evanna.player.SET_USB_POLICY"
        const val USB_POLICY = "usbPolicy"
        const val SET_FOLLOW_SONG_RATE = "com.evanna.player.SET_FOLLOW_SONG_RATE"
        const val FOLLOW_SONG_RATE = "followSongRate"
        const val ENQUEUE_LIBRARY = "com.evanna.player.ENQUEUE_LIBRARY"
        const val SELECT_LIBRARY = "com.evanna.player.SELECT_LIBRARY"
        const val SELECT_LIBRARY_CONTEXT = "com.evanna.player.SELECT_LIBRARY_CONTEXT"
        const val PLAY_NEXT_LIBRARY = "com.evanna.player.PLAY_NEXT_LIBRARY"
        const val CONTEXT_KIND = "contextKind"
        const val CONTEXT_VALUE = "contextValue"
        const val ALBUM_ARTIST = "albumArtist"
        const val SEARCH = "search"
        const val START_ID = "startId"
        const val CONTEXT_ACTION = "action"
        const val SORT="sort"
        const val QUEUE_TRUNCATED = "queueTruncated"
        const val QUEUE_COUNT = "queueCount"
        const val LIBRARY_ID = "libraryId"
        const val MOVE_QUEUE = "com.evanna.player.MOVE_QUEUE"
        const val REMOVE_QUEUE = "com.evanna.player.REMOVE_QUEUE"
        const val SELECT_QUEUE = "com.evanna.player.SELECT_QUEUE"
        const val QUEUE_ID = "queueId"
        const val QUEUE_INDEX = "queueIndex"
        const val QUEUE_NEIGHBOR_ID = "queueNeighborId"
        const val ARTWORK_SOURCE = "com.evanna.player.ARTWORK_SOURCE"
        const val LAST_DAC_SAVED="lastDacSaved"
        const val SET_MAX_USB_RATE="com.evanna.player.SET_MAX_USB_RATE"
        const val SET_SLEEP_TIMER="com.evanna.player.SET_SLEEP_TIMER"
        const val SLEEP_MODE="com.evanna.player.SLEEP_MODE"
        const val SLEEP_MINUTES="com.evanna.player.SLEEP_MINUTES"
        const val SAVE_QUEUE_PLAYLIST="com.evanna.player.SAVE_QUEUE_PLAYLIST"
        const val SET_FAVORITE="com.evanna.player.SET_FAVORITE"
        const val PLAYLIST_NAME="playlistName"
        const val PLAYLIST_INDEX="playlistIndex"
        const val PLAYLIST_REVISION="playlistRevision"
        const val MAX_USB_RATE="maxUsbRate"
    }
}
