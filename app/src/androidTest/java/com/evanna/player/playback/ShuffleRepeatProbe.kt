package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.library.LocalLibrary
import com.google.common.util.concurrent.ListenableFuture
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

@UnstableApi
internal object ShuffleRepeatProbe {
    fun store(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        val name="shuffle-v1-probe.db"
        context.deleteDatabase(name)
        val first=SavedQueueEntry("evanna:local:queue:migrate-first","a".repeat(64),null,"café",null,null,10_000_000)
        val second=first.copy(id="evanna:local:queue:migrate-second")
        val expected=SavedQueue(listOf(first,second),1,3_000_000)
        try {
            context.openOrCreateDatabase(name,0,null).use { db ->
                db.execSQL("CREATE TABLE session(singleton INTEGER PRIMARY KEY CHECK(singleton=1),current_index INTEGER NOT NULL,position_us INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE entries(sequence INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,library_id TEXT,file_uri TEXT,title TEXT NOT NULL,artist TEXT,album TEXT,duration_us INTEGER)")
                db.execSQL("INSERT INTO session VALUES(1,1,3000000)")
                for((index,item) in expected.items.withIndex())db.execSQL("INSERT INTO entries(sequence,id,library_id,title,duration_us) VALUES(?,?,?,?,?)",arrayOf(index,item.id,item.libraryId,item.title,item.durationUs))
                db.version=1
            }
            val shuffled=expected.copy(shuffleEnabled=true,repeat=QueueRepeat.SONG,playOrder=expected.playOrder.reversed())
            PlaybackQueueStore(context,name).use { db ->
                check(db.load()==expected){"v1 migration lost queue/index/position or changed defaults"}
                db.save(shuffled)
            }
            PlaybackQueueStore(context,name).use { db ->
                check(db.load()==shuffled)
                db.writableDatabase.execSQL("UPDATE entries SET play_order=0")
                check(runCatching{db.load()}.isFailure)
                check(db.readableDatabase.rawQuery("SELECT COUNT(*) FROM entries",null).use{it.moveToFirst();it.getInt(0)}==2)
                db.save(shuffled)
                db.writableDatabase.execSQL("UPDATE session SET repeat_mode='INVALID'")
                check(runCatching{db.load()}.isFailure)
                db.save(SavedQueue(shuffleEnabled=true,repeat=QueueRepeat.QUEUE))
            }
            PlaybackQueueStore(context,name).use { check(it.load()==SavedQueue(shuffleEnabled=true,repeat=QueueRepeat.QUEUE)) }
            return "Actual SQLite v1→v2 preserved duplicate order/current index/3s/default modes; exact shuffled order and modes survived reopen, corrupt order/mode rejected without deletion, settings survived empty queue"
        }finally{context.deleteDatabase(name)}
    }

