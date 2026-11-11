package com.evanna.player.playback

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.inputmethod.InputMethodManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.*
import com.evanna.player.decoder.inspectLocalQueueSource
import com.evanna.player.library.*
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

@UnstableApi
internal object PlayerUiProbe {
    /** Exercises context commands on a disposable installation; the writer below never touches hardware. */
    fun context(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        check(context.packageName=="com.evanna.player.validation") {"Requires isolated validation application"}
        val db=LocalLibrary.database(context);check(db.roots().isEmpty())
        val root="content://fixture/context-ui"
        db.addRoot(root,"Context UI",false);db.begin(root,"seed")
        repeat(17) {n->
            val id=n.toString(16).padStart(64,'0')
            db.record(root,"seed",LibraryDocument("$n",id,"content://fixture/$n","$n.flac","audio/flac",10,20),
                LibraryAudio("Context %02d".format(n+1),"Context Artist","Context album",600_000_000,"FLAC",48000,16,2,
                    com.evanna.player.decoder.MusicTags("Context Artist",n+1)))
        }
        db.finish(root,"seed")
        val main:(()->Unit)->Unit={instrumentation.runOnMainSync {it()}}
        val driver=Driver(instrumentation)
        val output=Output()
        val owner=UsbSessionOwner(UsbPlaybackController {output},object:PlaybackFocus {
            override var held=false
            override fun request():Boolean {held=true;return true}
            override fun abandon() {held=false}
        })
        var activity:MainActivity?=null
        var player:EvannaPlayer?=null;var session:MediaSession?=null;var remote:MediaController?=null
        val requests=java.util.concurrent.CopyOnWriteArrayList<LibraryContextRequest>()
        fun stage(value:String) {instrumentation.sendStatus(1,Bundle().apply {putString("stage",value)})}
        fun songSource(song:LibrarySong)=source(song.title).copy(libraryId=song.id,artist=song.artist,album=song.album)
        fun awaitPlaying(title:String?=null) = driver.await("Context did not reach synthetic writer") {
            owner.snapshot.value.state==UsbPlaybackState.PLAYING && (title==null || owner.queue.value.current?.title==title)
        }
        try {
            stage("service-validation")
            // The real service accepts a valid context even when its synthetic URI cannot be decoded.
            lateinit var realFuture:ListenableFuture<MediaController>
            main {realFuture=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
            val real=realFuture.get(10,TimeUnit.SECONDS)
            try {
                fun select(args:Bundle):SessionResult {
                    lateinit var pending:ListenableFuture<SessionResult>
                    main {pending=real.sendCustomCommand(SessionCommand(PlaybackService.SELECT_LIBRARY_CONTEXT,Bundle.EMPTY),args)}
                    return pending.get(15,TimeUnit.SECONDS)
                }
                check(select(Bundle()).resultCode==SessionError.ERROR_BAD_VALUE)
                val accepted=select(Bundle().apply {
                    putString(PlaybackService.CONTEXT_KIND,"ALBUM");putString(PlaybackService.CONTEXT_VALUE,"Context album")
                    putString(PlaybackService.ALBUM_ARTIST,"Context Artist");putString(PlaybackService.SEARCH,"")
                    putString(PlaybackService.START_ID,2.toString(16).padStart(64,'0'));putString(PlaybackService.CONTEXT_ACTION,"PLAY_FROM")
                })
                check(accepted.resultCode==SessionResult.RESULT_SUCCESS && accepted.extras.getInt(PlaybackService.QUEUE_COUNT)==17)
                driver.await("Real service lost accepted queue") { var ready=false;main {ready=real.mediaItemCount==17 && real.currentMediaItemIndex==2 && real.playerError!=null && !real.playWhenReady};ready }
                val realOwner=PlaybackRuntime.owner(context)
                check(realOwner.snapshot.value.route==null && realOwner.sourceSelectionFailed)
                check(select(Bundle()).resultCode==SessionError.ERROR_BAD_VALUE)
                check(realOwner.queue.value.items.size==17 && realOwner.queue.value.index==2)
                main {real.stop()}
                driver.await("Isolated service queue did not close") {realOwner.queue.value.items.isEmpty()}
            } finally {main {real.release()}}
            lateinit var pending:ListenableFuture<MediaController>
            main {
                player=EvannaPlayer(owner)
                session=MediaSession.Builder(context,player!!).setId("context-ui-probe").setCallback(object:MediaSession.Callback {
                    override fun onConnect(s:MediaSession,c:MediaSession.ControllerInfo):MediaSession.ConnectionResult {
                        val commands=MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        for(action in listOf(PlaybackService.SELECT_LIBRARY_CONTEXT,PlaybackService.ENQUEUE_LIBRARY,PlaybackService.PLAY_NEXT_LIBRARY))commands.add(SessionCommand(action,Bundle.EMPTY))
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(commands.build()).build()
                    }
                    override fun onCustomCommand(s:MediaSession,c:MediaSession.ControllerInfo,command:SessionCommand,args:Bundle):ListenableFuture<SessionResult> {
                        val result=if(command.customAction==PlaybackService.SELECT_LIBRARY_CONTEXT) {
                            val request=checkNotNull(libraryContextRequest(args.getString(PlaybackService.CONTEXT_KIND),args.getString(PlaybackService.CONTEXT_VALUE),
                                args.getString(PlaybackService.ALBUM_ARTIST),args.getString(PlaybackService.SEARCH),args.getString(PlaybackService.START_ID),args.getString(PlaybackService.CONTEXT_ACTION),args.getString(PlaybackService.SORT)))
                            requests+=request
                            player!!.libraryContext {
                                val window=db.queueWindow(request.filter,request.startId) ?: return@libraryContext null
                                ContextQueue(window.songs.map(::songSource),if(request.action==LibraryContextAction.SHUFFLE)window.songs.indices.random() else window.startIndex,
                                    request.action==LibraryContextAction.SHUFFLE)
                            }
                        } else player!!.libraryQueue(true,command.customAction==PlaybackService.PLAY_NEXT_LIBRARY) {
                            db.song(checkNotNull(args.getString(PlaybackService.LIBRARY_ID)))?.let(::songSource)
                        }
                        return Futures.transform(result,{SessionResult(if(it==true)SessionResult.RESULT_SUCCESS else SessionError.ERROR_UNKNOWN)},Runnable::run)
                    }
                }).build()
                pending=MediaController.Builder(context,session!!.token).buildAsync()
            }
            remote=pending.get(10,TimeUnit.SECONDS)
            runBlocking {owner.open(7);owner.setRepeat(QueueRepeat.QUEUE)}
            stage("launch")
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            main {activity!!.connectPlayback(session!!.token)}
            stage("album-tap")
            driver.click("Context album")
            driver.await("Album rows did not load") {driver.exists("Context 01")}
            driver.click("Context 03")
            awaitPlaying("Context 03")
            check(owner.queue.value.items.size==17 && owner.queue.value.index==2 && owner.queue.value.repeat==QueueRepeat.QUEUE)
            check(!owner.queue.value.shuffleEnabled)
            check(requests.last().filter==LibraryFilter(kind=LibraryGroupKind.ALBUM,value="Context album",albumArtist="Context Artist"))
            stage("album-play")
            driver.clickDescription("Back");driver.reveal("Play album");driver.click("Play album");awaitPlaying("Context 01")
            check(requests.last().action==LibraryContextAction.PLAY_FIRST)
            stage("album-shuffle")
            driver.clickDescription("Back");driver.reveal("Shuffle album");driver.click("Shuffle album")
            driver.await("Album shuffle did not reach owner") {owner.queue.value.shuffleEnabled};awaitPlaying()
            check(requests.last().action==LibraryContextAction.SHUFFLE && owner.queue.value.shuffleEnabled)
            main {remote!!.pause()};driver.await("Synthetic pause failed") {owner.snapshot.value.state==UsbPlaybackState.PAUSED}
            val current=owner.queue.value.current!!.id
            stage("row-menu")
            driver.clickDescription("Back");driver.clickDescription("More options for Context 02");driver.click("Play next")
            driver.await("Play next did not insert after current") {owner.queue.value.items.size==18}
            check(owner.queue.value.current!!.id==current && owner.queue.value.items[owner.queue.value.navigationIndex(1)].title=="Context 02")
            check(owner.snapshot.value.state==UsbPlaybackState.PAUSED)
            driver.clickDescription("More options for Context 02");driver.click("Add to queue")
            driver.await("Add to queue did not append") {owner.queue.value.items.size==19}
            stage("filtered-tap")
            driver.click("Search");driver.search("Context 03")
            main {context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity!!.window.decorView.windowToken,0)}
            driver.click("Context 03");awaitPlaying("Context 03")
            check(owner.queue.value.items.size==1 && !owner.queue.value.shuffleEnabled)
            check(requests.last().filter==LibraryFilter(search="Context 03"))
            // Pause arriving during lookup must revoke that request before queue replacement or playback.
            stage("pause-revokes-pending")
            driver.await("Synthetic player did not publish playing state") {var ready=false;main {ready=player!!.playWhenReady};ready}
            lateinit var stale:ListenableFuture<Boolean>
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            try {
                main {stale=player!!.libraryContext {entered.countDown();check(release.await(5,TimeUnit.SECONDS));ContextQueue(listOf(source("Stale")),0)}}
                check(entered.await(5,TimeUnit.SECONDS));main {player!!.pause()};release.countDown()
                check(!stale.get(10,TimeUnit.SECONDS))
            } finally {release.countDown()}
            driver.await("Newer Pause did not stay paused") {owner.snapshot.value.state==UsbPlaybackState.PAUSED}
            lateinit var newer:ListenableFuture<Boolean>
            main {player!!.pause();newer=player!!.libraryContext {ContextQueue(listOf(source("Newer explicit choice")),0)}}
            check(newer.get(10,TimeUnit.SECONDS));awaitPlaying("Newer explicit choice")
            return "Real service validation/accepted-source-failure and unchanged invalid request pass. Actual album tap/Play/Shuffle, filtered Songs tap, Play next/Add menu and pending-play revocation pass through an isolated synthetic writer; no physical audio or user queue changes."
        } catch(failure:Exception) {
            if(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName) {
                instrumentation.uiAutomation.takeScreenshot()?.let {screenshot->
                    File(context.cacheDir,"context-ui-failure.png").outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    screenshot.recycle()
                }
            }
            throw failure
        } finally {
            main {activity?.finish();remote?.release();session?.release();player?.release()}
            runBlocking {owner.close()}
            db.writableDatabase.execSQL("DELETE FROM files WHERE root=?",arrayOf(root))
            db.writableDatabase.execSQL("DELETE FROM roots WHERE uri=?",arrayOf(root))
            db.writableDatabase.execSQL("DELETE FROM tracks WHERE NOT EXISTS(SELECT 1 FROM files WHERE files.id=tracks.id)")
        }
    }

