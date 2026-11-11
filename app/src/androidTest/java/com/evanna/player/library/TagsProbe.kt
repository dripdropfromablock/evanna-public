package com.evanna.player.library

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import com.evanna.player.decoder.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Private fixtures and raw descriptors only; never takes grants or starts an output. */
internal object TagsProbe {
    fun run(instrumentation: Instrumentation): String {
        val directory = File(instrumentation.targetContext.cacheDir, "tags-probe").apply { check(isDirectory || mkdirs()) }
        fun read(asset: String): Pair<DecoderInfo, Pair<Long, String>> {
            val file = File(directory, "input.${asset.substringAfterLast('.')}")
            instrumentation.context.assets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                NativeDecoder.open(fd.fd).use { decoder ->
                    check(decoder.stats().positionFrames == 0L) { "Tag read consumed PCM for $asset" }
                    val info = decoder.info
                    val buffer = ByteBuffer.allocateDirect(4096 * info.channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var frames = 0L
                    while (true) {
                        buffer.clear()
                        val count = decoder.readPcm(buffer, 4096)
                        if (count == 0) break
                        frames += count
                        check(frames <= 2_000_000) { "Fixture decode bound exceeded" }
                        val bytes = ByteArray(count * info.channels * 4); buffer.get(bytes); digest.update(bytes)
                    }
                    info to (frames to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                }
            }
        }
        try {
            val expected = MusicTags("Evanna ensemble é", 2, 17, 1, 2, 2019, "Test genre", "Composer ü", true)
            for ((name, source) in listOf("album.flac" to "decoder/stereo-16.flac", "album.m4a" to "decoder/stereo-16.m4a",
                "album.ogg" to "decoder-extended/tone.ogg", "id3v23.mp3" to "decoder-extended/tone.mp3",
                "id3v24.mp3" to "decoder-extended/tone.mp3")) {
                val (info, pcm) = read("tags/$name")
                check(info.musicTags == expected) { "Tag mapping differs for $name: ${info.musicTags}" }
                val (original, originalPcm) = read(source)
                check(pcm == originalPcm && info.sampleRate == original.sampleRate && info.significantBits == original.significantBits) {
                    "PCM differs for $name: frames=${pcm.first}/${originalPcm.first}; sameSamples=${pcm.second==originalPcm.second} bits=${info.significantBits}/${original.significantBits}"
                }
            }
            for ((name, source) in listOf("album.wv" to "decoder/stereo-16.wv", "album.tta" to "decoder/stereo-16.tta")) {
                val (info, pcm) = read("tags-apev2/$name")
                check(info.musicTags == expected) { "APEv2 tag mapping differs for $name: ${info.musicTags}" }
                check(pcm == read(source).second) { "APEv2 tags changed PCM for $name" }
            }
            check(read("tags/totals.flac").first.musicTags == expected.copy(trackNumber = 3, totalTracks = 18, discNumber = 2, totalDiscs = 3)) { "Separate FLAC totals differ" }
            check(read("tags/bounded.flac").first.musicTags.composer == "x".repeat(512)) { "Composer bound differs" }
            val precedence = read("tags/precedence.m4a")
            check(precedence.first.musicTags == expected.copy(composer = "Stream composer")) { "Stream precedence differs: ${precedence.first.musicTags}" }
            check(precedence.second == read("decoder/stereo-16.m4a").second) { "Precedence fixture moved PCM" }
            check(read("decoder/stereo-16.wav").first.musicTags == MusicTags()) { "Tagless WAV returned music tags" }
            val expectedSort=SortTags("Visible, The","Artist, The","Album, The","Ensemble, The")
            for((name,source) in listOf("sort.flac" to "decoder/stereo-16.flac","sort.m4a" to "decoder/stereo-16.m4a",
                "sort.mp3" to "decoder-extended/tone.mp3","bounded.flac" to "decoder/stereo-16.flac","precedence.m4a" to "decoder/stereo-16.m4a")) {
                val (info,pcm)=read("sort-tags/$name")
                val wanted=when(name) {"bounded.flac"->expectedSort.copy(title="x".repeat(512));"precedence.m4a"->expectedSort.copy(artist="Stream sort artist");else->expectedSort}
                check(info.sortTags==wanted){"Sort mapping differs for $name: ${info.sortTags}"}
                check(info.title=="The Visible" && info.artist=="The Artist" && info.album=="The Album") {"Sort tags replaced visible names"}
                check(info.musicTags.albumArtist==null){"Sort name leaked into display album artist"}
                check(pcm==read(source).second){"Sort metadata changed PCM for $name"}
            }
            check(read("decoder/stereo-16.wav").first.sortTags==SortTags())
            return "Album/sort tags mapped across FLAC, MP4/ALAC, Ogg, ID3 and APEv2 (WavPack/TTA); totals, bounded text, stream-alias precedence and empty tags pass; visible names and decoded PCM unchanged; no audio output."
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
