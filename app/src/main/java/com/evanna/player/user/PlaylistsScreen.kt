package com.evanna.player.user

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.evanna.player.ScreenBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanna.player.ScreenHeader
import com.evanna.player.library.*
import kotlinx.coroutines.*

@Composable
internal fun PlaylistsScreen(onBack:()->Unit,onPlay:(Long,Long,Boolean,Int,(Boolean)->Unit)->Unit,
    onSaveQueue:(String,(Boolean)->Unit)->Unit,addIds:List<String> = emptyList(),addFilter:LibraryFilter?=null,
    saveQueue:Boolean=false,onAdded:()->Unit={}) {
    val context=LocalContext.current
    val database=remember {UserData.database(context)}
    val scope=rememberCoroutineScope()
    var revision by remember {mutableIntStateOf(0)}
    var selectedId by rememberSaveable {mutableStateOf<Long?>(null)}
    var lists by remember {mutableStateOf<List<PlaylistSummary>>(emptyList())}
    var selected by remember {mutableStateOf<Playlist?>(null)}
    var availability by remember {mutableStateOf<Set<String>>(emptySet())}
    var loaded by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var dialog by remember {mutableStateOf(if(saveQueue)"QUEUE" else "")}
    var name by remember {mutableStateOf("")}
    var delete by remember {mutableStateOf<Playlist?>(null)}
    var imported by remember {mutableStateOf<List<PlaylistEntry>?>(null)}
    var exportId by rememberSaveable {mutableStateOf<Long?>(null)}
    var exportRevision by rememberSaveable {mutableStateOf<Long?>(null)}
    var additions by remember {mutableStateOf<List<PlaylistEntry>?>(null)}
    val adding=addIds.isNotEmpty() || addFilter!=null
    fun operate(action:suspend ()->Unit) {
        if(busy)return
        busy=true
        scope.launch {
            try {action()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){message="The playlist change could not finish. Your saved list was kept; retry after checking access and storage."}
            finally {busy=false}
        }
    }
    fun save(before:Playlist?,title:String,entries:List<PlaylistEntry>)=operate {
        check(withContext(Dispatchers.IO){database.savePlaylist(before?.id,title,entries,before?.revision)}!=null)
        revision++;message="Playlist saved."
        if(adding)onAdded()
    }
    LaunchedEffect(addIds,addFilter) {
        additions=null
        if(adding)try {
            additions=withContext(Dispatchers.IO) {
                val ids=if(addFilter!=null)LocalLibrary.database(context).page(limit=1000,filter=addFilter).let {
                    require(it.total<=PLAYLIST_LIMIT){"Playlist limit"};it.songs.map {song->song.id}
                }else addIds
                ids.map {checkNotNull(playlistFile(context,it)).entry}
            }
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message="These songs could not be added. A playlist holds up to 1,000 songs; check folder access."}
    }
    LaunchedEffect(selectedId,revision,LocalLibrary.scansFinished) {
        loaded=false
        try {
            val result=withContext(Dispatchers.IO) {
                val list=database.playlists();val detail=selectedId?.let {database.playlist(it)}
                val available=detail?.items?.mapNotNull {it.trackId}?.distinct()?.filter {id ->
                    playlistFile(context,id)?.let {it.available && hasTreeReadGrant(context,android.net.Uri.parse(it.root))}==true
                }?.toSet().orEmpty()
                Triple(list,detail,available)
            }
            lists=result.first;selected=result.second;availability=result.third;loaded=true
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message="Saved playlists could not be read. Free storage and retry; no data was removed."}
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->if(uri!=null)operate {
        val result=withContext(Dispatchers.IO){val parsed=importPlaylist(context,uri);parsed to resolvePlaylistImport(context,uri,parsed)}
        imported=result.second;name="Imported playlist";dialog="IMPORT"
        message=(result.first.warnings+"${result.second.count {it.trackId==null}} entries could not be matched and will stay as missing songs.").joinToString(" ")
    }}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) {uri ->
        val id=exportId;val expected=exportRevision;exportId=null;exportRevision=null
        if(uri!=null && id!=null && expected!=null)operate {
            val fallback=withContext(Dispatchers.IO){exportPlaylist(context,uri,checkNotNull(database.playlist(id,expected)))}
            message=if(fallback)"Exported. Some entries use file names because a relative path could not be verified; importing elsewhere may need Find missing songs." else "Playlist exported with paths relative to the chosen folder."
        }
    }
    ScreenBackHandler(selectedId!=null){selectedId=null}
    if(dialog.isNotEmpty())AlertDialog(onDismissRequest={if(!busy)dialog=""},title={Text(when(dialog){"RENAME"->"Rename playlist";"QUEUE"->"Save queue as playlist";else->"New playlist"})},
        text={OutlinedTextField(value=name,onValueChange={name=it.take(200)},enabled=!busy,label="Playlist name",modifier=Modifier.fillMaxWidth())},
        confirmButton={TextButton(enabled=!busy && validPlaylistName(name)!=null && (!adding || additions!=null),onClick={
            val mode=dialog;dialog=""
            when(mode) {
                "QUEUE" -> {busy=true;onSaveQueue(name){success ->busy=false;if(success){revision++;onAdded()}else message="The queue could not be saved. Your queue is unchanged."}}
                "RENAME" -> selected?.let {save(it,name,it.items)}
                "IMPORT" -> imported?.let {save(null,name,it);imported=null}
                else -> save(null,name,additions ?: emptyList())
            }
        }){Text("Save")}},dismissButton={TextButton(onClick={dialog=""},enabled=!busy){Text("Cancel")}})
    delete?.let {list ->AlertDialog(onDismissRequest={delete=null},title={Text("Delete playlist?")},
        text={Text("Only this saved list is removed. Your music files stay on the device.")},
        confirmButton={TextButton(onClick={delete=null;operate {check(withContext(Dispatchers.IO){database.deletePlaylist(list.id,list.revision)});selectedId=null;revision++}}){Text("Delete playlist")}},
        dismissButton={TextButton(onClick={delete=null}){Text("Cancel")}})}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {ScreenHeader(if(adding)"Add to playlist" else selected?.takeIf {it.id==selectedId}?.name ?: "Playlists") {if(selectedId!=null)selectedId=null else onBack()}}
        message?.let {item {Text(it,style=EvannaDesign.typography.bodyMedium)}}
        if(!loaded || busy)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        item {TextButton(onClick={revision++},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Reload playlists")}}
        if(selectedId==null) {
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={name="";dialog="NEW"},enabled=!busy && (!adding || additions!=null),modifier=Modifier.heightIn(min=48.dp)){Text(if(adding)"Create and add" else "New playlist")}
                if(!adding)OutlinedButton(onClick={importer.launch(arrayOf("audio/x-mpegurl","audio/mpegurl","text/plain","application/octet-stream"))},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Import M3U8")}
            }}
            if(lists.isEmpty() && loaded)item {Text("No saved playlists. Create one, save your queue, or import a local M3U8 file.")}
            items(lists,key={it.id}) {list ->TextButton(onClick={
                if(adding)operate {
                    val before=withContext(Dispatchers.IO){checkNotNull(database.playlist(list.id))}
                    check(withContext(Dispatchers.IO){database.savePlaylist(before.id,before.name,before.items+checkNotNull(additions),before.revision)}!=null)
                    onAdded()
                }else selectedId=list.id
            },enabled=!busy && (!adding || additions!=null),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Column(Modifier.fillMaxWidth()){Text(list.name,style=EvannaDesign.typography.titleMedium);Text("${list.count} songs${if(adding)" · Add here" else ""}")}
            }}
        }else selected?.takeIf {it.id==selectedId && loaded}?.let {list ->
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onPlay(list.id,list.revision,false,0){if(!it)message="No playable songs were started. Reload this list and check access, output and Strict conversion permission."}},enabled=!busy && availability.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)){Text("Play playlist")}
                OutlinedButton(onClick={onPlay(list.id,list.revision,true,0){if(!it)message="The playlist could not start. Reload it and retry."}},enabled=!busy && availability.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)){Text("Shuffle playlist")}
            }}
            item {TextButton(onClick={name=list.name;dialog="RENAME"},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Rename playlist")}}
            item {TextButton(onClick={exportId=list.id;exportRevision=list.revision;exporter.launch("Evanna-playlist.m3u8")},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Export M3U8")}}
            item {TextButton(onClick={operate {
                val linked=withContext(Dispatchers.IO){list.items.map {entry ->
                    if(entry.trackId!=null && entry.trackId in availability)entry else matchPlaylistEntry(entry,playlistMatches(context,entry).map {it.entry}.distinctBy {it.trackId}) ?: entry
                }}
                check(withContext(Dispatchers.IO){database.savePlaylist(list.id,list.name,linked,list.revision)}!=null)
                revision++;message="Missing-song search finished; ambiguous or inaccessible songs were kept."
            }},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Find missing songs")}}
            item {TextButton(onClick={delete=list},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Delete playlist")}}
            itemsIndexed(list.items,key={index,_->index}) {index,entry ->Column {
                val available=entry.trackId in availability
                Text(entry.title,style=EvannaDesign.typography.titleMedium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)
                    .then(if(available && !busy)Modifier.clickable {onPlay(list.id,list.revision,false,index){if(!it)message="Song could not start; reload the list and check output and conversion permission."}}else Modifier))
                Text(entry.artist ?: "Unknown artist",style=EvannaDesign.typography.bodyMedium)
                if(!available)Text("Unavailable · kept in this playlist",style=EvannaDesign.typography.bodySmall)
                Row {
                    TextButton(enabled=!busy && index>0,onClick={val moved=list.items.toMutableList();moved.add(index-1,moved.removeAt(index));save(list,list.name,moved)},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Move ${entry.title} up in playlist"}){Text("Up")}
                    TextButton(enabled=!busy && index<list.items.lastIndex,onClick={val moved=list.items.toMutableList();moved.add(index+1,moved.removeAt(index));save(list,list.name,moved)},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Move ${entry.title} down in playlist"}){Text("Down")}
                    TextButton(enabled=!busy,onClick={save(list,list.name,list.items.filterIndexed {position,_->position!=index})},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Remove ${entry.title} from playlist"}){Text("Remove")}
                }
            }}
        }
    }
}
