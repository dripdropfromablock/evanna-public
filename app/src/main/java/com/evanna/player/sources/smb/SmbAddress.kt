package com.evanna.player.sources.smb

import java.net.URI
import java.net.URISyntaxException

internal class SmbAddress private constructor(
    val host: String,
    val port: Int,
    val share: String,
    private val base: List<String>,
) {
    fun path(relative: String): List<String> {
        require(relative.length <= 8192) { "INVALID_SMB_PATH" }
        val parts = base + if (relative.isEmpty()) emptyList() else relative.split('/')
        require(parts.size <= MAX_PATH_COMPONENTS && parts.all(::validName)) { "INVALID_SMB_PATH" }
        return parts
    }

    companion object {
        const val MAX_PATH_COMPONENTS = 32

        fun parse(url: String): SmbAddress {
            require(url.length <= 8192) { "INVALID_SOURCE_ADDRESS" }
            val uri = try {
                URI(url.trim())
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("INVALID_SOURCE_ADDRESS")
            }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
            require(uri.scheme.equals("smb", ignoreCase = true) && !host.isNullOrEmpty() && '%' !in host &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
            ) { "INVALID_SOURCE_ADDRESS" }
            val port = if (uri.port == -1) 445 else uri.port
            require(port in 1..65535) { "INVALID_SOURCE_ADDRESS" }
            // Split before unescaping so an encoded separator cannot introduce a path component.
            val parts = uri.rawPath.orEmpty().removePrefix("/").removeSuffix("/").split('/')
                .map { URI.create("smb:/" + it).path.removePrefix("/") }
            require(parts.size <= MAX_PATH_COMPONENTS + 1 && parts.all(::validName) && !parts.first().equals("IPC$", true)) {
                "INVALID_SMB_PATH"
            }
            return SmbAddress(host, port, parts.first(), parts.drop(1))
        }

        fun validName(name: String): Boolean = name.length in 1..255 &&
            !name.endsWith('.') && !name.endsWith(' ') && name.none {
                it in "/\\:*?\"<>|\uFFFD" || it.isISOControl()
            }
    }
}
