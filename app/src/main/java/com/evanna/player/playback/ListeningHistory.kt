package com.evanna.player.playback

import java.util.UUID

internal data class ListeningEvent(val sequence:Long,val eventId:String,val trackId:String,val playedAt:Long)

/** Completed output frames only. PCM callbacks keep a bounded buffer; database work stays in the owner. */
internal class ListeningHistory(private val now:()->Long={System.currentTimeMillis()}) {
    private var play:String?=null
    private var stream=Long.MIN_VALUE
    private var frames=0L
    private var listenedUs=0.0
    private var counted=false
    private var sequence=0L
    private var latestStream=Long.MIN_VALUE
    private var latestSegment=0L
    private val events=ArrayDeque<ListeningEvent>()
    @Synchronized fun progress(playId:String,trackId:String?,stream:Long,frames:Long,rate:Int,durationUs:Long?,segmentStartFrame:Long=0) {
        if(trackId==null || frames<0 || rate<=0 || stream<latestStream || stream==latestStream && segmentStartFrame<latestSegment)return
        latestStream=stream;latestSegment=segmentStartFrame
        if(play!=playId){play=playId;this.stream=Long.MIN_VALUE;this.frames=0;listenedUs=0.0;counted=false}
        val delta=if(this.stream==stream)(frames-this.frames).coerceAtLeast(0)else frames
        this.frames=if(this.stream==stream)maxOf(this.frames,frames)else frames;this.stream=stream
        listenedUs=(listenedUs+delta.toDouble()*1_000_000/rate).coerceAtMost(240_000_000.0)
        val threshold=durationUs?.takeIf {it>0}?.let {minOf(it/2.0,240_000_000.0)} ?: 240_000_000.0
        if(!counted && listenedUs>=threshold) {
            counted=true
            if(events.size==5000)events.removeFirst()
            events.addLast(ListeningEvent(++sequence,UUID.randomUUID().toString(),trackId,now()))
        }
    }
    @Synchronized fun pending():List<ListeningEvent> = events.toList()
    @Synchronized fun acknowledge(sequence:Long) {while(events.firstOrNull()?.let {it.sequence<=sequence}==true)events.removeFirst()}
}
