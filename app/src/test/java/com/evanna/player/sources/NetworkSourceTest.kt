package com.evanna.player.sources

import com.evanna.player.network.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

class NetworkSourceTest {
    @Test fun cancellationNotifiesBothExistingAndLateWorkOnce() {
        val scope=CancelScope();var calls=0
        scope.watch {calls++};scope.cancel();scope.cancel();scope.watch {calls++}
        assertEquals(2,calls);assertTrue(scope.cancelled)
    }
    @Test fun rangesAreExactBoundedAndRejectRepresentationChanges() {
        val data=ByteArray(200000){(it%251).toByte()}
        var tag="\"first\""
        server(data,{tag}) {url ->
            RangeReader(url,emptyMap(),NetworkPolicy(url,true,true)).use {reader ->
                assertEquals(data.size.toLong(),reader.length)
                assertArrayEquals(data.copyOfRange(65000,66000),reader.read(65000,1000))
                assertEquals(0,reader.read(data.size.toLong(),100).size)
                tag="\"changed\""
                assertTrue(runCatching {reader.read(150000,100)}.exceptionOrNull() is java.io.IOException)
                assertTrue(reader.cachedBytes<=65536)
            }
        }
    }
    @Test fun truncatedReadPermanentlyCancelsTheSource() {
        var truncate=false
        server(ByteArray(200000),{"\"same\""},truncate={truncate}) {url ->
            RangeReader(url,emptyMap(),NetworkPolicy(url,true,true)).use {reader ->
                truncate=true
                assertTrue(runCatching {reader.read(0,100)}.isFailure)
                assertTrue(reader.scope.cancelled)
            }
        }
    }
    @Test fun rangeOffsetsRemain64BitForSparseAudio() {
        val length=(1L shl 32)+123
        server(ByteArray(0),{"\"sparse\""},length=length) {url ->
            RangeReader(url,emptyMap(),NetworkPolicy(url,true,true)).use {reader ->
                assertEquals(length,reader.length)
                val start=length-80
                assertArrayEquals(ByteArray(80){((start+it)%251).toByte()},reader.read(start,80))
            }
        }
    }
    @Test fun webLengthCapRejectsAnOversizedRangeSourceBeforeReadingAnyChunks() {
        server(ByteArray(0), { "\"large\"" }, length = 100_000L) { url ->
            assertThrows(java.io.IOException::class.java) {
                RangeReader(url, emptyMap(), NetworkPolicy(url, true, true), maxLength = 99_999L)
            }
            RangeReader(url, emptyMap(), NetworkPolicy(url, true, true), maxLength = 100_000L).use {
                assertEquals(100_000L, it.length)
                assertEquals(0, it.cachedBytes)
            }
        }
    }
    @Test fun cancelInterruptsARequestBeforeOrdinaryTimeout() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        server(ByteArray(100),{ "\"same\"" },entered,release) {url ->
            val scope=CancelScope()
            try {
                val future=executor.submit<Boolean> {runCatching {HttpAccess(NetworkPolicy(url,true,true)).fetch(url,100,scope=scope)}.isFailure}
                assertTrue(entered.await(3,TimeUnit.SECONDS));scope.cancel()
                assertTrue(future.get(650,TimeUnit.MILLISECONDS))
            } finally {release.countDown();executor.shutdownNow()}
        }
    }
    @Test fun externalCleartextAndEmbeddedCredentialsAreRejected() {
        assertTrue(runCatching {NetworkPolicy("http://example.com",false)}.isFailure)
        assertTrue(runCatching {NetworkPolicy("https://user:secret@example.com",false)}.isFailure)
        assertTrue(runCatching {NetworkPolicy("http://93.184.215.14",true)}.isFailure)
        assertTrue(runCatching {NetworkPolicy("https://127.0.0.1")}.isFailure)
        assertTrue(runCatching {NetworkPolicy("https://[::1]")}.isFailure)
    }
    @Test fun httpsConnectionCannotDowngradeToCleartextEvenWithLanOptIn() {
        val policy=NetworkPolicy("https://example.com",true)
        assertTrue(runCatching {policy.validate("http://1.1.1.1/audio".toHttpUrl())}.isFailure)
    }
    private fun server(data:ByteArray,tag:()->String,entered:CountDownLatch?=null,release:CountDownLatch?=null,
        truncate:()->Boolean={false},length:Long=data.size.toLong(),run:(String)->Unit) {
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val worker=thread(isDaemon=true) {
            while(!server.isClosed)try {server.accept().use {socket ->
                socket.soTimeout=3000
                val input=socket.getInputStream().bufferedReader();input.readLine()
                var header:String?;var rangeText:String?=null
                do {header=input.readLine();if(header?.startsWith("Range:",true)==true)rangeText=header.substringAfter(':').trim()}while(!header.isNullOrEmpty())
                entered?.countDown();release?.await(5,TimeUnit.SECONDS)
                val range=rangeText?.let {Regex("bytes=(\\d+)-(\\d+)").matchEntire(it)}
                val start=range?.groupValues?.get(1)?.toLong() ?: 0L
                val end=range?.groupValues?.get(2)?.toLong() ?: length-1
                val count=(end-start+1).toInt()
                val response="HTTP/1.1 ${if(range==null)200 else 206} OK\r\nETag: ${tag()}\r\nContent-Length: ${end-start+1}\r\nConnection: close\r\n"+
                    (if(range!=null)"Content-Range: bytes $start-$end/$length\r\n"else "")+"\r\n"
                val actual=if(truncate())count/2 else count
                socket.getOutputStream().use {
                    it.write(response.toByteArray())
                    if(data.isNotEmpty())it.write(data,start.toInt(),actual)
                    else it.write(ByteArray(actual){index->((start+index)%251).toByte()})
                }
            }}catch(_:java.io.IOException){}
        }
        try {run("http://127.0.0.1:${server.localPort}/audio")} finally {release?.countDown();server.close();worker.join(1500)}
    }
}
