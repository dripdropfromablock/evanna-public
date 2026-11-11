// Evanna: invalid input must never become an unintended 0 dB hardware request.
package com.truesample.usbaudio

import kotlin.math.floor

internal fun hardwareVolume256(db: Float): Int {
    require(db.isFinite() && db >= -128f && db <= 32767f / 256f) { "Invalid hardware volume" }
    return floor(db.toDouble() * 256).toInt()
}
