package com.evanna.player.diagnostics

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.ActionRow
import com.evanna.player.LocalLookStyle
import com.evanna.player.ScreenBackHandler
import com.evanna.player.SettingsCell
import com.evanna.player.SettingsLink
import com.evanna.player.SettingsSection
import com.evanna.player.SwitchRow
import kotlinx.coroutines.*
import java.time.Instant

@Composable
internal fun CrashReportsScreen(onBack: () -> Unit, bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<Incident>>(emptyList()) }
    var recording by remember { mutableStateOf(CrashRecorder.enabled.get()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Incident?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    suspend fun refresh() { reports = withContext(Dispatchers.IO) { CrashRecorder.reports() } }
    fun task(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action() } catch (_: Exception) { status = "This action could not finish. Try refreshing; storage or system evidence may be unavailable." }
            finally { busy = false }
        }
    }
    // Hoisted above the detail branch so Back from a report restores the list's exact scroll spot.
    val reportsScroll = rememberLazyListState()
    LaunchedEffect(Unit) { task { refresh() } }
    // Respects LocalCovered: while this screen exits or sits under the sheet, it must not answer Back.
    ScreenBackHandler(true) { if (selected != null) { selected = null; preview = null } else onBack() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.evanna.player.ScreenHeader("Diagnostics") { if (selected != null) { selected = null; preview = null } else onBack() }
        val report = reports.firstOrNull { it.id == selected }
        if (selected == null) {
            // The same inset groups as Settings: the switch with its notes, the saved reports, then the list actions.
            val notes = listOf("No automatic uploads. Private media details and arbitrary exception messages are omitted.",
                if (Build.VERSION.SDK_INT >= 31) "System/native traces are included only when available and safely parsed."
                else if (Build.VERSION.SDK_INT >= 30) "Android exit reasons are supported. Native stacks need Android 12 or newer."
                else "Only JVM crashes and unknown previous exits can be recorded on this Android version.")
            LazyColumn(state = reportsScroll, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp + bottomInset)) {
                item(key = "recording") {
                    SettingsSection(footer = (listOfNotNull(status.ifEmpty { null }) + notes).joinToString("\n\n")) {
                        row {
                            SwitchRow("Save reports on this device", checked = recording) {
                                recording = it; CrashRecorder.setEnabled(it)
                                status = if (it) "Recording enabled for new events." else "Recording disabled. Existing reports remain until deleted or expired."
                            }
                        }
                    }
                }
                item(key = "reports") {
                    SettingsSection("Saved reports") {
                        if (reports.isEmpty()) row { SettingsCell { Text(if (busy) "Loading reports…" else "No saved reports.",
                            color = EvannaDesign.colorScheme.onSurfaceVariant) } }
                        for (incident in reports) row(incident.id) {
                            SettingsLink(incident.kind.label(), subtitle = "${Instant.ofEpochMilli(incident.timestamp)} · UTC") {
                                selected = incident.id; preview = null; status = ""
                            }
                        }
                    }
                }
                item(key = "actions") {
                    SettingsSection {
                        row { ActionRow("Refresh", enabled = !busy) { task { refresh(); status = "History refreshed." } } }
                        row {
                            ActionRow("Delete all", enabled = !busy && reports.isNotEmpty(), destructive = true) { task {
                                withContext(Dispatchers.IO) { CrashRecorder.store.deleteAll() }; refresh()
                                status = if (reports.isEmpty()) "Reports deleted." else "Some reports could not be deleted."
                            } }
                        }
                    }
                }
            }
        } else if (report == null) {
            Text("This report is unavailable. It may have expired or been deleted.")
        } else {
            if (status.isNotEmpty()) Text(status)
            Text(report.kind.label(), style = EvannaDesign.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { task { withContext(Dispatchers.IO) { CrashRecorder.store.delete(report.id) }; selected = null; preview = null; refresh() } },
                    enabled = !busy, colors = ButtonDefaults.textButtonColors(AppleColors.red(LocalLookStyle.current.look.dark))) { Text("Delete") }
                if (preview == null) Button(onClick = { preview = report }, enabled = !busy) { Text("Preview export") }
                else Button(onClick = { task {
                    val snapshot = checkNotNull(preview)
                    val file = withContext(Dispatchers.IO) { CrashRecorder.store.export(snapshot.id, snapshot) }
                    context.startActivity(Intent.createChooser(CrashRecorder.shareIntent(file), "Export Evanna report"))
                } }, enabled = !busy) { Text("Share ZIP") }
            }
            val displayed = preview ?: report
            val text = remember(displayed, preview) {
                if (preview != null) "report.txt\n${CrashRecorder.store.text(displayed)}\nreport.json\n${displayed.json()}"
                else CrashRecorder.store.text(displayed)
            }
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Text(text, style = EvannaDesign.typography.bodySmall) }
        }
    }
}

private fun IncidentKind.label(): String = when (this) {
    IncidentKind.JVM_CRASH -> "App crash"
    IncidentKind.NATIVE_CRASH -> "Native crash"
    IncidentKind.ANR -> "App not responding"
    IncidentKind.HANDLED_FAILURE -> "Handled playback error"
    IncidentKind.MEMORY_KILL -> "Closed for low memory"
    IncidentKind.USER_STOP -> "Stopped by user, Android or another app"
    IncidentKind.OTHER_EXIT -> "Other Android exit"
    IncidentKind.UNKNOWN_EXIT -> "Previous exit — cause unknown"
}
