package com.evanna.player.user

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.roundToLong

internal const val PLAYLIST_LIMIT=1000
internal data class PlaylistEntry(val trackId:String?,val title:String,val artist:String?=null,val album:String?=null,
    val durationUs:Long?=null,val fileName:String?=null) {
    init {
        require(trackId==null || trackId.matches(Regex("[0-9a-f]{64}")))
        require(title.length in 1..512 && (artist?.length ?: 0)<=512 && (album?.length ?: 0)<=512)
        require(durationUs==null || durationUs in 0..604_800_000_000L)
        require(fileName==null || fileName.length in 1..4096 && fileName.none {it=='/' || it=='\\' || it.isISOControl()})
    }
}
internal data class Playlist(val id:Long,val name:String,val revision:Long,val items:List<PlaylistEntry>)
internal data class PlaylistSummary(val id:Long,val name:String,val count:Int)
internal data class PlaylistFile(val entry:PlaylistEntry,val uri:String,val root:String,val documentId:String,val available:Boolean)
internal fun validPlaylistName(name:String):String?=name.trim().takeIf {it.length in 1..200 && it.none(Char::isISOControl)}
internal data class M3uEntry(val path:String,val title:String?,val artist:String?,val durationUs:Long?)
internal data class M3uImport(val entries:List<M3uEntry>,val warnings:List<String>)

internal fun parseM3u(bytes:ByteArray):M3uImport {
    require(bytes.size<=1024*1024){"Playlist exceeds 1 MiB"}
    val warnings=mutableListOf<String>()
    val text=try {Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}
        catch(_:java.nio.charset.CharacterCodingException){warnings+="Text used Windows-1252 encoding.";bytes.toString(charset("windows-1252"))}
    val entries=mutableListOf<M3uEntry>()
    var title:String?=null;var artist:String?=null;var duration:Long?=null
    for(raw in text.removePrefix("\uFEFF").lineSequence()) {
        require(raw.length<=4096){"Playlist line exceeds 4096 characters"}
        val line=raw.trim()
        if(line.isEmpty())continue
        if(line.startsWith("#EXTINF:",ignoreCase=true)) {
            val body=line.substringAfter(':');val seconds=body.substringBefore(',').trim().toDoubleOrNull()
            duration=seconds?.takeIf {it.isFinite() && it in 0.0..604800.0}?.let {(it*1_000_000).roundToLong()}
            val label=body.substringAfter(',',"").trim()
            artist=label.takeIf {" - " in it}?.substringBefore(" - ")?.take(512)
            title=label.substringAfter(" - ",label).takeIf {it.isNotBlank()}?.take(512)
            continue
        }
        if(line.startsWith('#'))continue
        require(entries.size<PLAYLIST_LIMIT){"Playlist exceeds 1000 entries"}
        require(line.none(Char::isISOControl)){"Invalid playlist reference"}
        entries+=M3uEntry(line,title,artist,duration);title=null;artist=null;duration=null
    }
    return M3uImport(entries,warnings)
}

internal fun writeM3u(entries:List<Pair<PlaylistEntry,String>>):ByteArray {
    require(entries.size<=PLAYLIST_LIMIT)
    fun tag(value:String)=value.replace('\r',' ').replace('\n',' ').replace('\u0000',' ')
    val text=buildString {
        append("#EXTM3U\n")
        for((entry,path) in entries) {
            require(path.length in 1..4096 && path.none(Char::isISOControl))
            val reference=if(path.startsWith('#'))"./$path" else path
            require(reference.length<=4096)
            append("#EXTINF:").append(entry.durationUs?.let {it/1_000_000.0} ?: -1).append(',')
            entry.artist?.takeIf {it.isNotBlank()}?.let {append(tag(it)).append(" - ")}
            append(tag(entry.title)).append('\n').append(reference).append('\n')
        }
    }
    return text.toByteArray(Charsets.UTF_8).also {require(it.size<=1024*1024){"Playlist exceeds 1 MiB"}}
}

/** Candidate rows must already be available inside granted library folders. Never choose between duplicates. */
internal fun matchPlaylistEntry(saved:PlaylistEntry,candidates:List<PlaylistEntry>):PlaylistEntry? {
    val known=candidates.filter {it.trackId!=null}
    fun durationMatches(candidate:PlaylistEntry)=saved.durationUs==null || candidate.durationUs==null || abs(saved.durationUs-candidate.durationUs)<=2_000_000
    val names=if(saved.fileName!=null)known.filter {saved.fileName.equals(it.fileName,ignoreCase=true) && durationMatches(it)} else emptyList()
    if(names.isNotEmpty())return names.singleOrNull()
    if(saved.durationUs==null)return null
    return known.filter {it.title.equals(saved.title,ignoreCase=true) && it.artist.orEmpty().equals(saved.artist.orEmpty(),ignoreCase=true) &&
        it.durationUs!=null && abs(it.durationUs-saved.durationUs)<=2_000_000}.singleOrNull()
}

internal fun resolveM3uEntry(entry:M3uEntry,candidates:List<PlaylistEntry>):PlaylistEntry {
    val name=entry.path.replace('\\','/').substringAfterLast('/').takeIf {it.isNotBlank()}?.take(4096)
    val missing=PlaylistEntry(null,entry.title?.take(512) ?: name?.take(512) ?: "Missing song",entry.artist,null,entry.durationUs,name)
    if('/' in entry.path || '\\' in entry.path || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(entry.path))return missing
    return matchPlaylistEntry(missing,candidates) ?: missing
}
