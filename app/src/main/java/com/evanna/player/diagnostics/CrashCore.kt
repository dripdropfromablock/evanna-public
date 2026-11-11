package com.evanna.player.diagnostics

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class IncidentKind { JVM_CRASH, NATIVE_CRASH, ANR, HANDLED_FAILURE, MEMORY_KILL, USER_STOP, OTHER_EXIT, UNKNOWN_EXIT }
internal enum class DiagnosticEvent { APP_START, FOREGROUND, BACKGROUND, USB_ATTACHED, USB_DETACHED, PERMISSION_REQUEST,
    PERMISSION_GRANTED, PERMISSION_DENIED, OPEN, PLAY, PAUSE, CLOSE, SOURCE_SELECTED, SOURCE_SELECTION_FAILED, FORMAT_OBSERVED, FOCUS_GAIN, FOCUS_LOSS, FOCUS_DENIED, PLAYBACK_FAILURE, DISABLED, ENABLED }
internal enum class ReportField {
    RUN_ID, PID, PROCESS_START_MS, UPTIME_MS, THREAD_ID, THREAD, EXCEPTION, STACK, EVENTS, DROPPED_EVENTS, EVIDENCE, COMPLETENESS, TRUNCATED,
    OPERATION, FAILURE, ROUTE, POLICY, FOREGROUND, RATE, BITS, CHANNELS, PLANNED_RATE, PLANNED_BITS, OUTPUT_RATE, OUTPUT_BITS, DEVICE_RATE, RESAMPLED, BIT_PERFECT_ELIGIBLE,
    SOURCE_STRICT_ELIGIBLE, CONVERSION_AUTHORIZED, SOURCE_FRAMES_ACCEPTED, OUTPUT_ENCODING, VOLUME_CONTROL, ANDROID_DEVICE_TYPE,
    CODEC, POSITION_MS, QUEUE_INDEX, VOLUME_VERIFIED, CLOCK_CONFIGURED, FEEDBACK_READY, USB_PERMISSION,
    FOCUS_HELD, USB_VID, USB_PID, UNDERRUNS, TRANSFER_ERRORS, FRAMES_QUEUED, FRAMES_SUBMITTED, FRAMES_COMPLETED, USB_INPUT_ENDED, CONTEXT_TIME_MS, CONTEXT_AGE_MS,
    MEMORY_TIME_MS, HEAP_BYTES, AVAILABLE_MEMORY_BYTES, AVAILABLE_STORAGE_BYTES, SAMPLE_AGE_MS,
    APP_VERSION, APP_VERSION_CODE, APP_SHA256, NATIVE_USBAUDIO_BUILD_ID, NATIVE_LIBUSB_BUILD_ID,
    NATIVE_DECODER_BUILD_ID, NATIVE_AVFORMAT_BUILD_ID, NATIVE_AVCODEC_BUILD_ID, NATIVE_AVUTIL_BUILD_ID, NATIVE_SWRESAMPLE_BUILD_ID,
    MANUFACTURER, MODEL, ANDROID_VERSION, SDK, ABI, EXIT_REASON, EXIT_STATUS, EXIT_PSS_KB, EXIT_RSS_KB,
    TRACE_STATUS, NATIVE_STACK, ANR_STACK
}

/** No free-form messages, paths, media metadata, descriptors or system descriptions cross this boundary. */
internal data class Incident(val id: String, val kind: IncidentKind, val timestamp: Long, val fields: Map<ReportField, String>) {
    fun safeFields(): Map<ReportField, String> = buildMap {
        fields.forEach { (key, raw) -> put(key, sanitize(key, raw)) }
        putIfAbsent(ReportField.EVIDENCE, if (kind == IncidentKind.JVM_CRASH) "JVM_HANDLER" else "LOCAL_CAPTURE")
        put(ReportField.COMPLETENESS, when {
            kind == IncidentKind.JVM_CRASH && get(ReportField.STACK)?.isNotBlank() == true ->
                if (get(ReportField.APP_SHA256)?.length == 64) "JVM_STACK_AND_CACHED_CONTEXT" else "JVM_STACK_PARTIAL_CONTEXT"
            get(ReportField.NATIVE_STACK)?.isNotBlank() == true || get(ReportField.ANR_STACK)?.isNotBlank() == true -> "FILTERED_SYSTEM_STACK"
            kind == IncidentKind.UNKNOWN_EXIT -> "CHECKPOINT_ONLY"
            kind == IncidentKind.HANDLED_FAILURE -> "TYPED_FAILURE_AND_CACHED_CONTEXT"
            else -> "EXIT_METADATA_ONLY"
        })
        if (fields.values.any { it.length > 32_768 } || fields[ReportField.TRACE_STATUS] == "TRUNCATED") put(ReportField.TRUNCATED, "true")
    }

