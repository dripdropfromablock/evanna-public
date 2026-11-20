package com.evanna.player.webmusic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsLink
import com.evanna.player.network.CancelScope
import com.evanna.player.ui.ActivityIndicator
import com.evanna.player.ui.Button
import com.evanna.player.ui.Text
import com.evanna.player.ui.TextButton
import com.evanna.player.ui.TextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MusicCatalogScreen(onBack: () -> Unit, bottomInset: Dp, initialQuery: String = "") {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var active by remember { mutableStateOf<CancelScope?>(null) }
    var result by remember { mutableStateOf<CatalogSearchResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CatalogRecording?>(null) }

    fun cancelSearch() {
        active?.cancel()
        active = null
    }

    DisposableEffect(Unit) { onDispose { active?.cancel() } }

    fun search() {
        val input = query.trim()
        if (input.length < 2 || active != null) return
        focus.clearFocus()
        val cancel = CancelScope()
        active = cancel
        result = null
        message = null
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { MusicCatalog.search(input, cancel) }
                if (active === cancel) result = response
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unavailable: CatalogUnavailable) {
                if (active === cancel) message = unavailable.message
            } catch (_: Exception) {
                if (active === cancel) message = "Could not load the catalog. Check your connection and try again."
            } finally {
                if (active === cancel) active = null
            }
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotEmpty()) search()
    }

    fun back() {
        if (selected != null) selected = null else {
            cancelSearch()
            onBack()
        }
    }

    ScreenBackHandler(true) { back() }
    val recording = selected
    val response = result
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(if (recording == null) "Find music" else "Try on", ::back) }
        if (recording == null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(query, { value ->
                        query = value.take(180)
                        cancelSearch()
                        result = null
                        message = null
                    }, modifier = Modifier.weight(1f), placeholder = "Song or artist",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }))
                    Button(onClick = ::search, enabled = active == null && query.trim().length >= 2) { Text("Search") }
                }
            }
            if (active != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ActivityIndicator(Modifier.fillMaxWidth()) }
                    TextButton(onClick = ::cancelSearch) { Text("Cancel") }
                }
            }
            if (response == null && active == null && message == null) item {
                Text("MusicBrainz search with Apple’s catalog as a fallback.", style = EvannaDesign.typography.bodyMedium)
            }
            message?.let { copy -> item { Text(copy, style = EvannaDesign.typography.bodyMedium) } }
            if (response != null) {
                item { Text(response.provider, style = EvannaDesign.typography.bodyMedium) }
                if (response.recordings.isEmpty()) item { Text("No recordings found. Try the title and artist.") }
                items(response.recordings, key = { it.id }) { entry ->
                    SettingsLink(entry.title, entry.details.ifBlank { null }) { selected = entry }
                }
            }
        } else {
            item { TryOnSites(recording) }
        }
    }
}
