package com.evanna.player.decoder

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.DocumentsContract
import com.evanna.player.playback.*
import com.evanna.player.library.hasTreeReadGrant
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest

/** Location/grant closures remain private; the playback layer sees format and an opaque identity. */
internal class DecodedAudioTrack private constructor(
    override val format: TrackPcmFormat, private val decoderFactory: () -> NativeDecoder,
    private val releaseGrant: () -> Unit,
    override val gaplessEligible:Boolean,
    override val loudnessTags:com.evanna.player.loudness.LoudnessTags,
) : PcmTrack {
    override val id = "evanna:local:${UUID.randomUUID()}"
    override val seekable = true
    private var closed = false
    override fun open(): PcmReader {
        check(!closed)
        val decoder = decoderFactory()
        return try {
            val actual = pcmFormat(decoder.info)
            check(actual == format && decoder.info.gaplessEligible==gaplessEligible && decoder.info.loudnessTags==loudnessTags) { "The selected audio format or gain tags changed" }
            object : PcmReader {
                override val format = actual
                override fun read(target: ByteBuffer, maxFrames: Int) = decoder.readPcm(target, maxFrames)
                override fun seekTo(positionUs: Long) = decoder.seekTo(positionUs)
                override fun requestCancel() = decoder.requestCancel()
                override fun close() = decoder.close()
            }
        } catch (failure: Throwable) { decoder.close(); throw failure }
    }
    override fun close() { if (!closed) { releaseGrant(); closed = true } }
    override fun closeForRestore() { closed = true }

    companion object {
        /** Caller owns grant acquisition and unwinds it if this preparation fails. */
        internal fun prepare(decoderFactory: () -> NativeDecoder, releaseGrant: () -> Unit = {}): DecodedAudioTrack {
            val info = decoderFactory().use { it.info }
            return DecodedAudioTrack(pcmFormat(info), decoderFactory, releaseGrant,info.gaplessEligible,info.loudnessTags)
        }

        fun local(context: Context, uri: Uri): DecodedAudioTrack {
            if (!isLocalDocument(context, uri)) throw IOException("LOCAL_PROVIDER_REQUIRED")
            val resolver = context.applicationContext.contentResolver
            if (DocumentsContract.isTreeUri(uri)) {
                val tree = DocumentsContract.buildTreeDocumentUri(uri.authority!!, DocumentsContract.getTreeDocumentId(uri))
                if (!hasTreeReadGrant(context, tree)) throw IOException("FOLDER_ACCESS_REQUIRED")
                return prepare({ resolver.openFileDescriptor(uri, "r")?.use { NativeDecoder.open(it.fd) }
                    ?: throw IOException("DESCRIPTOR_UNAVAILABLE") })
            }
            val leases = context.applicationContext.getSharedPreferences("local-source-grants", Context.MODE_PRIVATE)
            fun key(value: Uri) = "g_" + MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val requested = key(uri)
            // Reconcile only our abandoned leases. Retain a requested lease across process death.
            for (permission in resolver.persistedUriPermissions) {
                val owned = key(permission.uri)
                if (owned != requested && leases.getBoolean(owned, false)) {
                    try { resolver.releasePersistableUriPermission(permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { }
                }
            }
            val cleanup = leases.edit()
            leases.all.keys.filter { it != requested }.forEach { cleanup.remove(it) }
            if (!cleanup.commit()) throw IOException("GRANT_STORAGE_FAILED")
            val existing = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            val owned = !existing || leases.getBoolean(requested, false)
            if (owned && !leases.edit().putBoolean(requested, true).commit()) throw IOException("GRANT_STORAGE_FAILED")
            val release = {
                if (owned) {
                    try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { /* Already revoked; no grant remains to release. */ }
                    if (!leases.edit().remove(requested).commit()) throw IOException("GRANT_STORAGE_FAILED")
                }
            }
            return try {
                if (!existing) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prepare({ resolver.openFileDescriptor(uri, "r")?.use { NativeDecoder.open(it.fd) }
                    ?: throw IOException("DESCRIPTOR_UNAVAILABLE") }, release)
            } catch (failure: Throwable) { release(); throw failure }
        }

        private fun pcmFormat(info: DecoderInfo) = TrackPcmFormat(info.sampleRate, info.significantBits ?: 32,
            info.channels, info.significantBits, sourceCodec(info.codec, info.sampleRate),
            info.lossless && !info.quantized && info.significantBits != null, info.durationUs)
    }
}

/** FFmpeg's DSD decoders output PCM at 1/8 of the DSD rate, so 352.8 kHz names DSD64 (2.8224 MHz). */
internal fun sourceCodec(codec: String, rate: Int): String {
    if (!codec.startsWith("dsd_")) return codec.uppercase(Locale.ROOT)
    val multiple = rate * 8L / 44100
    return if (multiple * 44100 == rate * 8L) "DSD$multiple" else "DSD"
}

/** Only this app's abandoned single-file lease is releasable; catalog tree grants remain untouched. */
internal fun releaseLocalSourceLease(context: Context, uri: Uri) {
    val leases=context.applicationContext.getSharedPreferences("local-source-grants",Context.MODE_PRIVATE)
    val key="g_"+MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it.toInt() and 255)}
    if(!leases.getBoolean(key,false)) return
    try { context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    catch(_:SecurityException){ }
    if(!leases.edit().remove(key).commit()) throw IOException("GRANT_STORAGE_FAILED")
}

internal fun isLocalDocument(context: Context, uri: Uri): Boolean {
    return isLocalDocumentProvider(context, uri) && DocumentsContract.isDocumentUri(context, uri)
}

/** Explicit selection inspects tags without acquiring/releasing the playback factory's read lease. */
internal fun inspectLocalQueueSource(context: Context, uri: Uri): QueueSource {
    val app = context.applicationContext
    if (!isLocalDocument(app, uri)) throw IOException("LOCAL_PROVIDER_REQUIRED")
    val info = app.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        NativeDecoder.open(descriptor.fd).use { it.info }
    } ?: throw IOException("DESCRIPTOR_UNAVAILABLE")
    return QueueSource(null, info.title ?: "Local audio file", info.artist, info.album, info.durationUs, uri.toString(),info.musicTags) {
        DecodedAudioTrack.local(app, uri)
    }
}

internal fun isLocalDocumentProvider(context: Context, uri: Uri): Boolean {
    if (uri.scheme != "content" || uri.authority !in listOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")) return false
    val provider = context.packageManager.resolveContentProvider(uri.authority!!, 0) ?: return false
    return provider.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