    fun json(): String = "{\"schema\":1,\"id\":${quote(id)},\"kind\":${quote(kind.name)}," +
        "\"timestamp_utc\":${quote(Instant.ofEpochMilli(timestamp).toString())},\"timestamp_ms\":$timestamp," +
        "\"message_policy\":\"ARBITRARY_TEXT_OMITTED\",\"fields\":{" +
        safeFields().entries.sortedBy { it.key.name }.joinToString(",") { (key, value) -> "${quote(key.name.lowercase())}:${quote(value)}" } + "}}"

    companion object {
        fun jvm(id: String, time: Long, thread: Thread, error: Throwable, context: Map<ReportField, String>): Incident {
            val lines = mutableListOf<String>()
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            var count = 0
            var truncated = false
            while (cause != null && count++ < 8 && seen.add(cause)) {
                lines += "cause ${cause.javaClass.name}"
                val stack = cause.stackTrace
                lines += stack.take(64).map { frame ->
                    val file = frame.fileName?.takeIf { it.matches(Regex("[A-Za-z0-9_$-]{1,120}\\.(kt|java)")) } ?: "UnknownSource"
                    "at ${frame.className}.${frame.methodName}($file:${frame.lineNumber.coerceAtLeast(0)})"
                }
                if (stack.size > 64) truncated = true
                cause = cause.cause
            }
            if (cause != null) truncated = true
            val age = context[ReportField.CONTEXT_TIME_MS]?.toLongOrNull()?.let { (time - it).coerceAtLeast(0).toString() } ?: "UNKNOWN"
            val sampleAge = context[ReportField.MEMORY_TIME_MS]?.toLongOrNull()?.let { (time - it).coerceAtLeast(0).toString() } ?: "UNKNOWN"
            return Incident(id, IncidentKind.JVM_CRASH, time, context + mapOf(ReportField.CONTEXT_AGE_MS to age, ReportField.SAMPLE_AGE_MS to sampleAge,
                ReportField.THREAD_ID to thread.id.toString(), ReportField.THREAD to if (thread.name == "main") "main" else "other",
                ReportField.EXCEPTION to error.javaClass.name, ReportField.STACK to lines.joinToString("\n"),
                ReportField.TRUNCATED to truncated.toString(), ReportField.EVIDENCE to "JVM_HANDLER"))
        }

        fun quote(value: String): String = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }

