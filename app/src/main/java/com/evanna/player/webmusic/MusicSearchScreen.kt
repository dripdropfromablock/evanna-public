package com.evanna.player.webmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanna.player.CheckRow
import com.evanna.player.EvannaDesign
import com.evanna.player.PlayerIcons
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsLink
import com.evanna.player.library.LocalLibrary
import com.evanna.player.playback.SearchQueueAction
import com.evanna.player.sources.SourceCatalog
import com.evanna.player.sources.SourceKind
import com.evanna.player.ui.ActivityIndicator
import com.evanna.player.ui.AlertDialog
import com.evanna.player.ui.Button
import com.evanna.player.ui.Icon
import com.evanna.player.ui.IconButton
import com.evanna.player.ui.SearchField
import com.evanna.player.ui.SingleChoiceSegmentedButtonRow
import com.evanna.player.ui.Text
import com.evanna.player.ui.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SearchCategory(val label: String) {
    ALL("All"), ARTISTS("Artists"), ALBUMS("Albums"), TRACKS("Tracks")
}

private sealed interface SearchPage {
    data class Artist(val copies: List<SearchArtist>) : SearchPage
    data class Album(val copies: List<SearchRelease>) : SearchPage
    data class Track(val copies: List<SearchTrack>) : SearchPage
    data object Downloads : SearchPage
    data object Library : SearchPage
    data object Peers : SearchPage
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MusicSearchScreen(
    bottomInset: Dp,
    onQueue: (List<SearchTrack>, SearchQueueAction, (Boolean) -> Unit) -> Unit,
    librarySearch: @Composable () -> Unit
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val local = remember(context) { LibraryMusicSearch(LocalLibrary.database(context)) }
    var providers by remember(context) { mutableStateOf(emptyList<MusicSearchProvider>()) }
    LaunchedEffect(context) {
        providers = withContext(Dispatchers.IO) {
            runCatching {
                val catalog = SourceCatalog(context.applicationContext)
                catalog.store.connections().filter { it.enabled && it.kind == SourceKind.JELLYFIN }
                    .map { JellyfinMusicSearch(catalog, it.id, it.name) }
            }.getOrDefault(emptyList())
        }
    }
    val providerNames = remember(local, providers) { (listOf(local) + providers).associate { it.id to it.label } }
    val download = rememberSearchDownloader()
    val pages = remember { mutableStateListOf<SearchPage>() }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(SearchCategory.ALL) }
    var attempt by remember { mutableIntStateOf(0) }
    var playbackPending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val page = pages.lastOrNull()
    val rootScroll = rememberLazyListState()
    val detailScroll = key(page) { rememberLazyListState() }
    val revision = LocalLibrary.scansFinished
    val input = query.trim()

    fun go(next: SearchPage) {
        focus.clearFocus()
        message = null
        pages.add(next)
    }

