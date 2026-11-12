package com.evanna.player.equalizer

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.evanna.player.LocalLookStyle
import com.evanna.player.PlayerIcons
import com.evanna.player.playback.EvannaPlayer
import com.evanna.player.user.*
import com.truesample.usbaudio.EqBand
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.*

private enum class EqTab(val label: String) { GRAPHIC("Graphic"), PARAMETRIC("Parametric") }

/** Poweramp's six filter types in its order; the chips show these names. */
private val TYPES = listOf(EqBand.Type.LOW_PASS to "Low pass", EqBand.Type.HIGH_PASS to "High pass", EqBand.Type.BAND_PASS to "Band pass",
    EqBand.Type.PEAK to "Peak", EqBand.Type.LOW_SHELF to "Low shelf", EqBand.Type.HIGH_SHELF to "High shelf")
private fun typeName(type: EqBand.Type) = TYPES.first { it.first == type }.second

/** A graphic preset as the graphic editor shows it: Bass and Treble first, then the ladder ([flatGraphic]). */
private fun EqualizerConfig.graphicLayout() = graphic && bands.size >= 7 && bands[0].type == EqBand.Type.LOW_PASS && bands[1].type == EqBand.Type.HIGH_PASS
private fun EqualizerConfig.withBand(index: Int, band: EqBand) = copy(bands = bands.toMutableList().apply { set(index, band) })
private fun hertz(hz: Float) = if (hz >= 1000f) String.format(Locale.ROOT, "%.1f kilohertz", hz / 1000f) else "${hz.roundToInt()} hertz"

