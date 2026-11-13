package com.evanna.player.loudness

import androidx.compose.foundation.layout.*
import com.evanna.player.ActionRow
import com.evanna.player.EvannaDesign
import com.evanna.player.RowNoteStyle
import com.evanna.player.RowTitleStyle
import com.evanna.player.SettingsCell
import com.evanna.player.SettingsSection
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The "Measure songs offline" group: what the analysis is doing, its counts and the button that starts or stops it. */
@Composable
internal fun LoudnessAnalysisControls() {
    val context=LocalContext.current
    val progress by LibraryLoudnessAnalysis.progress.collectAsState()
    var confirm by remember {mutableStateOf(false)}
    val active=progress.state in listOf(LoudnessAnalysisState.RUNNING,LoudnessAnalysisState.WAITING)
    val muted=EvannaDesign.colorScheme.onSurfaceVariant
    SettingsSection("Measure songs offline") {
        row { SettingsCell {
            Text(progress.message ?: when(progress.state) {
                LoudnessAnalysisState.IDLE -> "Measures songs so the DAC can even out their volume."
                LoudnessAnalysisState.RUNNING -> "Measuring one song at a time…"
                LoudnessAnalysisState.WAITING -> "Waiting for USB startup or the music-folder scan…"
                LoudnessAnalysisState.DONE -> "Analysis finished. Measurements apply from the next playback start."
                LoudnessAnalysisState.CANCELLED -> "Analysis cancelled; saved measurements kept."
                LoudnessAnalysisState.ERROR -> "Analysis stopped; saved measurements kept."
            },style=RowTitleStyle)
            if(progress.checked>0)Text("${progress.checked} checked · ${progress.measured} measured · ${progress.cached} already saved · ${progress.changed} changed · ${progress.failed} unavailable",
                style=RowNoteStyle,color=muted)
            if(active)LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=4.dp))
        } }
        row(key="action") {
            if(active)ActionRow("Cancel analysis",onClick=LibraryLoudnessAnalysis::cancel)
            else ActionRow("Measure or resume library") {confirm=true}
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Measure library loudness?")},
        text={Text("Scans songs that need a measurement. This can take time and use battery. Your files stay unchanged, and you can resume later.")},
        confirmButton={TextButton(onClick={confirm=false;LibraryLoudnessAnalysis.start(context)},modifier=Modifier.heightIn(min=48.dp)){Text("Measure library")}},
        dismissButton={TextButton(onClick={confirm=false},modifier=Modifier.heightIn(min=48.dp)){Text("Cancel")}})
}
