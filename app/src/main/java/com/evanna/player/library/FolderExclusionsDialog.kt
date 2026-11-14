package com.evanna.player.library

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

private data class FolderListing(val requested:String?,val revision:Int,val folders:List<LibraryDocument>,val excluded:List<ExcludedFolder>)

@Composable
internal fun FolderExclusionsDialog(root:LibraryRoot,onDismiss:()->Unit) {
    val context=LocalContext.current
    var path by remember(root.uri){mutableStateOf<List<LibraryDocument>>(emptyList())}
    var listing by remember {mutableStateOf<FolderListing?>(null)}
    var failed by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    val requested=path.lastOrNull()?.id
    val revision=LocalLibrary.scansFinished
    val busy=LocalLibrary.scanning || LocalLibrary.managingFolders
    val shown=listing.takeIf {it?.requested==requested && it?.revision==revision}
    LaunchedEffect(requested,revision,retry) {
        failed=false
        try {listing=withContext(Dispatchers.IO) {
            val source=SafLibrarySource(context,Uri.parse(root.uri))
            val top=source.root()
            val folders=source.children(requested ?: top.id).filter {it.mime==DIRECTORY_MIME}.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.name})
            FolderListing(requested,revision,folders,LocalLibrary.database(context).excludedFolders(root.uri))
        }}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){failed=true}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Manage subfolders")},text={
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {Text("Excluded songs disappear from this library folder. Music files and saved personal lists stay on your device.")}
            item {Text(LocalLibrary.status,style=EvannaDesign.typography.bodySmall)}
            if(busy)item {ActivityIndicator(Modifier.fillMaxWidth())}
            item {Text(path.lastOrNull()?.name ?: root.name,style=EvannaDesign.typography.titleMedium,modifier=Modifier.semantics {heading()})}
            if(path.isNotEmpty())item {TextButton(onClick={path=path.dropLast(1)},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Back to parent folder")}}
            if(shown==null && !failed)item {ActivityIndicator(Modifier.fillMaxWidth())}
            if(failed)item {Column {
                Text("Folders could not be read. Reconnect folder access and try again.")
                TextButton(onClick={retry++},modifier=Modifier.heightIn(min=48.dp)){Text("Retry folders")}
            }}
            if(shown!=null) {
                path.lastOrNull()?.let {directory -> item {
                    val excluded=shown.excluded.any {it.id==directory.id}
                    OutlinedButton(onClick={LocalLibrary.excludeFolder(context,root.uri,directory)},
                        enabled=!busy && !excluded && shown.excluded.size<128,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text(if(excluded)"This folder is excluded" else "Exclude this folder")
                    }
                }}
                if(shown.folders.isEmpty())item {Text("No subfolders here.")}
                items(shown.folders,key={"folder:${it.id}"}) {folder ->
                    Column(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(enabled=!busy){path=path+folder}.padding(vertical=8.dp)) {
                        Text(folder.name,style=EvannaDesign.typography.bodyLarge)
                        if(shown.excluded.any {it.id==folder.id})Text("Excluded",style=EvannaDesign.typography.bodySmall)
                    }
                }
                if(shown.excluded.isNotEmpty())item {Text("Excluded subfolders",style=EvannaDesign.typography.titleMedium,modifier=Modifier.semantics {heading()})}
                items(shown.excluded,key={"excluded:${it.id}"}) {folder -> Column {
                    Text(folder.name)
                    TextButton(onClick={LocalLibrary.includeFolder(context,root.uri,folder.id)},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("Include ${folder.name}")}
                }}
                item {Text("After including a folder again, rescan the music folder to restore its songs.",style=EvannaDesign.typography.bodySmall)}
            }
        }
    },confirmButton={TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)){Text("Close")}})
}
