package com.evanna.player.library

internal fun nextLibraryPage(base:Int,loaded:Int,total:Int):Int? {
    if(base<0 || loaded<=0 || total<0)return null
    val next=base.toLong()+loaded
    return next.takeIf {it<total && it<=Int.MAX_VALUE}?.toInt()
}

internal val libraryAlphabet="#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
internal fun libraryEndIsNear(lastVisible:Int?,totalItems:Int,remaining:Int=12):Boolean =
    lastVisible!=null && lastVisible>=0 && totalItems>0 && remaining>0 && lastVisible>=totalItems-remaining
internal fun alphabetLetter(y:Float,height:Float):Char? {
    if(!y.isFinite() || !height.isFinite() || height<=0)return null
    return libraryAlphabet[((y/height).coerceIn(0f,1f)*libraryAlphabet.length).toInt().coerceAtMost(26)]
}
/** Top of the A–Z bar: beside the first list row (-1 until rows show), but never shorter than [minimum] unless the view is. */
internal fun alphabetBarTop(rowsTop:Int,height:Int,minimum:Int):Int? =
    if(rowsTop<0)null else minOf(rowsTop,height-minimum).coerceAtLeast(0)
