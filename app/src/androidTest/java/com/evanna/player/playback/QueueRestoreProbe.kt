package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.library.*
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

@UnstableApi
internal object QueueRestoreProbe {
    fun store(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        val name="restore-probe.db"
        context.deleteDatabase(name)
        val item=SavedQueueEntry("evanna:local:queue:store-first","a".repeat(64),null,"café","Artist","Album",10_000_000)
        val expected=SavedQueue(listOf(item,item.copy(id="evanna:local:queue:store-second")),1,3_000_000)
        try {
            PlaybackQueueStore(context,name).use { db ->
                check(db.load()==null);db.save(expected);check(db.load()==expected)
                db.writableDatabase.execSQL("CREATE TRIGGER refuse_entry BEFORE INSERT ON entries WHEN NEW.sequence=1 BEGIN SELECT RAISE(ABORT,'test'); END")
                check(runCatching{db.save(expected.copy(positionUs=4_000_000))}.isFailure)
                check(db.load()==expected){"Failed save replaced the last complete queue"}
                db.writableDatabase.execSQL("DROP TRIGGER refuse_entry")
            }
            PlaybackQueueStore(context,name).use { db ->
                check(db.load()==expected)
                db.writableDatabase.execSQL("UPDATE session SET current_index=99")
                check(runCatching{db.load()}.isFailure)
                check(db.readableDatabase.rawQuery("SELECT COUNT(*) FROM entries",null).use{it.moveToFirst();it.getInt(0)}==2)
                db.save(SavedQueue());check(db.load()==SavedQueue())
            }
            val cappedItems=List(1000) {n->item.copy(id="evanna:local:queue:cap-$n",libraryId=n.toString(16).padStart(64,'0'))}
            val capped=SavedQueue(cappedItems,800,3_000_000,shuffleEnabled=true,repeat=QueueRepeat.QUEUE,playOrder=cappedItems.map {it.id}.reversed())
            PlaybackQueueStore(context,name).use {it.save(capped)}
            PlaybackQueueStore(context,name).use {db->
                check(db.load()==capped)
                val owner=UsbSessionOwner(UsbPlaybackController {error("Output opened during restore")},object:PlaybackFocus {
                    override val held=false
                    override fun request():Boolean=error("Focus requested during restore")
                    override fun abandon()=Unit
                },persistence=db)
                runBlocking {owner.initialize()}
                check(owner.queue.value.items.map {it.id}==cappedItems.map {it.id} && owner.queue.value.index==800)
                check(owner.queue.value.playOrder==capped.playOrder && owner.queue.value.repeat==QueueRepeat.QUEUE)
                check(owner.positionUs==3_000_000L && owner.snapshot.value.route==null && owner.selectedTrack==null)
                runBlocking {check(owner.shutdown())}
                check(db.load()==capped)
            }
            return "Private SQLite queue atomic save/reopen, duplicate occurrence order/position, failed-write rollback, corrupt-state rejection, explicit clear and exact 1000-entry shuffled/repeated owner restore paused passed; no output opened"
        }finally{context.deleteDatabase(name)}
    }

