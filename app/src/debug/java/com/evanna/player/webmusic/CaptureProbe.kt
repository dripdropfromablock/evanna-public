package com.evanna.player.webmusic

import android.content.Context
import android.os.ParcelFileDescriptor
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.network.NetworkPolicy
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MINUTES
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class CaptureRequest(val url: String, val pageUrl: String, val userAgent: String?, val mime: String?)

internal data class CapturedAudio(
    val codec: String, val rate: Int, val channels: Int, val frames: Long, val pcmHash: String
)

internal data class CaptureObservation(
    val outcome: String, val bytes: Long = 0, val mime: String? = null, val audio: CapturedAudio? = null,
    val range: String = "not tested", val file: File? = null
) {
    fun summary(): String = listOfNotNull(
        outcome, mime, audio?.let { "${it.codec}, ${it.rate} Hz, ${it.channels} ch" },
        "${bytes} bytes".takeIf { bytes > 0 }, range.takeUnless { it == "not tested" }
    ).joinToString(" · ")
}

internal object CaptureProbe {
    private const val maximumBytes = 64L * 1024 * 1024
    private val deadline = Executors.newSingleThreadScheduledExecutor { job ->
        Thread(job, "WebCaptureDeadline").apply { isDaemon = true }
    }
    private val byteRange = Regex("bytes 0-0/([1-9][0-9]*)")
    private val mimePattern = Regex("[a-zA-Z0-9.+-]{1,40}/[a-zA-Z0-9.+-]{1,80}")

    fun capture(context: Context, request: CaptureRequest, cancel: CancelScope,
                allowLoopback: Boolean = false, cookie: String? = null): CaptureObservation {
        val scheme = request.url.substringBefore(':').lowercase()
        if (scheme == "blob" || scheme == "data") return CaptureObservation("unsupported $scheme")
        val address = request.url.toHttpUrlOrNull() ?: return CaptureObservation("invalid address")
        val fixture = allowLoopback && address.host == "127.0.0.1"
        if ((!address.isHttps && !fixture) || address.encodedUsername.isNotEmpty() ||
            address.encodedPassword.isNotEmpty() || address.fragment != null) return CaptureObservation("invalid address")
        val policy = NetworkPolicy(address.toString(), allowLanHttp = fixture, testLoopback = fixture)
        val headers = buildMap {
            request.userAgent?.takeIf { it.length <= 1024 }?.let { put("User-Agent", it) }
            val page = request.pageUrl.toHttpUrlOrNull()
            if (page != null && policy.sameOrigin(page)) cookie?.takeIf { it.length <= 8192 }?.let { put("Cookie", it) }
        }
        val access = HttpAccess(policy)
        var staged: File? = null
        var retain = false
        val timer = deadline.schedule({ cancel.cancel() }, 10, MINUTES)
        try {
            val mime = access.openStream(address.toString(), headers, cancel).use { response ->
                val responseMime = safeMime(response.headers["Content-Type"])
                when (response.code) {
                    200 -> Unit
                    401, 403 -> return CaptureObservation("authorization required or expired", mime = responseMime)
                    in 300..399 -> {
                        val host = response.headers["Location"]?.let(address::resolve)?.host
                        return CaptureObservation("redirect" + (host?.let { " to $it" } ?: ""))
                    }
                    else -> return CaptureObservation("HTTP ${response.code}", mime = responseMime)
                }
                if (isPageContent(responseMime))
                    return CaptureObservation("invalid content", mime = responseMime)
                val length = response.headers["Content-Length"]?.toLongOrNull()
                if (length != null && length !in 1..maximumBytes)
                    return CaptureObservation("invalid file size", mime = responseMime)
                val file = File.createTempFile("web-probe-", ".partial", context.cacheDir)
                staged = file
                try {
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            cancel.checkActive()
                            val count = response.input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maximumBytes) return CaptureObservation("file exceeds 64 MiB")
                            out.write(buffer, 0, count)
                        }
                    }
                } catch (_: IOException) {
                    return CaptureObservation(if (cancel.cancelled) "cancelled" else "incomplete transfer")
                }
                if (file.length() == 0L || (length != null && file.length() != length))
                    return CaptureObservation("incomplete transfer")
                responseMime ?: safeMime(request.mime)
            }
            val file = checkNotNull(staged)
            val bytes = file.length()
            val audio = try {
                inspect(file, cancel)
            } catch (_: Exception) {
                return CaptureObservation(if (cancel.cancelled) "cancelled" else "invalid audio", bytes, mime)
            }
            val range = try {
                access.openStream(address.toString(), headers + ("Range" to "bytes=0-0"), cancel).use { response ->
                    when (response.code) {
                        200 -> {
                            val claimedSize = response.headers["Content-Length"]?.toLongOrNull()
                            if (!isPageContent(safeMime(response.headers["Content-Type"])) &&
                                (claimedSize == null || claimedSize == bytes) &&
                                response.input.read() == file.inputStream().use { it.read() }) "full file only"
                            else "invalid range response"
                        }
                        401, 403 -> "authorization required or expired"
                        206 -> {
                            val total = byteRange.matchEntire(response.headers["Content-Range"].orEmpty())
                                ?.groupValues?.get(1)?.toLongOrNull()
                            if (total == bytes && response.input.read() == file.inputStream().use { it.read() } &&
                                response.input.read() == -1) "valid range" else "invalid range response"
                        }
                        else -> "range HTTP ${response.code}"
                    }
                }
            } catch (_: IOException) {
                "range request failed"
            }
            cancel.checkActive()
            retain = true
            return CaptureObservation("decoded", bytes, mime, audio, range, file)
        } catch (_: IOException) {
            return CaptureObservation(if (cancel.cancelled) "cancelled" else "request failed")
        } finally {
            timer.cancel(false)
            if (!retain) staged?.delete()
        }
    }

    fun inspect(file: File, cancel: CancelScope): CapturedAudio {
        cancel.checkActive()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            NativeDecoder.open(fd.fd).use { decoder ->
                val undo = cancel.watch(decoder::requestCancel)
                try {
                    val info = decoder.info
                    val pcm = ByteBuffer.allocateDirect(4096 * info.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    val hash = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    while (true) {
                        cancel.checkActive()
                        val frames = decoder.readPcm(pcm, 4096)
                        if (frames == 0) break
                        total += frames
                        pcm.position(0)
                        pcm.limit(frames * info.channels * 4)
                        hash.update(pcm)
                        pcm.clear()
                    }
                    check(total > 0)
                    CapturedAudio(info.codec, info.sampleRate, info.channels, total,
                        hash.digest().joinToString("") { "%02x".format(it) })
                } finally {
                    undo()
                }
            }
        }
    }

    private fun isPageContent(mime: String?) =
        mime != null && (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("xml"))

    private fun safeMime(raw: String?) = raw?.substringBefore(';')?.trim()?.takeIf(mimePattern::matches)?.lowercase()
}
