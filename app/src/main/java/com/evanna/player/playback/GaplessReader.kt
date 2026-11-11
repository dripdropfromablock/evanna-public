package com.evanna.player.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import kotlinx.coroutines.*

internal data class GaplessQueue(val queue:PlaybackQueue,val sources:Map<String,()->PcmTrack>,val blockedAfter:Set<String> = emptySet()) {
    fun next(id:String):Pair<String,()->PcmTrack>? {
        if(id in blockedAfter)return null
        val index=queue.items.indexOfFirst{it.id==id}
        if(index<0)return null
        val next=queue.copy(index=index).navigationIndex(1,automatic=true)
        val key=queue.items.getOrNull(next)?.id ?: return null
        return sources[key]?.let{key to it}
    }
}
internal data class GaplessSegment(val queueId:String,val track:PcmTrack,val startFrame:Long,val startPositionUs:Long=0,
    val playId:String=java.util.UUID.randomUUID().toString())
internal data class GaplessPlayback(val queueId:String,val queue:()->GaplessQueue,val focused:()->Boolean,
    val libraryIds:Map<String,String> = emptyMap())

/** One reader/writer, one prepared successor. No task takes the owner/controller command locks. */
internal class GaplessReader(
    queueId:String,initial:PcmTrack,input:PcmReader,
    private val queue:()->GaplessQueue,private val focused:()->Boolean,private val allowed:(PcmFormat)->Boolean,
    private val completedFrames:()->Long={Long.MAX_VALUE},startPositionUs:Long=0,
    initialPlayId:String=java.util.UUID.randomUUID().toString(),
    private val successorAllowed:suspend (String,PcmTrack)->Boolean={_,_->true},
    private val onCompleted:(GaplessSegment,Long)->Unit={_,_->},
) : PcmReader {
    override val format=initial.format
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private class Resource(val factory:(()->PcmTrack)?,val owned:Boolean) {
        @Volatile var track:PcmTrack?=null
        @Volatile var reader:PcmReader?=null
        var trackClosed=false
    }
    private class Pending(val id:String,val factory:()->PcmTrack,val resource:Resource) {
        lateinit var job:Job
        var buffer:ByteBuffer?=null
        var count=0
        var failure:Exception?=null
        var compatible=false
    }
    private val resources=CopyOnWriteArrayList<Resource>()
    private var current=Resource(null,false).also{it.track=initial;it.reader=input;resources+=it}
    private var submittedId=queueId
    private var frames=0L
    private val boundaries=ArrayDeque<GaplessSegment>().apply{add(GaplessSegment(queueId,initial,0,startPositionUs,initialPlayId))}
    private var pending:Pending?=null
    @Volatile private var cancelled=false
    private var ended=false
    private var first:ByteBuffer?=null
    private var firstCount=0
    private var revisionRetries=0

    @Synchronized fun completed(frame:Long):GaplessSegment {
        while(boundaries.size>1 && boundaries.elementAt(1).startFrame<=frame) {
            val end=boundaries.elementAt(1).startFrame
            onCompleted(boundaries.removeFirst(),end)
        }
        return boundaries.first()
    }
    private fun checkActive(){if(cancelled)throw CancellationException("Sequence cancelled")}
    private fun sameFormat(other:PcmFormat)=other.sampleRate==format.sampleRate &&
        other.channels==format.channels && other.significantBits==format.significantBits

    private fun prepare() {
        if(pending!=null || !focused() || !current.track!!.gaplessEligible)return
        val next=queue().next(submittedId) ?: return
        val resource=Resource(next.second,true).also{resources+=it}
        val work=Pending(next.first,next.second,resource)
        pending=work
        work.job=scope.launch {
            try {
                checkActive()
                val track=next.second().also{resource.track=it}
                checkActive()
                if(!track.gaplessEligible || !sameFormat(track.format) || !allowed(track.format) || !successorAllowed(next.first,track))return@launch
                val reader=track.open().also{resource.reader=it}
                checkActive()
                check(sameFormat(reader.format) && reader.format.sourceBits==track.format.sourceBits &&
                    reader.format.codec==track.format.codec && reader.format.strictEligible==track.format.strictEligible)
                val capacity=(format.sampleRate/100).coerceIn(1,4096)
                val buffer=ByteBuffer.allocateDirect(capacity*format.channels*4).order(ByteOrder.LITTLE_ENDIAN)
                val count=reader.read(buffer,capacity)
                check(count in 1..capacity){"Empty or invalid successor"}
                work.buffer=buffer;work.count=count;work.compatible=true
            }catch(failure:Exception){work.failure=failure}
        }
    }
    override fun read(target:ByteBuffer,maxFrames:Int):Int {
        checkActive()
        if(ended)return 0
        if(firstCount>0) {
            val count=minOf(firstCount,maxFrames)
            target.duplicate().put(checkNotNull(first).duplicate().apply{limit(count*format.channels*4)})
            firstCount-=count
            if(firstCount>0) {
                val rest=checkNotNull(first).duplicate().apply{position(count*format.channels*4)}.slice().order(ByteOrder.LITTLE_ENDIAN)
                first=rest
            }else first=null
            frames=Math.addExact(frames,count.toLong());prepare();return count
        }
        val count=current.reader!!.read(target,maxFrames)
        checkActive()
        if(count>0){revisionRetries=0;frames=Math.addExact(frames,count.toLong());prepare();return count}
        check(count==0)
        prepare()
        val work=pending
        if(work==null){ended=true;return 0}
        runBlocking{work.job.join()}
        checkActive()
        val selected=queue().next(submittedId)
        if(focused() && selected!=null && (selected.first!=work.id || selected.second!==work.factory)) {
            cleanup(work.resource);pending=null
            if(++revisionRetries<=2)return read(target,maxFrames)
            ended=true;return 0
        }
        if(focused() && selected?.first==work.id && work.failure!=null) {
            cleanup(work.resource);pending=null;throw checkNotNull(work.failure)
        }
        if(!focused() || selected?.first!=work.id || !work.compatible) {
            cleanup(work.resource);pending=null;ended=true;return 0
        }
        // The output buffer can contain many tiny songs; bound metadata without sealing the stream.
        val deadline=System.nanoTime()+1_500_000_000L
        while(synchronized(this){boundaries.size>=128}) {
            checkActive();completed(completedFrames())
            check(System.nanoTime()<deadline){"Sequence completion stalled"}
            Thread.sleep(2)
        }
        cleanup(current)
        current=work.resource;submittedId=work.id;pending=null
        val actual=checkNotNull(current.track)
        val descriptor=object:PcmTrack {
            override val id=actual.id
            override val format=actual.format
            override val seekable=actual.seekable
            override val gaplessEligible=actual.gaplessEligible
            override val loudnessTags=actual.loudnessTags
            override fun open():PcmReader {
                val track=work.factory()
                try {
                    check(track.format==format && track.gaplessEligible==gaplessEligible && track.loudnessTags==loudnessTags)
                    val reader=track.open()
                    return object:PcmReader by reader {
                        override fun close(){reader.close();track.closeForRestore()}
                    }
                }catch(failure:Exception){track.closeForRestore();throw failure}
            }
            override fun close()=Unit
        }
        synchronized(this){boundaries.addLast(GaplessSegment(work.id,descriptor,frames))}
        first=work.buffer;firstCount=work.count
        return read(target,maxFrames)
    }
    override fun seekTo(positionUs:Long) {
        check(frames==0L && pending==null)
        current.reader!!.seekTo(positionUs)
    }
    override fun requestCancel() {
        cancelled=true
        scope.cancel()
        for(resource in resources)try{resource.reader?.requestCancel()}catch(_:Exception){ }
    }
    private fun cleanup(resource:Resource) {
        resource.reader?.close();resource.reader=null
        if(resource.owned && !resource.trackClosed){resource.track?.closeForRestore();resource.trackClosed=true}
        resources.remove(resource)
    }
    override fun close() {
        requestCancel()
        check(runBlocking{withTimeoutOrNull(2000){pending?.job?.join();true}}==true){"Successor preparation still owns resources"}
        var failure:Exception?=null
        for(resource in resources)try{cleanup(resource)}catch(error:Exception){failure=error}
        failure?.let{throw it}
    }
}
