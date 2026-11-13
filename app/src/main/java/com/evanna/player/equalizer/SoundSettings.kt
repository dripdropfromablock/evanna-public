package com.evanna.player.equalizer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet

/** The one EQ setting for every output, plus the saved presets. The screen writes it; the audio writer reads it before each chunk. */
internal object SoundSettings {
    private const val KEY = "config"
    private const val PRESETS = "presets"
    private val state = MutableStateFlow(SoundConfig())
    private val saved = MutableStateFlow<List<SoundPreset>>(emptyList())
    val config: StateFlow<SoundConfig> get() = state
    val presets: StateFlow<List<SoundPreset>> get() = saved
    @Volatile private var preferences: SharedPreferences? = null

    fun load(context: Context) = synchronized(this) {
        if (preferences != null) return
        val stored = context.applicationContext.getSharedPreferences("sound", Context.MODE_PRIVATE)
        state.value = stored.getString(KEY, null)?.let { runCatching { decodeSound(it) }.getOrNull() } ?: SoundConfig()
        saved.value = stored.getString(PRESETS, null)?.let(::decodePresets) ?: emptyList()
        preferences = stored
    }

    fun update(change: (SoundConfig) -> SoundConfig) {
        val next = state.updateAndGet(change)
        preferences?.edit()?.putString(KEY, encodeSound(next))?.apply()
    }

    /** Saves the current sound under [name]; the same name is replaced. Returns false for an empty name or a full list. */
    fun savePreset(name: String): Boolean {
        val clean = cleanPresetName(name)
        if (clean.isEmpty()) return false
        val list = saved.value.filterNot { it.name.equals(clean, ignoreCase = true) }
        if (list.size >= MAX_PRESETS) return false
        store(list + SoundPreset(clean, state.value.copy(enabled = true), builtIn = false))
        return true
    }

    fun deletePreset(name: String) = store(saved.value.filterNot { it.name == name })

    private fun store(list: List<SoundPreset>) {
        saved.value = list
        preferences?.edit()?.putString(PRESETS, encodePresets(list))?.apply()
    }
}
