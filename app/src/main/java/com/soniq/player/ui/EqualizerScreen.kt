package com.soniq.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.soniq.player.ui.theme.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soniq.player.audio.AudioEffectsBridge
import com.soniq.player.audio.AudioStemsEngine
import kotlin.math.roundToInt

private data class EqPreset(val name: String, val curve: List<Float>)
private val eqPresets = listOf(
    EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Bass", listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 1f)),
    EqPreset("Pop", listOf(-1f, 1f, 3f, 4f, 2f, 0f, -1f, -1f)),
    EqPreset("Rock", listOf(4f, 2f, -1f, -2f, 2f, 4f, 5f, 4f)),
    EqPreset("Vocal", listOf(-2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f)),
    EqPreset("Electronic", listOf(4f, 3f, 0f, -1f, 2f, 0f, 3f, 4f))
)

@Composable
fun EqualizerScreen(modifier: Modifier = Modifier) {
    var available by remember { mutableStateOf(AudioEffectsBridge.isAvailable()) }
    var bands by remember { mutableIntStateOf(AudioEffectsBridge.bandCount()) }
    var range by remember { mutableStateOf(AudioEffectsBridge.bandLevelRange()) }
    var selected by remember { mutableStateOf("Custom") }
    var refresh by remember { mutableIntStateOf(0) }
    var bass by remember { mutableFloatStateOf(0f) }
    var virtual by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        repeat(20) {
            if (AudioEffectsBridge.isAvailable()) {
                available = true
                bands = AudioEffectsBridge.bandCount()
                range = AudioEffectsBridge.bandLevelRange()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(500)
        }
    }

    val levels = remember(bands, refresh) { (0 until bands).map { AudioEffectsBridge.bandLevel(it).toFloat() } }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 32.dp)
    ) {
        Text("SONIQ EQUALIZER & STEMS", color = NeoInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (available) "$bands native bands active on device" else "Play a track to activate hardware equalizer & stems.", color = NeoMuted, fontSize = 12.sp)

        Spacer(Modifier.height(18.dp))

        // SMART "AUDIO STEMS" / VOCAL & BASS ISOLATOR SECTION
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = NeoSurface, shadowElevation = 6.dp,
            border = BorderStroke(1.2.dp, Color(0x661B6FE0)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SpeakerGroup, null, tint = NeoAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("SMART AUDIO STEMS (ISOLATOR)", color = NeoAccent, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                Text("Real-time vocal isolation, sub-bass extractor & instrumental cut", color = NeoMuted, fontSize = 11.sp)

                Spacer(Modifier.height(14.dp))

                // Vocal Stem Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, tint = NeoAccentSoft, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Vocal Stem Isolator", color = NeoInk, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1fx".format(AudioStemsEngine.vocalStem), color = NeoAccent, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = AudioStemsEngine.vocalStem,
                    onValueChange = { AudioStemsEngine.updateVocalStem(it) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = NeoAccent, activeTrackColor = NeoAccentSoft)
                )

                // Bass Stem Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = NeoAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sub / Mid Bass Stem", color = NeoInk, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1fx".format(AudioStemsEngine.bassStem), color = NeoAccent, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = AudioStemsEngine.bassStem,
                    onValueChange = { AudioStemsEngine.updateBassStem(it) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = NeoAccent, activeTrackColor = NeoAccent)
                )

                // Instrumental Stem Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SpeakerGroup, null, tint = NeoAccentSoft, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Instruments & Treble", color = NeoInk, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1fx".format(AudioStemsEngine.instrumentalStem), color = NeoAccent, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = AudioStemsEngine.instrumentalStem,
                    onValueChange = { AudioStemsEngine.updateInstrumentalStem(it) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = NeoAccent, activeTrackColor = NeoAccentSoft)
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { AudioStemsEngine.resetStems() }) {
                        Text("Reset Stems", color = NeoDanger)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Text("HARDWARE PRESETS", color = NeoAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        eqPresets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    FilterChip(
                        selected = selected == preset.name,
                        onClick = {
                            selected = preset.name
                            repeat(bands) { i ->
                                val x = if (bands <= 1) 0f else i.toFloat() / (bands - 1)
                                val j = (x * (preset.curve.size - 1)).roundToInt()
                                AudioEffectsBridge.setBandLevel(i, (preset.curve[j] * 100).roundToInt())
                            }
                            refresh++
                        },
                        label = { Text(preset.name) },
                        enabled = available
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (available && bands > 0) {
            repeat(bands) { index ->
                val hz = AudioEffectsBridge.centerFrequencyHz(index)
                var value by remember(bands, index, refresh) { mutableFloatStateOf(levels.getOrElse(index) { 0f }) }
                Row {
                    Text(if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz", color = NeoInk, modifier = Modifier.weight(1f))
                    Text("%.1f dB".format(value / 100f), color = NeoMuted)
                }
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        selected = "Custom"
                        AudioEffectsBridge.setBandLevel(index, it.roundToInt())
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat()
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("BASS BOOST", color = NeoAccent, fontWeight = FontWeight.Bold)
        Slider(
            value = bass,
            onValueChange = {
                bass = it
                AudioEffectsBridge.setBassStrength((it * 10).roundToInt())
            },
            valueRange = 0f..100f,
            enabled = available && AudioEffectsBridge.bassSupported()
        )

        Text("VIRTUALIZER / 3D SPATIAL", color = NeoAccent, fontWeight = FontWeight.Bold)
        Slider(
            value = virtual,
            onValueChange = {
                virtual = it
                AudioEffectsBridge.setVirtualizerStrength((it * 10).roundToInt())
            },
            valueRange = 0f..100f,
            enabled = available && AudioEffectsBridge.virtualizerSupported()
        )

        OutlinedButton(
            onClick = {
                AudioEffectsBridge.reset()
                AudioStemsEngine.resetStems()
                bass = 0f
                virtual = 0f
                selected = "Flat"
                refresh++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset All Effects & DSP")
        }
    }
}
