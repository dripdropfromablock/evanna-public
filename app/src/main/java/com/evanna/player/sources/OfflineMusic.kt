package com.evanna.player.sources

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class OfflineMusic(private val directory:File,private val store:SourceStore) {

    fun file(item:SourceItem):File?=item.offlineFile?.takeIf {it.matches(filePattern)}
        ?.let {File(directory,it)}?.takeIf {it.isFile && it.length() in 1..maxTrackBytes}

    fun media(item:SourceItem):ResolvedNetworkMedia? = file(item)?.let(::localFileMedia)

    fun save(context:Context,ref:SourceReference,cancel:CancelScope,resolve:()->ResolvedNetworkMedia) {
        if(!lock.tryLock())throw IOException("OFFLINE_DOWNLOAD_BUSY")
        try {
            val item=checkNotNull(store.item(ref))
            if(file(item)!=null)return
            require(directory.isDirectory || directory.mkdirs())
            cleanup()
            val used=directory.listFiles().orEmpty().filter {it.name.matches(filePattern)}.sumOf {it.length()}
            val id=UUID.randomUUID().toString()
            val partial=File(directory,"$id.partial")
            val complete=File(directory,"$id.audio")
            var published=false
            try {
                resolve().open(context,cancel).use {source ->
                    val length=Os.fstat(source.descriptor.fileDescriptor).st_size
                    if(length !in 1..maxTrackBytes || used>maxTotalBytes-length || directory.usableSpace<length+reserveBytes)
                        throw IOException("OFFLINE_STORAGE_LIMIT")
                    FileOutputStream(partial).use {output ->
                        val buffer=ByteArray(64*1024)
                        var position=0L
                        while(position<length) {
                            cancel.checkActive()
                            val count=Os.pread(source.descriptor.fileDescriptor,buffer,0,minOf(buffer.size.toLong(),length-position).toInt(),position)
                            if(count<=0)throw IOException("OFFLINE_DOWNLOAD_INCOMPLETE")
                            output.write(buffer,0,count)
                            position+=count
                        }
                        output.fd.sync()
                    }
                    cancel.checkActive()
                    check(partial.length()==length)
                    ParcelFileDescriptor.open(partial,ParcelFileDescriptor.MODE_READ_ONLY).use {fd ->
                        NativeDecoder.open(fd.fd).use {check(it.info.seekable)}
                    }
                    cancel.checkActive()
                    Os.rename(partial.path,complete.path)
                    store.setOffline(ref,complete.name)
                    published=true
                }
            } finally {
                partial.delete()
                if(!published)complete.delete()
            }
        } finally {lock.unlock()}
    }

    fun remove(ref:SourceReference)=lock.withLock {
        val item=store.item(ref) ?: return@withLock
        store.setOffline(ref,null)
        file(item)?.delete()
        cleanup()
    }

    private fun cleanup() {
        val saved=store.offlineNames()
        directory.listFiles().orEmpty().forEach {file ->
            if(file.name.matches(partialPattern) || file.name.matches(filePattern) && file.name !in saved)file.delete()
        }
    }

    companion object {
        private val lock=ReentrantLock()
        private val filePattern=Regex("[0-9a-f-]{36}\\.audio")
        private val partialPattern=Regex("[0-9a-f-]{36}\\.partial")
        private const val maxTrackBytes=512L*1024*1024
        private const val maxTotalBytes=2L*1024*1024*1024
        private const val reserveBytes=64L*1024*1024
    }
}

internal fun localFileMedia(file: File): ResolvedNetworkMedia = ResolvedNetworkMedia { _, cancel ->
    cancel.checkActive()
    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    object : NetworkDescriptor {
        override val descriptor = fd
        override fun close() = fd.close()
    }
}
