package com.evanna.player.usb

import java.util.UUID

class UsbPermissionSession {
    data class Request(val deviceId: Int, val token: String)
    enum class Result { GRANTED, DENIED, DISCONNECTED }

    var pending: Request? = null
        private set

    fun begin(deviceId: Int): Request = Request(deviceId, UUID.randomUUID().toString()).also { pending = it }

    fun finish(request: Request, attached: Boolean, permitted: Boolean): Result? {
        if (pending != request) return null
        pending = null
        return when {
            !attached -> Result.DISCONNECTED
            permitted -> Result.GRANTED
            else -> Result.DENIED
        }
    }

    fun detach(deviceId: Int) {
        if (pending?.deviceId == deviceId) pending = null
    }
}
