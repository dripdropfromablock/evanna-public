package com.evanna.player.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import com.evanna.player.EvannaType
import com.evanna.player.LocalLookStyle
import com.evanna.player.PlayerIcons
import com.evanna.player.ScreenBackHandler
import com.evanna.player.SettingsLink
import com.evanna.player.UI_SETTINGS
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.evanna.player.user.*

private enum class LibraryView(val label: String) { ALBUMS("Albums"), TRACKS("Songs"), ARTISTS("Artists"), GENRES("Genres"),COMPOSERS("Composers"),ADDED("Recently added"), FOLDERS("Folders") }
private enum class AlbumLayout(val label: String) {
    COVERS("Covers"), COMPACT("Compact Grid"), LIST("List")
}

private fun LibraryView.groupKind()=when(this) {
    LibraryView.ALBUMS -> LibraryGroupKind.ALBUM
    LibraryView.ARTISTS -> LibraryGroupKind.ARTIST
    LibraryView.GENRES -> LibraryGroupKind.GENRE
    LibraryView.COMPOSERS -> LibraryGroupKind.COMPOSER
    else -> error("Not a group view")
}
private fun LibraryView.unknownName()=when(this) {
    LibraryView.GENRES -> "Unknown genre"
    LibraryView.COMPOSERS -> "Unknown composer"
    else -> "Unknown artist"
}
private data class LibraryListing(val roots: List<LibraryRoot> = emptyList(), val songs: List<LibrarySong> = emptyList(),
    val groups: List<LibraryGroup> = emptyList(), val total: Int = 0, val albumSummary: AlbumSummary? = null)
private data class LibraryPageCursor(val offset:Int=0,val base:Int=0)
private data class LibraryRequest(val view: LibraryView, val filter: LibraryFilter, val revision: Int, val offset: Int,val base:Int)

/** Room the list rows leave at the end for the A–Z bar. */
private val LocalBarRoom = compositionLocalOf { 0.dp }
/** Content type of the list rows the A–Z bar sits beside. */
private const val LIST_ROW = "list row"

/** A grid item that spans the whole row. Lazy grid items overlap their children, so content needs one root.
 *  Page lines also cover the rows' A–Z bar room, so the bar never narrows them. */
private fun LazyGridScope.line(key: Any? = null, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        val room = LocalBarRoom.current
        Box(Modifier.layout { measurable, constraints ->
            val wide = constraints.maxWidth + room.roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
            layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
        }, propagateMinConstraints = true) { content() }
    }

@Composable
internal fun rememberAddMusicFolder(): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { LocalLibrary.scan(context, it, true) }
    }
    return {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        })
    }
}

