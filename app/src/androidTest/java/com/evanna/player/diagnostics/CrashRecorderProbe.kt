package com.evanna.player.diagnostics

import android.app.Instrumentation
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Separate test APK only. No signal handler or deliberate failure hook enters production sources. */
internal object CrashRecorderProbe {
    fun run(instrumentation: Instrumentation, mode: String): String {
        val context = instrumentation.targetContext
        if (mode == "diagnostics-crash") {
            check(CrashRecorder.enabled.get()) { "Enable local diagnostic recording first" }
            await { CrashRecorder.store.previousCheckpoint()[ReportField.PID] == Process.myPid().toString() }
            val checkpoint = CrashRecorder.store.previousCheckpoint()
            File(context.noBackupFilesDir, "diagnostics-probe-expected").writeText(
                checkNotNull(checkpoint[ReportField.RUN_ID]) + "\n" + checkNotNull(checkpoint[ReportField.APP_SHA256]))
            Thread({ throw RecorderProbeFailure() }, "Evanna-Debug-Fault").start()
            SystemClock.sleep(10_000)
            error("Default crash handler did not terminate the test process")
        }
        if (mode == "diagnostics-history") {
            val marker = File(context.noBackupFilesDir, "diagnostics-probe-expected")
            val expected = marker.readLines()
            check(expected.size == 2) { "Run the deliberate crash probe first" }
            var found: Incident? = null
            await {
                found = CrashRecorder.store.list().firstOrNull {
                    it.kind == IncidentKind.JVM_CRASH && it.safeFields()[ReportField.EXCEPTION]?.endsWith("RecorderProbeFailure") == true &&
                        it.safeFields()[ReportField.RUN_ID] == expected[0] && it.safeFields()[ReportField.APP_SHA256] == expected[1] &&
                        it.safeFields()[ReportField.EVIDENCE]?.contains("ANDROID_EXIT") == true
                }
                found != null
            }
            val report = checkNotNull(found)
            val text = CrashRecorder.store.text(report)
            check(text.contains("RecorderProbeFailure") && !text.contains("PRIVATE_TOKEN") && !text.contains("Private.flac"))
            check(report.safeFields()[ReportField.STACK]?.isNotEmpty() == true)
            check(report.safeFields()[ReportField.APP_SHA256]?.matches(Regex("[0-9a-f]{64}")) == true)
            marker.delete()
            return "Real JVM fatal capture and Android exit reconciliation passed; stack retained, private message omitted, one merged incident"
        }
        val previousSetting = CrashRecorder.enabled.get()
        val directory = File(context.noBackupFilesDir, "diagnostics-probe-${UUID.randomUUID()}")
        val exports = File(context.cacheDir, "report-exports/probe")
        try {
            val store = ReportStore(directory, exports)
            val error = RecorderProbeFailure()
            val incident = Incident.jvm("probe-${UUID.randomUUID()}", System.currentTimeMillis(), Thread.currentThread(), error,
                mapOf(ReportField.OPERATION to "PLAY", ReportField.ROUTE to "DIRECT_USB"))
            check(store.write(incident))
            val loaded = store.list().single()
            val exported = store.export(loaded.id, loaded)
            ZipFile(exported).use { zip ->
                check(zip.entries().asSequence().map { it.name }.toSet() == setOf("report.txt", "report.json"))
                val json = zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText()
                check(JSONObject(json).getInt("schema") == 1)
                check(!json.contains("PRIVATE_TOKEN") && !json.contains("Private.flac") && !json.contains("content://"))
            }
            val intent = CrashRecorder.shareIntent(exported)
            check(intent.action == Intent.ACTION_SEND && intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = checkNotNull(intent.clipData?.getItemAt(0)?.uri)
            check(context.contentResolver.openInputStream(uri)?.use { it.read() } == 0x50)
            var excluded = false
            try { FileProvider.getUriForFile(context, "${context.packageName}.reports", directory) }
            catch (_: IllegalArgumentException) { excluded = true }
            check(excluded) { "Raw private report directory is exposed by FileProvider" }
            check(store.delete(loaded.id) && !exported.exists() && store.list().isEmpty())
            CrashRecorder.setEnabled(false)
            CrashRecorder.event(DiagnosticEvent.PLAY)
            await { CrashRecorder.store.previousCheckpoint().isEmpty() }
            CrashRecorder.setEnabled(true)
            await { CrashRecorder.store.previousCheckpoint()[ReportField.PID] == Process.myPid().toString() }
            val builds = CrashRecorder.store.previousCheckpoint()
            check(builds[ReportField.APP_SHA256]?.matches(Regex("[0-9a-f]{64}")) == true)
            check(builds[ReportField.NATIVE_USBAUDIO_BUILD_ID]?.matches(Regex("[0-9a-f]{8,128}")) == true)
            check(builds[ReportField.NATIVE_LIBUSB_BUILD_ID]?.matches(Regex("[0-9a-f]{8,128}")) == true)
            for (field in listOf(ReportField.NATIVE_DECODER_BUILD_ID, ReportField.NATIVE_AVFORMAT_BUILD_ID,
                ReportField.NATIVE_AVCODEC_BUILD_ID, ReportField.NATIVE_AVUTIL_BUILD_ID, ReportField.NATIVE_SWRESAMPLE_BUILD_ID)) {
                check(builds[field]?.matches(Regex("[0-9a-f]{8,128}")) == true) { "Missing decoder build identifier" }
            }
            return "Recorder privacy, parseable ZIP, scoped content URI, deletion, disable/re-enable and exact app/native build IDs passed; no share target launched"
        } finally {
            CrashRecorder.setEnabled(previousSetting)
            directory.deleteRecursively(); exports.deleteRecursively()
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        do { if (condition()) return; SystemClock.sleep(100) } while (SystemClock.elapsedRealtime() < end)
        error("Recorder state did not settle")
    }
}

private class RecorderProbeFailure : IllegalStateException("PRIVATE_TOKEN content://music/Private.flac https://signed.invalid/?token=PRIVATE_TOKEN")
