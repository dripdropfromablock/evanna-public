package com.evanna.player.equalizer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evanna.player.PlayerIcons
import com.evanna.player.ui.*
import java.util.Locale
import kotlin.math.roundToInt

/** The label under a graphic slider: whole hertz below 100, the nearest 5 Hz below 950, then kHz with at most one decimal. */
internal fun graphicLabel(hz: Float): String = when {
    hz < 100f -> hz.roundToInt().toString()
    hz < 950f -> ((hz / 5f).roundToInt() * 5).toString()
    else -> (hz / 100f).roundToInt().let { tenths -> if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k" }
}

private fun hertz(hz: Float) = if (hz >= 1000f) String.format(Locale.ROOT, "%.1f kilohertz", hz / 1000f) else "${hz.roundToInt()} hertz"

/**
 * The built-in EQ: one switch, the combined curve (drag a point, or tap to add a band), the 15 graphic sliders, the
 * parametric bands and the preamp. Every change plays at once on every output and is saved on this device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EqualizerScreen(onBack: () -> Unit, bottomInset: Dp) {
    val config by SoundSettings.config.collectAsState()
    val p = eqPalette()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chosen by rememberSaveable { mutableIntStateOf(0) }
    val applied = remember(config) { appliedPreampDb(config) }
    val index = chosen.coerceIn(0, (config.bands.size - 1).coerceAtLeast(0))
    fun setBand(i: Int, band: Band) = SoundSettings.update { it.copy(bands = it.bands.toMutableList().apply { set(i, band) }) }
    fun addBand(hz: Float, db: Float) {
        if (config.bands.size >= MAX_BANDS) return
        SoundSettings.update { it.copy(bands = it.bands + Band(FilterType.PEAK, hz.coerceIn(20f, 20000f), snapGain(db), 1f)) }
        chosen = config.bands.size; tab = 1
    }
    val mine by SoundSettings.presets.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // AutoEq's ParametricEQ.txt replaces the parametric bands and resets the preamp; the graphic sliders and tone stay.
    fun applyAutoEq(text: String, label: String): String {
        val found = parseAutoEq(text)
        SoundSettings.update { it.copy(enabled = true, preampDb = 0f, bands = found.bands) }
        chosen = 0; tab = 1
        return "$label: ${found.bands.size} bands." + if (found.skipped > 0) " ${found.skipped} lines were left out." else ""
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            note = try {
                applyAutoEq(withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use(::readSmallText) } ?: "", "Imported")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "That file has no AutoEq filters. Pick a ParametricEQ.txt file."
            }
        }
    }
    var searching by remember { mutableStateOf(false) }
    if (searching) AutoEqSearchDialog(onClose = { searching = false }) { entry ->
        searching = false
        scope.launch {
            note = "Loading ${entry.name}…"
            note = try {
                applyAutoEq(AutoEqLibrary.profile(entry), "${entry.name} (${entry.source})")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                "Could not load ${entry.name}. Check the internet and try again."
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { naming = false }, title = { Text("Save preset") },
            text = { OutlinedTextField(name, { name = it.take(32) }, "Name") },
            confirmButton = {
                TextButton(onClick = { note = if (SoundSettings.savePreset(name)) null else "You can keep up to $MAX_PRESETS presets."; naming = false },
                    enabled = cleanPresetName(name).isNotEmpty()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxSize()) {
        FilledIconButton(onClick = onBack, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = p.card, contentColor = p.text)) {
            Icon(PlayerIcons.ChevronLeft, contentDescription = "Back", modifier = Modifier.size(24.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "title") {
                Text("Equalizer", style = eqText(34.sp, 40.sp, FontWeight.Bold), color = p.text,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            }
            item(key = "switch") {
                EqCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PlayerIcons.Pipeline, contentDescription = null, tint = p.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Equalizer", style = eqText(16.sp, 21.sp, FontWeight.Medium), color = p.text)
                            Text("Speaker, Bluetooth, headphones and USB DACs", style = eqText(12.5.sp, 17.sp), color = p.muted)
                        }
                        Spacer(Modifier.width(12.dp))
                        val toggle: (Boolean) -> Unit = { on -> SoundSettings.update { it.copy(enabled = on) } }
                        AppleSwitch(config.enabled, true, "Equalizer on", toggle)
                    }
                }
            }
            item(key = "presets") {
                EqCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Presets", style = eqText(15.sp, 20.sp, FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f).semantics { heading() })
                        TextButton(onClick = { naming = true }) { Text("Save") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { searching = true }) { Text("Find headphones") }
                        TextButton(onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import file") }
                    }
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (preset in BUILT_IN_PRESETS + mine)
                            EqChip(preset.name, config.enabled && preset.matches(config), true) { SoundSettings.update { preset.applyTo(it) } }
                    }
                    mine.firstOrNull { it.matches(config) }?.let { preset ->
                        TextButton(onClick = { SoundSettings.deletePreset(preset.name) }, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                            Text("Delete ${preset.name}")
                        }
                    }
                    note?.let { Text(it, style = eqText(12.5.sp, 17.sp), color = p.muted, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            item(key = "curve") {
                EqCard {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Text("Drag a point, or tap to add a band", style = eqText(12.sp, 16.sp), color = p.muted, modifier = Modifier.weight(1f))
                        Text("Preamp ", style = eqText(12.sp, 16.sp), color = p.muted)
                        Text("${eqSigned(applied)} dB", style = eqText(12.sp, 16.sp, FontWeight.Medium, tabular = true), color = p.text)
                    }
                    EqCurve(config, 120.dp, config.bands.map { it.frequency to it.gainDb }, if (tab == 1) index else -1,
                        onSelect = { chosen = it; tab = 1 },
                        onHandle = { i, hz, db -> config.bands.getOrNull(i)?.let { b ->
                            setBand(i, b.copy(frequency = hz.coerceIn(20f, 20000f), gainDb = if (b.type.usesGain) snapGain(db) else b.gainDb)) } },
                        onAdd = ::addBand)
                }
            }
            item(key = "tone") {
                EqCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        EqKnob(config.bassDb, 0f..TONE_MAX_DB, config.bassDb / TONE_MAX_DB, eqSigned(config.bassDb), "Bass",
                            "Bass boost below ${BASS_HZ.roundToInt()} hertz", true) { v -> SoundSettings.update { it.copy(bassDb = v) } }
                        EqKnob(config.airDb, 0f..TONE_MAX_DB, config.airDb / TONE_MAX_DB, eqSigned(config.airDb), "Air",
                            "Treble boost above 10 kilohertz", true) { v -> SoundSettings.update { it.copy(airDb = v) } }
                        EqKnob(config.width, 0f..WIDTH_MAX, config.width / WIDTH_MAX, "${config.width.roundToInt()}%", "Width",
                            "Stereo width, 100 percent is unchanged", true, unit = "") { v ->
                            SoundSettings.update { it.copy(width = v.roundToInt().toFloat().coerceIn(0f, WIDTH_MAX)) }
                        }
                    }
                }
            }
            item(key = "tabs") { EqSegments(listOf("Graphic", "Parametric"), tab, { tab = it }) }
            if (tab == 0) item(key = "graphic") {
                EqCard {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("15 bands", style = eqText(15.sp, 20.sp, FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f).semantics { heading() })
                        TextButton(onClick = { SoundSettings.update { it.copy(graphic = List(GRAPHIC_HZ.size) { 0f }) } },
                            enabled = config.graphic.any { it != 0f }) { Text("Flat") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        GRAPHIC_HZ.forEachIndexed { i, hz ->
                            BandSlider(config.graphic[i], graphicLabel(hz), "Band at ${hertz(hz)}", true) { gain ->
                                SoundSettings.update { it.copy(graphic = it.graphic.toMutableList().apply { set(i, gain) }) }
                            }
                        }
                    }
                }
            } else {
                item(key = "bands") {
                    EqCard {
                        val count = config.bands.size
                        Text(if (count == 1) "1 band" else "$count of $MAX_BANDS bands", style = eqText(15.sp, 20.sp, FontWeight.Medium), color = p.text,
                            modifier = Modifier.semantics { heading() })
                        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            config.bands.forEachIndexed { i, band ->
                                BandChip("${i + 1}", i == index, true, "Band ${i + 1}, ${band.type.label}, ${hertz(band.frequency)}") { chosen = i }
                            }
                            AddBandChip(count < MAX_BANDS) { addBand(1000f, 0f) }
                        }
                        if (count == 0) Text("No bands yet. Tap the curve or + to add one.", style = eqText(12.5.sp, 17.sp),
                            color = p.muted, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                config.bands.getOrNull(index)?.let { band -> item(key = "band-editor") {
                    BandEditor(index, band, { setBand(index, it) }) {
                        SoundSettings.update { it.copy(bands = it.bands.filterIndexed { i, _ -> i != index }) }
                        chosen = (index - 1).coerceAtLeast(0)
                    }
                } }
            }
            item(key = "preamp") {
                EqCard {
                    EqHSlider("Preamp", "${eqSigned(config.preampDb)} dB", (config.preampDb + 20f) / 20f, false, "EQ preamp in decibels", true) { at ->
                        SoundSettings.update { it.copy(preampDb = snapGain(at * 20f - 20f, -20f..0f)) }
                    }
                    val auto = config.preampDb - applied
                    Text(if (auto > 0.05f) "Boosts get ${eqSigned(-auto)} dB of automatic headroom, and a limiter catches any peak, so nothing clips."
                        else "A limiter catches any peak, so nothing clips.", style = eqText(12.5.sp, 17.sp), color = p.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item(key = "notes") {
                EqCard {
                    for (note in listOf("With the equalizer on, Direct USB is no longer bit-perfect, even in Strict mode. Off or flat, every sample stays unchanged.",
                        "The JD1's own sound preset also changes its sound after the USB input."))
                        Text(note, style = eqText(12.5.sp, 17.sp), color = p.muted, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
}

/** One parametric band: its shape, Frequency, Gain (for shapes that have one), Q, on/off and remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandEditor(index: Int, band: Band, onBand: (Band) -> Unit, onRemove: () -> Unit) {
    val p = eqPalette()
    EqCard {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Band ${index + 1} · ${band.type.label}", style = eqText(15.sp, 20.sp, FontWeight.SemiBold), color = p.text,
                modifier = Modifier.weight(1f).semantics { heading() })
            val toggle: (Boolean) -> Unit = { onBand(band.copy(on = it)) }
            AppleSwitch(band.on, true, "Band ${index + 1} on", toggle)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in FilterType.entries) EqChip(type.label, band.type == type, true) { onBand(band.copy(type = type)) }
        }
        EqHSlider("Frequency", String.format(Locale.ROOT, "%,d Hz", band.frequency.roundToInt()), frequencyPosition(band.frequency), false,
            "Band ${index + 1} frequency", true) { onBand(band.copy(frequency = frequencyAt(it).coerceAtMost(20000f))) }
        EqHSlider("Gain", if (band.type.usesGain) "${eqSigned(band.gainDb)} dB" else "—", (band.gainDb + 15f) / 30f, true,
            "Band ${index + 1} gain", band.type.usesGain) { onBand(band.copy(gainDb = snapGain(it * 30f - 15f))) }
        EqHSlider("Q", String.format(Locale.ROOT, "%.2f", band.q), qPosition(band.q), false, "Band ${index + 1} Q", true) { onBand(band.copy(q = qAt(it))) }
        TextButton(onClick = onRemove, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) { Text("Remove band ${index + 1}") }
    }
}

/** Searches AutoEq's headphone list (fetched on open, cached a week) and hands back the chosen profile. */
@Composable
private fun AutoEqSearchDialog(onClose: () -> Unit, onPick: (AutoEqEntry) -> Unit) {
    val context = LocalContext.current
    val p = eqPalette()
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { entries = AutoEqLibrary.index(context) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed = true
        }
    }
    val results = remember(entries, query) { entries?.let { searchAutoEq(it, query) } ?: emptyList() }
    AlertDialog(onDismissRequest = onClose, title = { Text("Find headphones") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(60) }, "Headphone name", Modifier.fillMaxWidth())
                val status = when {
                    failed -> "Could not reach AutoEq. Check the internet and try again."
                    entries == null -> "Loading the headphone list…"
                    query.isBlank() -> "${entries!!.size} profiles. Type a name, like HD 600."
                    results.isEmpty() -> "No match."
                    else -> null
                }
                status?.let { Text(it, style = eqText(13.sp, 18.sp), color = p.muted) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(results, key = { it.path }) { entry ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(vertical = 10.dp)) {
                            Text(entry.name, style = eqText(15.sp, 20.sp, FontWeight.Medium), color = p.text)
                            Text(entry.source, style = eqText(12.5.sp, 17.sp), color = p.muted)
                        }
                    }
                }
                Text("Profiles from AutoEq by Jaakko Pasanen (MIT).", style = eqText(11.5.sp, 15.sp), color = p.muted)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
