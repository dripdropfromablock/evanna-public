package com.evanna.player.diagnostics

import android.app.*
import android.content.*
import android.os.*
import androidx.core.content.FileProvider
import com.evanna.player.playback.PlaybackRuntime
import com.evanna.player.playback.UsbPlaybackSnapshot
import com.evanna.player.playback.UsbPlaybackState
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import java.util.zip.ZipFile

/** Android collection runs on one background worker; fatal capture uses only the cached safe snapshot. */
internal object CrashRecorder {
    private lateinit var app: Application
    lateinit var store: ReportStore
        private set
    val enabled = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val cached = AtomicReference<Map<ReportField, String>>(emptyMap())
    private val journal = EventJournal()
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Evanna-Diagnostics").apply { isDaemon = true } }
    private val foreground = AtomicInteger()
    private val runId = UUID.randomUUID().toString()
    private var startTime = 0L
    private var startUptime = 0L
    private var base: Map<ReportField, String> = emptyMap()
    private var memory: Map<ReportField, String> = emptyMap()
    private var memoryTime = 0L
    private var maintenanceTime = 0L
    private val lastFailure = AtomicReference<String?>()
    private val preferences get() = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    fun install(application: Application) {
        if (::app.isInitialized) return
        app = application
        startTime = System.currentTimeMillis(); startUptime = SystemClock.uptimeMillis()
        store = ReportStore(File(app.noBackupFilesDir, "diagnostics"), File(app.cacheDir, "report-exports"))
        enabled.set(preferences.getBoolean("enabled", true))
        if (!preferences.contains("enabled_since")) preferences.edit().putLong("enabled_since", startTime).apply()
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ ->
            Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10)
        }
        Thread.setDefaultUncaughtExceptionHandler(RecordingExceptionHandler(enabled, { thread, error ->
            store.write(Incident.jvm("jvm-$runId", System.currentTimeMillis(), thread, error, cached.get() + mapOf(
                ReportField.RUN_ID to runId, ReportField.PID to Process.myPid().toString(),
                ReportField.EVENTS to journal.snapshot().joinToString("\n"), ReportField.DROPPED_EVENTS to journal.dropped.get().toString())), fatal = true)
        }, previous))
        val usbEvents = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val attached = intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
                if (!attached && intent.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) return
                @Suppress("DEPRECATION") val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                event(if (attached) DiagnosticEvent.USB_ATTACHED else DiagnosticEvent.USB_DETACHED,
                    device?.vendorId?.toLong() ?: 0, device?.productId?.toLong() ?: 0)
            }
        }
        val usbFilter = IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .apply { addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) }
        // Process-lifetime collection also sees removal when no Activity is visible.
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(usbEvents, usbFilter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(usbEvents, usbFilter)
        } catch (_: SecurityException) { journal.dropped.incrementAndGet() }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (foreground.getAndIncrement() == 0) { event(DiagnosticEvent.FOREGROUND); flushSoon() } }
            override fun onActivityStopped(activity: Activity) { if (foreground.decrementAndGet() == 0) { event(DiagnosticEvent.BACKGROUND); flushSoon() } }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        event(DiagnosticEvent.APP_START)
        worker.execute { safely {
            store.startSession(); maintenanceTime = System.currentTimeMillis()
            if (enabled.get()) { collectBuild(); reconcilePreviousExit(generation.get()); flush() } else store.clearCheckpoint()
        } }
        worker.scheduleWithFixedDelay({ safely {
            if (System.currentTimeMillis() - maintenanceTime >= 3_600_000) { store.maintain(); maintenanceTime = System.currentTimeMillis() }
            if (enabled.get()) flush()
        } }, 1, 1, TimeUnit.SECONDS)
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value); val token = generation.incrementAndGet()
        cached.set(emptyMap()); journal.clear(); lastFailure.set(null)
        preferences.edit().putBoolean("enabled", value).putLong("enabled_since", System.currentTimeMillis())
            .putLong("exit_watermark", System.currentTimeMillis()).apply()
        worker.execute { safely {
            if (generation.get() == token) {
                store.clearCheckpoint()
                if (value) { event(DiagnosticEvent.ENABLED); if (base.isEmpty()) collectBuild(); flush() }
            }
        } }
    }

    fun event(event: DiagnosticEvent, first: Long = 0, second: Long = 0) {
        if (enabled.get()) journal.record(event, System.currentTimeMillis(), first, second)
    }
    /** Called on an IO/test thread: history includes work queued before the refresh. */
    fun reports(): List<Incident> = worker.submit<List<Incident>> { store.list() }.get(10, TimeUnit.SECONDS)
    fun playbackFailure(snapshot: UsbPlaybackSnapshot) {
        if (snapshot.state != UsbPlaybackState.ERROR) return
        val failure = snapshot.failure ?: return
        if (!enabled.get()) return
        val key = "${snapshot.generation}:${failure.name}"
        if (lastFailure.getAndSet(key) == key) return
        event(DiagnosticEvent.PLAYBACK_FAILURE)
        val token = generation.get()
        worker.execute { safely {
            if (enabled.get() && generation.get() == token) {
                flush()
                store.write(Incident("handled-${UUID.randomUUID()}", IncidentKind.HANDLED_FAILURE, System.currentTimeMillis(),
                    cached.get() + playbackFields(snapshot) + mapOf(ReportField.OPERATION to "PLAYBACK_FAILURE", ReportField.FAILURE to failure.name)))
            }
        } }
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.reports", file)
        return Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("Evanna report", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    private fun flushSoon() { if (enabled.get()) worker.execute { safely { if (enabled.get()) flush() } } }
    private inline fun safely(action: () -> Unit) { try { action() } catch (_: Exception) { /* Storage/system evidence is best effort. */ } }

    private fun flush() {
        if (!enabled.get()) return
        val now = System.currentTimeMillis()
        if (now - memoryTime >= 15_000 || memoryTime == 0L) {
            val info = ActivityManager.MemoryInfo()
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            memory = mapOf(ReportField.HEAP_BYTES to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).toString(),
                ReportField.AVAILABLE_MEMORY_BYTES to info.availMem.toString(), ReportField.AVAILABLE_STORAGE_BYTES to app.noBackupFilesDir.usableSpace.toString())
            memoryTime = now
        }
        val snapshot = PlaybackRuntime.diagnosticSnapshot()
        val fields = base + memory + playbackFields(snapshot) + mapOf(
            ReportField.RUN_ID to runId, ReportField.PID to Process.myPid().toString(), ReportField.PROCESS_START_MS to startTime.toString(),
            ReportField.UPTIME_MS to (SystemClock.uptimeMillis() - startUptime).toString(),
            ReportField.FOREGROUND to (foreground.get() > 0).toString(), ReportField.CONTEXT_TIME_MS to now.toString(),
            ReportField.MEMORY_TIME_MS to memoryTime.toString(), ReportField.SAMPLE_AGE_MS to (now - memoryTime).coerceAtLeast(0).toString(),
            ReportField.EVENTS to journal.snapshot().joinToString("\n"), ReportField.DROPPED_EVENTS to journal.dropped.get().toString())
        if (!enabled.get()) return
        cached.set(Incident("context", IncidentKind.UNKNOWN_EXIT, now, fields).safeFields())
        store.checkpoint(cached.get())
    }

    private fun playbackFields(snapshot: UsbPlaybackSnapshot?): Map<ReportField, String> = buildMap {
        putAll(playbackSignalFields(snapshot))
        PlaybackRuntime.diagnosticFocusHeld()?.let { put(ReportField.FOCUS_HELD, it.toString()) }
        snapshot?.let {
            val manager = app.getSystemService(android.hardware.usb.UsbManager::class.java)
            val device = manager.deviceList.values.firstOrNull { device -> device.deviceId == it.deviceId }
            put(ReportField.USB_PERMISSION, (device != null && manager.hasPermission(device)).toString())
            device?.let { device -> put(ReportField.USB_VID, device.vendorId.toString()); put(ReportField.USB_PID, device.productId.toString()) }
        }
    }

    private fun collectBuild() {
        @Suppress("DEPRECATION") val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val values = mutableMapOf(ReportField.APP_VERSION to packageInfo.versionName.orEmpty(), ReportField.APP_VERSION_CODE to packageInfo.longVersionCode.toString(),
            ReportField.MANUFACTURER to Build.MANUFACTURER, ReportField.MODEL to Build.MODEL, ReportField.ANDROID_VERSION to Build.VERSION.RELEASE,
            ReportField.SDK to Build.VERSION.SDK_INT.toString(), ReportField.ABI to Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        safely {
            val digest = MessageDigest.getInstance("SHA-256")
            File(app.applicationInfo.sourceDir).inputStream().use { input ->
                val buffer = ByteArray(32_768)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            values[ReportField.APP_SHA256] = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            ZipFile(app.applicationInfo.sourceDir).use { zip ->
                for ((library, field) in listOf("libusbaudio.so" to ReportField.NATIVE_USBAUDIO_BUILD_ID, "libusb-1.0.so" to ReportField.NATIVE_LIBUSB_BUILD_ID,
                    "libevanna_decoder.so" to ReportField.NATIVE_DECODER_BUILD_ID, "libavformat.so" to ReportField.NATIVE_AVFORMAT_BUILD_ID,
                    "libavcodec.so" to ReportField.NATIVE_AVCODEC_BUILD_ID, "libavutil.so" to ReportField.NATIVE_AVUTIL_BUILD_ID,
                    "libswresample.so" to ReportField.NATIVE_SWRESAMPLE_BUILD_ID)) {
                    val entry = zip.getEntry("lib/arm64-v8a/$library") ?: continue
                    val bytes = zip.getInputStream(entry).use { TraceEvidence.readBounded(it, 131_072) }
                    TraceEvidence.elfBuildId(bytes)?.let { values[field] = it }
                }
            }
        }
        base = values
    }

    private fun reconcilePreviousExit(token: Long) {
        if (!enabled.get() || generation.get() != token) return
        val previous = store.previousCheckpoint()
        val previousPid = previous[ReportField.PID]?.toIntOrNull()
        val previousStart = previous[ReportField.PROCESS_START_MS]?.toLongOrNull() ?: Long.MAX_VALUE
        var matched = false
        if (Build.VERSION.SDK_INT >= 30) {
            val since = preferences.getLong("enabled_since", startTime)
            var watermark = preferences.getLong("exit_watermark", since)
            val exits = app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 20)
            for (exit in exits.sortedBy { it.timestamp }) {
                if (!enabled.get() || generation.get() != token) return
                if (exit.timestamp < since || exit.timestamp <= watermark) continue
                val matches = exit.pid == previousPid && exit.timestamp >= previousStart
                val fields = (if (matches) previous else emptyMap()) + mapOf(ReportField.EXIT_REASON to exit.reason.toString(),
                    ReportField.EXIT_STATUS to exit.status.toString(), ReportField.EXIT_PSS_KB to exit.pss.toString(), ReportField.EXIT_RSS_KB to exit.rss.toString())
                val trace = try {
                    if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT < 31) TraceResult("UNAVAILABLE_BEFORE_API31", "")
                    else exit.traceInputStream?.use { input ->
                        val bytes = TraceEvidence.readBounded(input)
                        if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) TraceEvidence.native(bytes) else TraceEvidence.anr(bytes)
                    } ?: TraceResult("ABSENT", "")
                } catch (_: Exception) { TraceResult("UNAVAILABLE", "") }
                if (!enabled.get() || generation.get() != token) return
                val stackField = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) ReportField.NATIVE_STACK else ReportField.ANR_STACK
                val evidence = fields + (ReportField.TRACE_STATUS to trace.status) +
                    if (trace.stack.isNotEmpty()) mapOf(stackField to trace.stack) else emptyMap()
                if (!store.reconcileExit(exit.pid, exit.timestamp, exitKind(exit.reason), evidence)) break
                if (matches) matched = true
                watermark = exit.timestamp
                preferences.edit().putLong("exit_watermark", watermark).apply()
            }
        }
        if (!enabled.get() || generation.get() != token) return
        if (!matched && previousPid != null && previous[ReportField.RUN_ID] != null && store.list().none {
            it.kind != IncidentKind.HANDLED_FAILURE && it.safeFields()[ReportField.RUN_ID] == previous[ReportField.RUN_ID]
        }) {
            val time = previous[ReportField.CONTEXT_TIME_MS]?.toLongOrNull() ?: startTime
            store.write(Incident("unknown-${previous[ReportField.RUN_ID]}", IncidentKind.UNKNOWN_EXIT, time, previous + (ReportField.EVIDENCE to "CHECKPOINT_ONLY")))
        }
    }

    private fun exitKind(reason: Int): IncidentKind = when (reason) {
        4 -> IncidentKind.JVM_CRASH; 5 -> IncidentKind.NATIVE_CRASH; 6 -> IncidentKind.ANR; 3 -> IncidentKind.MEMORY_KILL
        10, 11 -> IncidentKind.USER_STOP; 0 -> IncidentKind.UNKNOWN_EXIT; else -> IncidentKind.OTHER_EXIT
    }
}
