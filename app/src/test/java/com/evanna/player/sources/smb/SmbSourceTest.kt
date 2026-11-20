package com.evanna.player.sources.smb

import com.evanna.player.network.CancelScope
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SmbSourceTest {
    @Test fun escapedNamesStayWithinTheConfiguredShare() {
        val address = SmbAddress.parse("smb://nas.local:1445/Music/My%20albums/")
        assertEquals("nas.local", address.host)
        assertEquals(1445, address.port)
        assertEquals("Music", address.share)
        assertEquals(listOf("My albums", "Live", "Song + 1.flac"), address.path("Live/Song + 1.flac"))
        assertEquals(listOf("My albums", "100% live.flac"), address.path("100% live.flac"))
    }

    @Test fun addressesAndStoredPathsRejectTraversalAndAuthority() {
        for (url in listOf("smb://user:password@host/Music", "smb://host/Music/%2e%2e", "smb://host/Music/%2fchild", "smb://host/Music/%5cchild", "smb://host/Music/%FF", "smb://host/Music?query", "smb://host/IPC%24")) {
            assertThrows(IllegalArgumentException::class.java) { SmbAddress.parse(url) }
        }
        val address = SmbAddress.parse("smb://host/Music/Albums")
        for (path in listOf("../secret", ".. /secret", "//another/Music", "Disc//song.flac", "Disc\\elsewhere", "song.flac:secret")) {
            assertThrows(IllegalArgumentException::class.java) { address.path(path) }
        }
    }

    @Test fun readsExactBytesAtLongOffsetsInBoundedChunks() {
        val offset = 3_100_000_001L
        val size = 70_000
        val stamp = SmbFileStamp(offset + size, 1, 2, 3, 4)
        val offsets = mutableListOf<Long>()
        val reader = SmbReader(CancelScope(), stamp, { stamp }) { data, position, start, count ->
            assertTrue(count <= 65_536)
            offsets.add(position)
            for (i in 0 until count) data[start + i] = ((position + i) % 251).toByte()
            count
        }
        val data = ByteArray(size + 1024)
        assertEquals(size, reader.read(offset, data.size, data))
        assertEquals(listOf(offset, offset + 65_536), offsets)
        assertArrayEquals(ByteArray(size) { ((offset + it) % 251).toByte() }, data.copyOf(size))
        assertEquals(0, reader.read(stamp.length, data.size, data))
    }

    @Test fun aChangedOrTruncatedFilePermanentlyFailsTheRead() {
        val stamp = SmbFileStamp(5, 1, 2, 3, 4)
        for (changed in listOf(false, true)) {
            val cancel = CancelScope()
            var current = stamp
            val reader = SmbReader(cancel, stamp, { current }) { _, _, _, count ->
                if (changed) current = stamp.copy(changed = 9)
                if (changed) count else count - 1
            }
            assertThrows(IOException::class.java) { reader.read(0, 5, ByteArray(5)) }
            assertTrue(cancel.cancelled)
            assertThrows(IOException::class.java) { reader.read(5, 5, ByteArray(5)) }
        }
    }

    @Test fun cancellingClosesAnInFlightReadAndPreventsASecondConnection() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 2_000
            val address = SmbAddress.parse("smb://127.0.0.1:${server.localPort}/Music")
            val cancel = CancelScope()
            SmbSocketFactory(address, cancel).use { sockets ->
                assertThrows(IOException::class.java) { sockets.createSocket("other-host", address.port) }
                val socket = sockets.createSocket(address.host, address.port)
                server.accept().use {
                    val entered = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        val blockedRead = executor.submit<Boolean> {
                            entered.countDown()
                            runCatching { socket.getInputStream().read() }.exceptionOrNull() is IOException
                        }
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        assertThrows(IOException::class.java) { sockets.createSocket(address.host, address.port) }
                        cancel.cancel()
                        assertTrue(blockedRead.get(1, TimeUnit.SECONDS))
                    } finally {
                        sockets.close()
                        executor.shutdownNow()
                    }
                }
            }
        }
    }
}
