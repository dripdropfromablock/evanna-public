package com.evanna.player.decoder

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.evanna.player.ScreenBackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

@Composable
internal fun LocalDecodeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Select local WAV, AIFF, FLAC, ALAC, WavPack, Monkey's Audio, TTA, DSD64 (DSF/DFF, converted to PCM), MP3, AAC, Vorbis or Opus audio.") }
    var startSeconds by remember { mutableStateOf("0") }
    var job by remember { mutableStateOf<Job?>(null) }
    val active = remember { AtomicReference<NativeDecoder?>() }
    fun cancel() { active.get()?.requestCancel(); job?.cancel() }
    DisposableEffect(Unit) { onDispose { cancel() } }
    // Respects LocalCovered: while this screen exits or sits under the sheet, it must not answer Back.
    ScreenBackHandler(true) { cancel(); onBack() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null && !busy) {
            val start = startSeconds.toLongOrNull()?.takeIf { it in 0..600 }
            if (start == null) {
                status = "Choose a start time from 0 to 600 whole seconds, then select the file again."
                return@rememberLauncherForActivityResult
            }
            job = scope.launch {
                busy = true
                status = "Checking locally. No audio output is opened…"
                try {
                    status = withContext(Dispatchers.IO) {
                        if (!isLocalDocument(context, uri)) throw IOException("LOCAL_PROVIDER_REQUIRED")
                        // shortcut: this bounded check uses the picker grant; persist read grants when adding the library.
                        val decoder = context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            NativeDecoder.open(descriptor.fd)
                        } ?: throw IOException("DESCRIPTOR_UNAVAILABLE")
                        active.set(decoder)
                        try {
                            decoder.use {
                                ensureActive()
                                if (start > 0) it.seekTo(start * 1_000_000)
                                ensureActive()
                                val info = it.info
                                val buffer = ByteBuffer.allocateDirect(4096 * info.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                                val limit = info.sampleRate.toLong() * 10
                                var frames = 0L
                                var end = false
                                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                                while (frames < limit) {
                                    ensureActive()
                                    if (android.os.SystemClock.elapsedRealtime() > deadline) throw IOException("DECODER_LIMIT_EXCEEDED")
                                    val count = it.readPcm(buffer, minOf(4096L, limit - frames).toInt())
                                    if (count == 0) { end = true; break }
                                    frames += count
                                }
                                val stats = it.stats()
                                val precision = info.significantBits?.let { bits -> "$bits-bit source" } ?: "Original integer precision unknown"
                                val processing = if (info.quantized) "Processed to S32; ${stats.quantizedSamples} samples converted, ${stats.clippedSamples} clipped. Strict passthrough is unavailable."
                                    else "Integer samples preserved in S32."
                                "${info.codec.uppercase()} · ${info.sampleRate} Hz · $precision · ${info.channels} channel(s)\n" +
                                    "$processing\n$frames frames decoded from ${start}s; source position ${stats.positionFrames} frames. " +
                                    if (end) "End of file reached." else "Stopped at the 10-second check limit."
                            }
                        } finally { active.compareAndSet(decoder, null) }
                    }
                } catch (cancelled: CancellationException) {
                    status = "Check cancelled."
                    throw cancelled
                } catch (_: SecurityException) {
                    status = "File access expired. Select the file again."
                } catch (_: IOException) {
                    status = if (!isActive) "Check cancelled." else "This file or start time could not be checked within the work limit. Use supported local mono/stereo audio, try an earlier start, or select a complete file. Cloud providers and pipes are not supported."
                } finally { busy = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.evanna.player.ScreenHeader("Check a local audio file") { cancel(); onBack() }
        Text("Decodes up to 10 seconds without playing sound. This checks file decoding; it does not verify DAC playback.")
        OutlinedTextField(value = startSeconds, onValueChange = { startSeconds = it.take(3) },
            label = "Start time in whole seconds (0–600)", enabled = !busy,
            modifier = Modifier.fillMaxWidth())
        Text("Seeking decodes forward from the beginning. Large seeks may exceed this prototype's work limit.", style = EvannaDesign.typography.bodySmall)
        Text(status)
        Button(onClick = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Select local file") }
        if (busy) OutlinedButton(onClick = ::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel check") }
    }
}
