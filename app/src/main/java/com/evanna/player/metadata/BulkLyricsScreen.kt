package com.evanna.player.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.ActionRow
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsCell
import com.evanna.player.SettingsLink
import com.evanna.player.SettingsSection
import com.evanna.player.library.BulkLyricsOutcome
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.LrcLibBusy
import com.evanna.player.library.LyricsFiles
import com.evanna.player.library.lookupMissingLyrics
import com.evanna.player.library.lrcLibClient
import com.evanna.player.ui.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BulkLyricsScreen(bottomInset: Dp, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val jobs = rememberCoroutineScope()
    val list = rememberLazyListState()
    var sources by remember { mutableStateOf<List<MusicTagSource>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var scope by rememberSaveable { mutableStateOf<TagScope?>(null) }
    var pickingScope by rememberSaveable { mutableStateOf(false) }
    var scopeKind by rememberSaveable { mutableStateOf(TagScopeKind.ALBUM) }
    var search by rememberSaveable { mutableStateOf("") }
    var batch by remember { mutableStateOf(emptyList<MusicTagSource>()) }
    var counts by remember { mutableStateOf(emptyMap<BulkLyricsOutcome, Int>()) }
    var checked by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(false) }
    var stopRequested by remember { mutableStateOf(false) }
    var retryAfter by remember { mutableStateOf<Long?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(app, LocalLibrary.scansFinished, refresh) {
        loadFailed = false
        try {
            sources = withContext(Dispatchers.IO) { LocalLibrary.database(app).musicTagSources() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    LaunchedEffect(pickingScope) { list.scrollToItem(0) }
    LaunchedEffect(retryAfter) {
        retryAfter?.let {
            delay(it)
            retryAfter = null
        }
    }

    val selected = remember(sources, scope) { sources.orEmpty().filter { scope?.includes(it) != false } }
    val label = scope?.let { group -> selected.firstOrNull()?.let { scopeLabel(group, it) } ?: group.kind.label } ?: "All music"
    val resumable = checked < batch.size
    fun back() {
        when {
            pickingScope -> pickingScope = false
            active -> stopRequested = true
            else -> onBack()
        }
    }
    ScreenBackHandler(pickingScope || active, ::back)

    fun run() {
        if (active || retryAfter != null) return
        if (!resumable) {
            batch = selected
            counts = emptyMap()
            checked = 0
        }
        if (batch.isEmpty()) return
        active = true
        failed = false
        stopRequested = false
        jobs.launch {
            try {
                val client = withContext(Dispatchers.IO) {
                    lrcLibClient(app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty())
                }
                val folders = LyricsFiles.FolderIndex()
                while (checked < batch.size && !stopRequested) {
                    val result = lookupMissingLyrics(app, batch[checked], client, folders)
                    counts = counts + (result to ((counts[result] ?: 0) + 1))
                    checked++
                }
            } catch (busy: LrcLibBusy) {
                retryAfter = busy.retryAfterMs
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                active = false
            }
        }
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(if (pickingScope) "Choose tracks" else "Find missing lyrics", ::back) }
        if (pickingScope) {
            tagScopePicker(sources.orEmpty(), scopeKind, search, { scopeKind = it; search = "" }, { search = it }) {
                if (scope != it) {
                    scope = it
                    batch = emptyList()
                    counts = emptyMap()
                    checked = 0
                    failed = false
                }
                pickingScope = false
            }
        } else {
            item {
                SettingsSection(footer = "Evanna checks for existing lyrics first. For tracks with missing lyrics, " +
                    "it sends the title, artist, album, and length to LRCLIB.") {
                    if (loadFailed) {
                        row { ActionRow("Retry loading music") { refresh++ } }
                    } else if (sources == null) {
                        row { SettingsCell { Text("Loading music folders…") } }
                    } else {
                        if (!active) row { SettingsLink("Scope", value = label) { pickingScope = true } }
                        val status = when {
                            active -> "Checking ${checked + 1} of ${batch.size}"
                            retryAfter != null -> "LRCLIB is busy. Resume when it’s ready."
                            failed -> "Lookup paused. Resume to try again."
                            resumable -> "Paused after $checked of ${batch.size} tracks"
                            batch.isNotEmpty() -> "Checked all ${batch.size} tracks"
                            selected.isEmpty() -> "No tracks in this scope"
                            else -> "${selected.size} tracks to check"
                        }
                        row { SettingsCell { Text(status) } }
                        if (active) {
                            batch.getOrNull(checked)?.let { song -> row { SettingsCell { Text(song.title) } } }
                            row { ActionRow(if (stopRequested) "Stopping…" else "Stop after this track", enabled = !stopRequested) {
                                stopRequested = true
                            } }
                        } else {
                            val action = when {
                                resumable -> "Resume lookup"
                                batch.isNotEmpty() -> "Check again"
                                else -> "Look up missing lyrics"
                            }
                            row { ActionRow(action, enabled = retryAfter == null && (resumable || selected.isNotEmpty()), onClick = ::run) }
                        }
                    }
                }
            }
            if (counts.isNotEmpty()) item {
                SettingsSection("Results") {
                    for (outcome in BulkLyricsOutcome.entries) {
                        counts[outcome]?.let { count -> row(outcome) { SettingsCell { Text("${outcome.label} · $count") } } }
                    }
                }
            }
        }
    }
}
