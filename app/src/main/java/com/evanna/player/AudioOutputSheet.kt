package com.evanna.player

import android.media.AudioManager
import android.view.KeyEvent
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun AudioOutputSheet(
    state: PlayerPresentation,
    origin: Offset?,
    onAndroid: () -> Unit,
    onUsbVolume: (Int) -> Unit,
    onUsbVolumeSet: (Float, Long) -> Unit,
    onVolumeKey: (KeyEvent) -> Boolean,
    onDismiss: () -> Unit,
    usbOutputs: @Composable () -> Unit,
) {
    var showPipeline by rememberSaveable { mutableStateOf(false) }
    val colors = EvannaDesign.colorScheme
    val rowShape = RoundedCornerShape(14.dp)
    val android = state.route == "ANDROID"
    OutputSheet(
        origin = origin,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.surfaceContainer.copy(alpha = 0.74f),
        scrimColor = Color.Black.copy(alpha = 0.56f),
        dragHandle = {
            Box(Modifier.padding(vertical = 10.dp).size(32.dp, 4.dp)
                .background(colors.onSurfaceVariant.copy(alpha = 0.45f), CircleShape))
        },
    ) {
        OutputVolumeKeys(onVolumeKey)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Audio output", style = EvannaDesign.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).semantics { heading() })
            OutputRow("This phone", if (android) "Android system output" else "Phone or Bluetooth",
                PlayerIcons.Phone, selected = android, enabled = state.connected && !android, onClick = onAndroid)
            usbOutputs()
            Surface(shape = rowShape, color = colors.surfaceContainerHigh.copy(alpha = 0.72f), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.route == "DIRECT_USB" -> UsbVolume(state, onUsbVolume, onUsbVolumeSet)
                        state.androidVolumeBypassed -> Text("Volume is controlled by the DAC", style = EvannaDesign.typography.bodyMedium)
                        else -> AndroidMediaVolume(enabled = android && state.connected)
                    }
                }
            }
            val signal = state.signalStages.firstOrNull { it.brief && it.label == "Output" }?.value
            OutputRow("Audio Pipeline", signal?.let(::shortSourceQuality) ?: "View audio signal path", PlayerIcons.Pipeline,
                onClick = { showPipeline = true })
        }
        // The output list is right under this sheet, so "Pick another output" just closes it.
        if (showPipeline) SoundPathSheet(state.soundPath, { showPipeline = false }, { showPipeline = false })
    }
}

@Composable
private fun OutputVolumeKeys(onVolumeKey: (KeyEvent) -> Boolean) {
    val view = LocalView.current
    val handle by rememberUpdatedState(onVolumeKey)
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@DisposableEffect onDispose {}
        val original = window.callback ?: return@DisposableEffect onDispose {}
        val stream = window.volumeControlStream
        val bars = WindowCompat.getInsetsController(window, view)
        val lightStatus = bars.isAppearanceLightStatusBars
        val callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                return (volume && handle(event)) || original.dispatchKeyEvent(event)
            }
        }
        window.volumeControlStream = AudioManager.STREAM_MUSIC
        bars.isAppearanceLightStatusBars = false
        window.callback = callback
        onDispose {
            if (window.callback === callback) window.callback = original
            window.volumeControlStream = stream
            bars.isAppearanceLightStatusBars = lightStatus
        }
    }
}

@Composable
internal fun OutputRow(title: String, subtitle: String, icon: ImageVector, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = EvannaDesign.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).clip(shape)
        .background((if (selected) colors.surfaceContainerHighest else colors.surfaceContainerHigh).copy(alpha = 0.72f))
        .semantics { this.selected = selected }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(colors.onSurface.copy(alpha = if (selected) 0.14f else 0.08f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(title, style = EvannaDesign.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = EvannaDesign.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
        else if (enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

internal val PlayerPresentation.androidVolumeBypassed: Boolean
    get() = signalStages.any { it.brief && it.label == "Volume" && it.value.startsWith("Not changed by Android") }

@Composable
internal fun AndroidMediaVolume(enabled: Boolean, modifier: Modifier = Modifier, stepButtons: Boolean = false,
    showLevel: Boolean = true, onOpenOutput: ((Offset?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val stream = AudioManager.STREAM_MUSIC
    val min = remember(audio) { audio.getStreamMinVolume(stream) }
    val max = remember(audio) { audio.getStreamMaxVolume(stream) }
    var level by remember(audio) { mutableIntStateOf(audio.getStreamVolume(stream)) }
    LaunchedEffect(audio, lifecycle, enabled) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                level = audio.getStreamVolume(stream)
                delay(400)
            }
        }
    }
    val canSet = enabled && max > min && !audio.isVolumeFixed
    fun setLevel(step: Int) {
        if (step != level && canSet) {
            runCatching { audio.setStreamVolume(stream, step.coerceIn(min, max), 0) }
            level = audio.getStreamVolume(stream)
        }
    }
    Row(modifier.fillMaxWidth().heightIn(min = if (stepButtons) 48.dp else 40.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (stepButtons) 2.dp else 10.dp)) {
        if (stepButtons) IconButton(onClick = { setLevel(level - 1) }, enabled = canSet && level > min) {
            Icon(PlayerIcons.VolumeDown, contentDescription = "Android media quieter", modifier = Modifier.size(18.dp))
        } else Icon(PlayerIcons.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp), tint = EvannaDesign.colorScheme.onSurfaceVariant)
        val displayedLevel = if (showLevel) level else min
        PillSlider(value = displayedLevel.coerceIn(min, max).toFloat(), onValueChange = {
            setLevel(it.roundToInt())
        }, onValueChangeFinished = {}, valueRange = min.toFloat()..max.coerceAtLeast(min + 1).toFloat(),
            enabled = canSet,
            description = "Android media volume", modifier = Modifier.weight(1f), onOpenOutput = onOpenOutput)
        if (stepButtons) IconButton(onClick = { setLevel(level + 1) }, enabled = canSet && level < max) {
            Icon(PlayerIcons.VolumeUp, contentDescription = "Android media louder", modifier = Modifier.size(18.dp))
        }
    }
}
