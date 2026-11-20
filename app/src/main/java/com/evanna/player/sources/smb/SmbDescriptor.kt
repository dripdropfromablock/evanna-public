package com.evanna.player.sources.smb

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkDescriptor
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class SmbDescriptor(
    context: Context,
    private val connection: Closeable,
    private val cancel: CancelScope,
    private val reader: SmbReader,
) : ProxyFileDescriptorCallback(), NetworkDescriptor {
    private val worker = HandlerThread("EvannaSmbSource").apply { start() }
    private val released = CountDownLatch(1)
    private val closed = AtomicBoolean()
    override val descriptor: ParcelFileDescriptor = try {
        context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY, this, Handler(worker.looper),
        )
    } catch (failure: Throwable) {
        worker.quitSafely()
        throw failure
    }

    override fun onGetSize(): Long = reader.length

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
        reader.read(offset, size, data)
    } catch (_: IOException) {
        throw ErrnoException("sourceRead", OsConstants.EIO)
    }

    override fun onRelease() {
        cancel.cancel()
        released.countDown()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel.cancel()
        try {
            descriptor.close()
            check(released.await(2, TimeUnit.SECONDS)) { "SOURCE_DESCRIPTOR_STILL_OPEN" }
        } finally {
            worker.quitSafely()
            connection.close()
            worker.join(1500)
        }
        check(!worker.isAlive) { "SOURCE_CALLBACK_STILL_RUNNING" }
    }
}
