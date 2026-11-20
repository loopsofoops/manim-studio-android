package com.atelier.manim.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelier.manim.runtime.BatchJobItem
import com.atelier.manim.runtime.BrandKitConfig
import com.atelier.manim.runtime.HardwareTelemetry
import com.atelier.manim.runtime.VoiceModelSpec
import com.atelier.manim.ui.theme.LocalAtelierColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class CodeSymbolEntry(val line: Int, val kind: String, val name: String)

fun extractCodeSymbols(code: String, language: String): List<CodeSymbolEntry> {
    val out = mutableListOf<CodeSymbolEntry>()
    val pyClass = Regex("""^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)""")
    val pyDef = Regex("""^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)""")
    val texSec = Regex("""\\(section|subsection|subsubsection|chapter|begin\{theorem\})\{?([^}\n]*)""")
    code.lines().forEachIndexed { idx, line ->
        val ln = idx + 1
        if (language == "LATEX") {
            texSec.find(line)?.let { m ->
                out.add(CodeSymbolEntry(ln, m.groupValues[1].uppercase().take(4), m.groupValues[2].ifBlank { m.groupValues[1] }))
            }
        } else {
            pyClass.find(line)?.let { out.add(CodeSymbolEntry(ln, "CLASS", it.groupValues[1])) }
                ?: pyDef.find(line)?.let { out.add(CodeSymbolEntry(ln, "DEF", it.groupValues[1])) }
        }
    }
    return out
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymbolOutlineSheet(
    code: String,
    language: String,
    onJumpToLine: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val symbols = remember(code, language) { extractCodeSymbols(code, language) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surface) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.65f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Symbol Outline (${symbols.size})", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = colors.hairline)
            if (symbols.isEmpty()) {
                Text("No classes, functions, or LaTeX sections found.", color = colors.textMuted, fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    items(symbols) { s ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceElevated)
                                .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                                .clickable { onJumpToLine(s.line); onDismiss() }.padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(s.kind, color = if (s.kind == "CLASS") colors.accent else colors.synType, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                Text(s.name, color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            }
                            Text("L${s.line}", color = colors.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun FormulaPreviewDialog(
    initialFormula: String,
    previewHostPath: String,
    errorText: String,
    isBusy: Boolean,
    onRenderFormula: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    var expr by remember(initialFormula) { mutableStateOf(initialFormula) }
    var bmp by remember(previewHostPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(previewHostPath) {
        if (previewHostPath.isNotBlank() && File(previewHostPath).exists()) {
            bmp = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(previewHostPath) }.getOrNull() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.surface,
        title = { Text("Instant MathTex Preview", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProInput(expr, { expr = it }, "\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}")
                Box(
                    modifier = Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF06070A)).border(1.dp, colors.hairline, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isBusy -> Text("Compiling LaTeX formula...", color = colors.textSecondary, fontSize = 11.sp)
                        errorText.isNotBlank() -> Text(errorText, color = colors.error, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                        bmp != null -> Image(bmp!!.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().padding(8.dp))
                        else -> Text("Tap Render to preview equation (~1s)", color = colors.textMuted, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRenderFormula(expr) }) { Text("Render", color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = colors.textSecondary) } }
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitPanelSheet(
    gitOutput: String,
    gitBusy: Boolean,
    onRunGit: (action: String, arg1: String, arg2: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var commitMsg by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onRunGit("status", "", "") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surface) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Workspace Git Panel (/workspace)", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillChip("Init Repo", false) { onRunGit("init", "", "") }
                PillChip("Status", true) { onRunGit("status", "", "") }
                PillChip("Diff", false) { onRunGit("diff", "", "") }
                PillChip("Log", false) { onRunGit("log", "", "") }
                PillChip("Pull", false) { onRunGit("pull", "", "") }
                PillChip("Push", false) { onRunGit("push", "", "") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ProInput(commitMsg, { commitMsg = it }, "Commit message...", Modifier.weight(1f))
                PillChip("Commit All", true) {
                    if (commitMsg.isNotBlank()) { onRunGit("commit", commitMsg, ""); commitMsg = "" }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ProInput(branchName, { branchName = it }, "Branch (e.g. ep02)", Modifier.weight(1f))
                PillChip("Checkout -B", false) {
                    if (branchName.isNotBlank()) { onRunGit("branch", branchName, ""); branchName = "" }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ProInput(remoteUrl, { remoteUrl = it }, "Remote origin URL (optional)", Modifier.weight(1f))
                PillChip("Set Origin", false) {
                    if (remoteUrl.isNotBlank()) { onRunGit("remote", remoteUrl, ""); remoteUrl = "" }
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(colors.background).border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState()).padding(10.dp)
            ) {
                Text(
                    if (gitBusy) "Running git..." else gitOutput.ifBlank { "Tap Init Repo or Status to inspect /workspace." },
                    color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchQueueSheet(
    telemetry: HardwareTelemetry,
    jobs: List<BatchJobItem>,
    isRunning: Boolean,
    thermalPaused: Boolean,
    onEnqueueActiveScene: () -> Unit,
    onEnqueueActiveTimeline: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surface) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.78f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Thermal-Guarded Batch Queue", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Auto-pauses above 42.0 C · Resumes at 38.5 C", color = colors.textSecondary, fontSize = 11.sp)
                }
                val tempColor = when { telemetry.batteryTempC >= 42f -> colors.error; telemetry.batteryTempC >= 39f -> colors.warning; else -> colors.success }
                Text(
                    String.format(Locale.US, "%.1f C · %d MB", telemetry.batteryTempC, telemetry.ramAvailMb),
                    color = tempColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
                )
            }
            if (thermalPaused) {
                Text("Thermal Guard Active: cooling battery before next job...", color = colors.warning, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillChip(if (isRunning) "Pause Queue" else "Start Queue", true) { if (isRunning) onPause() else onStart() }
                PillChip("+ Active Scene", false, onEnqueueActiveScene)
                PillChip("+ Active Timeline", false, onEnqueueActiveTimeline)
                PillChip("Clear Done", false, onClearFinished)
            }
            HorizontalDivider(color = colors.hairline)
            if (jobs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Queue is empty. Add scenes or timeline exports for unattended overnight processing.", color = colors.textMuted, fontSize = 12.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    items(jobs, key = { it.id }) { j ->
                        val stColor = when (j.status) { "DONE" -> colors.success; "FAILED" -> colors.error; "RUNNING" -> colors.accent; "PAUSED_THERMAL" -> colors.warning; else -> colors.textMuted }
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(colors.surfaceElevated)
                                .border(1.dp, colors.hairline, RoundedCornerShape(9.dp)).padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(j.title, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("${j.kind} · ${j.resultSummary.ifBlank { j.target }}", color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(j.status, color = stColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                Text("x", color = colors.textMuted, fontSize = 12.sp, modifier = Modifier.clickable { onRemove(j.id) }.padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandAndBlueprintSheet(
    brand: BrandKitConfig,
    onSaveBrand: (BrandKitConfig) -> Unit,
    onExportThemePy: () -> Unit,
    onBuildBlueprint: (name: String, mdScript: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chName by remember(brand) { mutableStateOf(brand.channelName) }
    var bgHex by remember(brand) { mutableStateOf(brand.bgColor) }
    var accentHex by remember(brand) { mutableStateOf(brand.accentColor) }
    var hiHex by remember(brand) { mutableStateOf(brand.highlightColor) }
    var wm by remember(brand) { mutableStateOf(brand.watermark) }
    var bpName by remember { mutableStateOf("euler_blueprint") }
    var bpScript by remember {
        mutableStateOf(
            "[CHAPTER: Unit Circle]\n[MOOD: curious]\nOn the complex plane, multiplying by e^{i\\theta} rotates smoothly along the unit circle.\n" +
                "[SFX: soft_pop]\n[MOOD: climax]\nAt angle \\pi, we reach negative one, revealing Euler's identity: e^{i\\pi} + 1 = 0.\n" +
                "[SFX: qed_chord]\n[MOOD: resolve]\nFive constants united in a single equation."
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surface) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Channel Brand Kit (brand.json)", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            ProInput(chName, { chName = it }, "Channel Name")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProInput(bgHex, { bgHex = it }, "BG #06070A", Modifier.weight(1f))
                ProInput(accentHex, { accentHex = it }, "Accent #0A84FF", Modifier.weight(1f))
                ProInput(hiHex, { hiHex = it }, "Highlight #FDE047", Modifier.weight(1f))
            }
            ProInput(wm, { wm = it }, "Thumbnail Watermark (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillChip("Save Brand Kit", true) {
                    onSaveBrand(brand.copy(channelName = chName, bgColor = bgHex, accentColor = accentHex, highlightColor = hiHex, watermark = wm))
                }
                PillChip("Export atelier_theme.py", false) { onExportThemePy() }
            }

            HorizontalDivider(color = colors.hairline)
            Text("Unified Episode Blueprint (Markdown -> Timeline)", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Use [CHAPTER:], [MOOD: calm|build|climax|resolve], [SFX: soft_pop|qed_chord] tags.", color = colors.textSecondary, fontSize = 11.sp)
            ProInput(bpName, { bpName = it }, "Timeline Name")
            BasicTextField(
                value = bpScript, onValueChange = { bpScript = it },
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(9.dp))
                    .background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(9.dp)).padding(10.dp)
            )
            PillChip("Build Full Blueprint Timeline", true) { onBuildBlueprint(bpName, bpScript); onDismiss() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun MultiLangDubDialog(
    timelineName: String,
    voiceCatalog: List<VoiceModelSpec>,
    installedVoiceIds: Set<String>,
    onRunDub: (targetLang: String, modelId: String, translatedLines: String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val installed = remember(voiceCatalog, installedVoiceIds) { voiceCatalog.filter { it.id in installedVoiceIds } }
    var lang by remember { mutableStateOf("hi") }
    var modelId by remember(installed) { mutableStateOf(installed.firstOrNull { it.id.contains("hi") || it.id.contains("multi") || it.id.contains("supertonic") }?.id ?: installed.firstOrNull()?.id ?: "") }
    var linesText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.surface,
        title = { Text("Multi-Language Dubbing ($timelineName)", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("hi" to "Hindi", "es" to "Spanish", "fr" to "French", "ja" to "Japanese", "en" to "English").forEach { (k, l) ->
                        PillChip(l, lang == k) { lang = k }
                    }
                }
                if (installed.isEmpty()) {
                    Text("Download a voice model in Settings first.", color = colors.warning, fontSize = 11.sp)
                } else {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        installed.forEach { vm -> PillChip(vm.name, modelId == vm.id) { modelId = vm.id } }
                    }
                }
                BasicTextField(
                    value = linesText, onValueChange = { linesText = it },
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(8.dp),
                    decorationBox = { inner -> if (linesText.isEmpty()) Text("Paste translated narration lines (1 per cue; leave blank to re-voice existing captions)...", color = colors.textMuted, fontSize = 11.sp); inner() }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (modelId.isNotBlank()) { onRunDub(lang, modelId, linesText); onDismiss() } }) { Text("Dub & Auto-Retime", color = colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } }
    )
}

@Composable
private fun ProInput(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val colors = LocalAtelierColors.current
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceElevated)
            .border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = colors.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() }
    )
}
