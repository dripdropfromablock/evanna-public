package com.evanna.player.decoder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-only range source: loopback HTTP or public fixture HTTPS, using platform TLS verification. */
internal class HttpRangeProxy(context: Context, private val url: URL, private val length: Long) :
    ProxyFileDescriptorCallback(), Closeable {
    private val cancelled = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val released = CountDownLatch(1)
    @Volatile private var connection: HttpURLConnection? = null
    private val worker = HandlerThread("NetworkDecoderProxy")
    private var cacheStart = -1L
    private var cache = ByteArray(0)
    val fetched = AtomicLong()
    val requests = AtomicLong()
    @Volatile var maxCacheBytes = 0; private set
    val descriptor: ParcelFileDescriptor

    init {
        require(url.userInfo == null && url.ref == null &&
            (url.protocol == "http" && url.host == "127.0.0.1" ||
                url.protocol == "https" && url.host == "raw.githubusercontent.com" && url.port in listOf(-1, 443)))
        require(length in 1..64L * 1024 * 1024)
        worker.start()
        descriptor = try {
            context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, this, Handler(worker.looper))
        } catch (failure: Throwable) { worker.quitSafely(); throw failure }
    }

    override fun onGetSize() = length

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
        try {
            val deadline = SystemClock.elapsedRealtime() + if (url.protocol == "https") 8000 else 2000
            checkOpen(deadline)
            if (offset !in 0..length || size < 0 || size > data.size) throw IOException("Invalid read")
            val count = minOf(size.toLong(), length - offset).toInt()
            var copied = 0
            while (copied < count) {
                checkOpen(deadline)
                val position = offset + copied
                if (position < cacheStart || position >= cacheStart + cache.size) fetch(position, deadline)
                val from = (position - cacheStart).toInt()
                val chunk = minOf(count - copied, cache.size - from)
                cache.copyInto(data, copied, from, from + chunk)
                copied += chunk
            }
            return count
        } catch (_: IOException) {
            // A failed probe read must stay failed when the decoder tries the same header again.
            cancelled.set(true)
            throw ErrnoException("networkRead", OsConstants.EIO)
        }
    }

    private fun checkOpen(deadline: Long) {
        if (cancelled.get() || SystemClock.elapsedRealtime() >= deadline) throw IOException("Stream cancelled or timed out")
    }

    private fun fetch(position: Long, deadline: Long) {
        val end = minOf(length - 1, position + 64 * 1024 - 1)
        val count = (end - position + 1).toInt()
        val request = url.openConnection() as HttpURLConnection
        request.connectTimeout = if (url.protocol == "https") 3000 else 1000
        request.readTimeout = request.connectTimeout
        request.useCaches = false
        request.instanceFollowRedirects = false
        request.setRequestProperty("Accept-Encoding", "identity")
        request.setRequestProperty("Range", "bytes=$position-$end")
        connection = request
        try {
            checkOpen(deadline)
            requests.incrementAndGet()
            // shortcut: header parsing has an inactivity timeout only; add a transport deadline before production.
            if (request.responseCode != 206 || request.getHeaderField("Content-Range") != "bytes $position-$end/$length" ||
                request.contentLengthLong != count.toLong() || request.contentEncoding?.let { it != "identity" } == true)
                throw IOException("Invalid range response")
            val bytes = ByteArray(count)
            request.inputStream.use { input ->
                var read = 0
                while (read < count) {
                    checkOpen(deadline)
                    val got = input.read(bytes, read, count - read)
                    if (got <= 0) throw IOException("Truncated range response")
                    read += got
                    fetched.addAndGet(got.toLong())
                }
            }
            checkOpen(deadline)
            cache = bytes
            cacheStart = position
            maxCacheBytes = maxOf(maxCacheBytes, bytes.size)
        } finally { connection = null; request.disconnect() }
    }

    fun cancel() { cancelled.set(true); connection?.disconnect() }
    override fun onRelease() { cancel(); released.countDown() }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        try {
            descriptor.close()
            check(released.await(2000, TimeUnit.MILLISECONDS)) { "Proxy descriptor was not released" }
        } finally { worker.quitSafely(); worker.join(1500) }
        check(!worker.isAlive) { "Network callback thread did not stop" }
    }
}
