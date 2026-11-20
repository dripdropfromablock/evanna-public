package com.evanna.player.webmusic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsLink
import com.evanna.player.network.CancelScope
import com.evanna.player.ui.Button
import com.evanna.player.ui.OutlinedTextField
import com.evanna.player.ui.Text
import com.evanna.player.ui.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

internal val websiteShortcuts = listOf(
    "Monochrome" to "https://monochrome.st/".toHttpUrl(),
)

@Composable
internal fun WebSiteListScreen(onBack: () -> Unit, bottomInset: Dp) {
    val context = LocalContext.current
    val store = remember(context) { webSiteListStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var address by rememberSaveable { mutableStateOf("") }
    var current by remember { mutableStateOf<WebSiteList?>(null) }
    var loading by remember { mutableStateOf(true) }
    var operation by remember { mutableStateOf<CancelScope?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        val saved = withContext(Dispatchers.IO) { runCatching(store::current) }
        current = saved.getOrNull()
        if (saved.isFailure) message = "Could not read the saved source list. You can add it again."
        loading = false
    }
    DisposableEffect(Unit) { onDispose { operation?.cancel() } }
    ScreenBackHandler(true, onBack)

    fun changeList(success: String, work: (CancelScope) -> WebSiteList?) {
        if (operation != null || loading) return
        focus.clearFocus()
        val cancel = CancelScope()
        operation = cancel
        message = null
        scope.launch {
            try {
                current = withContext(Dispatchers.IO) { work(cancel) }
                address = ""
                message = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val error = when {
                    cancel.cancelled -> "Update cancelled."
                    failure is WebSiteListError -> failure.message.orEmpty()
                    else -> "Could not update the list. Check the address and connection."
                }
                message = error + if (current != null) " Your saved list is still available." else ""
            } finally {
                operation = null
            }
        }
    }

    val saved = current
    val busy = loading || operation != null
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Music websites", onBack) }
        item {
            OutlinedTextField(address, { address = it.take(WEB_SITE_URL_LIMIT + 1) }, label = "HTTPS source list URL",
                modifier = Modifier.fillMaxWidth(), enabled = !busy)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && address.isNotBlank(), onClick = {
                    val input = address
                    changeList("Source list saved.") { store.update(input, it) }
                }) { Text(if (saved == null) "Add list" else "Replace list") }
                operation?.let { active -> TextButton(onClick = active::cancel) { Text("Cancel") } }
            }
        }
        if (loading) item { Text("Loading source list…") }
        if (operation != null) item { Text("Updating source list…") }
        message?.let { status -> item { Text(status, style = EvannaDesign.typography.bodyMedium) } }
        if (saved != null) {
            item {
                Text("${saved.address.host} · ${saved.sites.size} ${if (saved.sites.size == 1) "site" else "sites"}",
                    style = EvannaDesign.typography.titleMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        changeList("Source list refreshed.") { store.update(saved.address.toString(), it) }
                    }) { Text("Refresh") }
                    TextButton(enabled = !busy, onClick = {
                        changeList("Source list removed.") {
                            store.remove()
                            null
                        }
                    }) { Text("Remove list") }
                }
            }
            items(saved.sites, key = { "recipe:${it.name}" }) { site ->
                if (site.queryKey != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                        Text(site.name, style = EvannaDesign.typography.bodyLarge)
                        Text("Try on · ${site.address.host}", style = EvannaDesign.typography.bodyMedium)
                    }
                } else {
                    SettingsLink(site.name, "Paste a link · ${site.address.host}") {
                        openWebMusicSite(context, site.address)
                    }
                }
            }
        }
        item { Text("Website shortcuts", style = EvannaDesign.typography.titleMedium) }
        items(websiteShortcuts, key = { "shortcut:${it.first}" }) { (name, url) ->
            SettingsLink(name, url.host) { openWebMusicSite(context, url) }
        }
    }
}
