package com.evanna.player.metadata

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.evanna.player.CheckRow
import com.evanna.player.EvannaDesign
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsCell
import com.evanna.player.SettingsLink
import com.evanna.player.SettingsSection
import com.evanna.player.SwitchRow
import com.evanna.player.library.LocalLibrary
import com.evanna.player.library.isLocalTree
import com.evanna.player.ui.OutlinedTextField
import com.evanna.player.ui.SearchField
import com.evanna.player.ui.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TagsPage {
    HOME, MISSING, MISSING_TRACKS, FILE_STATUS, AUDIT_FIELDS, SCOPE, BULK, EDIT_FIELD, ONLINE, PREVIEW, LYRICS
}

@Composable
internal fun MusicTagsScreen(onBack: () -> Unit, bottomInset: Dp) {
    val context = LocalContext.current.applicationContext
    var pages by rememberSaveable { mutableStateOf(arrayListOf(TagsPage.HOME)) }
    val page = pages.last()
    val list = rememberLazyListState()
    var scope by rememberSaveable { mutableStateOf<TagScope?>(null) }
    var scopeType by rememberSaveable { mutableStateOf(TagScopeKind.ALBUM) }
    var scopeSearch by rememberSaveable { mutableStateOf("") }
    var auditFields by rememberSaveable { mutableStateOf(ArrayList(MusicTagField.entries.filter { it.defaultAudit })) }
    var missingField by rememberSaveable { mutableStateOf(MusicTagField.TITLE) }
    var embeddedOnly by rememberSaveable { mutableStateOf(false) }
    var fileStatus by rememberSaveable { mutableStateOf(TagFileStatus.UNAVAILABLE) }
    var onlyMissing by rememberSaveable { mutableStateOf<MusicTagField?>(null) }
    var excluded by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var editField by rememberSaveable { mutableStateOf(MusicTagField.GENRE) }
    var newValue by rememberSaveable { mutableStateOf("") }
    var fillMissing by rememberSaveable { mutableStateOf(true) }
    var clearValue by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf("Checking music files…") }
    var entries by remember { mutableStateOf<List<TagAuditEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var stopSaving by remember { mutableStateOf(false) }
    var pendingRoot by rememberSaveable { mutableStateOf<String?>(null) }
    var grantRevision by remember { mutableIntStateOf(0) }
    var grantMessage by remember { mutableStateOf<String?>(null) }
    val saveResults = remember { mutableStateMapOf<String, TagSaveResult>() }
    var review by remember { mutableStateOf<ReviewedTagEdits?>(null) }
    var lookup by remember { mutableStateOf(OnlineTagLookupState("")) }
    val revision = LocalLibrary.scansFinished
    val jobs = rememberCoroutineScope()
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        val requested = pendingRoot
        pendingRoot = null
        if (folder != null && requested != null) {
            grantMessage = if (folder.toString() != requested || !isLocalTree(context, folder)) {
                "Select the same folder shown in Music Folders."
            } else {
                try {
                    context.contentResolver.takePersistableUriPermission(folder,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if (context.canWriteTags(folder)) null else "Android granted read access only."
                } catch (_: SecurityException) {
                    "Android could not grant write access to this folder."
                }
            }
            grantRevision++
        }
    }

    fun save(batch: List<TagFilePreview>, origin: TagOrigin?) {
        if (saving || batch.isEmpty()) return
        saving = true
        stopSaving = false
        jobs.launch {
            val updatedRoots = mutableSetOf<String>()
            try {
                for (file in batch) {
                    if (stopSaving) break
                    val result = try {
                        val name = saveTagSidecar(context, file, origin)
                        updatedRoots += file.source.root
                        TagSaveResult(true, "Saved · $name")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SecurityException) {
                        TagSaveResult(false, "Write access is required for this music folder.")
                    } catch (failure: Exception) {
                        val message = when (failure.message) {
                            "SOURCE_CHANGED" -> "The tags changed since review. Refresh and review them again."
                            "INVALID_SIDECAR" -> "The existing tag file could not be read."
                            else -> "Could not save tags beside this file."
                        }
                        TagSaveResult(false, message)
                    }
                    saveResults[file.source.id] = result
                }
            } finally {
                saving = false
                grantRevision++
                for (root in updatedRoots) LocalLibrary.refreshIndexedFolder(context, Uri.parse(root))
            }
        }
    }

    val auditing = page != TagsPage.LYRICS
    LaunchedEffect(context, revision, refresh, auditing) {
        if (!auditing) return@LaunchedEffect
        entries = null
        failed = false
        progress = "Checking music files…"
        try {
            entries = auditMusicTags(context) { checked, total ->
                withContext(Dispatchers.Main.immediate) { progress = "Checking music files · $checked of $total" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    fun open(destination: TagsPage) { pages = ArrayList(pages + destination) }
    fun back() {
        if (saving) return
        if (page == TagsPage.ONLINE) lookup = OnlineTagLookupState("")
        if (pages.size > 1) pages = ArrayList(pages.dropLast(1)) else onBack()
    }
    fun startBulk(field: MusicTagField?) {
        onlyMissing = field
        excluded = arrayListOf()
        editField = field?.takeUnless { it == MusicTagField.ARTWORK } ?: MusicTagField.GENRE
        newValue = ""
        fillMissing = true
        clearValue = false
        open(TagsPage.BULK)
    }

    ScreenBackHandler(pages.size > 1, ::back)
    if (page == TagsPage.LYRICS) {
        BulkLyricsScreen(bottomInset, ::back)
        return
    }
    LaunchedEffect(page) { list.scrollToItem(0) }

    val allEntries = entries.orEmpty()
    val scoped = remember(allEntries, scope) { allEntries.filter { scope?.includes(it.source) != false } }
    val missing = remember(scoped, missingField, embeddedOnly) { scoped.filter { it.isMissing(missingField, embeddedOnly) } }
    val bulk = remember(scoped, onlyMissing, embeddedOnly) {
        onlyMissing?.let { field -> scoped.filter { it.isMissing(field, embeddedOnly) } } ?: scoped
    }
    val selected = remember(bulk, excluded) {
        val unchecked = excluded.toHashSet()
        bulk.filter { it.values != null && !it.sidecarIssue && it.source.status == TagFileStatus.READABLE && it.source.id !in unchecked }
    }
    val values = remember(selected, editField) { selected.map { it.values?.get(editField) }.distinct() }
    val change = when {
        clearValue && !fillMissing -> TagChange.Clear
        editField.accepts(newValue.trim()) -> TagChange.Set(newValue.trim())
        else -> null
    }
    val scopeTitle = scope?.let { group ->
        allEntries.firstOrNull { group.includes(it.source) }?.source?.let { scopeLabel(group, it) } ?: group.kind.label
    } ?: "All music"
    if (page == TagsPage.ONLINE) {
        OnlineTagLookup(lookup, selected, bottomInset, ::back) { preview, origin ->
            saveResults.clear()
            grantMessage = null
            review = ReviewedTagEdits(preview, selected.size, origin)
            open(TagsPage.PREVIEW)
        }
        return
    }
    val writeGrants = if (page == TagsPage.PREVIEW) remember(grantRevision) {
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && it.isWritePermission }
            .map { it.uri.toString() }.toSet()
    } else emptySet()
    val title = when (page) {
        TagsPage.HOME -> "Music Tags"
        TagsPage.MISSING -> "Missing Tags"
        TagsPage.MISSING_TRACKS -> "Missing ${missingField.label.lowercase()}"
        TagsPage.FILE_STATUS -> fileStatus.label
        TagsPage.AUDIT_FIELDS -> "Tags to check"
        TagsPage.SCOPE -> "Choose tracks"
        TagsPage.BULK -> "Bulk Edit"
        TagsPage.EDIT_FIELD -> "Choose tag"
        TagsPage.ONLINE -> "Online tag lookup"
        TagsPage.PREVIEW -> "Review changes"
        TagsPage.LYRICS -> "Find missing lyrics"
    }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(title, ::back) }
        if (entries == null) item {
            SettingsSection {
                if (failed) {
                    row { SettingsCell { Text("Music tags could not be checked.") } }
                    row { ActionRow("Try again") { refresh++ } }
                } else row { SettingsCell { Text(progress) } }
            }
        }
        when (page) {
            TagsPage.HOME -> item {
                SettingsSection {
                    row { SettingsLink("Missing Tags", "Find blank tags and missing covers") { open(TagsPage.MISSING) } }
                    row { SettingsLink("Bulk Edit", "Review changes across albums, artists, or selected tracks") { startBulk(null) } }
                    row { SettingsLink("Find missing lyrics", "Fill missing lyrics by album, artist, or library") { open(TagsPage.LYRICS) } }
                }
            }
            TagsPage.MISSING -> {
                item {
                    SettingsSection {
                        row { SettingsLink("Scope", value = scopeTitle) { open(TagsPage.SCOPE) } }
                        row { SettingsLink("Tags to check", value = auditFields.size.toString()) { open(TagsPage.AUDIT_FIELDS) } }
                        row { SwitchRow("Check embedded tags only", checked = embeddedOnly) { embeddedOnly = it } }
                        row { ActionRow("Refresh audit", enabled = entries != null) { refresh++ } }
                    }
                }
                if (entries != null) item {
                    SettingsSection("Missing tags") {
                        for (field in MusicTagField.entries.filter { it in auditFields }) row(field) {
                            SettingsLink(field.label, value = scoped.count { it.isMissing(field, embeddedOnly) }.toString()) {
                                missingField = field
                                open(TagsPage.MISSING_TRACKS)
                            }
                        }
                        if (auditFields.isEmpty()) row { SettingsCell { Text("Choose tags to check.") } }
                    }
                }
                val sidecarIssues = scoped.count { it.sidecarIssue }
                if (sidecarIssues > 0) item {
                    SettingsSection { row { SettingsCell { Text("$sidecarIssues tracks have unreadable tag files.") } } }
                }
                val fileStatuses = scoped.groupingBy { it.source.status }.eachCount().filterKeys { it != TagFileStatus.READABLE }
                if (fileStatuses.isNotEmpty()) item {
                    SettingsSection("File status") {
                        for ((status, count) in fileStatuses) row(status) {
                            SettingsLink(status.label, value = count.toString()) { fileStatus = status; open(TagsPage.FILE_STATUS) }
                        }
                    }
                }
            }
            TagsPage.MISSING_TRACKS -> {
                if (entries != null) item {
                    SettingsSection {
                        row { SettingsCell { Text("${missing.size} tracks in $scopeTitle") } }
                        if (missingField != MusicTagField.ARTWORK && missing.isNotEmpty()) row {
                            ActionRow("Bulk Edit these tracks") { startBulk(missingField) }
                        }
                    }
                }
                items(missing, key = { it.source.id }) { TrackAuditRow(it.source) }
            }
            TagsPage.FILE_STATUS -> items(scoped.filter { it.source.status == fileStatus }, key = { it.source.id }) {
                TrackAuditRow(it.source)
            }
            TagsPage.AUDIT_FIELDS -> item {
                SettingsSection {
                    for (field in MusicTagField.entries) row(field) {
                        SwitchRow(field.label, checked = field in auditFields) { checked ->
                            auditFields = ArrayList(if (checked) auditFields + field else auditFields - field)
                        }
                    }
                }
            }
            TagsPage.SCOPE -> tagScopePicker(allEntries.map { it.source }, scopeType, scopeSearch,
                { scopeType = it; scopeSearch = "" }, { scopeSearch = it }) { group ->
                scope = group
                excluded = arrayListOf()
                back()
            }
            TagsPage.BULK -> {
                item {
                    SettingsSection {
                        row { SettingsLink("Scope", value = scopeTitle) { open(TagsPage.SCOPE) } }
                        if (onlyMissing != null) row {
                            SettingsCell { Text("Only tracks missing ${onlyMissing?.label?.lowercase()}") }
                        }
                        row { SettingsLink("Tag", value = editField.label) { open(TagsPage.EDIT_FIELD) } }
                        row { CheckRow("Fill missing only", selected = fillMissing) { fillMissing = true; clearValue = false } }
                        row { CheckRow("Replace existing", selected = !fillMissing) { fillMissing = false } }
                        if (!fillMissing) row { SwitchRow("Clear this tag", checked = clearValue) { clearValue = it } }
                    }
                }
                if (!clearValue) item {
                    OutlinedTextField(newValue, { newValue = it.take(512) }, "New ${editField.label.lowercase()}",
                        modifier = Modifier.fillMaxWidth(), isError = newValue.isNotBlank() && !editField.accepts(newValue.trim()))
                }
                item {
                    val summary = when {
                        values.isEmpty() -> "Choose tracks below."
                        values.size > 1 -> "Mixed values"
                        else -> values.single() ?: "Missing"
                    }
                    SettingsSection("${selected.size} tracks selected", footer = "Current ${editField.label.lowercase()} · $summary") {
                        row { ActionRow("Preview changes", enabled = change != null && selected.isNotEmpty()) {
                            if (change != null) {
                                saveResults.clear()
                                grantMessage = null
                                review = ReviewedTagEdits(previewTagEdits(selected, mapOf(editField to change), fillMissing), bulk.size)
                                open(TagsPage.PREVIEW)
                            }
                        } }
                        row { ActionRow("Look up tags online", enabled = selected.isNotEmpty()) {
                            val albums = selected.mapNotNull { it.values?.get(MusicTagField.ALBUM) }.distinct()
                            val releaseQuery = when (scope?.kind) {
                                TagScopeKind.ARTIST, TagScopeKind.ALBUM_ARTIST -> scope?.value
                                else -> albums.singleOrNull()
                            }
                            val query = releaseQuery ?: selected.singleOrNull()?.source?.title.orEmpty()
                            lookup = OnlineTagLookupState(query.take(240), byTrack = releaseQuery == null && selected.size == 1)
                            open(TagsPage.ONLINE)
                        } }
                    }
                }
                items(bulk, key = { it.source.id }) { entry ->
                    SettingsSection { row {
                        val editable = entry.values != null && !entry.sidecarIssue
                        SwitchRow(entry.source.title, note = if (entry.sidecarIssue) "Tag file could not be read" else trackSubtitle(entry.source),
                            checked = editable && entry.source.id !in excluded, enabled = editable) { checked ->
                            excluded = ArrayList(if (checked) excluded - entry.source.id else excluded + entry.source.id)
                        }
                    } }
                }
            }
            TagsPage.EDIT_FIELD -> item {
                SettingsSection {
                    for (field in MusicTagField.entries.filter { it != MusicTagField.ARTWORK }) row(field) {
                        CheckRow(field.label, selected = field == editField) { editField = field; newValue = ""; back() }
                    }
                }
            }
            TagsPage.PREVIEW -> {
                val preview = review?.files.orEmpty()
                val remaining = preview.filter { saveResults[it.source.id]?.saved != true }
                val writable = remaining.filter { it.source.root in writeGrants }
                val readOnly = remaining.filterNot { it.source.root in writeGrants }.distinctBy { it.source.root }
                item {
                    SettingsSection(footer = if (preview.isNotEmpty()) "Tags are saved in a JSON file beside each song." else null) {
                        row { SettingsCell {
                            Text(review?.let { "${preview.size} files would change · ${it.reviewed - preview.size} skipped" }
                                ?: "Return to Bulk Edit to review the current tags.")
                            review?.origin?.let { Text("Source · ${it.provider.label}") }
                        } }
                        grantMessage?.let { message -> row { SettingsCell { Text(message) } } }
                        if (saveResults.isNotEmpty()) row { SettingsCell {
                            val saved = saveResults.values.count { it.saved }
                            Text("$saved saved · ${saveResults.size - saved} failed")
                        } }
                        if (saving) row {
                            ActionRow("Stop after this file", enabled = !stopSaving) { stopSaving = true }
                        } else {
                            if (writable.isNotEmpty()) row {
                                ActionRow("Save beside songs") { save(writable, review?.origin) }
                            }
                            for (file in readOnly) row(file.source.root) {
                                ActionRow("Allow writes to ${file.source.folder}") {
                                    pendingRoot = file.source.root
                                    grantMessage = null
                                    folderPicker.launch(Uri.parse(file.source.root))
                                }
                            }
                        }
                    }
                }
                items(preview, key = { it.source.id }) { file ->
                    SettingsSection {
                        row { SettingsCell {
                            Text(file.source.title, style = EvannaDesign.typography.titleSmall)
                            Text(trackSubtitle(file.source))
                            for (edit in file.changes) Text("${edit.field.label} · ${edit.before ?: "Missing"} → ${edit.after ?: "Clear"}")
                            saveResults[file.source.id]?.let { Text(it.message) }
                        } }
                    }
                }
            }
            TagsPage.ONLINE, TagsPage.LYRICS -> Unit
        }
    }
}

private data class TagSaveResult(val saved: Boolean, val message: String)
private data class ReviewedTagEdits(val files: List<TagFilePreview>, val reviewed: Int, val origin: TagOrigin? = null)

internal fun LazyListScope.tagScopePicker(
    sources: List<MusicTagSource>,
    kind: TagScopeKind,
    search: String,
    onKindChange: (TagScopeKind) -> Unit,
    onSearchChange: (String) -> Unit,
    onSelect: (TagScope?) -> Unit,
) {
    item {
        SettingsSection {
            row { ActionRow("All music") { onSelect(null) } }
            for (option in TagScopeKind.entries) row(option) {
                CheckRow(option.label, selected = kind == option) { onKindChange(option) }
            }
        }
    }
    item { SearchField(search, { onSearchChange(it.take(120)) }, "Find ${kind.label.lowercase()}") }
    val groups = sources.groupBy(kind::scopeOf)
        .map { (group, tracks) -> Triple(group, scopeLabel(group, tracks.first()), tracks.size) }
        .filter { it.second.contains(search.trim(), ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
    items(groups, key = { it.first }) { (group, label, count) ->
        SettingsSection { row { SettingsLink(label, value = "$count tracks") { onSelect(group) } } }
    }
}

internal fun scopeLabel(scope: TagScope, source: MusicTagSource): String = when (scope.kind) {
    TagScopeKind.ALBUM -> listOfNotNull(scope.value ?: "Unknown album", scope.qualifier).joinToString(" · ")
    TagScopeKind.FOLDER -> source.folder
    else -> scope.value ?: "Unknown ${scope.kind.label.lowercase()}"
}

private fun trackSubtitle(source: MusicTagSource): String = listOfNotNull(source.artist, source.album, source.folder, source.filename,
    source.status.label.takeUnless { source.status == TagFileStatus.READABLE }).joinToString(" · ")

@Composable
private fun TrackAuditRow(source: MusicTagSource) {
    SettingsSection { row { SettingsCell {
        Text(source.title, style = EvannaDesign.typography.titleSmall)
        Text(trackSubtitle(source), color = EvannaDesign.colorScheme.onSurfaceVariant)
    } } }
}
