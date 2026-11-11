// Added for Evanna (2026-11-01): preserve handle lifetime during blocking writer calls.
package com.truesample.usbaudio

internal class UsbWriteGuard {
    private var writing = false

    fun checkIdle() = synchronized(this) {
        check(!writing) { "Stop and join the playback thread before start or close" }
    }

    fun <T> withWriter(acquire: () -> Long, block: (Long) -> T): T {
        val handle = synchronized(this) {
            checkIdle()
            acquire().also { writing = true }
        }
        // stop must be able to acquire this monitor while JNI write/drain is blocked.
        try {
            return block(handle)
        } finally {
            synchronized(this) { writing = false }
        }
    }
}