    fun back() {
        message = null
        if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex)
    }

    fun queue(tracks: List<SearchTrack>, action: SearchQueueAction) {
        if (playbackPending || tracks.isEmpty()) return
        focus.clearFocus()
        playbackPending = true
        message = if (action == SearchQueueAction.PLAY) "Opening audio…" else null
        onQueue(tracks, action) { accepted ->
            playbackPending = false
            message = when {
                !accepted -> "Could not open that audio. Check the source and try again."
                action == SearchQueueAction.NEXT -> "Playing next"
                action == SearchQueueAction.APPEND -> "Added to queue"
                else -> null
            }
        }
    }

    fun label(provider: String) = providerNames[provider] ?: provider
    val rootProviders = remember(local, input, providers) {
        if (input.length < 2) listOf(local).takeIf { input.isNotEmpty() }.orEmpty() else listOf(local) + providers
    }
    val rootResults = rememberProviderAnswers(input, attempt, revision, rootProviders) { provider, cancel ->
        provider.search(input, cancel)
    }

    when (page) {
        SearchPage.Library -> {
            ScreenBackHandler(true, ::back)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) { ScreenHeader("Library search", ::back) }
                Box(Modifier.weight(1f)) { librarySearch() }
            }
            return
        }
        SearchPage.Downloads -> {
            Box(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
                SearchDownloadsScreen(::back, bottomInset)
            }
            return
        }
        else -> Unit
    }

    val detailProviders = remember(local, page, providers) {
        when (page) {
            is SearchPage.Artist -> listOf(local) + providers.filter { provider -> page.copies.any { it.provider == provider.id } }
            is SearchPage.Album -> listOf(local) + providers.filter { provider -> page.copies.any { it.provider == provider.id } }
            else -> emptyList()
        }
    }
    val detail = rememberProviderAnswers(page, attempt, revision, detailProviders) { provider, cancel ->
        when (page) {
            is SearchPage.Artist -> {
                val artist = page.copies.firstOrNull { it.provider == provider.id }
                    ?: page.copies.first().copy(provider = provider.id)
                provider.artist(artist, cancel)
            }
            is SearchPage.Album -> {
                val release = page.copies.firstOrNull { it.provider == provider.id }
                    ?: local.matchingRelease(page.copies.first())
                SourceMatches(tracks = release?.let { provider.tracks(it, cancel) }.orEmpty())
            }
            else -> SourceMatches()
        }
    }

    fun select(copies: List<SearchTrack>) {
        val preferred = copies.first()
        if (preferred.libraryId != null || copies.size == 1 && preferred.playable) queue(listOf(preferred), SearchQueueAction.PLAY)
        else go(SearchPage.Track(copies))
    }

    ScreenBackHandler(page != null, ::back)
    LaunchedEffect(input, category) { rootScroll.scrollToItem(0) }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        state = if (page == null) rootScroll else detailScroll,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (page == null) {
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Search", modifier = Modifier.weight(1f).semantics { heading() },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, style = EvannaDesign.typography.titleLarge)
                    TextButton(onClick = { go(SearchPage.Peers) }) { Text("Peer-to-peer") }
                    TextButton(onClick = { go(SearchPage.Downloads) }) { Text("Downloads") }
                }
                SearchField(query, { query = it.take(180); message = null }, "Artist, album or track",
                    modifier = Modifier.fillMaxWidth(), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            }
            if (input.isEmpty()) {
                item { SettingsLink("Search library", icon = PlayerIcons.Album) { go(SearchPage.Library) } }
            } else {
                item {
                    SingleChoiceSegmentedButtonRow(SearchCategory.entries.map { it.label }, category.ordinal,
                        { category = SearchCategory.entries[it] }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                message?.let { item { SearchNote(it) } }
                val matched = rootProviders.mapNotNull { rootResults[it.id]?.matches }
                val artists = matched.flatMap { it.artists }.groupBy { normalizedTag(it.name) }.values
                val albums = matched.flatMap { it.releases }.groupBy { normalizedTag(it.title) to normalizedTag(it.artist) }.values
                val tracks = groupSearchRecordings(matched.flatMap { it.tracks })
                if (category == SearchCategory.ALL || category == SearchCategory.ARTISTS) {
                    val ranked = artists.sortedBy { searchRank(it.first().name, input) }
                    artists(ranked, limit(category), ::label) { go(SearchPage.Artist(it)) }
                    showMore(ranked.size, category, SearchCategory.ARTISTS) { category = it }
                }
                if (category == SearchCategory.ALL || category == SearchCategory.ALBUMS) {
                    val ranked = albums.sortedBy { searchRank(it.first().title, input) }
                    albums(ranked, limit(category), ::label) { go(SearchPage.Album(it)) }
                    showMore(ranked.size, category, SearchCategory.ALBUMS) { category = it }
                }
                if (category == SearchCategory.ALL || category == SearchCategory.TRACKS) {
                    val ranked = tracks.sortedBy { searchRank(it.first().title, input) }
                    if (ranked.isNotEmpty()) item { SearchHeading("Tracks") }
                    items(ranked.take(limit(category)), key = { trackKey(it.first()) }) { copies ->
                        SearchTrackRow(copies, ::label, { select(copies) }, { go(SearchPage.Track(copies)) })
                    }
                    showMore(ranked.size, category, SearchCategory.TRACKS) { category = it }
                }
                status(rootProviders, rootResults) { attempt++ }
                val visible = when (category) {
                    SearchCategory.ALL -> artists.size + albums.size + tracks.size
                    SearchCategory.ARTISTS -> artists.size
                    SearchCategory.ALBUMS -> albums.size
                    SearchCategory.TRACKS -> tracks.size
                }
                if (visible == 0 && rootProviders.all { it.id in rootResults }) item { SearchNote("No matches for “$input”.") }
            }
        } else {
            val title = when (page) {
                is SearchPage.Artist -> page.copies.first().name
                is SearchPage.Album -> page.copies.first().title
                is SearchPage.Track -> page.copies.first().title
                SearchPage.Peers -> "Peer-to-peer"
                else -> error("Search page already handled")
            }
            item { ScreenHeader(title, ::back) }
            message?.let { item { SearchNote(it) } }
            val matched = detailProviders.mapNotNull { detail[it.id]?.matches }
            when (page) {
                is SearchPage.Artist -> {
                    val releases = matched.flatMap { it.releases }.groupBy { normalizedTag(it.title) to normalizedTag(it.artist) }.values
                    albums(releases, Int.MAX_VALUE, ::label) { go(SearchPage.Album(it)) }
                    val tracks = groupSearchRecordings(matched.flatMap { it.tracks })
                    if (tracks.isNotEmpty()) item { SearchHeading("Tracks") }
                    items(tracks, key = { trackKey(it.first()) }) { copies ->
                        SearchTrackRow(copies, ::label, { select(copies) }, { go(SearchPage.Track(copies)) })
                    }
                    status(detailProviders, detail) { attempt++ }
                }
                is SearchPage.Album -> {
                    val tracks = groupSearchRecordings(matched.flatMap { it.tracks })
                        .sortedWith(compareBy({ it.first().disc ?: 1 }, { it.first().number ?: Int.MAX_VALUE }))
                    val playable = tracks.mapNotNull { copies -> copies.firstOrNull { it.playable && !it.preview } }
                    val album = page.copies.first()
                    val albumLabel = listOf(album.artist, title).filter(String::isNotBlank).joinToString(" · ")
                    item {
                        AlbumSearchActions(album.artist, tracks, playable.size, playbackPending, albumLabel, ::label,
                            { queue(playable, SearchQueueAction.PLAY) }, { queue(playable, SearchQueueAction.APPEND) },
                            { download(it, false) })
                    }
                    val discs = tracks.groupBy { it.first().disc ?: 1 }
                    for ((disc, rows) in discs) {
                        if (discs.size > 1) item { SearchHeading("Disc $disc") }
                        items(rows, key = { trackKey(it.first()) }) { copies ->
                            SearchTrackRow(copies, ::label, { select(copies) }, { go(SearchPage.Track(copies)) }, showNumber = true)
                        }
                    }
                    status(detailProviders, detail) { attempt++ }
                }
                is SearchPage.Track -> item {
                    TrackSearchActions(page.copies, playbackPending, ::label, ::queue, download) {
                        go(SearchPage.Album(listOf(it)))
                    }
                }
                SearchPage.Peers -> item { SoulseekSearch(query = input) }
                else -> Unit
            }
        }
    }
}