    fun run(instrumentation:Instrumentation):String {
        val main:(()->Unit)->Unit={action->instrumentation.runOnMainSync{action()}}
        val output=Output()
        val owner=UsbSessionOwner(UsbPlaybackController{output},object:PlaybackFocus{
            override var held=false
            override fun request():Boolean{held=true;return true}
            override fun abandon(){held=false}
        },shuffleOrder={it.reversed()})
        lateinit var player:EvannaPlayer;lateinit var session:MediaSession;lateinit var future:ListenableFuture<MediaController>
        main{player=EvannaPlayer(owner);session=MediaSession.Builder(instrumentation.targetContext,player).build();future=MediaController.Builder(instrumentation.targetContext,session.token).buildAsync()}
        val remote=future.get(10,TimeUnit.SECONDS)
        var stage="initial"
        fun await(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+10000;do{
            var done=false;main{done=condition() && (owner.queue.value.items.isEmpty() || (remote.currentMediaItemIndex==owner.queue.value.index && player.currentMediaItemIndex==owner.queue.value.index))}
            if(done)return;SystemClock.sleep(20)
        }while(SystemClock.elapsedRealtime()<end);error("Shuffle $stage: index=${owner.queue.value.index}, state=${owner.snapshot.value.state}, starts=${output.starts}, failure=${owner.snapshot.value.failure}")}
        fun command(action:()->ListenableFuture<Boolean>){lateinit var result:ListenableFuture<Boolean>;main{result=action()};check(result.get(10,TimeUnit.SECONDS))}
        fun modes(shuffle:Boolean,repeat:Int){
            main{remote.shuffleModeEnabled=shuffle;remote.repeatMode=repeat}
            await{
                val queue=owner.queue.value
                val manualRepeat=if(repeat==Player.REPEAT_MODE_ONE)Player.REPEAT_MODE_OFF else repeat
                remote.shuffleModeEnabled==shuffle && remote.repeatMode==repeat && player.shuffleModeEnabled==shuffle &&
                    queue.shuffleEnabled==shuffle && queue.repeat.ordinal==repeat &&
                    remote.currentTimeline.getNextWindowIndex(queue.index,manualRepeat,shuffle)==queue.navigationIndex(1) &&
                    remote.currentTimeline.getPreviousWindowIndex(queue.index,manualRepeat,shuffle)==queue.navigationIndex(-1)
            }
        }
        try {
            repeat(3){n->command{player.enqueue(source("Song ${n+1}",n+1))}}
            await{remote.mediaItemCount==3}
            val base=owner.queue.value.items.map{it.id}
            modes(true,Player.REPEAT_MODE_OFF)
            check(owner.queue.value.playOrder==listOf(base[0],base[2],base[1]))
            var timelineMatches=false
            main{timelineMatches=(0..2).map{remote.getMediaItemAt(it).mediaId}==base && remote.currentTimeline.getNextWindowIndex(0,Player.REPEAT_MODE_OFF,true)==2}
            check(timelineMatches){"Remote shuffled timeline differs from settled owner order"}
            main{remote.seekToNextMediaItem()}
            stage="shuffled next";await{remote.currentMediaItemIndex==2 && !remote.playWhenReady}
            main{remote.seekToPreviousMediaItem()};await{remote.currentMediaItemIndex==0}
            modes(true,Player.REPEAT_MODE_ALL)
            main{remote.seekToPreviousMediaItem()};await{remote.currentMediaItemIndex==1}
            main{remote.seekToNextMediaItem()};await{remote.currentMediaItemIndex==0}
            modes(true,Player.REPEAT_MODE_ONE)
            main{remote.seekToNextMediaItem()};await{remote.currentMediaItemIndex==2 && !remote.playWhenReady}
            modes(false,Player.REPEAT_MODE_ONE)
            check(owner.queue.value.playOrder==base)
            command{player.selectQueue(base[0])};command{player.openDevice(7)}
            stage="repeat song";output.holdAt=3
            main{remote.play()};await{output.starts==3 && owner.snapshot.value.state==UsbPlaybackState.PLAYING}
            check(output.samples==listOf(1,1,1,1).map{it shl 16})
            val generation=owner.snapshot.value.generation
            modes(true,Player.REPEAT_MODE_ALL)
            check(owner.snapshot.value.generation==generation && output.starts==3)
            main{remote.pause()};await{owner.snapshot.value.state==UsbPlaybackState.PAUSED && !remote.playWhenReady}
            modes(false,Player.REPEAT_MODE_ALL);command{player.selectQueue(base[0])}
            stage="repeat queue";output.holdAt=output.starts+4;output.samples.clear()
            main{remote.play()};await{output.starts==output.holdAt && remote.currentMediaItemIndex==0 && owner.snapshot.value.state==UsbPlaybackState.PLAYING}
            check(output.samples==listOf(1,1,2,2,3,3).map{it shl 16})
            main{remote.pause()};await{!remote.playWhenReady && owner.snapshot.value.state==UsbPlaybackState.PAUSED}
            modes(false,Player.REPEAT_MODE_OFF)
            main{remote.stop()};await{remote.mediaItemCount==0}
            return "Synthetic MediaController exact shuffled traversal/base-order retention, manual Next under Repeat Song, Repeat Queue wrapping, repeated EOF PCM, live mode changes without restart and Pause/Stop passed; no physical output opened"
        }finally{main{remote.release();session.release();player.release()};runBlocking{check(owner.close())}}
    }