        private fun sanitize(key: ReportField, raw: String): String {
            val value = raw.take(32_768)
            return when (key) {
                ReportField.STACK, ReportField.ANR_STACK -> value.lineSequence().filter { it.matches(Regex("cause [A-Za-z0-9_.$]+")) ||
                    it.matches(Regex("at [A-Za-z0-9_.$<>-]+\\([A-Za-z0-9_.$-]+:[0-9]+\\)")) }.take(512).joinToString("\n")
                ReportField.NATIVE_STACK -> value.lineSequence().filter {
                    it.matches(Regex("native [A-Za-z0-9_.+-]+ pc=0x[0-9a-f]+ build=([0-9a-f]+|UNKNOWN)"))
                }.take(64).joinToString("\n")
                ReportField.EVENTS -> value.lineSequence().filter { line ->
                    val parts = line.split(' ')
                    parts.size == 4 && parts[0].toLongOrNull() != null &&
                        DiagnosticEvent.entries.any { it.name == parts[1] } && parts[2].toLongOrNull() != null && parts[3].toLongOrNull() != null
                }.takeLastBounded(200).joinToString("\n")
                ReportField.EXCEPTION -> value.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{1,240}")) } ?: "REDACTED"
                ReportField.THREAD -> if (value == "main") "main" else "other"
                ReportField.FOREGROUND, ReportField.VOLUME_VERIFIED, ReportField.CLOCK_CONFIGURED, ReportField.FEEDBACK_READY,
                ReportField.USB_PERMISSION, ReportField.FOCUS_HELD, ReportField.TRUNCATED, ReportField.RESAMPLED,
                ReportField.BIT_PERFECT_ELIGIBLE, ReportField.SOURCE_STRICT_ELIGIBLE, ReportField.CONVERSION_AUTHORIZED, ReportField.USB_INPUT_ENDED -> if (value == "true" || value == "false") value else "UNKNOWN"
                ReportField.ROUTE -> value.takeIf { it in listOf("NONE", "DIRECT_USB", "ANDROID", "UNKNOWN") } ?: "REDACTED"
                ReportField.POLICY -> value.takeIf { it in listOf("STRICT", "COMPATIBILITY", "ANDROID_SYSTEM", "UNKNOWN") } ?: "REDACTED"
                ReportField.OUTPUT_ENCODING -> value.takeIf { it == "PCM_FLOAT" } ?: "UNKNOWN"
                ReportField.VOLUME_CONTROL -> value.takeIf { it in listOf("ANDROID_SYSTEM", "USB_HARDWARE") } ?: "UNKNOWN"
                ReportField.RUN_ID -> value.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,80}")) } ?: "REDACTED"
                ReportField.APP_SHA256, ReportField.NATIVE_USBAUDIO_BUILD_ID, ReportField.NATIVE_LIBUSB_BUILD_ID,
                ReportField.NATIVE_DECODER_BUILD_ID, ReportField.NATIVE_AVFORMAT_BUILD_ID, ReportField.NATIVE_AVCODEC_BUILD_ID, ReportField.NATIVE_AVUTIL_BUILD_ID,
                ReportField.NATIVE_SWRESAMPLE_BUILD_ID ->
                    value.takeIf { it.matches(Regex("[0-9a-f]{8,128}")) } ?: "UNAVAILABLE"
                ReportField.OPERATION, ReportField.FAILURE, ReportField.CODEC, ReportField.TRACE_STATUS, ReportField.EVIDENCE, ReportField.COMPLETENESS ->
                    value.takeIf { it.matches(Regex("[A-Z][A-Z0-9_+]{0,120}")) } ?: "REDACTED"
                ReportField.APP_VERSION, ReportField.MANUFACTURER, ReportField.MODEL, ReportField.ANDROID_VERSION, ReportField.ABI ->
                    value.takeIf { it.matches(Regex("[A-Za-z0-9 ._+-]{1,120}")) } ?: "REDACTED"
                else -> value.takeIf { it.toLongOrNull() != null } ?: "UNKNOWN"
            }
        }
    }
}

private fun <T> Sequence<T>.takeLastBounded(count: Int): List<T> {
    val items = ArrayDeque<T>()
    for (item in this) { if (items.size == count) items.removeFirst(); items.addLast(item) }
    return items.toList()
}

internal class EventJournal {
    private val lock = ReentrantLock()
    private val events = ArrayDeque<String>()
    val dropped = AtomicLong()
    fun record(event: DiagnosticEvent, timestamp: Long, first: Long = 0, second: Long = 0) {
        if (!lock.tryLock()) { dropped.incrementAndGet(); return }
        try {
            if (events.size == 200) events.removeFirst()
            events.addLast("$timestamp ${event.name} $first $second")
        } finally { lock.unlock() }
    }
    fun snapshot(): List<String> {
        if (!lock.tryLock()) { dropped.incrementAndGet(); return emptyList() }
        return try { events.toList() } finally { lock.unlock() }
    }
    fun clear() = lock.withLock { events.clear(); dropped.set(0) }
}

internal class RecordingExceptionHandler(private val enabled: AtomicBoolean, private val capture: (Thread, Throwable) -> Unit,
    private val previous: Thread.UncaughtExceptionHandler) : Thread.UncaughtExceptionHandler {
    private val capturing = AtomicBoolean()
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { if (enabled.get() && capturing.compareAndSet(false, true)) capture(thread, error) }
        catch (_: Throwable) { /* Fatal-path capture must never replace normal termination. */ }
        finally { previous.uncaughtException(thread, error) }
    }
}