/** Library opens on albums. Search starts with discovery shortcuts until the user types or picks a collection. */
@Composable
internal fun LibraryScreen(onSelect: (LibraryContextRequest, (Boolean) -> Unit) -> Unit, onAdd: (String, (Boolean) -> Unit) -> Unit,
    onPlayNext: (String, (Boolean) -> Unit) -> Unit, bottomInset: Dp,
    searchMode: Boolean = false,onPlaylists:()->Unit={},onPlaylistSong:(String)->Unit={},onPlaylistAlbum:(LibraryFilter)->Unit={},onFavorites:()->Unit={},
    onRecent:()->Unit={},onMostPlayed:()->Unit={}) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val preferences = remember(context) { context.getSharedPreferences(UI_SETTINGS, 0) }
    var albumLayout by remember(preferences) {
        val saved = preferences.all["album-layout"]
        mutableStateOf(AlbumLayout.entries.firstOrNull { it.name == saved } ?: AlbumLayout.COVERS)
    }
    val database = remember { LocalLibrary.database(context) }
    val personal=remember {UserData.database(context)}
    val personalChanges by personal.changes.collectAsState()
    val personalScope=rememberCoroutineScope()
    val artwork = remember { LocalLibrary.artwork(context) }
    var removeFolder by remember {mutableStateOf<LibraryRoot?>(null)}
    var manageFolder by remember {mutableStateOf<LibraryRoot?>(null)}
    var trackInfo by remember { mutableStateOf<LibrarySong?>(null) }
    var showMusicFolders by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(if (searchMode) LibraryView.TRACKS else LibraryView.ALBUMS) }
    var search by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<LibraryGroupKind?>(null) }
    var value by rememberSaveable { mutableStateOf<String?>(null) }
    var groupName by rememberSaveable { mutableStateOf("") }
    var albumArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var parentKind by rememberSaveable { mutableStateOf<LibraryGroupKind?>(null) }
    var parentValue by rememberSaveable { mutableStateOf<String?>(null) }
    var parentName by rememberSaveable { mutableStateOf("") }
    var songAlbumSearch by rememberSaveable { mutableStateOf<String?>(null) }
    val sortScope=when(view) {
        LibraryView.ALBUMS -> LibrarySortScope.ALBUMS
        LibraryView.FOLDERS -> LibrarySortScope.FOLDERS
        else -> if(searchMode)LibrarySortScope.SEARCH else LibrarySortScope.SONGS
    }
    var selectedSort by remember(sortScope,view) {mutableStateOf<LibrarySort?>(null)}
    var sortSaving by remember {mutableStateOf(false)}
    LaunchedEffect(sortScope,view,personalChanges) {
        selectedSort=when(view) {
            LibraryView.ADDED -> LibrarySort.ADDED
            LibraryView.ARTISTS,LibraryView.GENRES,LibraryView.COMPOSERS -> LibrarySort.TITLE
            else -> try {withContext(Dispatchers.IO){personal.librarySort(sortScope)}}
                catch(cancelled:CancellationException){throw cancelled}catch(_:Exception) {
                    LocalLibrary.status="Saved sort could not be read. This view is using Title; retry after checking storage."
                    LibrarySort.TITLE
                }
        }
    }
    // Scans run in LocalLibrary, so leaving this tab never stops one; each finished scan reloads lists and covers.
    val revision = LocalLibrary.scansFinished
    val busy = LocalLibrary.scanning || LocalLibrary.managingFolders
    var selectionFailed by remember { mutableStateOf(false) }
    val rootFilter = LibraryFilter(search = search, sort = selectedSort ?: LibrarySort.TITLE)
    val filter = rootFilter.copy(search = songAlbumSearch ?: search, kind = kind, value = value, albumArtist = albumArtist)
    val selectedAlbum = AlbumIdentity(value, albumArtist)
    // Keep the collection's loaded pages and position while a detail page is open.
    val rootCursor = remember(view, rootFilter, revision) { mutableStateOf(LibraryPageCursor()) }
    val detailCursor = remember(view, filter, revision) { mutableStateOf(LibraryPageCursor()) }
    var cursor by (if (kind == null) rootCursor else detailCursor)
    val offset=cursor.offset
    val request = LibraryRequest(view, filter, revision, offset,cursor.base)
    var jump by remember(view,filter,revision) {mutableStateOf<Char?>(null)}
    val rootLoaded = remember { mutableStateOf<LibraryRequest?>(null) }
    val detailLoaded = remember { mutableStateOf<LibraryRequest?>(null) }
    var loaded by (if (kind == null) rootLoaded else detailLoaded)
    val rootFailed = remember { mutableStateOf<LibraryRequest?>(null) }
    val detailFailed = remember { mutableStateOf<LibraryRequest?>(null) }
    var failed by (if (kind == null) rootFailed else detailFailed)
    var retry by remember { mutableIntStateOf(0) }
    val rootListing = remember { mutableStateOf(LibraryListing()) }
    val detailListing = remember { mutableStateOf(LibraryListing()) }
    var listing by (if (kind == null) rootListing else detailListing)
    val loading = loaded != request && failed != request
    val changingSearchLetter = searchMode && loading && loaded?.let {
        it.base != cursor.base && it.copy(offset = offset, base = cursor.base) == request
    } == true
    val shown = listing.takeIf { selectedSort != null && (loaded?.copy(offset = offset) == request || changingSearchLetter) }
    val libraryHome = !searchMode && kind == null
    val folderVolumes = if (view == LibraryView.FOLDERS && kind == null) rememberFolderVolumeNames() else emptyMap()
    val albumHome = libraryHome && view == LibraryView.ALBUMS
    val searchHome = searchMode && kind == null && view == LibraryView.TRACKS && search.isBlank()
    val songsView = kind != null || view in listOf(LibraryView.TRACKS,LibraryView.ADDED)
    val resultLabel = if (songsView) "songs" else view.label.lowercase()
    val muted = EvannaDesign.colorScheme.onSurfaceVariant
    val studio = !LocalLookStyle.current.look.apple

    fun back() {
        kind = parentKind
        value = if (parentKind == null) null else parentValue
        groupName = if (parentKind == null) "" else parentName
        albumArtist = null
        parentKind = null
        songAlbumSearch = null
    }

    fun updateSearch(query: String) {
        val text = query.take(200)
        if (songAlbumSearch == null) search = text else songAlbumSearch = text
    }

    LaunchedEffect(request, retry,selectedSort!=null) {
        if (selectedSort == null || (loaded == request && failed == null)) return@LaunchedEffect
        try {
            if (offset == 0 && filter.search.isNotBlank()) delay(250)
            val next = withContext(Dispatchers.IO) {
                when {
                    songsView -> database.page(offset = offset, filter = filter).let { LibraryListing(it.roots, it.songs, total = it.total,
                        albumSummary = if (kind == LibraryGroupKind.ALBUM) database.albumSummary(selectedAlbum) else null) }
                    view == LibraryView.FOLDERS -> database.roots().let { LibraryListing(roots = it, total = it.size) }
                    else -> database.groups(view.groupKind(),
                        search, offset = offset,sort=filter.sort).let { LibraryListing(database.roots(), groups = it.groups, total = it.total) }
                }
            }
            if (kind == LibraryGroupKind.ALBUM && next.total == 0 && withContext(Dispatchers.IO) {
                    database.page(limit = 1, filter = filter.copy(search = "")).total == 0
                }) {
                back()
                LocalLibrary.status = "Album information changed. Choose an album again."
                return@LaunchedEffect
            }
            ensureActive()
            listing = if (offset == cursor.base) next else next.copy(songs = listing.songs + next.songs, groups = listing.groups + next.groups)
            loaded = request
            failed = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = request }
    }

    val addFolder = rememberAddMusicFolder()
    fun open(groupKind: LibraryGroupKind, groupValue: String?, name: String, taggedArtist: String? = null) {
        parentKind = if (groupKind == LibraryGroupKind.ALBUM) kind else null
        parentValue = value
        parentName = groupName
        kind = groupKind
        value = groupValue
        groupName = name
        albumArtist = taggedArtist
        songAlbumSearch = null
    }
    fun selectView(next: LibraryView) {
        view = next
        parentKind = null
        back()
    }
    val searchBrowse = searchMode && kind == null && view != LibraryView.TRACKS && search.isBlank()
    ScreenBackHandler(kind != null || searchBrowse) {
        if (kind != null) back() else selectView(LibraryView.TRACKS)
    }
    manageFolder?.let {root -> FolderExclusionsDialog(root){manageFolder=null}}
    removeFolder?.let { root -> RemoveMusicFolderDialog(root, busy) { removeFolder = null } }
    if (showMusicFolders) MusicFoldersDialog { showMusicFolders = false }
    trackInfo?.let { song -> LibraryTrackInfo(song) { trackInfo = null } }
    if (selectionFailed) AlertDialog(onDismissRequest = { selectionFailed = false },
        title = { Text("Couldn’t open song") },
        text = { Text("Check folder access and reconnect playback controls, then try selecting the song again.") },
        confirmButton = { TextButton(onClick = { selectionFailed = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") } })
    val rootScroll = rememberLazyGridState()
    val detailScroll = key(view, filter, revision) { rememberLazyGridState() }
    val scroll = if (kind == null) rootScroll else detailScroll
    val alphabetic = kind == null && !searchHome && selectedSort == LibrarySort.TITLE &&
        view in listOf(LibraryView.TRACKS, LibraryView.ALBUMS, LibraryView.ARTISTS) &&
        (!albumHome || albumLayout == AlbumLayout.LIST)
    LaunchedEffect(view, rootFilter, revision, rootCursor.value.base) { rootScroll.scrollToItem(0) }
    LaunchedEffect(jump,view,filter,revision) {
        val letter=jump ?: return@LaunchedEffect
        if(!alphabetic)return@LaunchedEffect
        delay(60)
        try {
            val rank=withContext(Dispatchers.IO){database.letterOffset(letter,filter,when(view) {
                LibraryView.ALBUMS -> LibraryGroupKind.ALBUM
                LibraryView.ARTISTS -> LibraryGroupKind.ARTIST
                else -> null
            })}
            ensureActive();cursor=LibraryPageCursor(rank,rank);scroll.scrollToItem(0)
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){LocalLibrary.status="The letter could not be located. Your current list was kept."}
        jump=null
    }
    LaunchedEffect(request,loaded,failed,busy,shown?.total) {
        if(searchHome || loaded!=request || failed==request || busy || shown==null || !(songsView || view!=LibraryView.FOLDERS))return@LaunchedEffect
        val count=if(songsView)shown.songs.size else shown.groups.size
        val next=nextLibraryPage(cursor.base,count,shown.total) ?: return@LaunchedEffect
        snapshotFlow {val layout=scroll.layoutInfo;libraryEndIsNear(layout.visibleItemsInfo.lastOrNull()?.index,layout.totalItemsCount)}
            .first {it}
        cursor=cursor.copy(offset=next)
    }
    // The A–Z bar starts at the first list row, so the page lines above it keep the full width.
    val barRoom = if (!alphabetic) 0.dp else if (searchMode) 16.dp else 28.dp
    val rowsTop = remember(view) { mutableIntStateOf(-1) }
    LaunchedEffect(rowsTop) {
        snapshotFlow { rootScroll.layoutInfo.let { info -> info.visibleItemsInfo.firstOrNull { it.contentType == LIST_ROW }?.let { it.offset.y - info.viewportStartOffset } } }
            .filterNotNull().collect { rowsTop.intValue = it.coerceAtLeast(0) }
    }
    val cells = when {
        albumHome && albumLayout == AlbumLayout.LIST -> GridCells.Fixed(1)
        albumHome && albumLayout == AlbumLayout.COMPACT ->
            GridCells.Adaptive(96.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        albumHome -> GridCells.Adaptive(143.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
        else -> GridCells.Adaptive(143.dp)
    }
    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalBarRoom provides barRoom) {
    LazyVerticalGrid(cells, Modifier.fillMaxSize(), state = scroll,
        verticalArrangement = Arrangement.spacedBy(if (studio && libraryHome) 12.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = if (libraryHome) 6.dp else 8.dp, bottom = 24.dp + bottomInset, end = barRoom)) {
        // Grid items get a fixed full width; the Box lets the button keep its own width at the start.
        if (kind != null) line { Box {
            TextButton(onClick = { back() }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(EvannaDesign.colorScheme.onSurface)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back to ${parentKind?.name?.lowercase() ?: view.label.lowercase()}")
            }
        } }
        if (kind == LibraryGroupKind.ALBUM) line {
            val colors = EvannaDesign.colorScheme
            val ink = ButtonDefaults.textButtonColors(colors.onSurface)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (studio) Alignment.Start else Alignment.CenterHorizontally) {
                LibraryArtwork(selectedAlbum.key, revision,
                    Modifier.size(if (studio) 176.dp else 200.dp).shadow(12.dp, EvannaDesign.shapes.large),
                    EvannaDesign.shapes.large) { artwork.album(selectedAlbum, ArtworkSize.MEDIUM) }
                Spacer(Modifier.height(16.dp))
                Text(groupName, style = EvannaDesign.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    textAlign = if (studio) TextAlign.Start else TextAlign.Center,
                    modifier = Modifier.semantics { heading() })
                shown?.albumSummary?.displayArtist?.let { Text(it, style = EvannaDesign.typography.titleMedium, color = muted, textAlign = TextAlign.Center) }
                shown?.let { album ->
                    val count = if (album.total == 1) "1 song" else "${album.total} songs"
                    Text(listOfNotNull(album.albumSummary?.year?.toString(), count).joinToString(" · "),
                        style = if (studio) EvannaType.technical else EvannaDesign.typography.bodyMedium,
                        color = muted, modifier = Modifier.padding(bottom = 8.dp))
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onSelect(LibraryContextRequest(filter,null,LibraryContextAction.PLAY_FIRST)) { selectionFailed=!it } },
                        colors = ActionColors(colors.surfaceContainerHighest.copy(alpha = 0.74f), colors.onSurface),
                        enabled = !busy && !loading && (shown?.total ?: 0)>0, modifier = Modifier.weight(1f).heightIn(min=48.dp)) {
                        Icon(Icons.Filled.PlayArrow,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Play album")
                    }
                    OutlinedButton(onClick = { onSelect(LibraryContextRequest(filter,null,LibraryContextAction.SHUFFLE)) { selectionFailed=!it } },
                        colors = ink, enabled = !busy && !loading && (shown?.total ?: 0)>0, modifier = Modifier.weight(1f).heightIn(min=48.dp)) {
                        Icon(PlayerIcons.Shuffle,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Shuffle album")
                    }
                }
                TextButton(onClick = { onPlaylistAlbum(filter) }, enabled = !busy && !loading && (shown?.total ?: 0) > 0,
                    modifier = Modifier.heightIn(min = 48.dp), colors = ink) { Text("Add album to playlist") }
            }
        } else line {
            val title = if (kind != null) groupName else if (searchMode) "Search" else "Library"
            val canSort = (kind == null && view in listOf(LibraryView.ALBUMS, LibraryView.TRACKS)) ||
                kind == LibraryGroupKind.FOLDER
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = EvannaDesign.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (albumHome || (canSort && !searchHome)) Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    fun sortName(sort: LibrarySort) =
                        if (sortScope == LibrarySortScope.ALBUMS && sort == LibrarySort.ARTIST) "Album artist" else sort.label
                    IconButton(onClick = { menuOpen = true }, enabled = albumHome || (selectedSort != null && !sortSaving)) {
                        if (albumHome) Icon(PlayerIcons.LibraryLayout, "Library view: ${albumLayout.label}")
                        else Icon(PlayerIcons.Sort, "Sort: ${selectedSort?.let(::sortName) ?: "loading"}, saved for this view")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (albumHome) {
                            DropdownChoices(AlbumLayout.entries, albumLayout, menuOpen, onSelect = { layout ->
                                albumLayout = layout
                                preferences.edit().putString("album-layout", layout.name).apply()
                                menuOpen = false
                            }) { Text(it.label) }
                            HorizontalDivider()
                            Text("Sort by", style = EvannaDesign.typography.labelMedium, color = muted,
                                modifier = Modifier.padding(start = 14.dp, top = 8.dp))
                        }
                        DropdownChoices(allowedLibrarySorts(sortScope), selectedSort, menuOpen, enabled = selectedSort != null && !sortSaving,
                            onSelect = { sort ->
                                menuOpen = false
                                sortSaving = true
                                personalScope.launch {
                                    try {
                                        if (withContext(NonCancellable + Dispatchers.IO) { personal.saveLibrarySort(sortScope, sort) }) {
                                            selectedSort = sort
                                        } else LocalLibrary.status = "The sort choice could not be saved. Your previous choice was kept."
                                    } finally { sortSaving = false }
                                }
                            }) { sort ->
                            Text(sortName(sort), modifier = Modifier.semantics { contentDescription = "Sort by ${sortName(sort).lowercase()}" })
                        }
                    }
                }
            }
        }
        if (searchMode) line {
            val shape = if (studio) EvannaDesign.shapes.medium else EvannaDesign.shapes.extraLarge
            val colors = EvannaDesign.colorScheme
            TextField(value = filter.search, onValueChange = ::updateSearch,
                placeholder = when {
                    kind != null -> "Search songs in this collection"
                    view == LibraryView.FOLDERS -> "Filter songs in a folder"
                    view == LibraryView.ADDED -> "Search recently added songs"
                    view == LibraryView.TRACKS -> "Search songs by title, artist or album"
                    else -> "Search ${view.label.lowercase()}"
                },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (filter.search.isNotEmpty()) ({
                    IconButton(onClick = { updateSearch("") }, modifier = Modifier.semantics { contentDescription = "Clear search" }) {
                        Icon(Icons.Filled.Clear, contentDescription = null)
                    }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = shape,
                colors = if (studio) fieldColors() else fieldColors(colors.surfaceContainerHighest, colors.surfaceContainerHighest),
                modifier = Modifier.fillMaxWidth().then(if (studio) Modifier.border(1.dp, colors.outlineVariant, shape) else Modifier))
        }
        if (libraryHome || (searchMode && kind == null && !searchHome)) line {
            var collectionsOpen by remember { mutableStateOf(false) }
            val shape = EvannaDesign.shapes.small
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                    .border(1.dp, EvannaDesign.colorScheme.outlineVariant, shape)
                    .clickable(enabled = !busy, role = Role.Button) { collectionsOpen = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (searchMode) "Search in ${view.label.lowercase()}" else view.label,
                        style = EvannaDesign.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, null, tint = muted)
                }
                DropdownMenu(expanded = collectionsOpen, onDismissRequest = { collectionsOpen = false }) {
                    val collections = if (libraryHome) listOf(LibraryView.ALBUMS, LibraryView.ARTISTS, LibraryView.TRACKS, LibraryView.FOLDERS)
                        else LibraryView.entries
                    DropdownChoices(collections, view, collectionsOpen, onSelect = { collection ->
                        collectionsOpen = false
                        selectView(collection)
                    }) { Text(it.label) }
                    if (libraryHome) DropdownMenuItem(text = { Text("Playlists") }, onClick = {
                        collectionsOpen = false
                        onPlaylists()
                    })
                    if (libraryHome) DropdownMenuItem(text = { Text("Manage music folders") }, onClick = {
                        collectionsOpen = false
                        showMusicFolders = true
                    })
                }
            }
        }
        if (searchHome) {
            line {
                Text("Quick access", style = EvannaDesign.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            line { SettingsLink("Favorites", onClick = onFavorites) }
            line { SettingsLink("Recently played", onClick = onRecent) }
            line { SettingsLink("Most played", onClick = onMostPlayed) }
            line {
                Text("Browse", style = EvannaDesign.typography.titleSmall, modifier = Modifier.semantics { heading() })
            }
            for (collection in listOf(LibraryView.GENRES, LibraryView.COMPOSERS, LibraryView.ADDED, LibraryView.FOLDERS)) {
                line(key = collection.name) {
                    SettingsLink(collection.label) {
                        focus.clearFocus()
                        selectView(collection)
                    }
                }
            }
        }
        if ((libraryHome || (searchMode && view == LibraryView.FOLDERS)) && busy && !LocalLibrary.managingFolders) line { Box {
            OutlinedButton(onClick = LocalLibrary::cancelScan, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel scan") }
        } }
        // "All is well" notes are left out to save room; scan progress, results and errors still show.
        if (!LocalLibrary.statusIsIdle) line { Text(LocalLibrary.status, style = EvannaDesign.typography.bodySmall, color = muted) }
        if(view==LibraryView.ADDED && kind==null)line {Text("Sorted by when Evanna first saved the song. Existing songs share the upgrade date.",style=EvannaDesign.typography.bodySmall,color=muted)}
        if (loading && !changingSearchLetter && shown == null) line {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (searchMode && filter.search.isNotBlank()) "Searching…" else "Loading library…",
                    style = EvannaDesign.typography.bodySmall, color = muted)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if(!searchHome && cursor.base>0)line {TextButton(onClick={cursor=LibraryPageCursor()},modifier=Modifier.heightIn(min=48.dp)){Text("Start of list")}}
        // Albums go directly from the filters to the covers. Other lists keep their count.
        shown?.takeIf { it.total > 0 && !searchHome && (searchMode || kind != null || view != LibraryView.ALBUMS) }?.let {
            line { Text("${it.total} $resultLabel",
                style = if (studio) EvannaType.technical else EvannaDesign.typography.titleSmall, color = muted) }
        }
        if (failed == request) line {
            Column {
                Text("The saved library could not be read. Check free storage and try again.")
                OutlinedButton(onClick = { failed = null; retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            }
        }
        if(kind in listOf(LibraryGroupKind.ARTIST,LibraryGroupKind.GENRE,LibraryGroupKind.COMPOSER)) {
            line {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick={onSelect(LibraryContextRequest(filter,null,LibraryContextAction.PLAY_FIRST)){selectionFailed=!it}},
                    enabled=!busy && !loading && (shown?.total ?: 0)>0,modifier=Modifier.heightIn(min=48.dp)){Text("Play all")}
                OutlinedButton(onClick={onSelect(LibraryContextRequest(filter,null,LibraryContextAction.SHUFFLE)){selectionFailed=!it}},
                    enabled=!busy && !loading && (shown?.total ?: 0)>0,modifier=Modifier.heightIn(min=48.dp)){Text("Shuffle")}
            }}
            if(kind==LibraryGroupKind.ARTIST)line {ArtistAlbums(value,search,revision,!busy) {group ->
                open(LibraryGroupKind.ALBUM,group.value,group.value ?: "Unknown album",group.albumArtist)
            }}
        }
        if (searchHome && shown?.roots?.isEmpty() == true) line {
            Text("No music folders yet. Add one in Library Settings to start.", color = muted)
        }
        if (view == LibraryView.FOLDERS && kind == null) line {
            Column {
                TextButton(onClick = addFolder, enabled = !busy) { Text("Add folder") }
                if (shown?.roots?.isNotEmpty() == true) {
                    TextButton(onClick = { LocalLibrary.scan(context) }, enabled = !busy) { Text("Rescan all folders") }
                }
            }
        }
        if (shown != null && !searchHome) {
            if (view == LibraryView.FOLDERS && kind == null) {
                if (search.isNotBlank()) line { Text("Open a folder to see its matching songs.") }
                items(shown.roots, key = { "root:${it.uri}" }, span = { GridItemSpan(maxLineSpan) }) { root ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).heightIn(min = 64.dp).clip(EvannaDesign.shapes.medium)
                            .clickable(enabled = !busy) { open(LibraryGroupKind.FOLDER, root.uri, root.name) }
                            .padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
                            Text(root.name, style = EvannaDesign.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            FolderSourceDetails(root, folderVolumes)
                        }
                        FolderSourceActions(root, enabled = !busy && !loading, onSubfolders = { manageFolder = root }, onRemove = { removeFolder = root })
                    }
                }
            } else if (songsView) {
                itemsIndexed(shown.songs, key = { _, song -> "song:${song.id}" }, span = { _, _ -> GridItemSpan(maxLineSpan) },
                    contentType = { _, _ -> LIST_ROW }) { index, song ->
                    Column(Modifier.fillMaxWidth()) {
                        if (kind == LibraryGroupKind.ALBUM) albumDiscHeading(shown.songs, index, shown.albumSummary)?.let { label ->
                            Text(label, style = EvannaDesign.typography.titleSmall,
                                modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
                        }
                        val albumTrack = kind == LibraryGroupKind.ALBUM
                        val compactRow = searchMode || albumTrack
                        val duration = song.durationUs?.let { com.evanna.player.playbackTime(it / 1000) }
                        LibrarySongRow(song, enabled = !busy && !loading, scope = personalScope,
                            onPlay = {
                                onSelect(LibraryContextRequest(filter, song.id, LibraryContextAction.PLAY_FROM)) { success ->
                                    selectionFailed = !success
                                }
                            },
                            onAdd = onAdd, onPlayNext = onPlayNext, onPlaylistSong = onPlaylistSong,
                            onTrackInfo = { trackInfo = song },
                            onOpenAlbum = song.album?.takeUnless { albumTrack || it.isBlank() }?.let { album ->
                                {
                                    open(LibraryGroupKind.ALBUM, album, album, song.musicTags.albumArtist?.takeIf { it.isNotEmpty() })
                                    songAlbumSearch = ""
                                    focus.clearFocus()
                                }
                            },
                        ) {
                            if (albumTrack) {
                                val number = song.musicTags.trackNumber
                                Text(number?.toString().orEmpty(), color = muted, style = EvannaDesign.typography.bodyMedium,
                                    modifier = Modifier.widthIn(min = 28.dp).semantics {
                                        if (number != null) contentDescription = "Track $number"
                                    })
                            } else {
                                LibraryArtwork("song:${song.id}", revision, Modifier.size(if (searchMode) 44.dp else 52.dp)) { artwork.song(song.id) }
                            }
                            Column(Modifier.weight(1f).padding(start = if (albumTrack) 0.dp else 12.dp)) {
                                Text(song.title, style = EvannaDesign.typography.bodyLarge, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val subtitle = if (albumTrack) song.artist else listOfNotNull(song.artist, song.album).joinToString(" · ")
                                subtitle?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = EvannaDesign.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (!song.available || !compactRow) Text(if (!song.available) "Unavailable · reconnect or rescan the folder" else
                                    "${song.codec ?: "Unknown format"} · ${song.rate?.let { "$it Hz" } ?: "rate unknown"} · ${duration ?: "Duration unknown"}",
                                    style = EvannaDesign.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (compactRow && duration != null) Text(duration,
                                style = if (studio) EvannaType.technical else EvannaDesign.typography.labelSmall, color = muted,
                                maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                        }
                        if (studio) HorizontalDivider(color = EvannaDesign.colorScheme.outlineVariant.copy(alpha = 0.32f))
                    }
                }
            } else if (view == LibraryView.ALBUMS) {
                items(shown.groups, key = { AlbumIdentity(it.value, it.albumArtist).key }, contentType = { LIST_ROW }) { group ->
                    LibraryAlbumCard(group, revision, enabled = !busy && !loading,
                        compact = albumHome && albumLayout == AlbumLayout.COMPACT,
                        list = albumHome && albumLayout == AlbumLayout.LIST) {
                        open(LibraryGroupKind.ALBUM, group.value, group.value ?: "Unknown album", group.albumArtist)
                    }
                }
            } else {
                items(shown.groups, key = { it.value?.let { tag -> "tag:$tag" } ?: "unknown" }, span = { GridItemSpan(maxLineSpan) },
                    contentType = { LIST_ROW }) { group ->
                    val name = group.value ?: view.unknownName()
                    Row(Modifier.fillMaxWidth().clip(EvannaDesign.shapes.medium)
                        .clickable(enabled = !busy && !loading) { open(view.groupKind(), group.value, name) }
                        .padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(EvannaDesign.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center) {
                            Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = EvannaDesign.typography.titleLarge, color = muted)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(name, style = EvannaDesign.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${group.count} songs${if (group.value == null) " · tag missing" else ""}", style = EvannaDesign.typography.bodyMedium, color = muted)
                        }
                    }
                }
            }
            if (shown.total == 0 && !loading) line {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val noFolders = shown.roots.isEmpty()
                    val noMatches = !noFolders && filter.search.isNotBlank()
                    Text(when {
                        noFolders -> "No music folders yet"
                        noMatches -> "No matching $resultLabel"
                        else -> "No $resultLabel here yet"
                    }, style = EvannaDesign.typography.titleMedium)
                    Text(when {
                        noFolders && searchMode -> "Add a music folder in Library Settings to start."
                        noFolders -> "Choose a music folder on your phone or SD card to build the library."
                        noMatches && kind == null -> "Try another search or choose a different collection."
                        noMatches -> "Try another search or clear the query."
                        else -> "New music appears after your folders are scanned."
                    }, style = EvannaDesign.typography.bodyMedium, color = muted)
                    if (noMatches) TextButton(onClick = { updateSearch("") }) { Text("Clear query") }
                }
            }
            if (shown.total == 0 && !loading && shown.roots.isEmpty() && !searchMode && kind == null && view != LibraryView.FOLDERS) line { Box {
                FilledTonalButton(onClick = { showMusicFolders = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manage music folders")
                }
            } }
        }
    }
    }
    // As in the mockup the bar sits 2 dp from the screen edge, 14 dp into the page margin, with the letters 16 dp in.
    if(alphabetic && shown?.total!=0)AlphabeticIndex({rowsTop.intValue},
        Modifier.align(Alignment.TopEnd).offset(x=14.dp).padding(bottom=bottomInset), slim = searchMode) {jump=it}
    }
}