    fun runtime(instrumentation:Instrumentation,mode:String):String {
        val context=instrumentation.targetContext
        val main:(()->Unit)->Unit={action->instrumentation.runOnMainSync{action()}}
        lateinit var future:ListenableFuture<MediaController>
        main{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val control=future.get(10,TimeUnit.SECONDS)
        val owner=PlaybackRuntime.owner(context)
        fun await(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+10000;do{var done=false;main{done=condition()};if(done)return;SystemClock.sleep(20)}while(SystemClock.elapsedRealtime()<end);error("Restore $mode did not settle: ${owner.queue.value.index}/${owner.snapshot.value.state}/${owner.snapshot.value.failure}; position=${owner.positionUs}")}
        fun send(action:String,args:Bundle=Bundle.EMPTY):Int{lateinit var reply:ListenableFuture<SessionResult>;main{reply=control.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)};return reply.get(10,TimeUnit.SECONDS).resultCode}
        try {
            runBlocking{owner.initialize()}
            if(mode=="restore-seed"){
                main{control.stop()};await{control.mediaItemCount==0}
                val db=LocalLibrary.database(context)
                val root=db.page().roots.single{it.name=="Evanna-library-check"}
                runBlocking{scanLibrary(root.uri,db,SafLibrarySource(context,android.net.Uri.parse(root.uri)))}
                val silence=db.page().songs.single{it.title=="restore-silence.wav" && it.available}
                val tagged=db.page().songs.single{it.title=="Evanna café" && it.available}
                for(song in listOf(silence,tagged,silence))check(send(PlaybackService.ENQUEUE_LIBRARY,Bundle().apply{putString(PlaybackService.LIBRARY_ID,song.id)})==SessionResult.RESULT_SUCCESS)
                await{control.mediaItemCount==3}
                check(runBlocking{owner.seekTo(3_000_000)})
                val id=owner.queue.value.items[0].id
                check(send(PlaybackService.MOVE_QUEUE,Bundle().apply{putString(PlaybackService.QUEUE_ID,id);putInt(PlaybackService.QUEUE_INDEX,2)})==SessionResult.RESULT_SUCCESS)
                await{control.currentMediaItemIndex==2 && control.currentPosition==3000L}
                runBlocking{owner.checkpoint()}
                check(owner.snapshot.value.route==null && !owner.queue.value.saveFailed)
                return "Seeded three persistent library occurrences with duplicate silence, current index2 and position3s; no output/focus/playback; force-stop/relaunch next"
            }
            if(mode=="restore-check"){
                await{control.mediaItemCount==3 && control.currentMediaItemIndex==2}
                main{check(!control.playWhenReady && control.currentPosition==3000L)}
                check(owner.selectedTrack==null && owner.snapshot.value.route==null && PlaybackRuntime.diagnosticFocusHeld()!=true)
                check(owner.queue.value.items.map{it.title}==listOf("Evanna café","restore-silence.wav","restore-silence.wav"))
                return "Cold service restored duplicate order/current index2/3s position paused from SQLite, with no source/output/focus opened"
            }
            if(mode=="restore-missing"){
                check(send(PlaybackService.OPEN_ANDROID)==SessionResult.RESULT_SUCCESS)
                main{control.play()};await{owner.sourceSelectionFailed}
                check(owner.queue.value.items.size==3 && owner.queue.value.index==2 && owner.positionUs==3_000_000L)
                check(owner.snapshot.value.androidStream==null)
                main{check(!control.playWhenReady)}
                return "Missing saved source refused explicit Play before AudioTrack start, preserving queue order/current occurrence/3s anchor for retry"
            }
            if(mode=="restore-resume"){
                val audio=context.getSystemService(AudioManager::class.java)
                val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                if(owner.snapshot.value.route==null)check(send(PlaybackService.OPEN_ANDROID)==SessionResult.RESULT_SUCCESS)
                main{control.play()}
                await{owner.snapshot.value.state==UsbPlaybackState.PLAYING && owner.positionUs>=3_100_000}
                check(owner.snapshot.value.startPositionUs==3_000_000L && owner.snapshot.value.route==PlaybackRoute.ANDROID)
                main{control.pause()};await{owner.snapshot.value.state==UsbPlaybackState.PAUSED && !control.playWhenReady}
                runBlocking{owner.checkpoint()}
                check(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==volume)
                main{control.stop()};await{control.mediaItemCount==0}
                check(PlaybackQueueStore(context).use{it.load()}==SavedQueue())
                return "Restored explicit Android Play resumed all-zero source at saved3s, pause checkpoint and explicit Stop cleared persisted queue with unchanged system volume; no gain/nonzero PCM"
            }
            if(mode=="restore-empty"){
                await{control.mediaItemCount==0}
                check(owner.queue.value.items.isEmpty() && owner.snapshot.value.route==null && owner.selectedTrack==null)
                return "Explicit Stop remained empty after process restart; no stale saved queue or autoplay"
            }
            if(mode=="restore-direct-seed"){
                val saved=PlaybackQueueStore(context).use{it.load()} ?: error("Select the generated silent WAV through Choose local file first")
                val item=saved.items.single()
                check(item.fileUri!=null && item.libraryId==null)
                if(owner.selectedTrack==null)check(runBlocking{owner.selectQueue(item.id)}){"Direct source preparation failed"}
                val track=checkNotNull(owner.selectedTrack)
                check(track.format.codec=="PCM_S16LE" && track.format.sampleRate==48000 && track.format.durationUs==10_000_000L){"Unexpected generated source format: ${track.format.codec}/${track.format.sampleRate}/${track.format.durationUs}"}
                check(runBlocking{owner.seekTo(2_000_000)}){"Direct paused seek failed"}
                check(runBlocking{owner.shutdown()}){"Preservation shutdown failed"}
                check(context.contentResolver.persistedUriPermissions.any{it.uri.toString()==item.fileUri && it.isReadPermission}){"Direct lease was not retained"}
                check(PlaybackQueueStore(context).use{it.load()}!!.positionUs==2_000_000L){"Direct anchor was not retained"}
                return "Direct-file picker occurrence saved at2s; preservation shutdown closed sources/output but retained its read lease; force-stop/relaunch next"
            }
            if(mode=="restore-direct-check"){
                val saved=PlaybackQueueStore(context).use{it.load()} ?: error("Missing saved direct file")
                val item=saved.items.single()
                val uri=checkNotNull(item.fileUri)
                check(owner.selectedTrack==null && owner.snapshot.value.route==null && owner.positionUs==2_000_000L)
                check(context.contentResolver.persistedUriPermissions.any{it.uri.toString()==uri && it.isReadPermission})
                check(runBlocking{owner.selectQueue(item.id)})
                check(owner.selectedTrack?.format?.durationUs==10_000_000L)
                val key="g_"+java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
                val owned=context.getSharedPreferences("local-source-grants",0).getBoolean(key,false)
                check(owned){"Generated direct-file grant must be newly owned for this release regression"}
                main{control.stop()};await{control.mediaItemCount==0}
                check(context.contentResolver.persistedUriPermissions.none{it.uri.toString()==uri && it.isReadPermission})
                check(PlaybackQueueStore(context).use{it.load()}==SavedQueue())
                return "Direct-file cold restore kept2s paused and its lease, explicit selection reopened successfully, and Stop cleared saved state/released only its owned file grant; no output opened"
            }
            error("Unknown restore mode")
        }finally{main{control.release()}}
    }
}
