package com.evanna.player.equalizer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet

/** The one EQ setting for every output. The screen writes it; the audio writer reads it before each chunk. */
internal object SoundSettings {
    private const val KEY = "config"
    private val state = MutableStateFlow(SoundConfig())
    val config: StateFlow<SoundConfig> get() = state
    @Volatile private var preferences: SharedPreferences? = null

    fun load(context: Context) = synchronized(this) {
        if (preferences != null) return
        val stored = context.applicationContext.getSharedPreferences("sound", Context.MODE_PRIVATE)
        state.value = stored.getString(KEY, null)?.let { runCatching { decodeSound(it) }.getOrNull() } ?: SoundConfig()
        preferences = stored
    }

    fun update(change: (SoundConfig) -> SoundConfig) {
        val next = state.updateAndGet(change)
        preferences?.edit()?.putString(KEY, encodeSound(next))?.apply()
    }
}
