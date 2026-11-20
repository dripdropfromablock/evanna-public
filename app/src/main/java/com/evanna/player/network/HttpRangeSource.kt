package com.evanna.player.network

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A seekable Android FD for the existing decoder. The decoder must close its duplicate first. */
internal interface NetworkDescriptor:Closeable {val descriptor:ParcelFileDescriptor}

internal class HttpRangeSource(context:Context,url:String,headers:Map<String,String>,policy:NetworkPolicy,
    val cancel:CancelScope=CancelScope(),maxLength:Long=Long.MAX_VALUE):
    ProxyFileDescriptorCallback(),NetworkDescriptor {
    private val reader=RangeReader(url,headers,policy,cancel,maxLength)
    private val worker=HandlerThread("EvannaNetworkSource").apply {start()}
    private val released=CountDownLatch(1)
    private val closed=AtomicBoolean()
    override val descriptor=try {context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
        ParcelFileDescriptor.MODE_READ_ONLY,this,Handler(worker.looper))}
        catch(failure:Throwable){reader.close();worker.quitSafely();throw failure}
    override fun onGetSize()=reader.length
    override fun onRead(offset:Long,size:Int,data:ByteArray):Int = try {
        if(size !in 0..data.size)throw IOException("INVALID_SOURCE_READ")
        val bytes=reader.read(offset,size);bytes.copyInto(data);bytes.size
    }catch(_:IOException){cancel.cancel();throw ErrnoException("sourceRead",OsConstants.EIO)}
    override fun onRelease(){reader.close();released.countDown()}
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        reader.close()
        try {descriptor.close();check(released.await(2,TimeUnit.SECONDS)){"SOURCE_DESCRIPTOR_STILL_OPEN"}}
        finally {worker.quitSafely();worker.join(1500)}
        check(!worker.isAlive){"SOURCE_CALLBACK_STILL_RUNNING"}
    }
}
