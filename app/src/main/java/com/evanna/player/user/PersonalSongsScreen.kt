package com.evanna.player.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.evanna.player.EvannaDesign
import com.evanna.player.PlayerIcons
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evanna.player.ScreenHeader
import com.evanna.player.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
internal fun PersonalSongsScreen(view:PersonalView,onBack:()->Unit,onPlay:(String?,Boolean,(Boolean)->Unit)->Unit) {
    val context=LocalContext.current;val store=remember {UserData.database(context)}
    val changes by store.changes.collectAsState()
    val revision=LocalLibrary.scansFinished
    val scope=rememberCoroutineScope()
    var offset by remember(changes,revision){mutableIntStateOf(0)}
    var songs by remember {mutableStateOf<List<Pair<PersonalSong,LibrarySong?>>>(emptyList())}
    var loaded by remember {mutableStateOf<Triple<Long,Int,Int>?>(null)}
    val request=Triple(changes,revision,offset)
    var more by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var removing by remember {mutableStateOf<String?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    var failed by remember {mutableStateOf<Triple<Long,Int,Int>?>(null)}
    val scroll=rememberLazyListState()
    LaunchedEffect(request,retry) {
        try {
            val result=withContext(Dispatchers.IO) {
                val ids=store.personalPage(view,101,offset)
                ids.take(100).map {it to LocalLibrary.database(context).song(it.id)} to (ids.size>100)
            }
            songs=if(offset==0)result.first else songs+result.first
            more=result.second;loaded=request;message=null;failed=null
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){failed=request;message="${view.label} could not be loaded. Your saved data was kept."}
    }
    val shown=songs.takeIf {loaded?.let {it.first==changes && it.second==revision}==true}.orEmpty()
    LaunchedEffect(changes,revision){scroll.scrollToItem(0)}
    LaunchedEffect(request,loaded,failed,more) {
        if(loaded!=request || failed==request || !more)return@LaunchedEffect
        snapshotFlow {val layout=scroll.layoutInfo;libraryEndIsNear(layout.visibleItemsInfo.lastOrNull()?.index,layout.totalItemsCount)}.first {it}
        offset=songs.size
    }
    LazyColumn(Modifier.fillMaxSize(),state=scroll,contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {ScreenHeader(view.label,onBack)}
        if(view!=PersonalView.FAVORITES)item {Text("Counts cover the last 5,000 qualifying plays. A play counts after half the song or four minutes of confirmed listening, whichever is first.",style=EvannaDesign.typography.bodySmall)}
        message?.let {item {Text(it)}}
        if(message!=null)item {TextButton(onClick={retry++},modifier=Modifier.heightIn(min=48.dp)){Text("Retry ${view.label.lowercase()}")}}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={onPlay(null,false){if(!it)message="No available songs could start. Check output, access and Strict conversion permission."}},enabled=shown.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)){Text(if(view==PersonalView.FAVORITES)"Play favorites" else "Play list")}
            OutlinedButton(onClick={onPlay(null,true){if(!it)message="This list could not start."}},enabled=shown.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)){Text(if(view==PersonalView.FAVORITES)"Shuffle favorites" else "Shuffle list")}
        }}
        if(loaded!=request && failed!=request)item {ActivityIndicator(Modifier.fillMaxWidth())}
        if(shown.isEmpty() && loaded==request)item {Text(if(view==PersonalView.FAVORITES)"No favorites yet. Use the heart in Now Playing or a song's menu." else "No qualifying plays yet. Listen to songs in your library to build this list.")}
        items(shown,key={it.first.id}) {(personal,song)->Column {
            val id=personal.id
            Text(song?.title ?: "Unavailable song",style=EvannaDesign.typography.titleMedium,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)
                .then(if(song?.available==true)Modifier.clickable {onPlay(id,false){if(!it)message="This song could not start; check output and access."}}else Modifier))
            Text(song?.artist ?: "Unknown artist",style=EvannaDesign.typography.bodyMedium)
            if(song?.available!=true)Text("Unavailable · kept in ${view.label}",style=EvannaDesign.typography.bodySmall)
            if(view!=PersonalView.FAVORITES)Text("${personal.plays} qualifying plays",style=EvannaDesign.typography.bodySmall)
            if(view==PersonalView.FAVORITES)TextButton(onClick={if(removing==null){removing=id;scope.launch {
                try {if(!withContext(NonCancellable+Dispatchers.IO){store.setFavorite(id,false)})message="The favorite could not be changed."}
                finally {removing=null}
            }}},enabled=removing==null,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Remove ${song?.title ?: "unavailable song"} from favorites"}) {
                Icon(PlayerIcons.HeartFill, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Remove favorite")
            }
        }}
    }
}