    /** The actual widgets control a separate synthetic session, never the user's queue/audio route. */
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val saved = PlaybackQueueStore(context).use { it.load() }
        val fixture = LocalLibrary.database(context).roots().single { it.name == "Evanna-library-check" }
        val fixtureSource = SafLibrarySource(context, android.net.Uri.parse(fixture.uri))
        val lyricUri = runBlocking { fixtureSource.children(fixtureSource.root().id).single { it.name == "Glass Harbor.flac" }.uri }
        val output = Output()
        var allowLimitSave=true
        val owner = UsbSessionOwner(UsbPlaybackController(saveVolumeLimitDb={allowLimitSave}) { output }, object : PlaybackFocus {
            override var held = false
            override fun request(): Boolean { held = true; return true }
            override fun abandon() { held = false }
        })
        val driver = Driver(instrumentation)
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        lateinit var realFuture: ListenableFuture<MediaController>
        main { realFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val realController = realFuture.get(10, TimeUnit.SECONDS)
        lateinit var player: EvannaPlayer
        lateinit var session: MediaSession
        lateinit var future: ListenableFuture<MediaController>
        main {
            player = EvannaPlayer(owner)
            session = MediaSession.Builder(context, player).setId("player-ui-probe").setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    for (action in listOf(PlaybackService.SELECT_QUEUE, PlaybackService.MOVE_QUEUE, PlaybackService.REMOVE_QUEUE, PlaybackService.SELECT_LIBRARY_CONTEXT, PlaybackService.ARTWORK_SOURCE,PlaybackService.ADJUST_USB_VOLUME,PlaybackService.SET_USB_VOLUME,PlaybackService.SET_USB_VOLUME_LIMIT,PlaybackService.SET_USB_KEY_STEP,PlaybackService.SET_MAX_USB_RATE,PlaybackService.SET_SLEEP_TIMER))
                        commands.add(SessionCommand(action, Bundle.EMPTY))
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    val id = args.getString(PlaybackService.QUEUE_ID).orEmpty()
                    if (command.customAction == PlaybackService.ARTWORK_SOURCE) {
                        val song = owner.queue.value.items.firstOrNull { it.id == id }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                            putString(PlaybackService.QUEUE_ID, id)
                            if (song?.title == "First") putString(PlaybackService.FILE_URI, lyricUri)
                        }))
                    }
                    val result = when (command.customAction) {
                        PlaybackService.SELECT_QUEUE -> player.selectQueue(id)
                        PlaybackService.MOVE_QUEUE -> player.moveQueue(id, args.getString(PlaybackService.QUEUE_NEIGHBOR_ID).orEmpty())
                        PlaybackService.REMOVE_QUEUE -> player.removeQueue(id)
                        PlaybackService.ADJUST_USB_VOLUME -> player.adjustUsbVolume(args.getInt(PlaybackService.VOLUME_DIRECTION),args.getLong(PlaybackService.VOLUME_GENERATION))
                        PlaybackService.SET_USB_VOLUME -> player.setUsbVolume(args.getFloat(PlaybackService.VOLUME_DB),args.getLong(PlaybackService.VOLUME_GENERATION))
                        PlaybackService.SET_USB_VOLUME_LIMIT -> player.setVolumeLimit(args.getFloat(PlaybackService.VOLUME_DB,Float.NaN),args.getString(EvannaPlayer.DAC_PROFILE_KEY))
                        PlaybackService.SET_USB_KEY_STEP -> player.setUsbKeyStep(args.getFloat(PlaybackService.VOLUME_DB,Float.NaN),args.getString(EvannaPlayer.DAC_PROFILE_KEY))
                        PlaybackService.SET_MAX_USB_RATE -> player.setMaxUsbRate(args.getInt(PlaybackService.MAX_USB_RATE).takeUnless {it==0},args.getString(EvannaPlayer.DAC_PROFILE_KEY))
                        PlaybackService.SET_SLEEP_TIMER -> player.setSleepTimer(SleepMode.valueOf(args.getString(PlaybackService.SLEEP_MODE)!!),args.getInt(PlaybackService.SLEEP_MINUTES))
                        else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    return Futures.transform(result, { success -> SessionResult(if (success == true) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE,
                        if(command.customAction in listOf(PlaybackService.ADJUST_USB_VOLUME,PlaybackService.SET_USB_VOLUME))PlaybackService.volumeReadback(owner.snapshot.value) else Bundle.EMPTY) }, Runnable::run)
                }
            }).build()
            future = MediaController.Builder(context, session.token).buildAsync()
        }
        val remote = future.get(10, TimeUnit.SECONDS)
        var activity: Activity? = null
        try {
            runBlocking { check(owner.enqueue(source("First"))); check(owner.enqueue(source("Second"))); check(owner.open(7) {com.evanna.player.user.DacProfile(com.evanna.player.usb.DacIdentity(1,2,"Synthetic USB"))}) }
            driver.await("Synthetic session not ready") { var ready = false; main { ready = remote.mediaItemCount == 2 && remote.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }; ready }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main { (activity as MainActivity).connectPlayback(session.token) }
            driver.await("MainActivity did not bind the synthetic session") { driver.exists("First") }
            // Search instead of scrolling: a large library can push the test song past the first page.
            driver.click("Search")
            driver.search("Evanna embedded cover")
            main { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(checkNotNull(activity).window.decorView.windowToken, 0) }
            // The search reads 250 ms after typing. Clicking earlier falls back to a scroll, which turns the tab pager to Settings.
            driver.await("Search result did not show") {
                driver.find { it.isVisibleToUser && !it.isEditable && it.text?.toString() == "Evanna embedded cover" } != null
            }
            driver.click("Evanna embedded cover")
            driver.await("Failed library selection had no visible message") { driver.visible("Couldn’t open song") }
            driver.click("OK")
            driver.clickDescription("Open Now Playing")
            driver.await("The timed lyric did not appear above seek") { driver.visible("Lights on in the harbor,") }
            driver.clickDescription("Open lyrics")
            driver.await("The Lyrics button did not open the page") { driver.heading("Lyrics") && !driver.exists("Loading lyrics…") }
            check(driver.find { it.contentDescription?.toString() == "Hide lyrics" } == null)
            driver.clickDescription("Close lyrics")
            driver.await("The lyrics did not collapse into the preview") { driver.heading("Now Playing") && driver.visible("Lights on in the harbor,") }
            driver.clickDescription("Audio quality details")
            driver.await("Audio quality window did not open") {driver.exists("Audio quality") && driver.exists("Source")}
            driver.click("Advanced")
            driver.await("Advanced signal path did not show") {driver.exists("Decoder input")}
            driver.click("Close")
            driver.clickDescription("More options");driver.click("Sleep timer");driver.click("15 minutes")
            driver.await("Timer was not set") {owner.sleepTimer.value.mode==SleepMode.MINUTES}
            // The label counts down each second, so a slow device may never draw "15:00"; any "Sleep in m:ss" will do.
            driver.await("Sleep label did not show") {
                driver.find { it.isVisibleToUser && it.text?.toString()?.let { label -> label.startsWith("Sleep in ") && label.endsWith(" · Cancel") } == true } != null
            }
            driver.clickDescription("Cancel sleep timer")
            driver.await("Timer was not cancelled") {owner.sleepTimer.value.mode==SleepMode.OFF}
            driver.clickDescription("Play playback")
            driver.await("Play did not reach synthetic writer") { owner.snapshot.value.state == UsbPlaybackState.PLAYING && output.completed.get() > 0 }
            driver.await("USB key view not ready") {driver.find {it.contentDescription?.toString()=="USB louder" && it.isEnabled}!=null}
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
            driver.await("Volume Up did not reach DAC control") {owner.snapshot.value.usbVolume?.currentDb == -59f}
            driver.await("Confirmed key indicator was not shown") {driver.visible("USB volume -59 dB")}
            repeat(3) {instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)}
            driver.await("Quick volume presses lost steps") {owner.snapshot.value.usbVolume?.currentDb == -56f}
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            driver.await("Volume Down did not reach DAC control") {owner.snapshot.value.usbVolume?.currentDb == -57f}
            driver.progress("USB hardware volume",-60f)
            driver.await("Key check did not restore minimum") {owner.snapshot.value.usbVolume?.currentDb == -60f}
            driver.clickDescription("USB louder")
            driver.await("USB louder did not reach the owner") { owner.snapshot.value.usbVolume?.currentDb == -59f }
            driver.clickDescription("USB quieter")
            driver.await("USB quieter did not reach the owner") { owner.snapshot.value.usbVolume?.currentDb == -60f }
            // The volume slider sends one absolute target on release.
            driver.progress("USB hardware volume", -42f)
            driver.await("USB volume slider did not reach the owner") { owner.snapshot.value.usbVolume?.currentDb == -42f }
            driver.progress("USB hardware volume", -60f)
            driver.await("USB volume slider did not return") { owner.snapshot.value.usbVolume?.currentDb == -60f }
            val beforeSeek=owner.snapshot.value.generation
            driver.progress("Estimated playback position",30000f)
            driver.await("Active seek did not restart the stream") { owner.snapshot.value.generation>beforeSeek }
            driver.clickDescription("USB louder")
            driver.await("USB controls retained stale generation after active seek") { owner.snapshot.value.usbVolume?.currentDb == -59f }
            driver.clickDescription("USB quieter")
            driver.await("USB quieter after seek failed") { owner.snapshot.value.usbVolume?.currentDb == -60f }
            driver.reveal("Now Playing")
            driver.clickDescription("Pause playback")
            driver.await("Pause did not freeze") { owner.snapshot.value.state == UsbPlaybackState.PAUSED }
            driver.progress("Estimated playback position", 45000f)
            driver.await("Seek did not reach owner") { owner.positionUs == 45_000_000L }
            driver.clickDescription("Next")
            driver.await("Next did not select second") { owner.queue.value.current?.title == "Second" }
            driver.clickDescription("Previous")
            driver.await("Previous did not select first") { owner.queue.value.current?.title == "First" }
            val releaseDrag = driver.beginDrag("Estimated playback position")
            try {
                runBlocking { check(owner.skipQueue(1)) }
                driver.await("Replacement source did not settle during drag") { owner.queue.value.current?.title == "Second" }
                runBlocking { check(owner.seekTo(30_000_000L)) }
                SystemClock.sleep(200)
            } finally { releaseDrag() }
            val settled = SystemClock.elapsedRealtime() + 1500
            while (SystemClock.elapsedRealtime() < settled) {
                check(owner.positionUs == 30_000_000L) { "Stale drag sought the replacement song" }
                SystemClock.sleep(20)
            }
            driver.clickDescription("Previous")
            driver.await("Previous after pointer test did not settle") { owner.queue.value.current?.title == "First" }
            // Shuffle and Repeat live on the Queue page.
            driver.clickDescription("Queue · 2 songs")
            driver.await("Queue page did not open") { driver.heading("Queue") }
            driver.clickDescription("Shuffle: Off")
            driver.await("Shuffle did not settle") { owner.queue.value.shuffleEnabled }
            for ((label, mode) in listOf("Off" to QueueRepeat.SONG, "Song" to QueueRepeat.QUEUE, "Queue" to QueueRepeat.OFF)) {
                driver.clickDescription("Repeat: $label"); driver.await("Repeat did not settle") { owner.queue.value.repeat == mode }
            }
            check(runBlocking { owner.seekTo(4_000_000L) })
            driver.await("Preview did not follow a seek across the lyric boundary") { driver.visible("every window hums along") }
            // The current song (First) sits in the header; Second is the one song still to come.
            driver.await("First upcoming song must have Move up disabled") {
                driver.find { it.isVisibleToUser && it.contentDescription?.toString() == "Move Second up" }?.isEnabled == false
            }
            check(runBlocking { owner.enqueue(source("Third")) })
            driver.clickDescription("Move Third up")
            driver.await("Shuffled queue reorder did not change what plays next") {
                val queue = owner.queue.value
                queue.items.getOrNull(queue.navigationIndex(1))?.title == "Third" &&
                    queue.items.map { it.title } == listOf("First", "Second", "Third")
            }
            driver.clickDescription("Remove Third from queue")
            driver.await("Queue removal did not settle") { owner.queue.value.items.size == 2 }
            driver.click("Second")
            driver.await("Queue selection did not settle") { owner.queue.value.current?.title == "Second" }
            driver.await("The previous track's lyrics stayed in the preview") { driver.visible("No lyrics") }
            // The tabs sit under the sheet: close it first. This session refuses the starting-volume save, so Settings must show the saved -5 dB again.
            driver.clickDescription("Minimize player")
            driver.click("Settings")
            driver.click("Audio output and USB")
            driver.revealForward("USB starting volume")
            driver.progress("USB starting volume", -30f)
            SystemClock.sleep(500)
            driver.await("A refused starting volume stayed on screen") { driver.exists("-5 dB") && !driver.exists("-30 dB") }
            driver.clickDescription("Play playback")
            driver.await("Volume limit test did not start its synthetic writer") {owner.snapshot.value.state==UsbPlaybackState.PLAYING}
            fun inPlayer(action: () -> Unit) {
                driver.clickDescription("Open Now Playing")
                action()
                driver.clickDescription("Minimize player")
                driver.await("The mini player did not return") {
                    driver.find { it.isVisibleToUser && it.contentDescription?.toString() == "Open Now Playing" } != null
                }
            }
            inPlayer { driver.progress("USB hardware volume", -10f) }
            driver.await("Synthetic initial gain did not settle") {owner.snapshot.value.usbVolume?.currentDb == -10f}
            driver.revealForward("Max USB volume");driver.progress("Max USB volume",-20f)
            driver.await("Lowering the limit did not lower live gain") {owner.snapshot.value.volumeLimitDb == -20f && owner.snapshot.value.usbVolume?.currentDb == -20f}
            driver.progress("Max USB volume",-5f)
            driver.await("Raising the limit changed gain") {owner.snapshot.value.volumeLimitDb == -5f && owner.snapshot.value.usbVolume?.currentDb == -20f}
            driver.revealForward("USB volume step");driver.click("3 dB")
            driver.await("USB step selection did not settle") {owner.snapshot.value.usbKeyStepDb == 3f}
            inPlayer { driver.clickDescription("USB louder") }
            driver.await("USB button ignored saved step") {owner.snapshot.value.usbVolume?.currentDb == -17f}
            inPlayer { driver.progress("USB hardware volume", 0f) }
            driver.await("USB slider exceeded ceiling") {owner.snapshot.value.usbVolume?.currentDb == -5f}
            driver.reveal("Max USB volume");driver.progress("Max USB volume",-60f)
            driver.await("Minimum ceiling did not settle") {owner.snapshot.value.usbVolume?.let {it.minDb == it.maxDb && it.currentDb == -60f} == true}
            allowLimitSave=false;driver.progress("Max USB volume",-30f)
            driver.await("Refused ceiling did not show its saved value") {driver.exists("-60 dB maximum") && !driver.exists("-30 dB maximum")}
            main { session.release() }
            driver.clickDescription("Open Now Playing")
            driver.revealForward("Playback controls disconnected. Reopen Evanna to reconnect.")
            check(driver.find { it.isEnabled && it.isClickable && it.contentDescription?.toString()?.contains("Play playback") == true } == null) {
                "Session disconnection left enabled playback"
            }
            check(PlaybackQueueStore(context).use { it.load() } == saved) { "Synthetic UI changed the real saved queue" }
            return "Actual MainActivity mini/full controls, seek, queue/modes, failure feedback, starting-volume refusal, live limit lower/no-raise, capped slider, 3dB step, single-point ceiling, refused-limit display and session disconnection pass through a synthetic writer; real saved queue unchanged; no physical output."
        } catch (failure: Exception) {
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(context.cacheDir, "player-ui-failure.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            throw failure
        } finally {
            main { activity?.finish(); remote.release(); session.release(); player.release(); realController.release() }
            runBlocking { check(owner.close()) }
        }
    }

    /** Read-only production navigation/reference checks preserve any existing saved user session. */
    fun flow(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val driver = Driver(instrumentation)
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        lateinit var future: ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val remote = future.get(10, TimeUnit.SECONDS)
        val owner = PlaybackRuntime.owner(context)
        runBlocking { owner.initialize() }
        val saved = PlaybackQueueStore(context).use { it.load() }
        val before = owner.queue.value
        val position = owner.positionUs
        val outputBefore = owner.snapshot.value
        val grants = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val activity = AtomicReference<MainActivity?>()
        val app = context.applicationContext as Application
        var initialOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(screen: Activity, state: Bundle?) { if (screen is MainActivity) activity.set(screen) }
            override fun onActivityStarted(screen: Activity) = Unit
            override fun onActivityResumed(screen: Activity) = Unit
            override fun onActivityPaused(screen: Activity) = Unit
            override fun onActivityStopped(screen: Activity) = Unit
            override fun onActivitySaveInstanceState(screen: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(screen: Activity) = Unit
        }
        fun command(id: String): SessionResult {
            lateinit var reply: ListenableFuture<SessionResult>
            main { reply = remote.sendCustomCommand(SessionCommand(PlaybackService.ARTWORK_SOURCE, Bundle.EMPTY), Bundle().apply { putString(PlaybackService.QUEUE_ID, id) }) }
            return reply.get(10, TimeUnit.SECONDS)
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        try {
            check(command("invalid").resultCode == SessionError.ERROR_BAD_VALUE)
            before.current?.let { item ->
                val reply = command(item.id)
                check(reply.resultCode == SessionResult.RESULT_SUCCESS && reply.extras.getString(PlaybackService.QUEUE_ID) == item.id)
                main { check(remote.currentMediaItem?.localConfiguration == null && remote.mediaMetadata.extras?.getString(PlaybackService.FILE_URI) == null) }
            }
            val root = LocalLibrary.database(context).roots().single { it.name == "Evanna-library-check" }
            val scan = SafLibrarySource(context, android.net.Uri.parse(root.uri))
            val document = runBlocking { scan.children(scan.root().id).single { it.name == "Evanna-embedded-cover-check.flac" } }
            val source = inspectLocalQueueSource(context, android.net.Uri.parse(document.uri))
            check(source.title == "Evanna embedded cover" && source.artist == "Local artist" && source.album == "Embedded artwork check")
            check(runBlocking { LocalLibrary.artwork(context).file("evanna:local:queue:direct-cover-probe", document.uri) } != null)
            activity.set(instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
            initialOrientation = checkNotNull(activity.get()).requestedOrientation
            driver.await("Library was not the landing screen") { driver.heading("Library") }
            driver.click("Search")
            driver.await("Search tab did not open") { driver.heading("Search") }
            driver.search("Evanna")
            main { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(checkNotNull(activity.get()).window.decorView.windowToken, 0) }
            val hasSong = before.items.isNotEmpty()
            fun searchField() = driver.find { it.isEditable && it.text?.toString() == "Evanna" }
            if (hasSong) {
                driver.await("Search mini player not visible after keyboard hid") {
                    driver.find { it.isVisibleToUser && it.contentDescription?.toString() == "Open Now Playing" } != null
                }
                val result = driver.find { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Add ") == true &&
                    it.contentDescription?.toString()?.endsWith(" to queue") == true }
                val resultLabel = result?.contentDescription?.toString()
                val beforePlayer = Rect().also { result?.getBoundsInScreen(it) }
                driver.clickDescription("Open Now Playing")
                driver.await("Full player did not open") { driver.visible("Now Playing") }
                check(searchField() == null && (resultLabel == null || driver.find { it.contentDescription?.toString() == resultLabel } == null)) {
                    "Covered Search actions are exposed while Now Playing is open"
                }
                // The real current song shows its lyric entry or a quiet unavailable state.
                fun lyricsEntry() = driver.find { it.contentDescription?.toString() == "Show lyrics" }
                driver.await("Lyrics did not finish loading") { lyricsEntry() != null || driver.exists("No lyrics") }
                if (lyricsEntry() != null) {
                    driver.clickDescription("Show lyrics")
                    driver.await("Lyric preview did not expand") { driver.heading("Lyrics") && !driver.exists("Loading lyrics…") }
                    check(driver.find { it.contentDescription?.toString() == "Hide lyrics" } == null)
                    driver.clickDescription("Close lyrics")
                }
                driver.clickDescription("Queue · ${before.items.size} songs")
                driver.await("Queue view did not open") { driver.heading("Queue") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                driver.await("Back did not return to the full player") { driver.visible("Now Playing") }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                driver.await("Closing Now Playing did not restore the Search viewport") {
                    driver.heading("Search") && searchField() != null && (resultLabel == null ||
                        driver.find { it.isVisibleToUser && it.contentDescription?.toString() == resultLabel }?.let {
                            Rect().also(it::getBoundsInScreen) == beforePlayer
                        } == true)
                }
                driver.clickDescription("Open Now Playing")
                driver.clickVolumeOutput()
                driver.await("Audio output sheet did not open") { driver.heading("Audio output") }
                driver.click("Audio Pipeline")
                driver.await("Audio pipeline did not open") { driver.heading("Audio quality") }
                driver.click("Close")
                driver.await("Audio pipeline did not return to the output sheet") { driver.heading("Audio output") }
            } else { driver.click("Settings"); driver.click("Audio output and USB") }
            if (!hasSong) driver.await("Output diagnostics not reachable") { driver.visible("Check your DAC") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val previous = checkNotNull(activity.get())
            main { previous.recreate() }
            driver.await("Activity did not recreate") { activity.get() !== previous }
            if (hasSong) { driver.reveal("Now Playing"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) } else driver.click("Search")
            driver.await("Search query was not preserved") { driver.heading("Search") && searchField() != null }
            main { checkNotNull(activity.get()).requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            driver.await("Landscape did not arrive") { activity.get()?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE }
            if (hasSong) { driver.clickDescription("Open Now Playing"); driver.clickVolumeOutput() }
            else { driver.click("Settings"); driver.click("Audio output and USB") }
            driver.await("Landscape output controls unreachable") {
                if (hasSong) driver.heading("Audio output") else driver.exists("Check your DAC")
            }
            val outputAfter = owner.snapshot.value
            val openedDefault = outputBefore.route == null && outputAfter.route == PlaybackRoute.ANDROID &&
                outputAfter.state == UsbPlaybackState.OPEN && outputAfter.generation == outputBefore.generation + 1
            check(owner.queue.value == before && owner.positionUs == position &&
                (outputAfter.generation == outputBefore.generation || openedDefault)) {
                "Navigation changed the queue, playback position, or output beyond the paused startup selection"
            }
            check(PlaybackQueueStore(context).use { it.load() } == saved)
            check(context.contentResolver.persistedUriPermissions.map { it.uri }.toSet() == grants)
            return "Real artwork lookup, embedded file tags/cover, Library, Search, Now Playing, Lyrics, Queue, output, Back, recreation and landscape passed. Queue, position and folder grants unchanged; no audio started."
        } finally {
            main { activity.get()?.let { it.requestedOrientation = initialOrientation; it.finish() }; remote.release() }
            app.unregisterActivityLifecycleCallbacks(lifecycle)
        }
    }

    internal class Driver(private val instrumentation: Instrumentation) {
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) return node
                repeat(node.childCount) { visit(node.getChild(it))?.let { result -> return result } }
                return null
            }
            return visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun visible(text: String) = find { it.isVisibleToUser && it.text?.toString() == text } != null
        /** Screen titles are headings; the bottom bar repeats some of them as plain tab labels. */
        fun heading(text: String) = find { it.isVisibleToUser && it.isHeading && it.text?.toString() == text } != null
        fun exists(text: String) = find { it.text?.toString() == text } != null
        /** The first list that scrolls up or down. The tab pager scrolls sideways; turning it would leave the page under test. */
        private fun list() = find { node -> node.isScrollable && node.actionList.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id || it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        } }
        private fun scrollList(forward: Boolean) {
            val bounds = Rect()
            list()?.getBoundsInScreen(bounds) ?: return
            val x = bounds.left + bounds.width() / 16
            val top = bounds.top + bounds.height() / 4
            val bottom = bounds.top + bounds.height() * 5 / 8
            val start = if (forward) bottom else top
            val end = if (forward) top else bottom
            // A short swipe keeps headings visible between checks even when the dock overlaps the list.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input swipe $x $start $x $end 420")
            ).use { it.readBytes() }
        }
        fun reveal(text: String) {
            await("Player screen not reachable: $text") {
                if (visible(text)) true else { list()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); false }
            }
        }
        fun revealForward(text: String) {
            await("Player status not reachable: $text") {
                if (visible(text)) true else { scrollList(forward = true); false }
            }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15000
            do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < deadline)
            error(message)
        }
        /** Skips text fields: a search box can hold the same text as the result being clicked. */
        fun click(text: String) = clickWhere(text) { !it.isEditable && it.text?.toString() == text }
        fun clickDescription(text: String) = clickWhere(text) { it.contentDescription?.toString() == text }
        fun clickVolumeOutput() = clickWhere("volume slider") { node ->
            node.actionList.any { it.label?.toString() == "Open audio output" }
        }
        private fun clickTarget(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            var current = node
            repeat(6) { if (current?.isClickable == true) return current; current = current?.parent }
            return null
        }
        private fun clickWhere(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
            await("Player control not reachable: $label") {
                // A heading can repeat a tab label, so only text owned by a clickable control counts.
                val target = clickTarget(find { it.isVisibleToUser && it.isEnabled && predicate(it) && clickTarget(it) != null })
                if (target != null) return@await target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // A partially clipped row can be skipped by a whole-page scroll.
                val hidden=find {it.isEnabled && predicate(it)}
                if(hidden?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)==true)return@await false
                list()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
        }
        fun search(text: String) {
            await("Search field unavailable") {
                find { it.isEditable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }) == true
            }
        }
        /**
         * Now Playing has two sliders (seek and USB volume), so match by label. Material puts the slider's range on
         * one node and its label on a child with the same bounds.
         */
        private fun sliderNode(description: String) = find { node ->
            node.isVisibleToUser && node.isEnabled && node.rangeInfo != null && (node.contentDescription?.toString() == description ||
                (0 until node.childCount).any { node.getChild(it)?.contentDescription?.toString() == description })
        }
        fun progress(description: String, value: Float) {
            await("Slider unavailable: $description") {
                val node = sliderNode(description)
                if (node != null) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) })
                else { scrollList(forward = visible(description)); false }
            }
        }
        fun beginDrag(description: String): () -> Unit {
            var slider: AccessibilityNodeInfo? = null
            await("Pointer-test slider unavailable") {
                slider = sliderNode(description)
                if (slider != null) true else { list()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); false }
            }
            val bounds = Rect().also { checkNotNull(slider).getBoundsInScreen(it) }
            val down = SystemClock.uptimeMillis()
            fun event(action: Int, fraction: Float) {
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.left + bounds.width() * fraction, bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { check(instrumentation.uiAutomation.injectInputEvent(motion, true)) }
                finally { motion.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, .2f)
            event(MotionEvent.ACTION_MOVE, .7f)
            return { event(MotionEvent.ACTION_UP, .7f) }
        }
    }

    internal fun source(title: String): QueueSource = QueueSource(null, title, "Test artist", "Test album", 600_000_000) {
        val pcm = TrackPcmFormat(48000, 16, 2, 16, "FLAC", true, 600_000_000)
        object : PcmTrack {
            override val id = "evanna:local:synthetic:$title"
            override val format = pcm
            override val seekable = true
            override fun open() = object : PcmReader {
                override val format = pcm
                var frames = 0L
                override fun read(target: ByteBuffer, maxFrames: Int): Int {
                    val count = minOf(maxFrames.toLong(), 28_800_000L - frames).toInt()
                    repeat(count * 2) { target.putInt(it * 4, 0) }; frames += count; return count
                }
                override fun seekTo(positionUs: Long) { frames = positionUs * 48000 / 1_000_000 }
                override fun requestCancel() = Unit
                override fun close() = Unit
            }
            override fun close() = Unit
        }
    }
    internal class Output : UsbPlaybackOutput {
        override val description = "Synthetic UI output"
        val completed = AtomicLong()
        @Volatile private var running = false
        override var usbVolume:VolumeRange?=VolumeRange(-60f,0f,1f,-60f)
        override var volumeLimitDb=USB_VOLUME_LIMIT_DEFAULT_DB
        override fun setVolume(db:Float):Boolean {
            val previous=usbVolume?.currentDb ?: return false
            val gain=usbMusicVolume(VolumeRange(-60f,0f,1f,previous),previous,limitDb=volumeLimitDb) ?: return false
            usbVolume=gain.copy(currentDb=gain.snapDown(db) ?: return false);return true
        }
        override fun setEqualizer(bands: List<EqBand>, preampDb: Float) = Unit
        override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, source.significantBits, false, true)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            val previous=usbVolume?.currentDb ?: -60f
            usbVolume=usbMusicVolume(VolumeRange(-60f,0f,1f,previous),previous,limitDb=volumeLimitDb)
            completed.set(0); running = true
            return StreamInfo(48000, 16, 2, DacFormat(1, 1, 1, 2, 2, 16, DacFormat.SyncType.SYNC, 192, listOf(48000), 48000, 48000), 48000, 48000, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun stats() = StreamStats(completed.get(), 0, 0, false, completed.get(), completed.get())
        override fun write(buffer: ByteBuffer, frames: Int): Int { SystemClock.sleep(10); if (!running) return -1; completed.addAndGet(frames.toLong()); return frames }
        override fun drain() = true
        override fun stop(): Boolean { running = false; return true }
        override fun close() = Unit
    }
}
