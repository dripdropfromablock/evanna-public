package com.evanna.player.webmusic

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PersistableBundle
import android.util.AtomicFile
import com.evanna.player.library.LocalLibrary
import com.evanna.player.network.CancelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.SessionManager
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal data class TorrentTask(val hash: String, val slot: Int, val name: String, val selected: Set<Int>,
                               val folder: String, val state: String, val saved: Set<Int>, val magnet: String?)

internal object P2pTransfers {
    private const val SLOT = 0x45560100
    private const val LIMIT = 12
    val changes = MutableStateFlow(0L)
    val progress = ConcurrentHashMap<String, String>()
    fun prefs(ctx: Context) = ctx.getSharedPreferences("p2p-transfers", Context.MODE_PRIVATE)
    private fun rows(ctx: Context) = ctx.getSharedPreferences("torrent-jobs", Context.MODE_PRIVATE)
    fun dir(ctx: Context, hash: String): File {
        require(hash.matches(Regex("[a-fA-F0-9]{40}")))
        return File(File(ctx.noBackupFilesDir, "peer-transfers"), hash)
    }
    fun allowedNetwork(ctx: Context): Boolean {
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return prefs(ctx).getBoolean("mobile", false) || (!manager.isActiveNetworkMetered &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }
    private fun indices(row: JSONObject, name: String): Set<Int> {
        val array = row.getJSONArray(name)
        return (0 until array.length()).mapTo(linkedSetOf()) { array.getInt(it) }
    }
    fun tasks(ctx: Context) = rows(ctx).all.keys.mapNotNull { read(ctx, it) }.sortedBy { it.slot }
    fun read(ctx: Context, hash: String): TorrentTask? {
        val raw = rows(ctx).getString(hash, null) ?: return null
        return runCatching {
            val row = JSONObject(raw)
            TorrentTask(hash, row.getInt("slot"), row.getString("name"), indices(row, "selected"),
                row.getString("folder"), row.getString("state"), indices(row, "saved"), row.providerText("magnet", 8192))
        }.getOrNull()
    }
    private fun write(ctx: Context, task: TorrentTask) {
        val row = JSONObject().put("slot", task.slot).put("name", task.name).put("selected", JSONArray(task.selected))
            .put("folder", task.folder).put("state", task.state).put("saved", JSONArray(task.saved))
            .put("magnet", task.magnet)
        check(rows(ctx).edit().putString(task.hash, row.toString()).commit()) { "Could not save transfer." }
        changes.update { it + 1 }
    }
    @Synchronized
    fun update(ctx: Context, hash: String, transform: (TorrentTask) -> TorrentTask): TorrentTask? {
        val old = read(ctx, hash) ?: return null
        return transform(old).also { if (old != it) write(ctx, it) }
    }
    @Synchronized
    fun enqueue(ctx: Context, release: TorrentRelease, selected: Set<Int>, folder: Uri) {
        require(selected.isNotEmpty() && selected.all { id -> release.files.any { it.index == id } })
        val old = read(ctx, release.hash)
        require(old == null || old.folder == folder.toString()) { "Remove the earlier transfer to change this album’s destination." }
        val slot = old?.slot ?: (SLOT until SLOT + LIMIT).firstOrNull { id -> tasks(ctx).none { it.slot == id } }
            ?: throw IOException("Remove a finished transfer before adding another.")
        val directory = dir(ctx, release.hash)
        check(directory.isDirectory || directory.mkdirs())
        val metadata = AtomicFile(File(directory, "release.torrent"))
        val out = metadata.startWrite()
        try { out.write(release.bytes); metadata.finishWrite(out) } catch (failure: Throwable) {
            metadata.failWrite(out); throw failure
        }
        val task = TorrentTask(release.hash, slot, release.name, selected + old?.selected.orEmpty(), folder.toString(),
            "Queued", old?.saved.orEmpty(), release.magnet ?: old?.magnet)
        write(ctx, task)
        schedule(ctx, task)
    }
    private fun schedule(ctx: Context, task: TorrentTask) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(task.slot, ComponentName(ctx, P2pTorrentJob::class.java))
            .setExtras(PersistableBundle().apply { putString("hash", task.hash) })
            .setRequiredNetworkType(if (prefs(ctx).getBoolean("mobile", false)) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(0).build()
        check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule the transfer. Tap Resume to retry." }
    }
    fun restore(ctx: Context, reschedule: Boolean = false) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        tasks(ctx).filter { it.state == "Queued" || it.state == "Seeding" }.forEach {
            if (reschedule || scheduler.getPendingJob(it.slot) == null) schedule(ctx, it)
        }
    }
    fun pause(ctx: Context, task: TorrentTask) {
        update(ctx, task.hash) { it.copy(state = "Paused") }
        ctx.getSystemService(JobScheduler::class.java).cancel(task.slot)
        progress.remove(task.hash)
    }
    fun resume(ctx: Context, task: TorrentTask) {
        update(ctx, task.hash) { it.copy(state = "Queued") }?.let { schedule(ctx, it) }
    }
    @Synchronized
    fun forget(ctx: Context, task: TorrentTask) {
        ctx.getSystemService(JobScheduler::class.java).cancel(task.slot)
        check(rows(ctx).edit().remove(task.hash).commit())
        progress.remove(task.hash)
        // Only the app-private hash folder belongs to this transfer. Library documents remain user-owned.
        dir(ctx, task.hash).deleteRecursively()
        changes.update { it + 1 }
    }
}

