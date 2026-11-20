package com.evanna.player.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.ActionRow
import com.evanna.player.CheckRow
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsCell
import com.evanna.player.SettingsLink
import com.evanna.player.SettingsSection
import com.evanna.player.SwitchRow
import com.evanna.player.network.CancelScope
import com.evanna.player.ui.OutlinedTextField
import com.evanna.player.ui.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val DISCOGS_RESULT_AGE_MS = 30 * 60 * 1000L

internal class OnlineTagLookupState(query: String, byTrack: Boolean = false) {
    var provider by mutableStateOf(OnlineTagProvider.MUSICBRAINZ)
    var query by mutableStateOf(query)
    var token by mutableStateOf("")
    var market by mutableStateOf(defaultItunesMarket)
    var byTrack by mutableStateOf(byTrack)
    private val resultsByProvider = mutableStateMapOf<OnlineTagProvider, List<OnlineTagResult>>()
    private var discogsFetchedAt = 0L
    var results: List<OnlineTagResult>
        get() {
            if (provider == OnlineTagProvider.DISCOGS && discogsRemainingMs <= 0) return emptyList()
            return resultsByProvider[provider].orEmpty()
        }
        set(value) {
            if (provider == OnlineTagProvider.DISCOGS) discogsFetchedAt = System.nanoTime()
            resultsByProvider[provider] = value
        }
    val discogsRemainingMs: Long
        get() = DISCOGS_RESULT_AGE_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - discogsFetchedAt)
    var match by mutableStateOf<OnlineTagMatch?>(null)
    var fields by mutableStateOf(emptySet<MusicTagField>())
    var fillMissing by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
}

