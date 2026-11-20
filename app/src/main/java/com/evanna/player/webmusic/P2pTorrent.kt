package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.FileStorage
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.load_torrent_limits
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

internal const val TORRENT_METADATA_LIMIT = 2 * 1024 * 1024
internal data class TorrentFile(val index: Int, val path: String, val size: Long)
internal data class TorrentRelease(
    val name: String, val hash: String, val files: List<TorrentFile>, val bytes: ByteArray,
    val magnet: String? = null,
)

internal fun safePeerPath(path: String): Boolean {
    if (path.length !in 1..1024 || path.any(Char::isISOControl) || ':' in path) return false
    return path.replace('\\', '/').split('/').all { it.isNotBlank() && it != "." && it != ".." }
}

internal fun peerAudio(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mp3", "flac")

internal object P2pTorrent {
    fun fromDocument(input: InputStream): TorrentRelease {
        val bytes = ByteArray(TORRENT_METADATA_LIMIT + 1)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
        }
        return inspect(bytes.copyOf(count))
    }

    fun fromMagnet(magnet: String, workspace: File, cancel: CancelScope): TorrentRelease {
        require(magnet.length in 20..8192 && magnet.startsWith("magnet:?", ignoreCase = true) &&
            magnet.none(Char::isISOControl)) { "Enter a BitTorrent magnet link." }
        val engine = session()
        val thread = Thread.currentThread()
        val unregister = cancel.watch { thread.interrupt() }
        return try {
            cancel.checkActive()
            val metadata = engine.fetchMagnet(magnet, 35, workspace)
            cancel.checkActive()
            inspect(metadata ?: throw IOException("No peer sent the file list. Try another magnet or open a .torrent file."))
                .copy(magnet = magnet)
        } finally {
            unregister()
            Thread.interrupted()
            engine.stop()
        }
    }

    fun inspect(bytes: ByteArray): TorrentRelease {
        require(bytes.size in 1..TORRENT_METADATA_LIMIT) { "Choose a .torrent file under 2 MB." }
        val info = TorrentInfo(bytes)
        require(info.isValid && info.numFiles() in 1..4096 && info.pieceLength() in 1..16 * 1024 * 1024) {
            "This torrent has too many files or oversized pieces."
        }
        val storage = info.files()
        val tracks = buildList {
            for (index in 0 until info.numFiles()) {
                val path = storage.filePath(index).replace('\\', '/')
                require(!storage.fileAbsolutePath(index) && safePeerPath(path) &&
                    !storage.fileFlags(index).and_(FileStorage.FLAG_SYMLINK).non_zero()) { "This torrent contains an unsafe path." }
                if (!storage.padFileAt(index) && peerAudio(path) && storage.fileSize(index) in 1..WEB_AUDIO_MAX_BYTES) {
                    add(TorrentFile(index, path, storage.fileSize(index)))
                }
            }
        }
        require(tracks.isNotEmpty()) { "No importable MP3 or FLAC files up to 64 MB were found." }
        return TorrentRelease(info.name().take(240), info.infoHashes().best.toHex(), tracks, bytes)
    }

    fun session(): SessionManager = SessionManager().apply {
        val settings = SettingsPack().apply {
            setMaxMetadataSize(TORRENT_METADATA_LIMIT)
            setInteger(settings_pack.int_types.connections_limit.swigValue(), 32)
            setBoolean(settings_pack.bool_types.ssrf_mitigation.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            isEnableLsd = false
        }
        start(SessionParams(settings).apply { setPosixDiskIO() })
    }

    fun start(session: SessionManager, metadata: File, destination: File, indices: Set<Int>, magnet: String? = null) {
        val ec = error_code()
        val params = AddTorrentParams(libtorrent.load_torrent_file(metadata.absolutePath, ec, load_torrent_limits().apply {
            max_buffer_size = TORRENT_METADATA_LIMIT
        }))
        require(ec.value() == 0 && params.torrentInfo.isValid) { "The torrent metadata could not be read." }
        params.savePath = destination.absolutePath
        if (magnet != null) params.peers(AddTorrentParams.parseMagnetUri(magnet).peers().take(12))
        params.filePriorities(Array(params.torrentInfo.numFiles()) { if (it in indices) Priority.DEFAULT else Priority.IGNORE })
        params.flags = params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv())
        session.swig().async_add_torrent(params.swig())
    }
}