/**
 * The equalizer starts with the DAC switch and preset, followed by the graphic faders or parametric editor.
 * Edits stay a draft until "Apply changes and save"; the saved preset, Strict and headroom rules apply as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@UnstableApi
internal fun EqualizerScreen(profileKey: String?, onBack: () -> Unit, onChange: (Bundle, (Boolean) -> Unit) -> Unit, bottomInset: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { UserData.database(context) }
    val apple = LocalLookStyle.current.look.apple
    val p = eqPalette()
    var profile by remember { mutableStateOf<DacProfile?>(null) }
    var presets by remember { mutableStateOf<List<EqualizerPreset>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var name by remember { mutableStateOf("Custom EQ") }
    var counts by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(EqTab.GRAPHIC) }
    var graphicDraft by remember { mutableStateOf(flatGraphic(10)) }
    var parametricDraft by remember { mutableStateOf(EqualizerConfig()) }
    var chosen by remember { mutableIntStateOf(0) }
    val draft = if (tab == EqTab.GRAPHIC) graphicDraft else parametricDraft
    val draftBands = draft.bands
    // Keep the last preview visible while a new draft settles.
    var headroom by remember { mutableStateOf<EqualizerHeadroom?>(null) }
    LaunchedEffect(draftBands) {
        delay(100)
        headroom = withContext(Dispatchers.Default) { equalizerHeadroom(EqualizerConfig(bands = draftBands), 48000) }
    }
    // A zero-preamp analysis is processed only when there are active filters.
    val preview = headroom
    val applied = if (preview?.processed == true) safeEqualizerPreampDb(draft.preampDb, preview.requiredDb)
        else draft.preampDb
    val editable = loaded && !busy
    val ready = editable && profile != null
    val userPreset = profile?.equalizer?.id?.let { it > 0 } == true
    // A saved or imported message appears at the top. A lazy list keeps its first row in place, so a row
    // added above it stays out of sight; the list scrolls up to show the message.
    val list = rememberLazyListState()
    LaunchedEffect(message) { if (message != null) list.animateScrollToItem(0) }

    /** Shows a preset in the editor that suits it; Flat opens a flat 10-band graphic EQ. */
    fun show(config: EqualizerConfig) {
        when {
            config.graphicLayout() -> { graphicDraft = config; tab = EqTab.GRAPHIC }
            config.bands.isEmpty() -> { graphicDraft = flatGraphic(10).copy(preampDb = config.preampDb); parametricDraft = config; tab = EqTab.GRAPHIC }
            else -> { parametricDraft = config.copy(graphic = false); chosen = 0; tab = EqTab.PARAMETRIC }
        }
    }
    LaunchedEffect(profileKey, refresh) {
        loaded = false
        try {
            val result = withContext(Dispatchers.IO) { profileKey?.let { checkNotNull(database.profile(it)) } to database.equalizerPresets() }
            profile = result.first; presets = result.second
            val selected = result.first?.equalizer
            name = selected?.takeIf { it.id > 0 }?.name ?: "Custom EQ"
            show(selected?.config ?: EqualizerConfig())
            loaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { profile = null; message = "Saved EQ settings could not be read. Reopen Settings and retry." }
    }
    fun submit(action: String, config: EqualizerConfig, enabled: Boolean, target: EqualizerPreset? = null) {
        val before = profile ?: return
        if (!ready) return
        val arguments = Bundle().apply {
            putString(EvannaPlayer.DAC_PROFILE_KEY, profileKey)
            putLong("eqExpectedId", before.equalizer?.id ?: -1); putLong("eqExpectedRevision", before.equalizer?.revision ?: 0)
            putString("eqAction", action); putBoolean(EvannaPlayer.EQ_ENABLED, enabled)
            putFloatArray("eqBands", equalizerValues(config)); putFloat("eqRequestedPreamp", config.preampDb); putBoolean("eqGraphic", config.graphic)
            putString("eqPresetName", name); putLong("eqTargetId", target?.id ?: -1); putLong("eqTargetRevision", target?.revision ?: 0)
        }
        busy = true
        onChange(arguments) { success ->
            busy = false
            if (success) { message = "EQ settings saved."; refresh++ }
            else message = "EQ settings were not applied. Reopen this page if the preset changed; check storage or reduce boosts that need more than 20 dB of headroom."
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && editable) {
            busy = true
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri).use { input ->
                            checkNotNull(input)
                            val bytes = ByteArray(65537); var count = 0
                            while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; check(n > 0); count += n }
                            parseAutoEq(bytes.copyOf(count))
                        }
                    }
                    parametricDraft = imported.config; chosen = 0; tab = EqTab.PARAMETRIC; warnings = imported.warnings
                    message = "File imported into the editor. Save a copy to keep and apply it."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "The EQ file could not be imported. Use a UTF-8 ParametricEQ.txt file up to 64 KiB with at most $MAX_EQ_BANDS enabled supported filters." }
                finally { busy = false }
            }
        }
    }
    val dac = profile?.identity?.displayName
    val presetName = profile?.equalizer?.name ?: "Flat"
    val enabled = profile?.eqEnabled == true
    fun toggle(on: Boolean) {
        val selected = profile?.equalizer ?: presets.firstOrNull { it.id == 0L }
        selected?.let { submit("SELECT", it.config, on, it) }
    }
    Column(Modifier.fillMaxSize()) {
        FilledIconButton(onClick = onBack, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = p.card, contentColor = p.text)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", modifier = Modifier.size(28.dp))
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "title") {
                Text("Equalizer", style = eqText(34.sp, 40.sp, FontWeight.Bold), color = p.text,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            }
            message?.let { text -> item(key = "message") {
                Text(text, style = eqText(13.sp, 18.sp), color = p.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } }
            item(key = "dac") {
                EqCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PlayerIcons.Pipeline, contentDescription = null, tint = p.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("USB equalizer", style = eqText(16.sp, 21.sp, FontWeight.Medium), color = p.text)
                            Text(dac?.let { "$it · Direct USB" } ?: "Open a USB DAC in Settings to save its EQ.",
                                style = eqText(12.5.sp, 17.sp), color = p.muted)
                        }
                        Spacer(Modifier.width(12.dp))
                        if (apple) AppleSwitch(enabled, ready, "Enable USB equalizer", ::toggle)
                        else Switch(checked = enabled, onCheckedChange = ::toggle, enabled = ready,
                            modifier = Modifier.semantics { contentDescription = "Enable USB equalizer" })
                    }
                }
            }
            item(key = "tabs") { EqSegments(EqTab.entries.map { it.label }, tab.ordinal, { tab = EqTab.entries[it] }, enabled = loaded) }
            item(key = "preset-picker") {
                var chooser by remember { mutableStateOf(false) }
                Surface(onClick = { chooser = true }, enabled = ready, shape = EvannaDesign.shapes.small, color = p.card) {
                    Box {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Preset", style = eqText(15.sp, 20.sp, FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f))
                            Text(presetName, style = eqText(14.sp, 18.sp), color = p.muted, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f).padding(horizontal = 8.dp),
                                textAlign = TextAlign.End)
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = p.muted)
                        }
                        DropdownMenu(expanded = chooser, onDismissRequest = { chooser = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                            val selectedPreset = presets.firstOrNull { it.id == (profile?.equalizer?.id ?: 0L) }
                            DropdownChoices(presets, selectedPreset, chooser, onSelect = { preset ->
                                chooser = false; warnings = emptyList(); submit("SELECT", preset.config, enabled, preset)
                            }) { Text(it.name) }
                        }
                    }
                }
            }
            if (tab == EqTab.GRAPHIC) {
                item(key = "graphic-bands") {
                    GraphicBands(graphicDraft, editable, counts, { counts = it }, { count -> graphicDraft = flatGraphic(count).copy(preampDb = graphicDraft.preampDb) }) { index, gain ->
                        graphicDraft = graphicDraft.withBand(index, graphicDraft.bands[index].copy(gainDb = gain))
                    }
                }
                item(key = "graphic-curve") { EqCard { EqCurve(graphicDraft, if (apple) 84.dp else 96.dp) } }
                item(key = "graphic-knobs") {
                    EqCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            val bass = graphicDraft.bands[0].gainDb; val treble = graphicDraft.bands[1].gainDb
                            EqKnob(bass, 0f..15f, bass / 15f, eqSigned(bass), "Bass", "Bass", editable) { graphicDraft = graphicDraft.withBand(0, bassBand(it)) }
                            EqKnob(treble, 0f..15f, treble / 15f, eqSigned(treble), "Treble", "Treble", editable) { graphicDraft = graphicDraft.withBand(1, trebleBand(it)) }
                            PreampKnob(graphicDraft.preampDb, applied, editable) { graphicDraft = graphicDraft.copy(preampDb = it) }
                        }
                        HeadroomWarning(applied)
                    }
                }
            } else {
                item(key = "headphones") {
                    HeadphoneCard(name, parametricDraft.bands.isEmpty(), editable) {
                        importer.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                    }
                }
                item(key = "parametric-curve") {
                    EqCard {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            val bands = parametricDraft.bands.size
                            Text(if (bands == 1) "1 band" else "$bands bands", style = eqText(12.sp, 16.sp), color = p.muted, modifier = Modifier.weight(1f))
                            Text("Preamp ", style = eqText(12.sp, 16.sp), color = p.muted)
                            Text(applied?.let { "${eqSigned(it)} dB" } ?: "—", style = eqText(12.sp, 16.sp, FontWeight.Medium, tabular = true), color = p.text)
                            if (applied != null && applied < parametricDraft.preampDb) Text(" · auto", style = eqText(12.sp, 16.sp), color = p.muted)
                        }
                        EqCurve(parametricDraft, if (apple) 80.dp else 92.dp, parametricDraft.bands.map { it.frequencyHz to it.gainDb },
                            chosen.coerceAtMost(parametricDraft.bands.lastIndex))
                        // Ten bands and the add chip fit on one row at the mockup's 360 dp width.
                        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            parametricDraft.bands.forEachIndexed { index, band ->
                                BandChip("${index + 1}", index == chosen, loaded, "Band ${index + 1}, ${typeName(band.type)}, ${hertz(band.frequencyHz)}") { chosen = index }
                            }
                            AddBandChip(editable && parametricDraft.bands.size < MAX_EQ_BANDS) {
                                parametricDraft = parametricDraft.copy(bands = parametricDraft.bands + EqBand(EqBand.Type.PEAK, 1000f, 0f, 1f))
                                chosen = parametricDraft.bands.lastIndex
                            }
                        }
                        if (parametricDraft.bands.isEmpty()) Text("No bands yet. Add one with +, or find your headphones.", style = eqText(12.5.sp, 17.sp),
                            color = p.muted, modifier = Modifier.padding(top = 8.dp))
                        HeadroomWarning(applied)
                    }
                }
                val index = chosen.coerceIn(0, (parametricDraft.bands.size - 1).coerceAtLeast(0))
                parametricDraft.bands.getOrNull(index)?.let { band -> item(key = "band-editor") {
                    BandEditor(index, band, parametricDraft.bands.size, editable, { parametricDraft = parametricDraft.withBand(index, it) }) {
                        parametricDraft = parametricDraft.copy(bands = parametricDraft.bands.filterIndexed { i, _ -> i != index })
                        chosen = (index - 1).coerceAtLeast(0)
                    }
                } }
                item(key = "parametric-preamp") {
                    EqCard {
                        val preamp = parametricDraft.preampDb
                        EqHSlider("Preamp", "${eqSigned(preamp)} dB", (preamp + 20f) / 20f, false, "EQ preamp in decibels", editable) {
                            parametricDraft = parametricDraft.copy(preampDb = snapGain(it * 20f - 20f, -20f..0f))
                        }
                Text("Preamp lowers the overall level. Auto headroom makes extra room for boosts.", style = eqText(12.5.sp, 17.sp),
                            color = p.muted, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            item(key = "preset") {
                EqCard {
                    Text("Save preset", style = eqText(15.sp, 20.sp, FontWeight.SemiBold), color = p.text, modifier = Modifier.semantics { heading() })
                    OutlinedTextField(value = name, onValueChange = { if (it.length <= 200) name = it }, label = "Preset name",
                        enabled = editable, isError = validEqualizerName(name) == null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Button(onClick = { submit(if (userPreset) "SAVE" else "COPY", draft, enabled) }, enabled = ready && validEqualizerName(name) != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                        Text(if (userPreset) "Apply changes and save" else "Save and use copy")
                    }
                    OutlinedButton(onClick = { submit("COPY", draft, enabled) }, enabled = ready && validEqualizerName(name) != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save and use another copy") }
                    TextButton(onClick = { deleting = true }, enabled = ready && userPreset, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete selected preset") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }, enabled = editable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Import ParametricEQ.txt") }
                    for (warning in warnings) Text(warning, color = EvannaDesign.colorScheme.error, style = eqText(12.5.sp, 17.sp))
        if (profile == null) Text("Select a USB DAC in Settings to save and apply EQ.",
                        style = eqText(12.5.sp, 17.sp), color = p.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item(key = "notes") {
                EqCard {
                    for (note in listOf(
                        "On/off and bypass changes pause Direct USB; press Play afterward. Strict asks for conversion permission when EQ changes samples. Active filter edits apply between audio chunks. Android audio has no EQ.",
                        "Headroom is checked for the combined filters at the actual output rate. The applied preamp may be lower than your setting; Audio quality (Advanced) shows it.",
                        "The JD1's own sound preset also changes its sound after the USB input."))
                        Text(note, style = eqText(12.5.sp, 17.sp), color = p.muted, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this EQ preset?") },
        text = { Text("It will be removed from every DAC profile that uses it. Those profiles will have EQ off.") },
        confirmButton = { TextButton(onClick = { deleting = false; submit("DELETE", EqualizerConfig(), false) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

/** "Bands" with the band-count chooser, then one slider per band (scrolling sideways when they do not fit). */
@Composable
private fun GraphicBands(config: EqualizerConfig, editable: Boolean, menu: Boolean, onMenu: (Boolean) -> Unit, onCount: (Int) -> Unit, onGain: (Int, Float) -> Unit) {
    val p = eqPalette(); val apple = LocalLookStyle.current.look.apple
    val ladder = config.bands.drop(2)
    EqCard {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bands", style = eqText(15.sp, 20.sp, FontWeight.Medium), color = p.text, modifier = Modifier.weight(1f).semantics { heading() })
            Box {
                Row(Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(8.dp))
                    .then(if (apple) Modifier else Modifier.border(1.dp, p.line, RoundedCornerShape(8.dp)))
                    .clickable(enabled = editable, role = Role.Button) { onMenu(true) }
                    .semantics { contentDescription = "Band count: ${ladder.size}" }
                    .padding(start = if (apple) 0.dp else 12.dp, end = if (apple) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(graphicCountLabel(ladder.size), style = eqText(13.5.sp, 18.sp, FontWeight.Medium), color = if (apple) p.accent else p.text)
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = if (apple) p.accent else p.text, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                    DropdownChoices(GRAPHIC_BAND_COUNTS, ladder.size, menu, onSelect = { count ->
                        onMenu(false)
                        if (count != ladder.size) onCount(count)
                    }) { Text(graphicCountLabel(it)) }
                }
            }
        }
        val sliders: @Composable () -> Unit = {
            ladder.forEachIndexed { i, band ->
                BandSlider(band.gainDb, graphicLabel(band.frequencyHz), "Band at ${hertz(band.frequencyHz)}", editable) { onGain(i + 2, it) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Ten sliders fill the card as in the mockup; bigger ladders keep 28 dp sliders 4 dp apart and scroll.
            if (ladder.size * 28 <= maxWidth.value) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { sliders() }
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { sliders() }
        }
    }
}

/** The preamp knob shows what plays (after auto headroom); dragging sets the most it may be. */
@Composable
private fun PreampKnob(preamp: Float, applied: Float?, editable: Boolean, onPreamp: (Float) -> Unit) {
    val shown = applied ?: -20f
    EqKnob(shown, -20f..0f, (shown + 20f) / 20f, applied?.let(::eqSigned) ?: "—",
        if (applied != null && applied < preamp) "Preamp · auto" else "Preamp", "EQ preamp in decibels", editable, onPreamp)
}

@Composable
private fun HeadroomWarning(applied: Float?) {
    if (applied == null) Text("These boosts need more than 20 dB of headroom. Lower some bands to apply them.", style = eqText(12.5.sp, 17.sp),
        color = EvannaDesign.colorScheme.error, modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

/** Show the current preset and import a user-selected AutoEq text file. */
@Composable
private fun HeadphoneCard(name: String, empty: Boolean, editable: Boolean, onImport: () -> Unit) {
    val p = eqPalette(); val apple = LocalLookStyle.current.look.apple
    val source = Regex("^(.*) \\((.+)\\)$").matchEntire(name)
    EqCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(if (apple) 9.dp else 12.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Icon(PlayerIcons.Headphones, contentDescription = null, tint = p.accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                // One line each in the mockup; large font sizes get room to wrap before "…" cuts the name.
                Text(if (empty) "No headphone preset" else source?.groupValues?.get(1) ?: name, style = eqText(16.sp, 21.sp, FontWeight.Medium), color = p.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(when { empty -> "Import a ParametricEQ.txt correction file"; source != null -> "AutoEQ · ${source.groupValues[2]}"; else -> "Your preset" },
                    style = eqText(12.5.sp, 17.sp), color = p.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text("Import", style = eqText(14.sp, 20.sp, FontWeight.Medium), color = p.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = editable, role = Role.Button, onClick = onImport)
                    .semantics { contentDescription = "Import headphone EQ file" }.padding(horizontal = 4.dp, vertical = 12.dp))
        }
    }
}

/** One parametric band: its type chips, Frequency, Gain and Q, and which ears it plays in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandEditor(index: Int, band: EqBand, total: Int, editable: Boolean, onBand: (EqBand) -> Unit, onRemove: () -> Unit) {
    val p = eqPalette()
    EqCard {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("Band ${index + 1} · ${typeName(band.type)}", style = eqText(15.sp, 20.sp, FontWeight.SemiBold), color = p.text,
                modifier = Modifier.weight(1f).semantics { heading() })
            Text("$total of $MAX_EQ_BANDS bands", style = eqText(12.5.sp, 17.sp), color = p.muted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((type, label) in TYPES) EqChip(label, band.type == type, editable) { onBand(band.copy(type = type)) }
        }
            Text("Pass filters adjust a range. Low pass affects below the frequency, high pass above it, and band pass around it.",
            style = eqText(12.5.sp, 17.sp), color = p.muted, modifier = Modifier.padding(top = 8.dp))
        EqHSlider("Frequency", String.format(Locale.ROOT, "%,d Hz", band.frequencyHz.roundToInt()), frequencyPosition(band.frequencyHz), false,
            "Band ${index + 1} frequency", editable) { onBand(band.copy(frequencyHz = frequencyAt(it))) }
        EqHSlider("Gain", "${eqSigned(band.gainDb)} dB", (band.gainDb + 15f) / 30f, true, "Band ${index + 1} gain", editable) {
            onBand(band.copy(gainDb = snapGain(it * 30f - 15f)))
        }
        EqHSlider("Q", String.format(Locale.ROOT, "%.2f", band.q), qPosition(band.q), false, "Band ${index + 1} Q", editable) { onBand(band.copy(q = qAt(it))) }
        EqSegments(listOf("Both", "Left", "Right"), band.channels, { onBand(band.copy(channels = it)) }, Modifier.padding(top = 12.dp), small = true, enabled = editable)
        TextButton(onClick = onRemove, enabled = editable, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) { Text("Remove band ${index + 1}") }
    }
}
