package com.evanna.player.diagnostics

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

internal class ReportStore(val directory: File, val exports: File, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = ReentrantLock()
    init { directory.mkdirs(); exports.mkdirs() }

    fun write(incident: Incident, fatal: Boolean = false): Boolean {
        if (!validId(incident.id) || incident.timestamp < 0) return false
        if (fatal) { if (!lock.tryLock()) return false } else lock.lock()
        return try {
            val target = if (fatal) File(directory, "pending-fatal.report") else File(directory, "${incident.id}.report")
            atomic(target, encode(incident))
            if (!fatal) prune()
            true
        } catch (_: IOException) { false } finally { lock.unlock() }
    }

    fun list(): List<Incident> = lock.withLock {
        prune()
        reportFiles().mapNotNull(::read).sortedByDescending { it.timestamp }
    }
    fun maintain() = lock.withLock { prune() }
    fun startSession() = lock.withLock {
        val pending = File(directory, "pending-fatal.report")
        read(pending)?.let { replace(pending, File(directory, "${it.id}.report")) }
        prune()
    }

    fun text(incident: Incident): String = buildString {
        append("Evanna report · schema 1\n${incident.kind}\n${java.time.Instant.ofEpochMilli(incident.timestamp)}\n")
        append("Arbitrary exception messages, raw traces and private media data are omitted.\n")
        incident.safeFields().entries.sortedBy { it.key.name }.forEach { (key, value) -> append("\n${key.name.lowercase()}: $value\n") }
    }

    fun delete(id: String): Boolean = lock.withLock {
        if (!validId(id)) return@withLock false
        var deleted = false
        reportFiles().forEach { file -> if (read(file)?.id == id) deleted = file.delete() || deleted }
        File(exports, "$id.zip").delete()
        deleted
    }
    fun deleteAll() = lock.withLock {
        reportFiles().forEach { it.delete() }
        exports.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    fun export(id: String, preview: Incident? = null): File = lock.withLock {
        require(validId(id))
        val incident = list().firstOrNull { it.id == id } ?: throw FileNotFoundException("Report unavailable")
        if (preview != null && incident.json() != preview.json()) throw IOException("Report changed; preview again")
        val target = File(exports, "$id.zip")
        val pending = File(exports, "$id.zip.tmp")
        try {
            ZipOutputStream(pending.outputStream()).use { zip ->
                for ((name, content) in listOf("report.txt" to text(incident), "report.json" to incident.json())) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
            }
            replace(pending, target)
            target
        } finally { pending.delete() }
    }

    fun reconcileExit(pid: Int, timestamp: Long, kind: IncidentKind, fields: Map<ReportField, String>): Boolean = lock.withLock {
        val existing = list().firstOrNull { it.kind == IncidentKind.JVM_CRASH &&
            it.safeFields()[ReportField.PID] == pid.toString() && kotlin.math.abs(it.timestamp - timestamp) <= 30_000 }
        val systemFields = setOf(ReportField.EXIT_REASON, ReportField.EXIT_STATUS, ReportField.EXIT_PSS_KB,
            ReportField.EXIT_RSS_KB, ReportField.TRACE_STATUS, ReportField.ANR_STACK, ReportField.NATIVE_STACK)
        val incident = if (existing != null) existing.copy(fields = existing.safeFields() + fields.filterKeys { it in systemFields } +
            (ReportField.EVIDENCE to "JVM_HANDLER+ANDROID_EXIT"))
        else Incident("exit-$pid-$timestamp", kind, timestamp, fields + mapOf(ReportField.PID to pid.toString(), ReportField.EVIDENCE to "ANDROID_EXIT"))
        val fatal = reportFiles().any { it.name == "pending-fatal.report" && read(it)?.id == incident.id }
        write(incident, fatal)
    }

    fun checkpoint(fields: Map<ReportField, String>) = lock.withLock {
        val safe = Incident("checkpoint", IncidentKind.UNKNOWN_EXIT, now(), fields).safeFields()
        val props = Properties().apply { safe.forEach { (key, value) -> setProperty(key.name, value) } }
        atomic(File(directory, "session.state"), propsBytes(props))
    }
    fun previousCheckpoint(): Map<ReportField, String> = lock.withLock {
        val file = File(directory, "session.state")
        if (!file.exists() || file.length() > MAX_BYTES) return@withLock emptyMap()
        try {
            val props = Properties().apply { file.inputStream().use(::load) }
            val fields = ReportField.entries.mapNotNull { key -> props.getProperty(key.name)?.let { key to it } }.toMap()
            Incident("checkpoint", IncidentKind.UNKNOWN_EXIT, now(), fields).safeFields()
        } catch (_: Exception) { emptyMap() }
    }
    fun clearCheckpoint() = lock.withLock { File(directory, "session.state").delete() }

    private fun prune() {
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".tmp") }?.forEach { it.delete() }
        val live = reportFiles().mapNotNull { file ->
            val incident = read(file)
            if (incident == null || now() - incident.timestamp > 30L * DAY) { file.delete(); incident?.let { File(exports, "${it.id}.zip").delete() }; null }
            else file to incident
        }
        live.filter { it.first.name != "pending-fatal.report" }.sortedByDescending { it.second.timestamp }.drop(19)
            .forEach { (file, incident) -> file.delete(); File(exports, "${incident.id}.zip").delete() }
        exports.listFiles()?.filter { it.isFile && (it.name.endsWith(".tmp") || now() - it.lastModified() > DAY) }?.forEach { it.delete() }
    }
    private fun reportFiles() = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".report") }.orEmpty()
    private fun read(file: File): Incident? = try {
        if (file.length() > MAX_BYTES) null else {
            val props = Properties().apply { file.inputStream().use(::load) }
            val id = props.getProperty("id") ?: ""
            val time = props.getProperty("time")?.toLongOrNull()
            val kind = IncidentKind.entries.firstOrNull { it.name == props.getProperty("kind") }
            if (props.getProperty("schema") != "1" || !validId(id) || time == null || time < 0 || kind == null) null
            else Incident(id, kind, time, ReportField.entries.mapNotNull { key -> props.getProperty(key.name)?.let { key to it } }.toMap())
        }
    } catch (_: Exception) { null }
    private fun encode(incident: Incident): ByteArray = propsBytes(Properties().apply {
        setProperty("schema", "1"); setProperty("id", incident.id); setProperty("time", incident.timestamp.toString()); setProperty("kind", incident.kind.name)
        incident.safeFields().forEach { (key, value) -> setProperty(key.name, value) }
    })
    private fun propsBytes(properties: Properties): ByteArray = ByteArrayOutputStream().use {
        properties.store(it, null)
        it.toByteArray().also { bytes -> require(bytes.size <= MAX_BYTES) }
    }
    private fun atomic(target: File, bytes: ByteArray) {
        val pending = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            replace(pending, target)
        } finally { pending.delete() }
    }
    private fun replace(pending: File, target: File) {
        try { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    companion object {
        const val MAX_BYTES = 1_048_576
        const val DAY = 86_400_000L
        private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9-]{1,100}"))
    }
}
