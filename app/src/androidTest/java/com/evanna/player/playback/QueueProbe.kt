package com.evanna.player.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.evanna.player.library.LocalLibrary
import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking

@UnstableApi
internal object QueueProbe {
    fun run(instrumentation: Instrumentation): String {
        val main: (() -> Unit) -> Unit = { action -> instrumentation.runOnMainSync { action() } }
        val output=Output()
        val owner=UsbSessionOwner(UsbPlaybackController { output },object : PlaybackFocus {
            override var held=false
            override fun request():Boolean {held=true;return true}
            override fun abandon(){held=false}
        })
        lateinit var player:EvannaPlayer;lateinit var session:MediaSession;lateinit var future:ListenableFuture<MediaController>
        main { player=EvannaPlayer(owner);session=MediaSession.Builder(instrumentation.targetContext,player).build();future=MediaController.Builder(instrumentation.targetContext,session.token).buildAsync() }
        val remote=future.get(10,TimeUnit.SECONDS)
        var stage="initial timeline"
        fun await(condition:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+10000
            do {
                var done=false
                main { done=condition() && (owner.queue.value.items.isEmpty() ||
                    (player.currentMediaItemIndex==owner.queue.value.index && remote.currentMediaItemIndex==owner.queue.value.index)) }
                if(done)return;SystemClock.sleep(20)
            }while(SystemClock.elapsedRealtime()<end)
            var remoteState=""
            main { remoteState="remote index=${remote.currentMediaItemIndex}, title=${remote.mediaMetadata.title}, count=${remote.mediaItemCount}, state=${remote.playbackState}" }
            error("$stage: ${owner.queue.value.index}/${owner.snapshot.value.state}/${owner.snapshot.value.failure}; $remoteState")
        }
        fun command(action:()->ListenableFuture<Boolean>){lateinit var result:ListenableFuture<Boolean>;main{result=action()};check(result.get(10,TimeUnit.SECONDS))}
        try {
            command { player.enqueue(source("First",1)) };command { player.enqueue(source("Second",2)) };command { player.enqueue(source("First",1)) }
            await {remote.mediaItemCount==3}
            main {
                check(!remote.playWhenReady && remote.currentMediaItemIndex==0)
                check(remote.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && !remote.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                check((0..2).map{remote.getMediaItemAt(it).mediaId}.toSet().size==3)
                check((0..2).none {remote.getMediaItemAt(it).localConfiguration!=null})
                remote.seekToNextMediaItem()
            }
            stage="paused next"
            await {remote.currentMediaItemIndex==1 && !remote.playWhenReady}
            stage="paused previous"
            main {remote.seekToPreviousMediaItem()};await{remote.currentMediaItemIndex==0}
            val first=owner.queue.value.items[0].id
            stage="reorder"
            command {player.moveQueue(first,2)};await{remote.currentMediaItemIndex==2 && remote.mediaMetadata.title.toString()=="First"}
            stage="select after reorder"
            command {player.selectQueue(owner.queue.value.items[0].id)};await{remote.currentMediaItemIndex==0}
            stage="active start"
            command {player.openDevice(7)}
            output.block=true;main{remote.play()};await{remote.isPlaying && output.writing.count==0L}
            stage="active next"
            main{remote.seekToNextMediaItem()};await{remote.currentMediaItemIndex==1 && remote.isPlaying && output.starts==2}
            stage="seek beyond previous threshold"
            main{remote.seekTo(5000)}
            await{owner.snapshot.value.positionUs==5_000_000L && remote.currentPosition>=5000L && remote.isPlaying}
            stage="generic previous restarts current"
            main{remote.seekToPrevious()}
            await{remote.currentMediaItemIndex==1 && owner.snapshot.value.startPositionUs==0L && remote.currentPosition<3000L && remote.isPlaying}
            main{remote.pause()};await{owner.snapshot.value.state==UsbPlaybackState.PAUSED && !remote.playWhenReady}
            stage="automatic EOF"
            command {player.selectQueue(owner.queue.value.items[0].id)}
            output.block=false;output.samples.clear();val before=output.starts
            main{remote.play()}
            await{remote.currentMediaItemIndex==2 && remote.playbackState==Player.STATE_ENDED}
            check(output.starts==before+3)
            check(output.samples==listOf(2,2,1,1,1,1).map{it shl 16}) {"Automatic order/PCM: ${output.samples}"}
            command {player.removeQueue(owner.queue.value.items[2].id)}
            await{remote.mediaItemCount==2 && !remote.playWhenReady}
            main{remote.stop()};await{remote.mediaItemCount==0 && owner.selectedTrack==null}
            check(owner.queue.value.items.isEmpty())
            return "Synthetic MediaController full timeline, duplicate IDs, paused next/previous, reorder/current identity, active skip/stop-join, automatic EOF order/literal PCM, current removal and Stop cleanup passed; no physical output opened"
        } finally {main{remote.release();session.release();player.release()};runBlocking{check(owner.close())}}
    }

    fun runLibrary(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        val songs=LocalLibrary.database(context).page().songs.filter{it.available}
        check(songs.size>=2){"Keep the generated test library indexed"}
        val main:(()->Unit)->Unit={action->instrumentation.runOnMainSync{action()}}
        lateinit var future:ListenableFuture<MediaController>
        main{future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()}
        val remote=future.get(10,TimeUnit.SECONDS)
        fun send(action:String,args:Bundle):Int {lateinit var reply:ListenableFuture<SessionResult>;main{reply=remote.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)};return reply.get(10,TimeUnit.SECONDS).resultCode}
        fun await(condition:()->Boolean) {val end=SystemClock.elapsedRealtime()+10000;do{var done=false;main{done=condition()};if(done)return;SystemClock.sleep(20)}while(SystemClock.elapsedRealtime()<end);error("Service queue did not settle")}
        val owner=PlaybackRuntime.owner(context)
        val grants=context.contentResolver.persistedUriPermissions.map{it.uri}.toSet()
        try {
            main{remote.stop()};await{remote.mediaItemCount==0}
            check(send(PlaybackService.ENQUEUE_LIBRARY,Bundle().apply{putString(PlaybackService.LIBRARY_ID,"invalid")})==SessionError.ERROR_BAD_VALUE)
            for(song in listOf(songs[0],songs[1],songs[0]))check(send(PlaybackService.ENQUEUE_LIBRARY,Bundle().apply{putString(PlaybackService.LIBRARY_ID,song.id)})==SessionResult.RESULT_SUCCESS)
            await{remote.mediaItemCount==3}
            val ids=mutableListOf<String>();main{repeat(3){ids+=remote.getMediaItemAt(it).mediaId};check(!remote.playWhenReady && remote.mediaMetadata.title.toString()==songs[0].title)}
            check(ids.toSet().size==3 && owner.snapshot.value.route==null)
            check(send(PlaybackService.MOVE_QUEUE,Bundle().apply{putString(PlaybackService.QUEUE_ID,ids[0]);putInt(PlaybackService.QUEUE_INDEX,2)})==SessionResult.RESULT_SUCCESS)
            await{remote.currentMediaItemIndex==2}
            main{remote.seekToPreviousMediaItem()};await{remote.currentMediaItemIndex==1 && !remote.playWhenReady}
            check(send(PlaybackService.REMOVE_QUEUE,Bundle().apply{putString(PlaybackService.QUEUE_ID,ids[1])})==SessionResult.RESULT_SUCCESS)
            await{remote.mediaItemCount==2}
            main{remote.stop()};await{remote.mediaItemCount==0}
            check(context.contentResolver.persistedUriPermissions.map{it.uri}.toSet()==grants)
            return "Real library stable-ID app-UID enqueue, invalid-ID refusal, duplicate occurrences, reorder/current index, paused standard skip, removal and Stop preserve tree grants; no audio output opened"
        }finally{main{remote.release()};runBlocking{check(owner.close())}}
    }
    private fun source(title:String,value:Int):QueueSource {
        val pcm=TrackPcmFormat(48000,16,2,16,"FLAC",true,10_000_000)
        return QueueSource("test:$title",title,null,null,10_000_000){object:PcmTrack{
        override val id="evanna:local:$title:${java.util.UUID.randomUUID()}"
        override val format=pcm
        override val seekable=true
        override fun open()=object:PcmReader{
            override val format=pcm
            var read=false
            override fun read(target:ByteBuffer,maxFrames:Int):Int{if(read)return 0;read=true;target.putInt(0,value shl 16);target.putInt(4,value shl 16);return 1}
            override fun requestCancel()=Unit
            override fun seekTo(positionUs:Long)=Unit
            override fun close()=Unit
        }
        override fun close()=Unit
    }}
    }
    private class Output:UsbPlaybackOutput{
        override val description="Synthetic queue output"
        var starts=0;var block=false;var stopped=CountDownLatch(1);var writing=CountDownLatch(1)
        val samples=java.util.Collections.synchronizedList(mutableListOf<Int>())
        override fun plan(source:PcmFormat)=PlaybackPlan(48000,16,false,true)
        override fun prepareVolume()=true
        override fun start(source:PcmFormat):StreamInfo{starts++;stopped=CountDownLatch(1);writing=CountDownLatch(1);return StreamInfo(48000,16,2,DacFormat(1,1,1,2,2,16,DacFormat.SyncType.SYNC,192,listOf(48000),48000,48000),48000,48000,true,false)}
        override fun readiness()=UsbPlaybackReadiness(true,true,true)
        override fun stats()=StreamStats(0,0,0,false)
        override fun write(buffer:ByteBuffer,frames:Int):Int{writing.countDown();if(block){check(stopped.await(15,TimeUnit.SECONDS));return -1};repeat(frames*2){samples+=buffer.getInt(it*4)};return frames}
        override fun drain()=true
        override fun stop():Boolean{stopped.countDown();return true}
        override fun close()=Unit
    }
}
