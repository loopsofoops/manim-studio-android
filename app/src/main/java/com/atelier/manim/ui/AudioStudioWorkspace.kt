package com.atelier.manim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelier.manim.runtime.ExecutionState
import com.atelier.manim.runtime.VoiceModelSpec
import com.atelier.manim.ui.theme.LocalAtelierColors
import java.io.File

@Composable
fun AudioStudioWorkspace(
    execution: ExecutionState,
    voiceCatalog: List<VoiceModelSpec>,
    installedVoiceIds: Set<String>,
    scriptText: String,
    normalizedPreview: String,
    voiceStatusSummary: String,
    generatedMusicFiles: List<File>,
    generatedSfxFiles: List<File>,
    latestManifestPath: String,
    activeVideoTitle: String,
    onScriptChange: (String) -> Unit,
    onSynthesizeScript: (modelId: String, sid: Int, speed: Float) -> Unit,
    onTestSingleLine: (modelId: String, sid: Int, speed: Float) -> Unit,
    onComposeMusic: (style: String, key: String, scale: String, bpm: Int, durationSec: Int, arc: String) -> Unit,
    onGenerateSfx: (preset: String) -> Unit,
    onPlayAudioFile: (String) -> Unit,
    onMuxWithActiveVideo: (musicGuestPath: String, useShorts9x16: Boolean) -> Unit,
    onOpenSettingsForVoices: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAtelierColors.current
    var subTab by remember { mutableIntStateOf(0) }

    // Voice controls
    val installedSpecs = remember(voiceCatalog, installedVoiceIds) { voiceCatalog.filter { it.id in installedVoiceIds } }
    var selectedModelId by remember(installedSpecs) { mutableStateOf(installedSpecs.firstOrNull()?.id ?: "") }
    val selectedSpec = installedSpecs.firstOrNull { it.id == selectedModelId }
    var selectedSid by remember(selectedModelId) { mutableIntStateOf(selectedSpec?.defaultSid ?: 0) }
    var speed by remember { mutableFloatStateOf(1.0f) }

    // Music composer controls
    var musicStyle by remember { mutableStateOf("felt_piano") }
    var musicKey by remember { mutableStateOf("D") }
    var musicScale by remember { mutableStateOf("minor") }
    var musicBpm by remember { mutableIntStateOf(84) }
    var musicDur by remember { mutableIntStateOf(45) }
    var musicArc by remember { mutableStateOf("theorem_arc") }
    var selectedMusicFile by remember(generatedMusicFiles) { mutableStateOf(generatedMusicFiles.firstOrNull()?.name ?: "") }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        // Segmented Sub-Tab Strip
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Voiceover TTS", "Math Score BGM", "SFX & Master Mix").forEachIndexed { idx, lbl ->
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (subTab == idx) colors.surfaceElevated else Color.Transparent)
                        .border(1.dp, if (subTab == idx) colors.accent.copy(alpha = 0.65f) else colors.hairline, RoundedCornerShape(8.dp))
                        .clickable { subTab = idx }.padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(lbl, color = if (subTab == idx) colors.textPrimary else colors.textSecondary, fontSize = 11.sp, fontWeight = if (subTab == idx) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        if (execution.isRunning) {
            Column(modifier = Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${execution.modeLabel} · ${execution.stageLabel}", color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                LinearProgressIndicator(color = colors.accent, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }

        when (subTab) {
            0 -> {
                // VOICE STUDIO
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (installedSpecs.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceElevated)
                                .border(1.dp, colors.warning.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("No offline voice model installed yet", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Download Kitten Nano (~35 MB) or Kokoro EN FP32 (~305 MB) in Settings to synthesize offline narration.", color = colors.textSecondary, fontSize = 11.sp)
                            PillChip("Open Voice Model Manager", true, onOpenSettingsForVoices)
                        }
                    } else {
                        Text("Voice Model", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            installedSpecs.forEach { sp -> PillChip(sp.name, selectedModelId == sp.id) { selectedModelId = sp.id } }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Speaker ID: $selectedSid", color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                PillChip("-", false) { if (selectedSid > 0) selectedSid-- }
                                PillChip("+", false) { selectedSid++ }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf(0.9f, 1.0f, 1.1f, 1.2f).forEach { sp ->
                                    PillChip("${sp}x", speed == sp) { speed = sp }
                                }
                            }
                        }
                    }

                    Text("Narration Script (1 cue per line — supports LaTeX math like e^{i\\pi})", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    BasicTextField(
                        value = scriptText,
                        onValueChange = onScriptChange,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(10.dp)).padding(10.dp)
                    )

                    if (normalizedPreview.isNotBlank()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surface)
                                .border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("Math-to-Speech Spoken Preview (Line 1)", color = colors.textMuted, fontSize = 10.sp)
                            Text(normalizedPreview, color = colors.textPrimary, fontSize = 11.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(if (installedSpecs.isNotEmpty()) colors.accent else colors.surfaceElevated)
                                .clickable(enabled = installedSpecs.isNotEmpty() && !execution.isRunning) {
                                    onSynthesizeScript(selectedModelId, selectedSid, speed)
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Synthesize Script + SRT", color = if (installedSpecs.isNotEmpty()) Color.White else colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        PillChip("Preview L1", false) {
                            if (installedSpecs.isNotEmpty()) onTestSingleLine(selectedModelId, selectedSid, speed)
                        }
                    }

                    if (voiceStatusSummary.isNotBlank()) {
                        Text(voiceStatusSummary, color = colors.success, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (latestManifestPath.isNotBlank()) {
                        Text("Active Manifest: $latestManifestPath", color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            1 -> {
                // PROCEDURAL BEAT-LOCKED MATH SCORE COMPOSER
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Instrument Style (100% Copyright-Free DSP)", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("felt_piano" to "Felt Piano", "ambient_cello" to "Ambient Cello", "minimal_marimba" to "Minimal Marimba", "rhodes_pulse" to "Rhodes Pulse").forEach { (k, lbl) ->
                            PillChip(lbl, musicStyle == k) { musicStyle = k }
                        }
                    }

                    Text("Key & Scale", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("C", "D", "Eb", "F", "G", "A", "Bb").forEach { k -> PillChip(k, musicKey == k) { musicKey = k } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("minor" to "Minor", "major" to "Major", "dorian" to "Dorian", "lydian" to "Lydian").forEach { (k, lbl) ->
                            PillChip(lbl, musicScale == k) { musicScale = k }
                        }
                    }

                    Text("Tempo (BPM) & Length", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(68, 76, 84, 96, 108).forEach { b -> PillChip("$b BPM", musicBpm == b) { musicBpm = b } }
                    }
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(20, 45, 75, 120, 180).forEach { d -> PillChip("${d}s", musicDur == d) { musicDur = d } }
                    }

                    Text("Tension / Energy Curve", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("calm_study" to "Calm Study", "theorem_arc" to "Theorem Climax", "dynamic_build" to "Dynamic Build").forEach { (k, lbl) ->
                            PillChip(lbl, musicArc == k) { musicArc = k }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                            .background(colors.accent)
                            .clickable(enabled = !execution.isRunning) { onComposeMusic(musicStyle, musicKey, musicScale, musicBpm, musicDur, musicArc) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Compose Beat-Locked Math Score", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    HorizontalDivider(color = colors.hairline)
                    Text("Generated Scores (${generatedMusicFiles.size})", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    generatedMusicFiles.forEach { f ->
                        val isSel = selectedMusicFile == f.name
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) colors.surfaceElevated else colors.surface)
                                .border(1.dp, if (isSel) colors.accent else colors.hairline, RoundedCornerShape(8.dp))
                                .clickable { selectedMusicFile = f.name }.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(f.name, color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            PillChip("Play", true) { onPlayAudioFile(f.absolutePath) }
                        }
                    }
                }
            }

            else -> {
                // SFX & BROADCAST AUTO-DUCK MASTER MIXER
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Tactile Math Foley & SFX Generator", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    val sfxRows = listOf(
                        listOf("soft_pop" to "Soft Pop", "chalk_stroke" to "Chalk Stroke", "glass_ping" to "Glass Ping", "whoosh" to "Whoosh"),
                        listOf("riser" to "Riser Swell", "qed_chord" to "Q.E.D. Chord", "tick" to "Precision Tick", "reveal_shimmer" to "Reveal Shimmer")
                    )
                    sfxRows.forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (preset, lbl) ->
                                PillChip(lbl, false) { if (!execution.isRunning) onGenerateSfx(preset) }
                            }
                        }
                    }

                    if (generatedSfxFiles.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            generatedSfxFiles.forEach { f ->
                                PillChip("Play ${f.nameWithoutExtension.removePrefix("sfx_")}", false) { onPlayAudioFile(f.absolutePath) }
                            }
                        }
                    }

                    HorizontalDivider(color = colors.hairline)
                    Text("Broadcast Master & Auto-Duck Video Export", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Combines your active Manim video + latest synthesized voice script + selected BGM score using voice-carved EQ, FFmpeg sidechain auto-ducking (-13 dB), freeze-frame tail padding, and YouTube -14 LUFS loudness normalization.",
                        color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Active Video : ${activeVideoTitle.ifBlank { "Render a Manim video first" }}", color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Voice Script : ${latestManifestPath.ifBlank { "None (will keep video audio / BGM only)" }}", color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Selected BGM : ${selectedMusicFile.ifBlank { "None (compose a score in Music tab)" }}", color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }

                    val bgmGuest = if (selectedMusicFile.isNotBlank()) "/outputs/audio/music/$selectedMusicFile" else ""
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(if (activeVideoTitle.isNotBlank()) colors.accent else colors.surfaceElevated)
                                .clickable(enabled = activeVideoTitle.isNotBlank() && !execution.isRunning) {
                                    onMuxWithActiveVideo(bgmGuest, false)
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Master 16:9 YouTube", color = if (activeVideoTitle.isNotBlank()) Color.White else colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(colors.surfaceElevated).border(1.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
                                .clickable(enabled = activeVideoTitle.isNotBlank() && !execution.isRunning) {
                                    onMuxWithActiveVideo(bgmGuest, true)
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Export 9:16 Short", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
