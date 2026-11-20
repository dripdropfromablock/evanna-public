package com.evanna.player.network

import java.io.IOException

/** Registration and cancellation are atomic, including work registered after cancellation. */
internal class CancelScope {
    @Volatile var cancelled=false; private set
    private val callbacks=mutableSetOf<()->Unit>()
    fun watch(action:()->Unit):()->Unit {
        val runNow=synchronized(this) {if(cancelled)true else {callbacks+=action;false}}
        if(runNow)action()
        return {synchronized(this){callbacks.remove(action)};Unit}
    }
    fun cancel() {
        val pending=synchronized(this){if(cancelled)return;cancelled=true;callbacks.toList().also {callbacks.clear()}}
        pending.forEach {runCatching(it)}
    }
    fun checkActive(){if(cancelled)throw IOException("SOURCE_CANCELLED")}
}
