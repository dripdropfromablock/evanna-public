package com.evanna.player.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanna.player.LocalLookStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
internal fun LibraryAlbumCard(group: LibraryGroup, revision: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, compact: Boolean = false, list: Boolean = false, onOpen: () -> Unit,
) {
    val context=LocalContext.current
    val album=AlbumIdentity(group.value,group.albumArtist)
    val artwork=remember {LocalLibrary.artwork(context)}
    // The mockup's album card: a 7 dp cover, a 15 sp title and the artist in grey; the year shows on the album page.
    // No side padding, so the grid's 12 dp gap and 16 dp edge are the visible ones.
    val apple = LocalLookStyle.current.look.apple
    val cover = if (apple) androidx.compose.foundation.shape.RoundedCornerShape(7.dp) else EvannaDesign.shapes.small
    val frame = if (apple) Modifier else Modifier.border(1.dp, EvannaDesign.colorScheme.outlineVariant.copy(alpha = 0.55f), cover)
    val clickable = modifier.clip(EvannaDesign.shapes.small).clickable(enabled = enabled, onClick = onOpen)
    val title = group.value ?: "Unknown album"
    val artist = group.artist ?: "${group.count} songs"
    if (list) {
        Column {
            Row(clickable.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LibraryArtwork(album.key, revision, Modifier.size(48.dp).then(frame), cover) {
                    artwork.album(album, ArtworkSize.MEDIUM)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = EvannaDesign.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artist, style = EvannaDesign.typography.bodySmall, color = EvannaDesign.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(start = 60.dp), color = EvannaDesign.colorScheme.outlineVariant.copy(alpha = 0.32f))
        }
    } else Column(clickable.padding(vertical = 4.dp)) {
        LibraryArtwork(album.key, revision, Modifier.fillMaxWidth().aspectRatio(1f).then(frame), cover) {
            artwork.album(album, ArtworkSize.MEDIUM)
        }
        Text(title, style = EvannaDesign.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
            fontWeight = if (apple) FontWeight.Medium else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        if (!compact) Text(artist, style = EvannaDesign.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = EvannaDesign.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Independent horizontal paging keeps the artist's songs reachable even with many albums. */
@Composable
internal fun ArtistAlbums(artist:String?,search:String,revision:Int,enabled:Boolean,onOpen:(LibraryGroup)->Unit) {
    val context=LocalContext.current;val database=remember {LocalLibrary.database(context)}
    key(artist,search,revision) {
        var offset by remember {mutableIntStateOf(0)}
        var loaded by remember {mutableIntStateOf(-1)}
        var groups by remember {mutableStateOf<List<LibraryGroup>>(emptyList())}
        var total by remember {mutableIntStateOf(0)}
        var failed by remember {mutableStateOf(false)}
        var retry by remember {mutableIntStateOf(0)}
        val scroll=rememberLazyListState()
        LaunchedEffect(offset,retry) {
            failed=false
            try {
                val page=withContext(Dispatchers.IO){database.albumsForArtist(artist,search,offset=offset)}
                groups=if(offset==0)page.groups else groups+page.groups;total=page.total;loaded=offset
            }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){failed=true}
        }
        LaunchedEffect(offset,loaded,total,enabled,failed) {
            if(loaded!=offset || failed || !enabled)return@LaunchedEffect
            val next=nextLibraryPage(0,groups.size,total) ?: return@LaunchedEffect
            snapshotFlow {val layout=scroll.layoutInfo;libraryEndIsNear(layout.visibleItemsInfo.lastOrNull()?.index,layout.totalItemsCount,4)}.first {it}
            offset=next
        }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Albums · by year",style=EvannaDesign.typography.titleMedium,modifier=Modifier.semantics {heading()})
            if(failed) {
                Text("Albums could not be read. Your library was kept.")
                TextButton(onClick={retry++},modifier=Modifier.heightIn(min=48.dp)){Text("Retry albums")}
            }else if(loaded!=offset)LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyRow(Modifier.fillMaxWidth(),state=scroll,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items(groups,key={AlbumIdentity(it.value,it.albumArtist).key}) {group ->
                    LibraryAlbumCard(group,revision,Modifier.width(156.dp),enabled){onOpen(group)}
                }
            }
            Text("Songs",style=EvannaDesign.typography.titleMedium,modifier=Modifier.semantics {heading()})
        }
    }
}
