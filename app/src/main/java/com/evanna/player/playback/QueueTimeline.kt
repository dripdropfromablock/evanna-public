package com.evanna.player.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/** Keep base windows/period IDs intact while exporting the owner's exact shuffle traversal. */
@UnstableApi
internal class QueueTimeline(private val base:Timeline,private val order:List<Int>):Timeline() {
    init { require(order.sorted()==(0 until base.windowCount).toList()) }
    override fun getWindowCount()=base.windowCount
    override fun getWindow(index:Int,window:Window,defaultPositionProjectionUs:Long)=base.getWindow(index,window,defaultPositionProjectionUs)
    override fun getPeriodCount()=base.periodCount
    override fun getPeriod(index:Int,period:Period,setIds:Boolean)=base.getPeriod(index,period,setIds)
    override fun getIndexOfPeriod(uid:Any)=base.getIndexOfPeriod(uid)
    override fun getUidOfPeriod(index:Int)=base.getUidOfPeriod(index)
    override fun getFirstWindowIndex(shuffleModeEnabled:Boolean)=if(shuffleModeEnabled)order.firstOrNull() ?: -1 else base.getFirstWindowIndex(false)
    override fun getLastWindowIndex(shuffleModeEnabled:Boolean)=if(shuffleModeEnabled)order.lastOrNull() ?: -1 else base.getLastWindowIndex(false)
    override fun getNextWindowIndex(windowIndex:Int,repeatMode:Int,shuffleModeEnabled:Boolean):Int =
        if(shuffleModeEnabled)neighbor(windowIndex,repeatMode,1) else base.getNextWindowIndex(windowIndex,repeatMode,false)
    override fun getPreviousWindowIndex(windowIndex:Int,repeatMode:Int,shuffleModeEnabled:Boolean):Int =
        if(shuffleModeEnabled)neighbor(windowIndex,repeatMode,-1) else base.getPreviousWindowIndex(windowIndex,repeatMode,false)
    private fun neighbor(index:Int,repeatMode:Int,direction:Int):Int {
        if(index !in 0 until windowCount)return -1
        if(repeatMode==Player.REPEAT_MODE_ONE)return index
        val target=order.indexOf(index)+direction
        return if(target in order.indices)order[target] else if(repeatMode==Player.REPEAT_MODE_ALL)order[(target+order.size)%order.size] else -1
    }
}