@Composable
private fun rememberProviderAnswers(
    request: Any?, attempt: Int, revision: Int, providers: List<MusicSearchProvider>,
    load: (MusicSearchProvider, com.evanna.player.network.CancelScope) -> SourceMatches
): Map<String, ProviderAnswer> {
    val remote = remember(request, attempt, providers) { mutableStateMapOf<String, ProviderAnswer>() }
    var local by remember(request, attempt, revision) { mutableStateOf<ProviderAnswer?>(null) }
    LaunchedEffect(request, attempt, providers) {
        searchEachProvider(providers.filter { it.id != LIBRARY_SEARCH_ID }, debounceMs = if (request is String) 350 else 0, load = load)
            .collect { remote[it.provider] = it }
    }
    LaunchedEffect(request, attempt, revision, providers) {
        searchEachProvider(providers.filter { it.id == LIBRARY_SEARCH_ID }, load = load).collect { local = it }
    }
    return if (local == null) remote.toMap() else remote.toMap() + (LIBRARY_SEARCH_ID to checkNotNull(local))
}

private fun limit(category: SearchCategory) = if (category == SearchCategory.ALL) 5 else Int.MAX_VALUE

private fun searchRank(name: String, query: String): Int {
    val key = normalizedTag(name).orEmpty()
    val terms = normalizedTag(query).orEmpty()
    return when {
        key == terms -> 0
        key.startsWith(terms) -> 1
        terms in key -> 2
        else -> 3
    }
}

private fun LazyListScope.showMore(size: Int, category: SearchCategory, section: SearchCategory, select: (SearchCategory) -> Unit) {
    if (size > limit(category)) item { TextButton(onClick = { select(section) }) { Text("All ${section.label.lowercase()}") } }
}

private fun LazyListScope.status(providers: List<MusicSearchProvider>, answers: Map<String, ProviderAnswer>, retry: () -> Unit) {
    if (providers.any { it.id !in answers }) item {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActivityIndicator(Modifier.size(20.dp))
            SearchNote("Checking sources…")
        }
    }
    val problems = answers.values.mapNotNull { it.problem }
    if (problems.isNotEmpty()) item {
        Column {
            problems.forEach { SearchNote(it) }
            TextButton(onClick = retry) { Text("Retry search") }
        }
    }
}