    fun runtime(instrumentation:Instrumentation,mode:String):String {
        val context=instrumentation.targetContext
        val main:(()->Unit)->Unit={action->instrumentation.runOnMainSync{action()}}
        lateinit var future:ListenableFuture<MediaController>
        main{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val remote=future.get(10,TimeUnit.SECONDS);val owner=PlaybackRuntime.owner(context)
        fun await(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+10000;do{var done=false;main{done=condition()};if(done)return;SystemClock.sleep(20)}while(SystemClock.elapsedRealtime()<end);error("Shuffle runtime $mode did not settle")}
        try {
            runBlocking{owner.initialize()}
            if(mode=="shuffle-seed"){
                main{remote.shuffleModeEnabled=false;remote.repeatMode=Player.REPEAT_MODE_OFF;remote.stop()};await{remote.mediaItemCount==0 && !owner.queue.value.shuffleEnabled && owner.queue.value.repeat==QueueRepeat.OFF}
                val songs=LocalLibrary.database(context).page().songs.filter{it.available}.take(3);check(songs.size==3)
                for(song in songs){lateinit var result:ListenableFuture<SessionResult>;main{result=remote.sendCustomCommand(SessionCommand(PlaybackService.ENQUEUE_LIBRARY,Bundle.EMPTY),Bundle().apply{putString(PlaybackService.LIBRARY_ID,song.id)})};check(result.get(10,TimeUnit.SECONDS).resultCode==SessionResult.RESULT_SUCCESS)}
                main{remote.shuffleModeEnabled=true;remote.repeatMode=Player.REPEAT_MODE_ALL}
                await{remote.shuffleModeEnabled && remote.repeatMode==Player.REPEAT_MODE_ALL && owner.queue.value.shuffleEnabled && owner.queue.value.repeat==QueueRepeat.QUEUE}
                runBlocking{owner.checkpoint()}
                check(owner.snapshot.value.route==null)
                return "Saved actual library queue plus exact shuffle permutation/Repeat Queue through standard controller commands without output; force-stop/relaunch next"
            }
            if(mode=="shuffle-check"){
                val saved=PlaybackQueueStore(context).use{it.load()}!!
                await{remote.mediaItemCount==3 && remote.shuffleModeEnabled && remote.repeatMode==Player.REPEAT_MODE_ALL}
                check(owner.queue.value.playOrder==saved.playOrder && owner.selectedTrack==null && owner.snapshot.value.route==null)
                main{check(!remote.playWhenReady)}
                return "Cold service restored shuffle/Repeat Queue/exact permutation and base queue paused, without source/output/focus opening"
            }
            if(mode=="shuffle-clear"){
                main{remote.shuffleModeEnabled=false;remote.repeatMode=Player.REPEAT_MODE_OFF;remote.stop()}
                await{remote.mediaItemCount==0 && !owner.queue.value.shuffleEnabled && owner.queue.value.repeat==QueueRepeat.OFF}
                runBlocking{owner.checkpoint()}
                check(PlaybackQueueStore(context).use{it.load()}==SavedQueue())
                return "Explicit test cleanup restored Shuffle off/Repeat off and empty saved queue; no output opened"
            }
            error("Unknown shuffle mode")
        }finally{main{remote.release()}}
    }

    private fun source(title:String,value:Int):QueueSource {
        val pcm=TrackPcmFormat(48000,16,2,16,"FLAC",true,1_000_000)
        return QueueSource(null,title,null,null,1_000_000){object:PcmTrack{
            override val id="evanna:local:${java.util.UUID.randomUUID()}"
            override val format=pcm
            override fun open()=object:PcmReader{
                override val format=pcm
                var read=false
                override fun read(target:ByteBuffer,maxFrames:Int):Int{if(read)return 0;read=true;target.putInt(0,value shl 16);target.putInt(4,value shl 16);return 1}
                override fun requestCancel()=Unit
                override fun close()=Unit
            }
            override fun close()=Unit
        }}
    }
    private class Output:UsbPlaybackOutput {
        override val description="Synthetic shuffle output"
        var starts=0;var holdAt=Int.MAX_VALUE;private var stopped=CountDownLatch(1)
        val samples=java.util.Collections.synchronizedList(mutableListOf<Int>())
        override fun plan(source:PcmFormat)=PlaybackPlan(48000,16,false,true)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo{starts++;stopped=CountDownLatch(1);return StreamInfo(48000,16,2,DacFormat(1,1,1,2,2,16,DacFormat.SyncType.SYNC,512,listOf(48000),48000,48000),48000,48000,true,false)}
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(0,0,0,false)
        override fun write(buffer:ByteBuffer,frames:Int):Int{if(starts==holdAt){check(stopped.await(15,TimeUnit.SECONDS));return -1};repeat(frames*2){samples+=buffer.getInt(it*4)};return frames}
        override fun drain()=true
        override fun stop():Boolean{stopped.countDown();return true}
        override fun close()=Unit
    }
}
