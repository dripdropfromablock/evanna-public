package com.evanna.player.sources.smb

import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.sources.RemoteEntry
import com.evanna.player.sources.SourceClient
import com.evanna.player.sources.SourceConnection
import com.evanna.player.sources.SourceItem
import com.evanna.player.sources.SourceKind
import com.evanna.player.sources.SourcePage
import com.hierynomus.msdtyp.AccessMask.FILE_READ_ATTRIBUTES
import com.hierynomus.msdtyp.AccessMask.FILE_READ_DATA
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY
import com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT
import com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_OPEN_REPARSE_POINT
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.paths.PathResolver
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SmbClient : SourceClient {
    override fun browse(
        connection: SourceConnection,
        parent: SourceItem?,
        offset: Int,
        query: String?,
        cancel: CancelScope,
    ): SourcePage {
        require(offset in 0..MAX_OFFSET && (query?.length ?: 0) <= 256) { "INVALID_SOURCE_PAGE" }
        require(parent == null || (parent.folder && parent.ref.connectionId == connection.id)) { "INVALID_SOURCE_FOLDER" }
        val config = connection.config
        val address = SmbAddress.parse(config.getString("url"))
        val relative = parent?.details?.getString("path").orEmpty()
        val directories = address.path(relative)
        return smbOperation(cancel) {
            SmbShare(address, config, cancel).use { smb ->
                withDirectory(smb.share, directories, cancel) { directory ->
                    if (directories.size == SmbAddress.MAX_PATH_COMPONENTS) SourcePage(emptyList())
                    else browseDirectory(directory, relative, offset, query, cancel)
                }
            }
        }
    }

    override fun resolve(connection: SourceConnection, item: SourceItem, cancel: CancelScope): ResolvedNetworkMedia {
        cancel.checkActive()
        require(!item.folder && item.ref.connectionId == connection.id) { "INVALID_SOURCE_FILE" }
        val config = connection.config
        val address = SmbAddress.parse(config.getString("url"))
        val relative = item.details.getString("path")
        require(relative.isNotEmpty() && isAudio(relative.substringAfterLast('/'))) { "SOURCE_AUDIO_REQUIRED" }
        val path = address.path(relative)
        val expected = item.details.readStamp()
        return ResolvedNetworkMedia { context, scope ->
            smbOperation(scope) {
                val smb = SmbShare(address, config, scope)
                try {
                    val file = withDirectory(smb.share, path.dropLast(1), scope) {
                        smb.share.openFile(path.joinToString("\\"), READ_ACCESS, emptySet(), READ_ONLY_SHARING,
                            FILE_OPEN, NO_REPARSE)
                    }
                    val actual = file.stamp()
                    if (actual != expected) throw IOException("SOURCE_REPRESENTATION_CHANGED")
                    scope.checkActive()
                    SmbDescriptor(context, smb, scope, SmbReader(scope, actual, file::stamp, file::read))
                } catch (failure: Throwable) {
                    smb.close()
                    throw failure
                }
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_OFFSET = 1_000_000
        private val AUDIO_EXTENSIONS = setOf("flac", "wav", "wave", "mp3", "m4a", "aac", "ogg", "opus", "aiff",
            "aif", "alac", "ape", "wv", "dsf", "dff")

        fun validate(input: JSONObject, cancel: CancelScope): JSONObject {
            val config = JSONObject(input.toString()).put("url", input.getString("url").trim())
            SmbClient().browse(SourceConnection("", SourceKind.SMB, "", config, true), null, 0, null, cancel)
            return config
        }

        private fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

        private fun browseDirectory(
            directory: Directory, relative: String, offset: Int, query: String?, cancel: CancelScope,
        ): SourcePage {
            // shortcut: pages rescan server order, use persistent directory handles if deep page latency matters.
            val entries = ArrayList<RemoteEntry>(PAGE_SIZE)
            var skipped = 0
            for (item in directory) {
                cancel.checkActive()
                val name = item.fileName
                if (!SmbAddress.validName(name) || item.fileAttributes.isReparse() ||
                    (!query.isNullOrBlank() && !name.contains(query, ignoreCase = true))) continue
                val folder = item.fileAttributes and FILE_ATTRIBUTE_DIRECTORY.value != 0L
                if (!folder && (!isAudio(name) || item.endOfFile <= 0)) continue
                if (skipped++ < offset) continue
                if (entries.size == PAGE_SIZE) return SourcePage(entries, (offset + PAGE_SIZE).takeIf { it <= MAX_OFFSET })
                val path = if (relative.isEmpty()) name else "$relative/$name"
                val details = JSONObject().put("path", path)
                if (!folder) details.put("length", item.endOfFile)
                    .put("index", fileIndex(item.fileId))
                    .put("created", item.creationTime.windowsTimeStamp)
                    .put("modified", item.lastWriteTime.windowsTimeStamp)
                    .put("changed", item.changeTime.windowsTimeStamp)
                entries += RemoteEntry(path, name, folder = folder, details = details)
            }
            cancel.checkActive()
            return SourcePage(entries)
        }
    }
}

private val READ_ACCESS = setOf(FILE_READ_DATA, FILE_READ_ATTRIBUTES)
private val READ_ONLY_SHARING = setOf(FILE_SHARE_READ)
private val NO_REPARSE = setOf(FILE_OPEN_REPARSE_POINT)

private fun <T> withDirectory(
    share: DiskShare, path: List<String>, cancel: CancelScope, visit: (Directory) -> T,
): T {
    fun open(depth: Int): T {
        cancel.checkActive()
        val name = path.take(depth).joinToString("\\")
        return share.openDirectory(name, READ_ACCESS, emptySet(), READ_ONLY_SHARING, FILE_OPEN, NO_REPARSE).use { directory ->
            if (directory.fileInformation.basicInformation.fileAttributes.isReparse()) throw IOException("INVALID_SMB_PATH")
            if (depth == path.size) visit(directory) else open(depth + 1)
        }
    }
    // Keep each verified ancestor open without write or delete sharing while opening its child.
    return open(if (path.isEmpty()) 0 else 1)
}

private fun Long.isReparse() = this and FILE_ATTRIBUTE_REPARSE_POINT.value != 0L
private fun fileIndex(index: Long) = if (index == -1L) 0 else index

private fun File.stamp(): SmbFileStamp {
    val result = fileInformation
    val basic = result.basicInformation
    if (basic.fileAttributes.isReparse() || basic.fileAttributes and FILE_ATTRIBUTE_DIRECTORY.value != 0L) {
        throw IOException("INVALID_SMB_PATH")
    }
    return SmbFileStamp(result.standardInformation.endOfFile, fileIndex(result.internalInformation.indexNumber),
        basic.creationTime.windowsTimeStamp, basic.lastWriteTime.windowsTimeStamp, basic.changeTime.windowsTimeStamp)
}

private fun JSONObject.readStamp() = SmbFileStamp(
    getLong("length"), getLong("index"), getLong("created"), getLong("modified"), getLong("changed"),
)

private class SmbShare(address: SmbAddress, config: JSONObject, cancel: CancelScope) : Closeable {
    private val guest = config.optBoolean("allowGuest", false)
    private val auth = authentication(config, guest)
    private val sockets = SmbSocketFactory(address, cancel)
    private val client = try {
        SMBClient(SmbConfig.builder()
            .withSocketFactory(sockets)
            .withAuthenticators(NtlmAuthenticator.Factory())
            .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
            .withMultiProtocolNegotiate(false)
            .withDfsEnabled(false)
            .withSigningRequired(!guest)
            .withEncryptData(!guest)
            .withBufferSize(SMB_BLOCK_BYTES)
            .withTimeout(8, TimeUnit.SECONDS)
            .build())
    } catch (failure: Throwable) {
        sockets.close()
        auth.password.fill('\u0000')
        throw failure
    }

    val share: DiskShare = try {
        val session = client.connect(address.host, address.port).authenticate(auth)
        if (!guest && (session.isGuest || session.isAnonymous)) throw IOException("CONNECTION_LOGIN_REQUIRED")
        val share = session.connectShare(address.share) as? DiskShare ?: throw IOException("INVALID_SMB_SHARE")
        if (share.treeConnect.isDfsShare) throw IOException("INVALID_SMB_SHARE")
        // Use the same tree with a resolver that cannot redirect an open through a symlink.
        DiskShare(share.smbPath, share.treeConnect, PathResolver.LOCAL)
    } catch (failure: Throwable) {
        close()
        throw failure
    } finally {
        auth.password.fill('\u0000')
    }

    override fun close() {
        sockets.close()
        runCatching { client.close() }
    }
}

private fun authentication(config: JSONObject, guest: Boolean): AuthenticationContext {
    val user = config.optString("username")
    val pass = config.optString("password")
    val domain = config.optString("domain")
    require(user.length <= 512 && pass.length <= 4096 && domain.length <= 512 &&
        listOf(user, pass, domain).none { it.any(Char::isISOControl) }) { "INVALID_SOURCE_AUTH" }
    if (guest) {
        require(user.isEmpty() && pass.isEmpty() && domain.isEmpty()) { "INVALID_SOURCE_AUTH" }
        return AuthenticationContext.guest()
    }
    require(user.isNotBlank()) { "CONNECTION_LOGIN_REQUIRED" }
    val password = pass.toCharArray()
    return try {
        AuthenticationContext(user, password, domain.ifBlank { null })
    } finally {
        password.fill('\u0000')
    }
}

private inline fun <T> smbOperation(cancel: CancelScope, operation: () -> T): T {
    cancel.checkActive()
    try {
        return operation()
    } catch (failure: Exception) {
        cancel.checkActive()
        val code = when {
            failure is SMBApiException && failure.status in setOf(NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_LOGON_FAILURE) ->
                "CONNECTION_LOGIN_REQUIRED"
            failure is IOException && failure.message in setOf("SOURCE_AUDIO_REQUIRED", "INVALID_SMB_PATH", "INVALID_SMB_SHARE",
                "SOURCE_REPRESENTATION_CHANGED", "CONNECTION_LOGIN_REQUIRED") -> failure.message
            failure is IllegalArgumentException && failure.message in setOf("INVALID_SOURCE_AUTH", "CONNECTION_LOGIN_REQUIRED") ->
                failure.message
            else -> "SOURCE_SERVER_UNAVAILABLE"
        }
        throw IOException(code)
    }
}
