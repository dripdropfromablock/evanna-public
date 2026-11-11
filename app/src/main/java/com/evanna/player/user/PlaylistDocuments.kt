package com.evanna.player.user

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.evanna.player.decoder.isLocalDocumentProvider
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.hasTreeReadGrant

internal fun playlistFile(context:Context,id:String):PlaylistFile? {
    val files=LocalLibrary.database(context).playlistFiles(id=id)
    return files.firstOrNull {it.available && hasTreeReadGrant(context,Uri.parse(it.root))} ?: files.firstOrNull()
}
internal fun playlistMatches(context:Context,entry:PlaylistEntry):List<PlaylistFile> =
    LocalLibrary.database(context).playlistFiles(entry=entry).filter {it.available && hasTreeReadGrant(context,Uri.parse(it.root))}

private fun parentDocument(id:String)=if('/' in id)id.substringBeforeLast('/') else id.substringBefore(':')+":"
private fun within(root:String,id:String)=id==root || id.startsWith(if(root.endsWith(':'))root else "$root/")
internal fun relativeDocument(parent:String,path:String):String? {
    if(':' !in parent || path.startsWith('/') || path.startsWith('\\') || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(path))return null
    val parts=parent.substringAfter(':').split('/').filter {it.isNotEmpty()}.toMutableList()
    for(part in path.replace('\\','/').split('/'))when(part) {
        "","." -> Unit
        ".." -> if(parts.isEmpty())return null else parts.removeAt(parts.lastIndex)
        else -> parts+=part
    }
    return parent.substringBefore(':')+":"+parts.joinToString("/")
}
internal fun playlistDocument(parent:String,path:String,primaryRoot:String):String? {
    val prefix=primaryRoot.trimEnd('/')+"/"
    if(path.startsWith(prefix))return relativeDocument("primary:",path.removePrefix(prefix))
    return relativeDocument(parent,path)
}

/** Read only the selected local document; references never acquire grants or open arbitrary paths. */
internal fun importPlaylist(context:Context,uri:Uri):M3uImport {
    require(isLocalDocumentProvider(context,uri) && DocumentsContract.isDocumentUri(context,uri)){"Choose a local playlist document"}
    val bytes=checkNotNull(context.contentResolver.openInputStream(uri)).use {input ->
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(output.size()<=1024*1024) {
            val count=input.read(buffer,0,minOf(buffer.size,1024*1024+1-output.size()))
            if(count<0)break
            check(count>0){"Playlist read stalled"};output.write(buffer,0,count)
        }
        output.toByteArray()
    }
    return parseM3u(bytes)
}
internal fun resolvePlaylistImport(context:Context,uri:Uri,parsed:M3uImport):List<PlaylistEntry> {
    val parent=runCatching {parentDocument(DocumentsContract.getDocumentId(uri))}.getOrNull()
    return parsed.entries.map {entry ->
        val missing=resolveM3uEntry(entry,emptyList())
        val document=parent?.let {playlistDocument(it,entry.path,android.os.Environment.getExternalStorageDirectory().path)}
        val matches=if(document==null)emptyList() else
            LocalLibrary.database(context).playlistFiles(documentId=document).filter {it.available && hasTreeReadGrant(context,Uri.parse(it.root))}
        val exact=if(document==null)emptyList()else matches.filter {file ->
            val root=Uri.parse(file.root)
            root.authority==uri.authority && within(DocumentsContract.getTreeDocumentId(root),document) && file.documentId==document
        }.distinctBy {it.entry.trackId}
        if(exact.size==1)exact.single().entry else if(exact.isNotEmpty())missing
        else if('/' !in entry.path && '\\' !in entry.path)resolveM3uEntry(entry,playlistMatches(context,missing).map {it.entry}.distinctBy {it.trackId}) else missing
    }
}
internal fun exportPlaylist(context:Context,uri:Uri,playlist:Playlist):Boolean {
    require(isLocalDocumentProvider(context,uri) && DocumentsContract.isDocumentUri(context,uri)){"Choose a local export document"}
    val parent=runCatching {parentDocument(DocumentsContract.getDocumentId(uri))}.getOrNull()
    var fallback=false
    val entries=playlist.items.map {entry ->
        val file=entry.trackId?.let {playlistFile(context,it)}
        val root=file?.let {Uri.parse(it.root)}
        val rootId=root?.let {DocumentsContract.getTreeDocumentId(it)}
        val path=if(file!=null && parent!=null && root!=null && rootId!=null && root.authority==uri.authority &&
            hasTreeReadGrant(context,root) && within(rootId,parent) && within(rootId,file.documentId) && ':' in parent) {
            val a=parent.substringAfter(':').split('/').filter {it.isNotEmpty()}
            val b=file.documentId.substringAfter(':').split('/').filter {it.isNotEmpty()}
            var common=0;while(common<minOf(a.size,b.size) && a[common]==b[common])common++
            (List(a.size-common){".."}+b.drop(common)).joinToString("/")
        }else {fallback=true;entry.fileName ?: file?.entry?.fileName ?: "missing-audio"}
        entry to path
    }
    val bytes=writeM3u(entries)
    checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use {it.write(bytes);it.flush()}
    return fallback
}
