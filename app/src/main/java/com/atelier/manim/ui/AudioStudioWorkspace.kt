package com.atelier.manim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.atelier.manim.runtime.VoiceEngineKind
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
    voiceBusy: Boolean,
    isRecordingRef: Boolean,
    cloneRefWavPath: String,
    generatedMusicFiles: List<File>,
    generatedSfxFiles: List<File>,
    latestManifestPath: String,
    activeVideoTitle: String,
    onScriptChange: (String) -> Unit,
    onSynthesizeScript: (modelId: String, sid: Int, speed: Float, lang: String) -> Unit,
    onTestSingleLine: (modelId: String, sid: Int, speed: Float, lang: String) -> Unit,
    onToggleRecordCloneSample: () -> Unit,
    onDenoiseCloneSample: () -> Unit,
    onRunForcedAlignment: () -> Unit,
    onComposeMusic: (style: String, key: String, scale: String, bpm: Int, durationSec: Int, arc: String) -> Unit,
    onInstallStableAudio: (variant: String, hfToken: String) -> Unit,
    onGenerateStableAudio: (prompt: String, seconds: Int, variant: String) -> Unit,
    onGenerateSfx: (preset: String) -> Unit,
    onPlayAudioFile: (String) -> Unit,
    onMuxWithActiveVideo: (musicGuestPath: String, useShorts9x16: Boolean) -> Unit,
    onOpenSettingsForVoices: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAtelierColors.current
    var subTab by remember { mutableIntStateOf(0) }

    val ttsSpecs = remember(voiceCatalog, installedVoiceIds) {
        voiceCatalog.filter { it.id in installedVoiceIds && it.kind != VoiceEngineKind.ASR_ZIPFORMER && it.kind != VoiceEngineKind.ASR_SENSEVOICE }
    }
    var selectedModelId by remember(ttsSpecs) { mutableStateOf(ttsSpecs.firstOrNull()?.id ?: "") }
    val selectedSpec = ttsSpecs.firstOrNull { it.id == selectedModelId }
    var selectedSid by remember(selectedModelId) { mutableIntStateOf(selectedSpec?.defaultSid ?: 0) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var voiceLang by remember { mutableStateOf("en") }

    var musicEngineMode by remember { mutableIntStateOf(0) }
    var musicStyle by remember { mutableStateOf("felt_piano") }
    var musicKey by remember { mutableStateOf("D") }
    var musicScale by remember { mutableStateOf("minor") }
    var musicBpm by remember { mutableIntStateOf(84) }
    var musicDur by remember { mutableIntStateOf(45) }
    var musicArc by remember { mutableStateOf("theorem_arc") }
    var neuralPrompt by remember { mutableStateOf("Warm minimalist felt piano and soft cello pad, curious mathematical documentary underscore, clean studio recording") }
    var neuralDur by remember { mutableIntStateOf(30) }
    var hfTokenDraft by remember { mutableStateOf("") }
    var selectedMusicFile by remember(generatedMusicFiles) { mutableStateOf(generatedMusicFiles.firstOrNull()?.name ?: "") }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Voice & Cloning", "Music (DSP + AI)", "SFX & Master Mix").forEachIndexed { idx, lbl ->
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

        if (execution.isRunning || voiceBusy) {
            Column(modifier = Modifier.fillMaxWidth().background(colors.surfaceElevated).padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (voiceBusy) "Synthesizing / Aligning Offline Audio..." else "${execution.modeLabel} · ${execution.stageLabel}", color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                LinearProgressIndicator(color = colors.accent, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }

        when (subTab) {
            0 -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    if (ttsSpecs.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceElevated)
                                .border(1.dp, colors.warning.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("No offline voice model installed yet", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Download Kitten Nano (~35 MB), Kokoro FP32 (~305 MB), Kyutai Pocket 5s Voice Cloner (~98 MB), or Supertonic 3 in Settings.", color = colors.textSecondary, fontSize = 11.sp)
                            PillChip("Open Voice & ASR Model Manager", true, onOpenSettingsForVoices)
                        }
                    } else {
                        Text("Voice Engine", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ttsSpecs.forEach { sp -> PillChip(sp.name, selectedModelId == sp.id) { selectedModelId = sp.id } }
                        }

                        if (selectedSpec?.kind == VoiceEngineKind.POCKET) {
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(colors.surfaceElevated)
                                    .border(1.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(9.dp)).padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Kyutai Pocket 5s Zero-Shot Voice Cloner", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (cloneRefWavPath.isBlank()) "Record 5 seconds of your voice (or uses bundled studio reference)."
                                    else "Reference voice: ${File(cloneRefWavPath).name}",
                                    color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PillChip(if (isRecordingRef) "Stop Recording" else "Record 5s Voice", isRecordingRef, onToggleRecordCloneSample)
                                    if (cloneRefWavPath.isNotBlank()) {
                                        PillChip("Play Ref", false) { onPlayAudioFile(cloneRefWavPath) }
                                        PillChip("Denoise Mic", false, onDenoiseCloneSample)
                                    }
                                }
                            }
                        } else if (selectedSpec?.kind == VoiceEngineKind.SUPERTONIC) {
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("en", "es", "fr", "pt", "ko").forEach { lg -> PillChip("Lang: $lg", voiceLang == lg) { voiceLang = lg } }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("SID: $selectedSid", color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                PillChip("-", false) { if (selectedSid > 0) selectedSid-- }
                                PillChip("+", false) { selectedSid++ }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(0.9f, 1.0f, 1.1f, 1.2f).forEach { sp -> PillChip("${sp}x", speed == sp) { speed = sp } }
                            }
                        }
                    }

                    Text("Narration Script (1 cue per line · LaTeX math supported)", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    BasicTextField(
                        value = scriptText, onValueChange = onScriptChange,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().height(155.dp).clip(RoundedCornerShape(10.dp))
                            .background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(10.dp)).padding(10.dp)
                    )

                    if (normalizedPreview.isNotBlank()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surface)
                                .border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(9.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Math-to-Speech Spoken Preview (L1)", color = colors.textMuted, fontSize = 10.sp)
                            Text(normalizedPreview, color = colors.textPrimary, fontSize = 11.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(if (ttsSpecs.isNotEmpty() && !voiceBusy) colors.accent else colors.surfaceElevated)
                                .clickable(enabled = ttsSpecs.isNotEmpty() && !execution.isRunning && !voiceBusy) {
                                    onSynthesizeScript(selectedModelId, selectedSid, speed, voiceLang)
                                }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Synthesize Script + SRT", color = if (ttsSpecs.isNotEmpty()) Color.White else colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        PillChip("Preview L1", false) {
                            if (ttsSpecs.isNotEmpty() && !voiceBusy) onTestSingleLine(selectedModelId, selectedSid, speed, voiceLang)
                        }
                        PillChip("Align ASR", false) { if (!voiceBusy) onRunForcedAlignment() }
                    }

                    if (voiceStatusSummary.isNotBlank()) Text(voiceStatusSummary, color = colors.success, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (latestManifestPath.isNotBlank()) Text("Active Manifest: $latestManifestPath", color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }

            1 -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillChip("Beat-Locked DSP (<90 MB)", musicEngineMode == 0) { musicEngineMode = 0 }
                        PillChip("Stable Audio 3.0 Neural", musicEngineMode == 1) { musicEngineMode = 1 }
                    }

                    if (musicEngineMode == 0) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("felt_piano" to "Felt Piano", "ambient_cello" to "Ambient Cello", "minimal_marimba" to "Minimal Marimba", "rhodes_pulse" to "Rhodes Pulse").forEach { (k, lbl) ->
                                PillChip(lbl, musicStyle == k) { musicStyle = k }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("C", "D", "Eb", "F", "G", "A", "Bb").forEach { k -> PillChip(k, musicKey == k) { musicKey = k } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("minor" to "Minor", "major" to "Major", "dorian" to "Dorian", "lydian" to "Lydian").forEach { (k, lbl) -> PillChip(lbl, musicScale == k) { musicScale = k } }
                        }
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(68, 76, 84, 96, 108).forEach { b -> PillChip("$b BPM", musicBpm == b) { musicBpm = b } }
                        }
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(20, 45, 75, 120, 180).forEach { d -> PillChip("${d}s", musicDur == d) { musicDur = d } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("calm_study" to "Calm Study", "theorem_arc" to "Theorem Climax", "dynamic_build" to "Dynamic Build").forEach { (k, lbl) -> PillChip(lbl, musicArc == k) { musicArc = k } }
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(colors.accent)
                                .clickable(enabled = !execution.isRunning) { onComposeMusic(musicStyle, musicKey, musicScale, musicBpm, musicDur, musicArc) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Compose Beat-Locked Math Score", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Text("Official Stability AI Stable Audio 3.0 Small (LiteRT ARM64)", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Generates up to 120s 44.1 kHz stereo copyright-safe audio on-device (~1.7 GB one-time pack, ~2.1 GB isolated subprocess RAM).", color = colors.textSecondary, fontSize = 11.sp)
                        BasicTextField(
                            value = neuralPrompt, onValueChange = { neuralPrompt = it },
                            textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(9.dp))
                                .background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(9.dp)).padding(9.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15, 30, 60, 90, 120).forEach { d -> PillChip("${d}s", neuralDur == d) { neuralDur = d } }
                        }
                        BasicTextField(
                            value = hfTokenDraft, onValueChange = { hfTokenDraft = it }, singleLine = true,
                            textStyle = TextStyle(color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceElevated)
                                .border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                            decorationBox = { inner -> if (hfTokenDraft.isEmpty()) Text("Optional HuggingFace read token (avoids rate limits)", color = colors.textMuted, fontSize = 11.sp); inner() }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillChip("Install Music Pack", false) { if (!execution.isRunning) onInstallStableAudio("sm-music", hfTokenDraft) }
                            PillChip("Install SFX Pack", false) { if (!execution.isRunning) onInstallStableAudio("sm-sfx", hfTokenDraft) }
                            PillChip("Generate Neural BGM", true) { if (!execution.isRunning) onGenerateStableAudio(neuralPrompt, neuralDur, "sm-music") }
                        }
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
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(f.name, color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            PillChip("Play", true) { onPlayAudioFile(f.absolutePath) }
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text("Tactile Math Foley & SFX Generator", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    listOf(
                        listOf("soft_pop" to "Soft Pop", "chalk_stroke" to "Chalk Stroke", "glass_ping" to "Glass Ping", "whoosh" to "Whoosh"),
                        listOf("riser" to "Riser Swell", "qed_chord" to "Q.E.D. Chord", "tick" to "Precision Tick", "reveal_shimmer" to "Reveal Shimmer")
                    ).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (preset, lbl) -> PillChip(lbl, false) { if (!execution.isRunning) onGenerateSfx(preset) } }
                        }
                    }
                    if (generatedSfxFiles.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            generatedSfxFiles.forEach { f -> PillChip("Play ${f.nameWithoutExtension.removePrefix("sfx_")}", false) { onPlayAudioFile(f.absolutePath) } }
                        }
                    }
                    HorizontalDivider(color = colors.hairline)
                    Text("Broadcast Master & Auto-Duck Video Export", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Active Video : ${activeVideoTitle.ifBlank { "Render a Manim video first" }}", color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Voice Script : ${latestManifestPath.ifBlank { "None" }}", color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Selected BGM : ${selectedMusicFile.ifBlank { "None" }}", color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    val bgmGuest = if (selectedMusicFile.isNotBlank()) "/outputs/audio/music/$selectedMusicFile" else ""
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(if (activeVideoTitle.isNotBlank()) colors.accent else colors.surfaceElevated)
                                .clickable(enabled = activeVideoTitle.isNotBlank() && !execution.isRunning) { onMuxWithActiveVideo(bgmGuest, false) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Master 16:9 YouTube", color = if (activeVideoTitle.isNotBlank()) Color.White else colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        Box(
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                                .background(colors.surfaceElevated).border(1.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
                                .clickable(enabled = activeVideoTitle.isNotBlank() && !execution.isRunning) { onMuxWithActiveVideo(bgmGuest, true) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Export 9:16 Short", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
    }
}
