package com.evanna.player

import android.app.Application
import com.evanna.player.diagnostics.CrashRecorder

class EvannaApplication : Application() {
    override fun onCreate() { super.onCreate(); CrashRecorder.install(this) }
}
