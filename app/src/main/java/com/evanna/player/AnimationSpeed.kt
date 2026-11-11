package com.evanna.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale

internal const val ANIMATION_SPEED_PREFERENCE = "animation-speed"

internal enum class AnimationSpeed(val label: String, val durationMultiplier: Float) {
    SLOW("Slow", 1.5f),
    MEDIUM("Medium", 1f),
    FAST("Fast", 2f / 3f),
}

internal fun storedAnimationSpeed(value: Any?) =
    AnimationSpeed.entries.firstOrNull { it.name == value } ?: AnimationSpeed.MEDIUM

internal class AppMotionScale : MotionDurationScale {
    var speed by mutableStateOf(AnimationSpeed.MEDIUM)
    var systemScale by mutableFloatStateOf(1f)
    override val scaleFactor: Float get() = systemScale * speed.durationMultiplier
}
