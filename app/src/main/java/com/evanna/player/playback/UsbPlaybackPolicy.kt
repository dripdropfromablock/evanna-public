package com.evanna.player.playback

internal enum class UsbPlaybackPolicy {
    STRICT, COMPATIBILITY;

    companion object {
        fun fromStored(value: String?): UsbPlaybackPolicy = entries.firstOrNull { it.name == value } ?: STRICT
    }
}

/** With "Follow song rate" off, direct USB plays songs above this rate at a lower supported rate. */
internal const val FOLLOW_SONG_RATE_CAP = 48_000