private fun LazyListScope.artists(artists: Collection<List<SearchArtist>>, limit: Int, label: (String) -> String,
    open: (List<SearchArtist>) -> Unit) {
    if (artists.isEmpty()) return
    item { SearchHeading("Artists") }
    items(artists.take(limit), key = { "artist:${it.first().provider}:${it.first().id}" }) { copies ->
        SettingsLink(copies.first().name, copies.map { label(it.provider) }.distinct().joinToString(" · "),
            icon = PlayerIcons.Headphones) { open(copies.distinctBy { it.provider }) }
    }
}

private fun LazyListScope.albums(albums: Collection<List<SearchRelease>>, limit: Int, label: (String) -> String,
    open: (List<SearchRelease>) -> Unit) {
    if (albums.isEmpty()) return
    item { SearchHeading("Albums") }
    items(albums.take(limit), key = { "release:${it.first().provider}:${it.first().id}" }) { copies ->
        val album = copies.first()
        val sources = copies.map { label(it.provider) }.distinct()
        val subtitle = (listOf(album.artist).filter(String::isNotBlank) + sources).joinToString(" · ")
        SettingsLink(album.title, subtitle, icon = PlayerIcons.Album) { open(copies.distinctBy { it.provider }) }
    }
}

@Composable
private fun SearchHeading(text: String) {
    Text(text, style = EvannaDesign.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun SearchNote(text: String) {
    Text(text, style = EvannaDesign.typography.bodyMedium, color = EvannaDesign.colorScheme.onSurfaceVariant)
}

private fun trackKey(track: SearchTrack) = "track:${track.provider}:${track.id}"

@Composable
private fun SearchTrackRow(copies: List<SearchTrack>, label: (String) -> String, open: () -> Unit, actions: () -> Unit,
    showNumber: Boolean = false) {
    val track = copies.first()
    val sources = copies.map { label(it.provider) }.distinct().joinToString(" · ")
    val subtitle = listOfNotNull(track.artist.ifBlank { null }, track.album).joinToString(" · ")
    val availability = when {
        track.preview -> "Preview"
        copies.any { it.playable } -> null
        copies.any { it.downloads.isNotEmpty() } -> "Download only"
        else -> "Unavailable"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = open).padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showNumber && track.number != null) SearchNote(track.number.toString())
            else Icon(PlayerIcons.SongFile, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = EvannaDesign.colorScheme.onSurfaceVariant)
            Column {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) SearchNote(subtitle)
                SearchNote(listOfNotNull(sources, availability).joinToString(" · "))
            }
        }
        IconButton(onClick = actions) { Icon(PlayerIcons.More, "Actions for ${track.title}") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumSearchActions(artist: String, tracks: List<List<SearchTrack>>, playable: Int, busy: Boolean,
    album: String, label: (String) -> String, play: () -> Unit, queue: () -> Unit, download: (List<SearchDownload>) -> Unit) {
    var confirmingQueue by remember { mutableStateOf(false) }
    var pickingDownload by remember { mutableStateOf(false) }
    val total = tracks.size
    val downloadable = tracks.count { copies -> copies.any { it.downloads.isNotEmpty() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (artist.isNotBlank()) SearchNote(artist)
        if (total > 0) {
            val playback = "$playable of $total tracks playable"
            SearchNote(if (downloadable > 0) "$playback · $downloadable downloadable" else playback)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playable > 0) {
                Button(onClick = play, enabled = !busy) { Text("Play Album") }
                TextButton(onClick = { if (playable == total) queue() else confirmingQueue = true },
                    enabled = !busy) { Text("Queue Album") }
            }
            if (downloadable > 0) TextButton(onClick = { pickingDownload = true }) { Text("Download Album") }
        }
    }
    if (confirmingQueue) AlertDialog(onDismissRequest = { confirmingQueue = false },
        title = { Text("Queue $playable of $total tracks?") },
        text = { Text("Evanna will queue the playable tracks shown on this album.") },
        confirmButton = { TextButton(onClick = { confirmingQueue = false; queue() }) { Text("Queue tracks") } },
        dismissButton = { TextButton(onClick = { confirmingQueue = false }) { Text("Cancel") } })
    if (pickingDownload) DownloadSourcePicker(tracks, label, album = album, onClose = { pickingDownload = false }, onDownload = download)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackSearchActions(
    copies: List<SearchTrack>, busy: Boolean, label: (String) -> String,
    queue: (List<SearchTrack>, SearchQueueAction) -> Unit, download: (List<SearchDownload>, Boolean) -> Unit,
    openAlbum: (SearchRelease) -> Unit
) {
    var selected by remember(copies) { mutableIntStateOf(0) }
    var downloadAndPlay by remember { mutableStateOf<Boolean?>(null) }
    val track = copies[selected]
    val downloadable = copies.any { it.downloads.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (track.artist.isNotBlank()) SearchNote(track.artist)
        if (copies.size > 1) SearchHeading("Choose a source")
        copies.forEachIndexed { index, copy ->
            val details = listOfNotNull(copy.format, copy.quality, "Preview".takeIf { copy.preview },
                "Download only".takeIf { !copy.playable && copy.downloads.isNotEmpty() }).joinToString(" · ")
            if (copies.size > 1) CheckRow(label(copy.provider), selected == index, note = details.ifBlank { null }) { selected = index }
            else SearchNote(listOf(label(copy.provider), details).filter(String::isNotBlank).joinToString(" · "))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (track.playable) {
                Button(onClick = { queue(listOf(track), SearchQueueAction.PLAY) }, enabled = !busy) {
                    Text(if (track.preview) "Play preview" else "Play")
                }
                TextButton(onClick = { queue(listOf(track), SearchQueueAction.NEXT) }, enabled = !busy) { Text("Play Next") }
                TextButton(onClick = { queue(listOf(track), SearchQueueAction.APPEND) }, enabled = !busy) { Text("Add to Queue") }
            } else if (downloadable) {
                Button(onClick = { downloadAndPlay = true }) { Text("Download then play") }
            }
            if (downloadable) TextButton(onClick = { downloadAndPlay = false }) { Text("Download") }
        }
        track.release?.let { SettingsLink(it.title, "Open album", icon = PlayerIcons.Album) { openAlbum(it) } }
    }
    downloadAndPlay?.let { play ->
        DownloadSourcePicker(listOf(copies), label, preferred = track.provider, onClose = { downloadAndPlay = null }) { download(it, play) }
    }
}

@Composable
private fun DownloadSourcePicker(
    recordings: List<List<SearchTrack>>, label: (String) -> String, album: String? = null, preferred: String? = null,
    onClose: () -> Unit, onDownload: (List<SearchDownload>) -> Unit,
) {
    val offers = remember(recordings) { searchDownloadOffers(recordings) }
    if (offers.isEmpty()) return
    var selection by remember { mutableStateOf<SearchDownloadChoice?>(null) }
    val chosen = selection?.takeIf { choice -> offers.any { it.choice == choice } }
        ?: (offers.firstOrNull { it.choice.provider == preferred } ?: offers.first()).choice
    var fallback by remember(chosen) { mutableStateOf(false) }
    val alternatives = recordings.any { copies ->
        copies.any { it.provider == chosen.provider && it.downloads.any { file -> file.format == chosen.format } } &&
            copies.any { it.provider != chosen.provider && it.downloads.any { file -> file.format == chosen.format } }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Download source") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            for ((choice, count) in offers) {
                val format = choice.format.uppercase()
                val detail = if (album == null) format else "$format · $count of ${recordings.size} tracks"
                CheckRow(label(choice.provider), chosen == choice, note = detail) { selection = choice }
            }
            if (alternatives) CheckRow("Try another source", fallback,
                note = "Only matching recordings in ${chosen.format.uppercase()}") { fallback = !fallback }
        }
    }, confirmButton = { TextButton(onClick = {
        onClose()
        onDownload(planSearchDownloads(recordings, chosen, fallback && alternatives, label, album))
    }) { Text("Add to Downloads") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
internal fun TryOnSites(recording: CatalogRecording) {
    val context = LocalContext.current
    var sites by remember { mutableStateOf(emptyList<WebSiteRecipe>()) }
    LaunchedEffect(context) {
        sites = withContext(Dispatchers.IO) { runCatching { webSiteListStore(context).current()?.sites.orEmpty() }.getOrDefault(emptyList()) }
    }
    Column {
        SearchHeading(recording.title)
        SearchNote(recording.details)
        CatalogSite.entries.forEach { site ->
            SettingsLink(site.label, "Open source") { openWebMusicSite(context, site.searchUrl(recording)) }
        }
        websiteShortcuts.forEach { (name, address) ->
            SettingsLink(name, "Open source") { openWebMusicSite(context, address) }
        }
        sites.forEach { site ->
            SettingsLink(site.name, "Open source") { openWebMusicSite(context, site.searchUrl(recording) ?: site.address) }
        }
    }
}