@Composable
internal fun OnlineTagLookup(
    state: OnlineTagLookupState,
    selected: List<TagAuditEntry>,
    bottomInset: Dp,
    onBack: () -> Unit,
    onReview: (List<TagFilePreview>, TagOrigin) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    var operation by remember { mutableStateOf<CancelScope?>(null) }
    val match = state.match
    val suggestions = remember(selected, match) { match?.let { onlineTagSuggestions(selected, it) }.orEmpty() }
    val suggestedFields = remember(suggestions) { suggestions.flatMap { it.second.keys }.toSet() }
    val preview = remember(suggestions, state.fields, state.fillMissing) {
        previewOnlineTagEdits(suggestions, state.fields, state.fillMissing)
    }

    fun runLookup(action: suspend (CancelScope) -> Unit) {
        if (operation != null) return
        val cancel = CancelScope()
        val provider = state.provider
        operation = cancel
        state.message = null
        scope.launch {
            try { action(cancel) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!cancel.cancelled) state.message = (failure as? OnlineTagLookupError)?.message
                    ?: "Could not reach ${provider.label}. Try again."
            } finally { if (operation === cancel) operation = null }
        }
    }

    fun back() {
        when {
            operation != null -> operation?.cancel()
            state.match != null -> { state.match = null; state.message = null }
            else -> { state.token = ""; onBack() }
        }
    }
    fun openSource(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure { state.message = "Could not open the source page." }
    }

    ScreenBackHandler(match != null || operation != null, ::back)
    DisposableEffect(Unit) {
        onDispose { operation?.cancel() }
    }
    LaunchedEffect(match) { list.scrollToItem(0) }
    LaunchedEffect(state.provider, state.results, match) {
        if (state.provider == OnlineTagProvider.DISCOGS && state.results.isNotEmpty()) {
            delay(state.discogsRemainingMs.coerceAtLeast(0))
            state.results = emptyList()
            state.match = null
            state.message = "Discogs results expired. Search again to refresh them."
        }
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            val title = when {
                match == null -> "Online tag lookup"
                match.artistEntity -> "Artist details"
                else -> "Release details"
            }
            ScreenHeader(title, ::back)
        }
        state.message?.let { message -> item { SettingsSection { row { SettingsCell { Text(message) } } } } }
        if (match == null) {
            item {
                SettingsSection("Source") {
                    for (provider in OnlineTagProvider.entries) row(provider) {
                        CheckRow(provider.label, selected = state.provider == provider) {
                            if (operation == null && state.provider != provider) {
                                state.provider = provider
                                state.token = ""
                                state.message = null
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(state.query, { state.query = it.take(240) }, when (state.provider) {
                    OnlineTagProvider.DISCOGS -> "Album, artist, or release link"
                    OnlineTagProvider.APPLE -> "Song, album, or artist"
                    OnlineTagProvider.WIKIDATA -> "Album, artist, or Wikidata item"
                    OnlineTagProvider.MUSICBRAINZ -> if (state.byTrack) "Track title" else "Album, artist, barcode, or release link"
                }, modifier = Modifier.fillMaxWidth(), enabled = operation == null)
            }
            if (state.provider == OnlineTagProvider.MUSICBRAINZ) item {
                SettingsSection { row {
                    SwitchRow("Search by track title", checked = state.byTrack, enabled = operation == null) {
                        state.byTrack = it
                    }
                } }
            }
            if (state.provider == OnlineTagProvider.APPLE) item {
                OutlinedTextField(state.market, { state.market = it.filter(Char::isLetter).take(2).uppercase(Locale.ROOT) },
                    "Storefront country code", modifier = Modifier.fillMaxWidth(), enabled = operation == null)
            }
            if (state.provider == OnlineTagProvider.DISCOGS) item {
                OutlinedTextField(state.token, { state.token = it.take(128) }, "Discogs personal access token",
                    modifier = Modifier.fillMaxWidth(), enabled = operation == null, secret = true)
            }
            item {
                SettingsSection(footer = if (state.provider == OnlineTagProvider.DISCOGS)
                    "This application uses Discogs’ API but is not affiliated with, sponsored or endorsed by Discogs. " +
                        "‘Discogs’ is a trademark of Zink Media, LLC." else null) {
                    if (operation != null) row {
                        ActionRow("Cancel lookup") { operation?.cancel() }
                    } else row {
                        ActionRow("Search ${state.provider.label}", enabled = state.query.trim().length >= 2) {
                            val provider = state.provider
                            val query = state.query.trim()
                            val token = state.token.trim()
                            val market = state.market
                            val byTrack = state.byTrack
                            state.results = emptyList()
                            runLookup { cancel ->
                                val results = withContext(Dispatchers.IO) {
                                    OnlineMusicTags.search(provider, query, token, market, byTrack, cancel)
                                }
                                if (!cancel.cancelled) {
                                    state.results = results
                                    if (results.isEmpty()) state.message = "No matches found. Try an album name or release ID."
                                }
                            }
                        }
                    }
                }
            }
            items(state.results.distinctBy { it.origin.id }, key = { it.origin.id }) { result ->
                SettingsSection {
                    row { SettingsLink(result.title, subtitle = result.detail.ifBlank { null }) {
                        val token = state.token.trim()
                        runLookup { cancel ->
                            val release = withContext(Dispatchers.IO) { OnlineMusicTags.details(result, token, cancel) }
                            if (!cancel.cancelled) {
                                state.match = release
                                state.fields = onlineTagSuggestions(selected, release).flatMap { it.second.keys }.toSet()
                            }
                        }
                    } }
                    if (result.origin.provider == OnlineTagProvider.DISCOGS) row {
                        ActionRow("Data provided by Discogs") { openSource(result.origin.provider.page(result.origin.id)) }
                    }
                }
            }
        } else {
            item {
                val provider = match.origin.provider
                SettingsSection(match.tags[MusicTagField.ALBUM] ?: match.artistNames.firstOrNull() ?: provider.label,
                    footer = if (provider.canSave) match.detail.ifBlank { null } else "Comparison only") {
                    for ((field, value) in match.tags) row(field) { SettingsCell { Text("${field.label} · $value") } }
                    if (!provider.canSave && match.detail.isNotBlank()) row { SettingsCell { Text(match.detail) } }
                    row { ActionRow(if (provider == OnlineTagProvider.DISCOGS) "Data provided by Discogs" else "View on ${provider.label}") {
                        openSource(match.reference)
                    } }
                }
            }
            if (match.origin.provider.canSave) item {
                SettingsSection("Fields to review") {
                    if (suggestions.isEmpty()) row { SettingsCell { Text("No confident matches among the selected tracks.") } }
                    if (suggestions.isNotEmpty()) {
                        row { SettingsCell { Text("${suggestions.size} of ${selected.size} selected tracks matched") } }
                        row { CheckRow("Fill missing only", selected = state.fillMissing) { state.fillMissing = true } }
                        row { CheckRow("Replace existing", selected = !state.fillMissing) { state.fillMissing = false } }
                        for (field in MusicTagField.entries.filter { it in suggestedFields }) row(field) {
                            SwitchRow(field.label, checked = field in state.fields) { checked ->
                                state.fields = if (checked) state.fields + field else state.fields - field
                            }
                        }
                        row { ActionRow("Review changes for ${preview.size} tracks", enabled = preview.isNotEmpty()) {
                            onReview(preview, match.origin)
                        } }
                    }
                }
            }
            if (match.origin.provider == OnlineTagProvider.APPLE) item {
                SettingsSection("Track tags") {
                    for ((field, value) in match.tracks.singleOrNull()?.tags.orEmpty()) row(field) {
                        SettingsCell { Text("${field.label} · $value") }
                    }
                }
            }
            items(match.tracks) { track ->
                val tags = track.tags
                val position = track.position ?: tags[MusicTagField.TRACK_NUMBER]?.let { number ->
                    tags[MusicTagField.DISC_NUMBER]?.let { "$it-$number" } ?: number
                }
                val duration = track.durationMs?.div(1000)?.let { seconds ->
                    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                }
                SettingsSection {
                    row { SettingsCell {
                        Text(listOfNotNull(position, tags[MusicTagField.TITLE]).joinToString(" · "))
                        val detail = listOfNotNull(tags[MusicTagField.ARTIST], duration).joinToString(" · ")
                        if (detail.isNotBlank()) Text(detail)
                    } }
                    if (match.origin.provider == OnlineTagProvider.DISCOGS) row {
                        ActionRow("Data provided by Discogs") { openSource(match.reference) }
                    }
                }
            }
        }
    }
}
