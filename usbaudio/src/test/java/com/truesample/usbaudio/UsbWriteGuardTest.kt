package com.truesample.usbaudio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class UsbWriteGuardTest {
    @Test fun blockedWriterPreventsCloseOrRestartButDoesNotBlockStop() {
        val guard = UsbWriteGuard()
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Long> {
                guard.withWriter({ 42L }) { handle ->
                    entered.countDown()
                    check(stopped.await(5, TimeUnit.SECONDS))
                    handle
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            synchronized(guard) {
                assertThrows(IllegalStateException::class.java) { guard.checkIdle() }
                assertThrows(IllegalStateException::class.java) { guard.withWriter({ 42L }) { fail("second writer entered") } }
                stopped.countDown()
            }
            assertEquals(42L, result.get(5, TimeUnit.SECONDS).toLong())
            guard.checkIdle()
        } finally {
            stopped.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun failedAcquisitionAndFailedWritesReleaseGuard() {
        val guard = UsbWriteGuard()
        assertThrows(IllegalStateException::class.java) {
            guard.withWriter({ error("closed handle") }) { fail("closed handle entered writer") }
        }
        guard.checkIdle()
        assertThrows(IllegalArgumentException::class.java) {
            guard.withWriter({ 42L }) { throw IllegalArgumentException("bad buffer") }
        }
        guard.checkIdle()
        assertEquals(42L, guard.withWriter({ 42L }) { it })
    }
}
