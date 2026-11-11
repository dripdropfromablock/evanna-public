package com.evanna.player.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class CrashCoreTest {
    @Test fun decoderBuildIdsAreRecordedAsHashesOnly() {
        val names = setOf("NATIVE_DECODER_BUILD_ID", "NATIVE_AVFORMAT_BUILD_ID", "NATIVE_AVCODEC_BUILD_ID", "NATIVE_AVUTIL_BUILD_ID", "NATIVE_SWRESAMPLE_BUILD_ID")
        val fields = ReportField.entries.filter { it.name in names }
        assertEquals(5, fields.size)
        val valid = Incident("decoder", IncidentKind.NATIVE_CRASH, 1, fields.associateWith { "abcdef1234567890" }).safeFields()
        fields.forEach { assertEquals("abcdef1234567890", valid[it]) }
        val invalid = Incident("decoder", IncidentKind.NATIVE_CRASH, 1, fields.associateWith { "content://private/media" }).json()
        assertFalse(invalid.contains("content://"))
        assertTrue(invalid.contains("UNAVAILABLE"))
    }
    private fun temp() = Files.createTempDirectory("evanna-reports-test").toFile()
    private fun report(id: String, time: Long = 1_000) = Incident(id, IncidentKind.HANDLED_FAILURE, time,
        mapOf(ReportField.OPERATION to "PLAY", ReportField.FAILURE to "VOLUME_UNVERIFIED"))

    @Test fun reportsRoundTripAndExportsContainOnlyApprovedFields() {
        val root = temp()
        try {
            val store = ReportStore(File(root, "reports"), File(root, "exports")) { 2_000 }
            assertTrue(store.write(report("test-1")))
            val loaded = store.list().single()
            assertEquals("test-1", loaded.id)
            val zip = store.export(loaded.id)
            ZipFile(zip).use { archive ->
                assertEquals(setOf("report.txt", "report.json"), archive.entries().asSequence().map { it.name }.toSet())
                val json = archive.getInputStream(archive.getEntry("report.json")).bufferedReader().readText()
                assertTrue(json.contains("VOLUME_UNVERIFIED"))
                assertTrue(json.contains("schema"))
            }
            assertTrue(store.delete(loaded.id))
            assertEquals(0, store.list().size)
            assertEquals(0, File(root, "exports").listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun retentionReservesFatalSlotAndIgnoresCorruptOrTemporaryRecords() {
        val root = temp()
        try {
            val store = ReportStore(root, File(root, "exports")) { 2_000 }
            repeat(25) { assertTrue(store.write(report("test-$it", 1_000 + it.toLong()))) }
            assertEquals(19, store.list().size)
            assertTrue(store.write(Incident("fatal-1", IncidentKind.JVM_CRASH, 1_500, emptyMap()), fatal = true))
            assertEquals(20, store.list().size)
            File(root, "broken.report").writeText("private=data")
            File(root, "left.tmp").writeText("partial")
            assertEquals(20, store.list().size)
            assertFalse(File(root, "left.tmp").exists())
            assertFalse(File(root, "broken.report").exists())
            assertTrue(store.list().all { store.text(it).toByteArray().size < ReportStore.MAX_BYTES })
        } finally { root.deleteRecursively() }
    }

    @Test fun oldReportsAndExportsExpireAndInvalidIdsCannotEscapeTheStore() {
        val root = temp()
        try {
            var now = 2_000L
            val store = ReportStore(File(root, "reports"), File(root, "exports")) { now }
            assertFalse(store.write(report("../escape")))
            assertTrue(store.write(report("test-1")))
            val exported = store.export("test-1")
            exported.setLastModified(1_000)
            now += 86_400_001
            store.list()
            assertFalse(exported.exists())
            now += 30L * 86_400_000
            assertTrue(store.list().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun fatalCaptureOmitsSecretsAndAlwaysDelegatesIncludingDisabledOrDiskFailure() {
        val root = temp()
        try {
            val store = ReportStore(root, File(root, "exports")) { 2_000 }
            val enabled = AtomicBoolean(true)
            var delegated = 0
            val handler = RecordingExceptionHandler(enabled, { _, error ->
                store.write(Incident.jvm("fatal-1", 2_000, Thread.currentThread(), error, emptyMap()), fatal = true)
            }, Thread.UncaughtExceptionHandler { _, _ -> delegated++ })
            val error = IllegalStateException("Bearer SECRET content://music/Private.flac https://signed/?token=PRIVATE")
            handler.uncaughtException(Thread.currentThread(), error)
            val text = store.text(store.list().single())
            assertTrue(text.contains("IllegalStateException"))
            assertTrue(text.contains("JVM_STACK_PARTIAL_CONTEXT"))
            assertFalse(text.contains("SECRET")); assertFalse(text.contains("Private.flac")); assertFalse(text.contains("content://"))
            enabled.set(false)
            handler.uncaughtException(Thread.currentThread(), error)
            assertEquals(2, delegated)
            val broken = RecordingExceptionHandler(AtomicBoolean(true), { _, _ -> throw java.io.IOException("disk full") },
                Thread.UncaughtExceptionHandler { _, _ -> delegated++ })
            broken.uncaughtException(Thread.currentThread(), error)
            assertEquals(3, delegated)
        } finally { root.deleteRecursively() }
    }

    @Test fun journalBoundsAndContextRedactionCannotPersistFreeText() {
        val journal = EventJournal()
        repeat(250) { journal.record(DiagnosticEvent.PLAY, it.toLong(), 7, 48000) }
        assertEquals(200, journal.snapshot().size)
        val incident = Incident("test-1", IncidentKind.HANDLED_FAILURE, 1_000,
            mapOf(ReportField.OPERATION to "https://private", ReportField.ROUTE to "content://secret",
                ReportField.RATE to "48000", ReportField.RESAMPLED to "true", ReportField.BIT_PERFECT_ELIGIBLE to "false",
                ReportField.SOURCE_STRICT_ELIGIBLE to "false", ReportField.CONVERSION_AUTHORIZED to "true",
                ReportField.DEVICE_RATE to "UNKNOWN", ReportField.STACK to "Bearer SECRET\nat com.evanna.Test.go(Test.kt:12)"))
        assertFalse(incident.json().contains("private")); assertFalse(incident.json().contains("SECRET"))
        assertTrue(incident.json().contains("48000"))
        assertEquals("true", incident.safeFields()[ReportField.RESAMPLED])
        assertEquals("false", incident.safeFields()[ReportField.BIT_PERFECT_ELIGIBLE])
        assertEquals("UNKNOWN", incident.safeFields()[ReportField.DEVICE_RATE])
        assertEquals("false", incident.safeFields()[ReportField.SOURCE_STRICT_ELIGIBLE])
        assertEquals("true", incident.safeFields()[ReportField.CONVERSION_AUTHORIZED])
    }

    @Test fun duplicateSystemEvidenceMergesWithJvmAndDoesNotGrowHistory() {
        val root = temp()
        try {
            val store = ReportStore(root, File(root, "exports")) { 3_000 }
            store.write(Incident("jvm-1", IncidentKind.JVM_CRASH, 2_000,
                mapOf(ReportField.PID to "42", ReportField.RUN_ID to "run-1", ReportField.STACK to "at com.evanna.Crash.run(Crash.kt:12)")), fatal = true)
            repeat(2) { store.reconcileExit(42, 2_100, IncidentKind.JVM_CRASH, mapOf(ReportField.EXIT_REASON to "4", ReportField.STACK to "")) }
            assertEquals(1, store.list().size)
            assertEquals("4", store.list().single().safeFields()[ReportField.EXIT_REASON])
            assertTrue(store.list().single().safeFields()[ReportField.EVIDENCE]!!.contains("ANDROID_EXIT"))
            assertTrue(store.list().single().safeFields()[ReportField.STACK]!!.contains("Crash.run"))
        } finally { root.deleteRecursively() }
    }

    @Test fun exportRefusesChangedEvidenceUntilPreviewedAgain() {
        val root = temp()
        try {
            val store = ReportStore(root, File(root, "exports")) { 2_000 }
            val preview = report("test-1")
            assertTrue(store.write(preview))
            assertTrue(store.write(preview.copy(fields = preview.fields + (ReportField.EXIT_REASON to "4"))))
            var rejected = false
            try { store.export(preview.id, preview) } catch (_: java.io.IOException) { rejected = true }
            assertTrue(rejected)
        } finally { root.deleteRecursively() }
    }

    @Test fun nextStartupPreservesThePreviousFatalWhenTheReservedSlotIsReused() {
        val root = temp()
        try {
            val store = ReportStore(root, File(root, "exports")) { 3_000 }
            assertTrue(store.write(Incident("jvm-1", IncidentKind.JVM_CRASH, 1_000, emptyMap()), fatal = true))
            store.startSession()
            assertTrue(store.write(Incident("jvm-2", IncidentKind.JVM_CRASH, 2_000, emptyMap()), fatal = true))
            assertEquals(setOf("jvm-1", "jvm-2"), store.list().map { it.id }.toSet())
        } finally { root.deleteRecursively() }
    }

    @Test fun recursiveFatalCaptureDoesNotReenterTheWriterButStillDelegates() {
        var captured = 0; var delegated = 0
        lateinit var handler: RecordingExceptionHandler
        handler = RecordingExceptionHandler(AtomicBoolean(true), { thread, error ->
            captured++
            handler.uncaughtException(thread, error)
        }, Thread.UncaughtExceptionHandler { _, _ -> delegated++ })
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("private"))
        assertEquals(1, captured)
        assertEquals(2, delegated)
    }
}
