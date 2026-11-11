package com.evanna.player.library

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import com.evanna.player.decoder.NativeDecoder
import java.io.File

/** Real native tag reading for each container Evanna plays; private cache files only, no audio output. */
internal object LyricsProbe {
    // Keep these texts identical to scripts/generate-lyrics-fixtures.py.
    private const val M4A = "[00:00.50]First line\n[00:01.25]Second line é"
    private const val FLAC = "[ar:Evanna]\n[offset:+250]\n[00:01.00][00:03.50]Hello <00:01.20>again\n[00:02.00]"
    private const val OGG = "Plain first\n\nPlain second"
    private const val MP3 = "[00:00.40]Uslt line ü\n[00:00.90]Second"

    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "lyrics-probe").apply { mkdirs() }
        fun asset(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }
        fun lyricsOf(name: String, bytes: ByteArray): List<String> {
            val file = File(directory, name).apply { writeBytes(bytes) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                NativeDecoder.open(descriptor.fd).use { decoder ->
                    val texts = decoder.lyrics()
                    check(decoder.stats().positionFrames == 0L) { "Reading lyrics consumed audio in $name" }
                    texts
                }
            }
        }
        try {
            val m4a = lyricsOf("timed.m4a", asset("lyrics/timed.m4a"))
            check(m4a == listOf(M4A)) { "MP4 lyrics differ: $m4a" }
            checkNotNull(bestLyrics(m4a)).let { check(it.timed && it.lines == listOf(LyricLine(500, "First line"), LyricLine(1250, "Second line é"))) }

            val flac = lyricsOf("timed.flac", asset("lyrics/timed.flac"))
            check(flac == listOf(FLAC)) { "FLAC lyrics differ: $flac" }
            checkNotNull(bestLyrics(flac)).let {
                check(it.lines == listOf(
                    LyricLine(750, "Hello again", listOf(LyricWord(750, "Hello "), LyricWord(950, "again"))),
                    LyricLine(1750, ""),
                    LyricLine(3250, "Hello again", listOf(LyricWord(3250, "Hello "), LyricWord(3450, "again"))),
                )) { "FLAC parse: ${it.lines}" }
            }

            val ogg = lyricsOf("plain.ogg", asset("lyrics/plain.ogg"))
            check(ogg == listOf(OGG)) { "Ogg stream lyrics differ: $ogg" }
            checkNotNull(bestLyrics(ogg)).let { check(!it.timed && it.lines.map { line -> line.text } == listOf("Plain first", "", "Plain second")) }

            val mp3 = lyricsOf("uslt.mp3", uslt(asset("decoder-extended/tone.mp3"), MP3))
            check(mp3 == listOf(MP3)) { "ID3 USLT lyrics differ: $mp3" }
            checkNotNull(bestLyrics(mp3)).let { check(it.lines.map { line -> line.timeMs } == listOf(400L, 900L)) }

            check(lyricsOf("none.wav", asset("decoder/stereo-16.wav")).isEmpty()) { "A file without lyrics returned text" }
            return "Native MP4 ©lyr, FLAC LYRICS, Ogg stream UNSYNCEDLYRICS and ID3v2.4 USLT texts read exactly without consuming PCM; " +
                "LRC timing/offset/repeat/break/word-stamp and plain parsing matched; no-lyrics WAV empty; no audio output."
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    /** Replaces any ID3 tag with an ID3v2.4 tag holding one UTF-8 USLT frame (language "eng", empty description). */
    private fun uslt(mp3: ByteArray, text: String): ByteArray {
        fun syncsafe(size: Int) = byteArrayOf((size shr 21 and 127).toByte(), (size shr 14 and 127).toByte(), (size shr 7 and 127).toByte(), (size and 127).toByte())
        var skip = 0
        if (mp3.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) {
            skip = 10 + (mp3[6].toInt() and 127 shl 21) + (mp3[7].toInt() and 127 shl 14) + (mp3[8].toInt() and 127 shl 7) + (mp3[9].toInt() and 127)
        }
        val payload = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.UTF_8)
        val frame = "USLT".toByteArray() + syncsafe(payload.size) + byteArrayOf(0, 0) + payload
        return "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frame.size) + frame + mp3.copyOfRange(skip, mp3.size)
    }
}
