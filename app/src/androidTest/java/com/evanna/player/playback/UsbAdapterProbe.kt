package com.evanna.player.playback

import android.app.Activity
import android.app.Instrumentation
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.truesample.usbaudio.UsbAudioOutput
import androidx.media3.common.util.UnstableApi
import com.evanna.player.diagnostics.CrashRecorderProbe
import kotlinx.coroutines.runBlocking

/** Dependency-free, debug-test-only probe: open/inspect/close and prove the absent-calibration gate. */
@UnstableApi
class UsbAdapterProbe : Instrumentation() {
    private var probe: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probe = arguments?.getString("probe")
        start()
    }

    override fun onStart() {
        // Instrumentation.start() can race Application.onCreate(); settings/handlers must be installed first.
        waitForIdleSync()
        val result = Bundle()
        try {
            if(probe=="usb-music") {
                result.putString("report",UsbMusicProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="dac-profiles") {
                result.putString("report",DacProfileProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="playlists") {
                result.putString("report",PlaylistProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="favorites") {
                result.putString("report",FavoritesProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="history") {
                result.putString("report",HistoryProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="recently-added") {
                result.putString("report",com.evanna.player.library.RecentlyAddedProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="library-sort") {
                result.putString("report",com.evanna.player.library.LibrarySortProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="library-paging") {
                result.putString("report",com.evanna.player.library.LibraryPagingProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="artist-library") {
                result.putString("report",com.evanna.player.library.ArtistLibraryProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="genre-composer") {
                result.putString("report",com.evanna.player.library.GenreComposerProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="folder-management") {
                result.putString("report",com.evanna.player.library.FolderManagementProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="loudness") {
                result.putString("report",com.evanna.player.decoder.LoudnessProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="loudness-store") {
                result.putString("report",LoudnessStoreProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="equalizer-store") {
                result.putString("report",EqualizerStoreProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="crossfeed-store") {
                result.putString("report",CrossfeedStoreProbe.run(this));finish(Activity.RESULT_OK,result);return
            }
            if(probe=="session-metadata") {
                result.putString("report",SessionMetadataProbe.run(this))
                finish(android.app.Activity.RESULT_OK,result);return
            }
            if(probe=="volume-settings" || probe=="volume-settings-restore") {
                result.putString("report",VolumeSettingsProbe.run(this,probe=="volume-settings-restore"))
                finish(Activity.RESULT_OK,result);return
            }
            if(probe in listOf("volume-keys","volume-keys-usb","volume-keys-awake-negative")) {
                result.putString("report",VolumeKeyProbe.run(this,probe=="volume-keys-usb",probe=="volume-keys-awake-negative"))
                finish(Activity.RESULT_OK,result);return
            }
            if (probe == "player-ui" || probe == "player-flow") {
                result.putString("report", if (probe == "player-ui") PlayerUiProbe.run(this) else PlayerUiProbe.flow(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "lyrics") {
                result.putString("report", com.evanna.player.library.LyricsProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "tags") {
                result.putString("report", com.evanna.player.library.TagsProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "cue") {
                result.putString("report", com.evanna.player.library.CueProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "library-migration") {
                result.putString("report", com.evanna.player.library.LibraryMigrationProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "album-library") {
                result.putString("report", com.evanna.player.library.AlbumLibraryProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "library-context") {
                result.putString("report", com.evanna.player.library.LibraryContextProbe.run(this))
                finish(Activity.RESULT_OK,result)
                return
            }
            if (probe == "context-ui") {
                result.putString("report",PlayerUiProbe.context(this))
                finish(Activity.RESULT_OK,result)
                return
            }
            if (probe == "album-ui") {
                result.putString("report", com.evanna.player.library.AlbumUiProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "library-state") {
                result.putString("report", com.evanna.player.library.LibraryStateProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "hidden-tab-back") {
                result.putString("report", com.evanna.player.library.HiddenTabBackProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "artwork" || probe == "artwork-library") {
                result.putString("report", if (probe == "artwork") com.evanna.player.library.ArtworkProbe.run(this)
                    else com.evanna.player.library.ArtworkProbe.library(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if(probe=="gapless-android" || probe=="audio-endurance" || probe=="audio-route-loss") {
                result.putString("report",GaplessAndroidProbe.run(this,probe=="audio-endurance",probe=="audio-route-loss"))
                finish(Activity.RESULT_OK,result)
                return
            }
            if(probe=="gapless") {
                result.putString("report",GaplessProbe.run(this))
                finish(Activity.RESULT_OK,result)
                return
            }
            if (probe == "picker") {
                result.putString("report", PickerHandoffProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "android") {
                result.putString("report", AndroidOutputProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "modes") {
                result.putString("report", UsbModesProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "queue" || probe == "queue-library") {
                result.putString("report", if (probe == "queue") QueueProbe.run(this) else QueueProbe.runLibrary(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if(probe?.startsWith("shuffle")==true){
                result.putString("report",when(probe){
                    "shuffle" -> ShuffleRepeatProbe.run(this)
                    "shuffle-store" -> ShuffleRepeatProbe.store(this)
                    else -> ShuffleRepeatProbe.runtime(this,checkNotNull(probe))
                })
                finish(Activity.RESULT_OK,result)
                return
            }
            if (probe?.startsWith("restore-")==true) {
                result.putString("report",if(probe=="restore-store")QueueRestoreProbe.store(this) else QueueRestoreProbe.runtime(this,checkNotNull(probe)))
                finish(Activity.RESULT_OK,result)
                return
            }
            if (probe in listOf("library", "library-browse", "library-browse-ui", "library-grant", "library-revoke")) {
                result.putString("report", when (probe) {
                    "library" -> com.evanna.player.library.LibraryProbe.run(this)
                    "library-browse" -> com.evanna.player.library.LibraryBrowseProbe.run(this)
                    "library-browse-ui" -> com.evanna.player.library.LibraryBrowseUiProbe.run(this)
                    "library-grant" -> com.evanna.player.library.LibraryProbe.runGrant(this)
                    else -> com.evanna.player.library.LibraryProbe.revokeTestFolder(this)
                })
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "filegrant") {
                result.putString("report", FileSessionProbe.runGrant(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "native-library-replacement") {
                System.loadLibrary("usbaudio")
                result.putString("report", com.evanna.player.decoder.DecoderProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "decoder") {
                result.putString("report", com.evanna.player.decoder.DecoderProbe.run(this) + "\n" +
                    com.evanna.player.decoder.ExtendedDecoderProbe.run(this) + "\n" +
                    com.evanna.player.decoder.LongSeekProbe.run(this) + "\n" +
                    com.evanna.player.decoder.LongCompressedSeekProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "decoder-seek-budget") {
                result.putString("report", com.evanna.player.decoder.LongCompressedSeekProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "decoder-extended") {
                result.putString("report", com.evanna.player.decoder.ExtendedDecoderProbe.run(this))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe in listOf("diagnostics", "diagnostics-crash", "diagnostics-history")) {
                result.putString("report", CrashRecorderProbe.run(this, checkNotNull(probe)))
                finish(Activity.RESULT_OK, result)
                return
            }
            if (probe == "session" || probe == "session-usb") {
                result.putString("report", PlaybackSessionProbe.run(this, realUsb = probe == "session-usb") +
                    if (probe == "session") "\n" + FileSessionProbe.run(this) + "\n" + ResumeSessionProbe.run(this) else "")
                finish(Activity.RESULT_OK, result)
                return
            }
            runBlocking {
                val manager = targetContext.getSystemService(UsbManager::class.java)
                check(probe == null || probe in listOf("silence", "silence-matrix", "silence-disconnect")) { "Unknown probe mode" }
                if (probe != null) {
                    result.putString("report", UsbSilentTransportProbe.run(manager,
                        allModes = probe == "silence-matrix", awaitDisconnect = probe == "silence-disconnect") { message ->
                        sendStatus(1, Bundle().apply { putString("progress", message) })
                    })
                    return@runBlocking
                }
                val devices = manager.deviceList.values.filter(UsbAudioOutput::isUsbAudioOutput)
                check(devices.size == 1) { "Connect exactly one USB audio output" }
                val device = devices.single()
                check(manager.hasPermission(device)) { "Grant USB access in Evanna before running the probe" }
                var opened: NativeUsbPlaybackOutput? = null
                val controller = UsbPlaybackController { id -> openUsbPlaybackOutput(manager, id).also { opened = it } }
                try {
                    check(controller.open(device.deviceId)) { "Native USB open failed" }
                    val output = checkNotNull(opened)
                    val mode = checkNotNull(output.modes.firstOrNull { it.channels in 1..2 && it.bitsPerSample >= 16 }) {
                        "No mode supports the prototype fixture formats"
                    }
                    val bits = if (mode.bitsPerSample >= 24) 24 else 16
                    check(!controller.start(PcmFixture(mode.sampleRate, bits, mode.channels)))
                    check(controller.snapshot.value.failure == UsbPlaybackFailure.VOLUME_UNVERIFIED)
                    check(controller.snapshot.value.stream == null && output.readiness() == UsbPlaybackReadiness())
                    val stats = output.stats()
                    check(stats.framesSent == 0L && stats.underruns == 0L && stats.transferErrors == 0L && !stats.disconnected)
                    result.putString("report", "Native open and planning passed; no calibration, gain write or stream start. " +
                        "VID=%04X PID=%04X; modes=%s".format(device.vendorId, device.productId,
                            output.modes.joinToString { "${it.sampleRate}Hz/${it.bitsPerSample}bit/${it.channels}ch" }))
                } finally {
                    check(controller.close()) { "USB cleanup failed; ownership retained for retry" }
                }
            }
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            val frame = failure.stackTrace.firstOrNull { it.className.startsWith("com.evanna.player") }
            result.putString("failure", (failure.javaClass.simpleName + ": " + failure.message.orEmpty() + " at " + frame).take(400))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
