package com.evanna.player.decoder

import android.content.Context
import com.evanna.player.network.*
import com.evanna.player.playback.*
import java.nio.ByteBuffer

internal class ResolvedNetworkMedia private constructor(val url:String?,val headers:Map<String,String>,val policy:NetworkPolicy?,
    private val openSource:((Context,CancelScope)->NetworkDescriptor)?) {
    constructor(url:String,headers:Map<String,String>,policy:NetworkPolicy):this(url,headers,policy,null)
    constructor(openSource:(Context,CancelScope)->NetworkDescriptor):this(null,emptyMap(),null,openSource)
    fun open(context:Context,cancel:CancelScope):NetworkDescriptor=openSource?.invoke(context,cancel)
        ?: HttpRangeSource(context,checkNotNull(url),headers,checkNotNull(policy),cancel)
}

internal class NetworkAudioTrack private constructor(private val context:Context,override val id:String,val info:DecoderInfo,
    private val fastRangeSeek:Boolean,private val resolve:(CancelScope)->ResolvedNetworkMedia):PcmTrack {
    override val format=TrackPcmFormat(info.sampleRate,info.significantBits ?: 32,info.channels,info.significantBits,
        sourceCodec(info.codec,info.sampleRate),info.lossless && !info.quantized && info.significantBits!=null,info.durationUs)
    override val seekable=info.seekable
    override val loudnessTags=info.loudnessTags
    override fun open():PcmReader=open(CancelScope())
    override fun open(cancel:CancelScope):PcmReader {
        cancel.checkActive();val media=resolve(cancel)
        val source=media.open(context,cancel)
        val decoder=try {
            NativeDecoder.open(source.descriptor.fd, fastRangeSeek).also {decoder ->
                try {
                    check(decoder.info.sampleRate==info.sampleRate && decoder.info.significantBits==info.significantBits &&
                        decoder.info.channels==info.channels && decoder.info.codec==info.codec && decoder.info.durationUs==info.durationUs &&
                        decoder.info.lossless==info.lossless && decoder.info.quantized==info.quantized && decoder.info.loudnessTags==info.loudnessTags){"SOURCE_FORMAT_CHANGED"}
                }catch(failure:Throwable){decoder.close();throw failure}
            }
        }catch(failure:Throwable){source.close();throw failure}
        return object:PcmReader {
            override val format=this@NetworkAudioTrack.format
            override fun read(target:ByteBuffer,maxFrames:Int)=decoder.readPcm(target,maxFrames)
            override fun seekTo(positionUs:Long)=decoder.seekTo(positionUs)
            override fun requestCancel(){cancel.cancel();decoder.requestCancel()}
            override fun close(){cancel.cancel();try {decoder.close()}finally {source.close()}}
        }
    }
    override fun close()=Unit
    companion object {
        fun prepare(context:Context,id:String,cancel:CancelScope,fastRangeSeek:Boolean=false,
            resolve:(CancelScope)->ResolvedNetworkMedia):NetworkAudioTrack {
            val media=resolve(cancel)
            val child=CancelScope()
            val unlink=cancel.watch {child.cancel()}
            val info=try {media.open(context,child).use {source ->
                NativeDecoder.open(source.descriptor.fd, fastRangeSeek).use {it.info}
            }}finally {unlink()}
            // Preparation closes its reader/scope; playback resolves a fresh request with its own scope.
            return NetworkAudioTrack(context.applicationContext,id,info,fastRangeSeek,resolve)
        }
    }
}