class P2pTorrentJob : JobService() {
    private val jobs = ConcurrentHashMap<Int, CancelScope>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override fun onStartJob(params: JobParameters): Boolean {
        val hash = params.extras.getString("hash") ?: return false
        val cancel = CancelScope()
        jobs.put(params.jobId, cancel)?.cancel()
        scope.launch {
            var later = false
            try { transfer(hash, cancel) }
            catch (failure: Exception) {
                if (!cancel.cancelled) {
                    if (!P2pTransfers.allowedNetwork(this@P2pTorrentJob)) later = true
                    else P2pTransfers.update(this@P2pTorrentJob, hash) { it.copy(state = "Stopped · " +
                        (failure.message?.take(160) ?: "could not receive files")) }
                }
            } finally {
                P2pTransfers.progress.remove(hash)
                P2pTransfers.changes.update { it + 1 }
                withContext(Dispatchers.Main) {
                    if (jobs.remove(params.jobId, cancel)) jobFinished(params, later)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return params.extras.getString("hash")?.let { P2pTransfers.read(this, it)?.state in setOf("Queued", "Seeding") } == true
    }
    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        super.onDestroy()
    }
    private suspend fun transfer(hash: String, cancel: CancelScope) {
        val task = P2pTransfers.read(this, hash) ?: return
        if (task.state !in setOf("Queued", "Seeding")) return
        val dir = P2pTransfers.dir(this, hash)
        val metadata = File(dir, "release.torrent")
        val release = P2pTorrent.inspect(AtomicFile(metadata).readFully())
        check(release.hash == hash)
        val selected = release.files.filter { it.index in task.selected }
        require(selected.isNotEmpty())
        val target = File(dir, "pieces")
        check(target.isDirectory || target.mkdirs())
        val spaceRequired = selected.sumOf { (it.size - File(target, it.path).length()).coerceAtLeast(0) }
        if (target.usableSpace < spaceRequired + 16 * 1024 * 1024) throw IOException("Free up storage and resume.")
        var session: SessionManager? = null
        var completed = false
        try {
            checkNetwork(cancel)
            val engine = P2pTorrent.session()
            session = engine
            P2pTorrent.start(engine, metadata, target, task.selected, task.magnet)
            val infoHash = org.libtorrent4j.Sha1Hash(org.libtorrent4j.swig.sha1_hash.from_hex(hash))
            var waiting = 0
            while (true) {
                checkNetwork(cancel)
                val current = P2pTransfers.read(this, hash) ?: return
                if (current.state !in setOf("Queued", "Seeding") || current.selected != task.selected) return
                val handle = engine.find(infoHash)
                if (handle == null) {
                    if (++waiting > 20) throw IOException("The torrent engine could not start this release.")
                } else {
                    val status = handle.status(true)
                    if (status.errorCode().isError) throw IOException("Torrent error · " + status.errorCode().message)
                    val done = status.totalWanted() > 0 && status.totalWantedDone() >= status.totalWanted() && status.isFinished
                    val seed = P2pTransfers.prefs(this).getBoolean("seed", false)
                    if (done && !seed) handle.pause()
                    val note = if (done && current.saved != current.selected) "Saving verified tracks"
                        else if (done) "Seeding"
                        else "${(status.progress() * 100).toInt()}% · ${status.numPeers()} peers"
                    if (P2pTransfers.progress.put(hash, note) != note) P2pTransfers.changes.update { it + 1 }
                    if (done && current.saved != current.selected) {
                        val folder = Uri.parse(task.folder)
                        for (file in selected.filter { it.index !in current.saved }) {
                            checkNetwork(cancel)
                            val source = File(target, file.path)
                            check(source.canonicalFile.toPath().startsWith(target.canonicalFile.toPath()) && source.length() == file.size)
                            val audio = WebMusicImport.stageAudio(source, source.name, null, cancel)
                            WebMusicImport.saveSearchTrack(this, folder, audio, cancel)
                            P2pTransfers.update(this, hash) { it.copy(saved = it.saved + file.index) }
                        }
                        withContext(Dispatchers.Main) { LocalLibrary.scanImportedFolder(this@P2pTorrentJob, folder) }
                    }
                    if (done) {
                        if (!seed) { completed = true; break }
                        P2pTransfers.update(this, hash) { it.copy(state = "Seeding") }
                    }
                }
                kotlinx.coroutines.delay(650)
            }
        } finally {
            session?.stop()
        }
        if (completed && !cancel.cancelled) {
            P2pTransfers.update(this, hash) { it.copy(state = "Saved to Library") }
            target.deleteRecursively()
        }
    }
    private fun checkNetwork(cancel: CancelScope) {
        cancel.checkActive()
        if (!P2pTransfers.allowedNetwork(this)) throw IOException("Waiting for an allowed network.")
    }
}
