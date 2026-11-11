package com.evanna.player.loudness

import androidx.compose.foundation.layout.*
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun LoudnessAnalysisControls() {
    val context=LocalContext.current
    val progress by LibraryLoudnessAnalysis.progress.collectAsState()
    var confirm by remember {mutableStateOf(false)}
    val active=progress.state in listOf(LoudnessAnalysisState.RUNNING,LoudnessAnalysisState.WAITING)
    Text("Measure songs offline",style=EvannaDesign.typography.titleSmall)
    Text(progress.message ?: when(progress.state) {
        LoudnessAnalysisState.IDLE -> "Saved measurements let the DAC level songs without changing their audio samples."
        LoudnessAnalysisState.RUNNING -> "Measuring one song at a time…"
        LoudnessAnalysisState.WAITING -> "Waiting for USB startup or the music-folder scan…"
        LoudnessAnalysisState.DONE -> "Analysis finished. Measurements apply from the next playback start."
        LoudnessAnalysisState.CANCELLED -> "Analysis cancelled; saved measurements kept."
        LoudnessAnalysisState.ERROR -> "Analysis stopped; saved measurements kept."
    },style=EvannaDesign.typography.bodySmall)
    if(progress.checked>0)Text("${progress.checked} checked · ${progress.measured} measured · ${progress.cached} already saved · ${progress.changed} changed · ${progress.failed} unavailable",
        style=EvannaDesign.typography.bodySmall)
    if(active) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick=LibraryLoudnessAnalysis::cancel,modifier=Modifier.heightIn(min=48.dp)){Text("Cancel analysis")}
    }else OutlinedButton(onClick={confirm=true},modifier=Modifier.heightIn(min=48.dp)){Text("Measure or resume library")}
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Measure library loudness?")},
        text={Text("This reads every song that needs a current measurement. It may take a long time and use battery. Nothing plays, music files stay unchanged, and you can cancel and resume later.")},
        confirmButton={TextButton(onClick={confirm=false;LibraryLoudnessAnalysis.start(context)},modifier=Modifier.heightIn(min=48.dp)){Text("Measure library")}},
        dismissButton={TextButton(onClick={confirm=false},modifier=Modifier.heightIn(min=48.dp)){Text("Cancel")}})
}
