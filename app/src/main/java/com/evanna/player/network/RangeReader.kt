package com.evanna.player.network

import java.io.Closeable
import java.io.IOException

/** Finite-file transport only. One 64 KiB cache per active reader; no whole-track allocation. */
internal class RangeReader(private val url:String,private val headers:Map<String,String>,policy:NetworkPolicy,
    val scope:CancelScope=CancelScope(),maxLength:Long=Long.MAX_VALUE):Closeable {
    private val http=HttpAccess(policy)
    private var cacheStart=-1L
    private var cache=ByteArray(0)
    val cachedBytes get()=cache.size
    val length:Long
    private val validatorName:String?
    private val validator:String?
    init {
        val first=http.fetch(url,1,headers+mapOf("Range" to "bytes=0-0"),scope=scope)
        val mime=first.headers["Content-Type"].orEmpty().substringBefore(';').trim().lowercase()
        if(mime.startsWith("text/") || "json" in mime || "xml" in mime ||
            first.headers["Content-Encoding"]?.equals("identity",ignoreCase=true)==false)
            throw IOException("SOURCE_AUDIO_REQUIRED")
        val match=Regex("bytes 0-0/(\\d+)").matchEntire(first.headers["Content-Range"].orEmpty())
        length=match?.groupValues?.get(1)?.toLongOrNull()?.takeIf {it>0} ?: throw IOException("SEEKABLE_FILE_REQUIRED")
        if(first.code!=206 || first.bytes.size!=1)throw IOException("SEEKABLE_FILE_REQUIRED")
        if(length>maxLength)throw IOException("SOURCE_TOO_LARGE")
        validatorName=when {first.headers["ETag"]!=null -> "ETag";first.headers["Last-Modified"]!=null -> "Last-Modified";else->null}
        validator=validatorName?.let {first.headers[it]}
    }
    @Synchronized fun read(offset:Long,count:Int):ByteArray = try {readChecked(offset,count)}
        catch(failure:IOException){scope.cancel();throw failure}
    private fun readChecked(offset:Long,count:Int):ByteArray {
        scope.checkActive()
        if(offset !in 0..length || count !in 0..1024*1024)throw IOException("INVALID_SOURCE_READ")
        val output=ByteArray(minOf(count.toLong(),length-offset).toInt())
        var copied=0
        while(copied<output.size) {
            scope.checkActive();val position=offset+copied
            if(position<cacheStart || position>=cacheStart+cache.size) {
                val start=position/65536*65536
                val end=start+minOf(65535,length-1-start)
                val expected=(end-start+1).toInt()
                val rangeHeaders=headers+mapOf("Range" to "bytes=$start-$end")+
                    (validator?.takeUnless {it.startsWith("W/")}?.let {mapOf("If-Range" to it)} ?: emptyMap())
                val response=http.fetch(url,expected,rangeHeaders,scope=scope)
                if(response.code!=206 || response.headers["Content-Range"]!="bytes $start-$end/$length" ||
                    response.bytes.size!=expected || response.headers["Content-Encoding"]?.let {it!="identity"}==true ||
                    validatorName!=null && response.headers[validatorName]!=validator) {
                    scope.cancel();throw IOException("SOURCE_CHANGED_OR_INVALID_RANGE")
                }
                cacheStart=start;cache=response.bytes
            }
            val from=(position-cacheStart).toInt();val size=minOf(output.size-copied,cache.size-from)
            cache.copyInto(output,copied,from,from+size);copied+=size
        }
        return output
    }
    override fun close(){scope.cancel()}
}
