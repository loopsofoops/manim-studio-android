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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.atelier.manim.data.DiffKind
import com.atelier.manim.data.FileRevisionEntity
import com.atelier.manim.data.LineDiff
import com.atelier.manim.data.ProjectFileEntity
import com.atelier.manim.runtime.ComputeProfile
import com.atelier.manim.runtime.DiagnosticItem
import com.atelier.manim.runtime.VoiceDownloadState
import com.atelier.manim.runtime.VoiceEngineKind
import com.atelier.manim.runtime.VoiceModelSpec
import com.atelier.manim.runtime.ExecutionState
import com.atelier.manim.runtime.ManimPreset
import com.atelier.manim.runtime.OutputFormat
import com.atelier.manim.runtime.ReplEntry
import com.atelier.manim.runtime.ReplVar
import com.atelier.manim.runtime.RuntimeState
import com.atelier.manim.runtime.formatBytesShort
import com.atelier.manim.ui.icons.AtelierIcons
import com.atelier.manim.ui.theme.LocalAtelierColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeSetupSheet(
    state: RuntimeState,
    activeManifestUrl: String,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onSetCustomUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showUrlField by remember { mutableStateOf(false) }
    var urlDraft by remember(activeManifestUrl) { mutableStateOf(activeManifestUrl) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Offline Native Runtime", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(
                "One-time verified installation of CPython 3.12, Manim Community Edition, TeX Live, FFmpeg, Cython, and GCC for ARM64. Operates completely offline once installed.",
                fontSize = 12.sp,
                color = colors.textSecondary,
                lineHeight = 18.sp
            )

            when (state) {
                is RuntimeState.Checking -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.accent)
                    Text("Inspecting local runtime...", fontSize = 12.sp, color = colors.textSecondary)
                }
                is RuntimeState.NotInstalled -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val m = state.manifest
                        MetaRow("Download Size", m?.let { formatBytesShort(it.compressedBytes) } ?: "Waiting for runtime-v1")
                        MetaRow("Installed Footprint", m?.let { formatBytesShort(it.estimatedExtractedBytes) } ?: "~1.4 GB")
                        MetaRow("Available Storage", formatBytesShort(state.freeBytes))
                        if (m != null) {
                            MetaRow("Toolchain", "Python ${m.pythonVersion} · Manim ${m.manimVersion} · GCC ${m.gccVersion}")
                        }
                        if (state.statusMessage.isNotBlank()) {
                            Text(state.statusMessage, color = colors.warning, fontSize = 11.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onInstall,
                            enabled = state.prootPresent,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Download & Install", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                        OutlinedButton(onClick = onRefresh) {
                            Text("Refresh", color = colors.textPrimary)
                        }
                    }
                }
                is RuntimeState.Downloading -> {
                    val frac = if (state.totalBytes > 0L) (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { frac }, color = colors.accent, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatBytesShort(state.downloadedBytes)} / ${formatBytesShort(state.totalBytes)} · ${formatBytesShort(state.speedBytesPerSec)}/s",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Pause / Cancel", color = colors.error)
                    }
                }
                is RuntimeState.Verifying -> {
                    LinearProgressIndicator(progress = { state.progress }, color = colors.accent, modifier = Modifier.fillMaxWidth())
                    Text("Verifying SHA-256 checksum (${(state.progress * 100).roundToInt()}%)...", fontSize = 12.sp, color = colors.textSecondary)
                }
                is RuntimeState.Extracting -> {
                    LinearProgressIndicator(progress = { state.progress }, color = colors.accent, modifier = Modifier.fillMaxWidth())
                    Text(
                        "Extracting ${state.extractedFiles} files (${(state.progress * 100).roundToInt()}%): ${state.currentEntry}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is RuntimeState.SelfTesting -> {
                    LinearProgressIndicator(color = colors.accent, modifier = Modifier.fillMaxWidth())
                    Text(state.step, fontSize = 12.sp, color = colors.textSecondary)
                }
                is RuntimeState.Ready -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        MetaRow("Status", "Verified & Offline Ready")
                        MetaRow("Python", state.info.pythonVersion)
                        MetaRow("ManimCE", state.info.manimVersion)
                        MetaRow("GCC / Cython", state.info.gccVersion)
                        MetaRow("PRoot Mode", if (state.info.useNoSeccomp) "Compatible (No-Seccomp)" else "Seccomp")
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue to Workspace", color = Color.White)
                    }
                }
                is RuntimeState.Error -> {
                    Text(state.message, color = colors.error, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onInstall,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retry", color = Color.White)
                        }
                        OutlinedButton(onClick = onRefresh) { Text("Check Status", color = colors.textPrimary) }
                    }
                }
            }

            Text(
                text = if (showUrlField) "Hide Manifest Override" else "Configure Custom Manifest URL",
                color = colors.textMuted,
                fontSize = 11.sp,
                modifier = Modifier.clickable { showUrlField = !showUrlField }
            )
            if (showUrlField) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AtelierInputBox(value = urlDraft, onValueChange = { urlDraft = it }, placeholder = "https://.../rootfs-manifest.json", modifier = Modifier.weight(1f))
                    Text(
                        "Apply",
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onSetCustomUrl(urlDraft) }.padding(6.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    files: List<ProjectFileEntity>,
    activeFileId: Long?,
    onSelectFile: (ProjectFileEntity) -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateShowcase: (String) -> Unit,
    onDeleteFile: (ProjectFileEntity) -> Unit,
    onImportFiles: () -> Unit,
    onExportZip: () -> Unit,
    onSyncDisk: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf("") }
    var newLang by remember { mutableStateOf("PYTHON_MANIM") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Workspace Files", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("Import", true) { onDismiss(); onImportFiles() }
                    PillChip("ZIP", false) { onDismiss(); onExportZip() }
                    PillChip("Sync", false) { onSyncDisk() }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AtelierInputBox(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = "File or folder/file (e.g. ep1/scene.py)",
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accent)
                        .clickable {
                            if (newName.isNotBlank()) {
                                val lang = if (newName.endsWith(".tex", true)) "LATEX" else newLang
                                onCreateFile(newName, lang)
                                newName = ""
                                onDismiss()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text("Create", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("PYTHON_MANIM" to "Manim", "PYTHON" to "Python", "LATEX" to "LaTeX").forEach { (k, label) ->
                    PillChip(label = label, selected = newLang == k) { newLang = k }
                }
                PillChip("+ Narrated Euler", false) { onCreateShowcase("narrated_euler"); onDismiss() }
                PillChip("+ Epicycles HD", false) { onCreateShowcase("fourier_hd"); onDismiss() }
            }

            HorizontalDivider(color = colors.hairline)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(files, key = { it.id }) { item ->
                    val isSelected = item.id == activeFileId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) colors.surfaceElevated else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) colors.accent.copy(alpha = 0.6f) else colors.hairline,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectFile(item); onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.relativePath,
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                "${item.language} · ${formatTimestamp(item.updatedAt)}",
                                color = colors.textMuted,
                                fontSize = 11.sp
                            )
                        }
                        if (files.size > 1) {
                            Icon(
                                imageVector = AtelierIcons.Trash,
                                contentDescription = "Delete",
                                tint = colors.textMuted,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDeleteFile(item) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileHistorySheet(
    fileName: String,
    currentCode: String,
    revisions: List<FileRevisionEntity>,
    readContent: (FileRevisionEntity) -> String,
    onRestore: (FileRevisionEntity) -> Unit,
    onPreviewOutput: (FileRevisionEntity) -> Unit,
    onManualSnapshot: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var inspecting by remember { mutableStateOf<FileRevisionEntity?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("File History", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    Text(fileName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary)
                }
                PillChip(label = "Save Snapshot", selected = true, onClick = onManualSnapshot)
            }

            if (inspecting != null) {
                val rev = inspecting!!
                val revText = remember(rev.id) { readContent(rev) }
                val diffLines = remember(rev.id, currentCode) { LineDiff.compute(revText, currentCode) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Back to Timeline",
                        color = colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { inspecting = null }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rev.outputPath.isNotBlank()) {
                            PillChip("Preview Output", false) { onPreviewOutput(rev); onDismiss() }
                        }
                        PillChip("Restore", true) { onRestore(rev); onDismiss() }
                    }
                }
                HorizontalDivider(color = colors.hairline)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.background)
                        .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                        .padding(6.dp)
                ) {
                    items(diffLines) { dl ->
                        val bg = when (dl.kind) {
                            DiffKind.ADDED -> colors.diffAddedBg
                            DiffKind.REMOVED -> colors.diffRemovedBg
                            DiffKind.SAME -> Color.Transparent
                        }
                        val prefix = when (dl.kind) {
                            DiffKind.ADDED -> "+"
                            DiffKind.REMOVED -> "-"
                            DiffKind.SAME -> " "
                        }
                        val fg = when (dl.kind) {
                            DiffKind.ADDED -> colors.success
                            DiffKind.REMOVED -> colors.error
                            DiffKind.SAME -> colors.textSecondary
                        }
                        Text(
                            text = "$prefix ${dl.text}",
                            color = fg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            } else {
                HorizontalDivider(color = colors.hairline)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(revisions, key = { it.id }) { rev ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                                .clickable { inspecting = rev }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(rev.label, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    rev.triggerType,
                                    color = if (rev.triggerType == "RENDER_OK") colors.success else colors.textMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                "${formatTimestamp(rev.createdAt)} · ${rev.lineCount} lines · ${formatBytesShort(rev.sizeBytes)}",
                                color = colors.textSecondary,
                                fontSize = 11.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                PillChip("Diff", false) { inspecting = rev }
                                PillChip("Restore", false) { onRestore(rev); onDismiss() }
                                if (rev.outputPath.isNotBlank()) {
                                    PillChip("1:1 Output", true) { onPreviewOutput(rev); onDismiss() }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleDrawerSheet(
    execution: ExecutionState,
    consoleLog: String,
    replEntries: List<ReplEntry>,
    replVars: List<ReplVar>,
    replBusy: Boolean,
    onSendStdin: (String) -> Unit,
    onClearConsole: () -> Unit,
    onCopyErrors: () -> Unit,
    onCopyFullLog: () -> Unit,
    onCopyIssues: () -> Unit,
    onEvalRepl: (String) -> Unit,
    onResetRepl: () -> Unit,
    onRunPip: (String, String) -> Unit,
    onRunShell: (String) -> Unit,
    onCompileBytecode: () -> Unit,
    onCompileNativeCython: () -> Unit,
    onJumpToLine: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(0) }
    var stdinDraft by remember { mutableStateOf("") }
    var replDraft by remember { mutableStateOf("") }
    var pipDraft by remember { mutableStateOf("") }
    var shellDraft by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Segmented Apple-style selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val labels = listOf(
                    "Console",
                    "REPL",
                    "Compiler",
                    "Packages",
                    "Issues (${execution.diagnostics.size})"
                )
                labels.forEachIndexed { idx, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (tab == idx) colors.surface else Color.Transparent)
                            .clickable { tab = idx }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (tab == idx) colors.textPrimary else colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (tab == idx) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }

            when (tab) {
                0 -> {
                    // Console + Copy buttons + live stdin + quick shell
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            execution.stageLabel.ifBlank { "Idle" },
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Copy Errors", color = colors.error, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onCopyErrors))
                            Text("Copy Log", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onCopyFullLog))
                            Text("Clear", color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onClearConsole))
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.background)
                            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = consoleLog.ifBlank { "No console output yet." },
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val hint = if (execution.isRunning) "Send stdin line..." else "Run Linux shell command..."
                        AtelierInputBox(
                            value = if (execution.isRunning) stdinDraft else shellDraft,
                            onValueChange = { if (execution.isRunning) stdinDraft = it else shellDraft = it },
                            placeholder = hint,
                            modifier = Modifier.weight(1f)
                        )
                        PillChip(if (execution.isRunning) "Send" else "Exec", true) {
                            if (execution.isRunning) {
                                onSendStdin(stdinDraft); stdinDraft = ""
                            } else {
                                onRunShell(shellDraft); shellDraft = ""
                            }
                        }
                    }
                }
                1 -> {
                    // Persistent Interactive Python REPL
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("CPython 3.12 Interactive Interpreter", color = colors.textSecondary, fontSize = 11.sp)
                        Text("Reset", color = colors.error, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onResetRepl))
                    }
                    if (replVars.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            replVars.forEach { v ->
                                Text(
                                    text = "${v.name}: ${v.type} = ${v.valueRepr}",
                                    color = colors.textSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.surfaceElevated)
                                        .border(1.dp, colors.hairline, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.background)
                            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(replEntries, key = { it.id }) { e ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(">>> ${e.input}", color = colors.accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                if (e.stdout.isNotBlank()) {
                                    Text(e.stdout, color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                if (e.latex.isNotBlank()) {
                                    Text("LaTeX: ${e.latex}", color = colors.synRawLatex, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                if (e.stderr.isNotBlank()) {
                                    Text(e.stderr, color = colors.error, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AtelierInputBox(
                            value = replDraft,
                            onValueChange = { replDraft = it },
                            placeholder = "import sympy as sp; sp.integrate(sp.sin(sp.Symbol('x')))",
                            modifier = Modifier.weight(1f)
                        )
                        PillChip(if (replBusy) "..." else "Eval", true) {
                            if (!replBusy && replDraft.isNotBlank()) {
                                onEvalRepl(replDraft)
                                replDraft = ""
                            }
                        }
                    }
                }
                2 -> {
                    // Python Bytecode / AST / Native Cython+GCC Compiler
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillChip("Verify AST & Bytecode", true, onCompileBytecode)
                        PillChip("Build Native Cython (.so)", false, onCompileNativeCython)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.background)
                            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = execution.compilerReport.ifBlank { "Run AST & Bytecode or Native Cython / GCC compilation to inspect output." },
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                3 -> {
                    // pip Package Manager
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AtelierInputBox(
                            value = pipDraft,
                            onValueChange = { pipDraft = it },
                            placeholder = "Package name (e.g. pandas, manim-slides)",
                            modifier = Modifier.weight(1f)
                        )
                        PillChip("Install", true) {
                            if (pipDraft.isNotBlank()) {
                                onRunPip("install", pipDraft)
                                pipDraft = ""
                                tab = 0
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillChip("List Installed", false) { onRunPip("list", ""); tab = 0 }
                    }
                    Text(
                        "Packages install into persistent /user-site storage and remain intact across runtime upgrades. Full GCC/G++ and Cython toolchains are available for source wheels.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.weight(1f))
                }
                4 -> {
                    // Diagnostics
                    if (execution.diagnostics.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No errors or warnings detected.", color = colors.textMuted, fontSize = 12.sp)
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Tap an issue to jump to its line", color = colors.textSecondary, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Copy Issues", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onCopyIssues))
                                Text("Copy Full Report", color = colors.error, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onCopyErrors))
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            items(execution.diagnostics) { d ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceElevated)
                                        .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                                        .clickable { onJumpToLine(d.line); onDismiss() }
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "L${d.line}",
                                        color = colors.error,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(d.message, color = colors.textPrimary, fontSize = 12.sp)
                                        Text(d.source, color = colors.textMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    preset: ManimPreset,
    outputFormat: OutputFormat,
    customW: Int,
    customH: Int,
    customFps: Int,
    disableCache: Boolean,
    autoSaveStorage: Boolean,
    autoExportCrashReports: Boolean,
    computeProfile: ComputeProfile,
    voiceCatalog: List<VoiceModelSpec>,
    installedVoiceIds: Set<String>,
    voiceDownloadState: VoiceDownloadState,
    isDark: Boolean,
    fontSizeSp: Int,
    useNoSeccomp: Boolean,
    onSelectPreset: (ManimPreset) -> Unit,
    onSelectFormat: (OutputFormat) -> Unit,
    onCustomDims: (Int, Int, Int) -> Unit,
    onSelectComputeProfile: (ComputeProfile) -> Unit,
    onInstallVoice: (String) -> Unit,
    onCancelVoiceDownload: () -> Unit,
    onTestVoice: (String) -> Unit,
    onDeleteVoice: (String) -> Unit,
    onToggleCache: (Boolean) -> Unit,
    onToggleAutoSave: (Boolean) -> Unit,
    onToggleAutoExportCrash: (Boolean) -> Unit,
    onToggleTheme: () -> Unit,
    onFontSize: (Int) -> Unit,
    onToggleNoSeccomp: (Boolean) -> Unit,
    onClearManimCache: () -> Unit,
    onOpenBrandKit: () -> Unit,
    onOpenGitPanel: () -> Unit,
    onOpenBatchQueue: () -> Unit,
    onOpenInspector: () -> Unit,
    onOpenRuntimeSetup: () -> Unit,
    onReinstallRuntime: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var wText by remember(customW) { mutableStateOf(customW.toString()) }
    var hText by remember(customH) { mutableStateOf(customH.toString()) }
    var fpsText by remember(customFps) { mutableStateOf(customFps.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Studio & Render Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)

            SectionTitle("Manim Resolution & Frame Rate")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ManimPreset.entries.forEach { p ->
                    PillChip(p.shortLabel, preset == p) { onSelectPreset(p) }
                }
            }
            if (preset == ManimPreset.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AtelierInputBox(wText, { wText = it; onCustomDims(it.toIntOrNull() ?: customW, customH, customFps) }, "Width", Modifier.weight(1f))
                    AtelierInputBox(hText, { hText = it; onCustomDims(customW, it.toIntOrNull() ?: customH, customFps) }, "Height", Modifier.weight(1f))
                    AtelierInputBox(fpsText, { fpsText = it; onCustomDims(customW, customH, it.toIntOrNull() ?: customFps) }, "FPS", Modifier.weight(1f))
                }
            }

            SectionTitle("Output Format")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutputFormat.entries.forEach { f ->
                    PillChip(f.label, outputFormat == f) { onSelectFormat(f) }
                }
            }

            HorizontalDivider(color = colors.hairline)

            SectionTitle("CPU Thread Profile (OpenMP / OpenBLAS)")
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ComputeProfile.entries.forEach { cp -> PillChip(cp.label, computeProfile == cp) { onSelectComputeProfile(cp) } }
            }

            SettingSwitchRow("Auto-Save Renders to Device Storage", "Saves MP4 to Movies/Atelier, PDF to Documents/Atelier", autoSaveStorage, onToggleAutoSave)
            SettingSwitchRow("Auto-Save Crash & Failure Reports", "Saves failed run & crash logs to Documents/Atelier/CrashReports", autoExportCrashReports, onToggleAutoExportCrash)
            SettingSwitchRow("Disable Manim Partial Movie Cache", "Forces clean full re-render on every run", disableCache, onToggleCache)
            SettingSwitchRow("Obsidian Dark Appearance", "Switch between Obsidian dark and Alabaster light", isDark) { onToggleTheme() }
            SettingSwitchRow("PRoot No-Seccomp Mode", "Recommended for Samsung, Xiaomi, OnePlus & Android 14+", useNoSeccomp, onToggleNoSeccomp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Editor Font Size (${fontSizeSp}sp)", color = colors.textPrimary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("-", false) { onFontSize(fontSizeSp - 1) }
                    PillChip("+", false) { onFontSize(fontSizeSp + 1) }
                }
            }

            HorizontalDivider(color = colors.hairline)
            SectionTitle("Offline Neural Voice Models (sherpa-onnx)")
            if (voiceDownloadState.activeModelId.isNotBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceElevated)
                        .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(voiceDownloadState.stage, color = colors.textPrimary, fontSize = 11.sp)
                    if (voiceDownloadState.progress in 0f..1f) {
                        LinearProgressIndicator(progress = { voiceDownloadState.progress }, color = colors.accent, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(color = colors.accent, modifier = Modifier.fillMaxWidth())
                    }
                    Text("Cancel Download", color = colors.error, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onCancelVoiceDownload))
                }
            }
            if (voiceDownloadState.error.isNotBlank()) {
                Text(voiceDownloadState.error, color = colors.error, fontSize = 11.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                voiceCatalog.forEach { vm ->
                    val installed = vm.id in installedVoiceIds
                    val busy = voiceDownloadState.activeModelId.isNotBlank()
                    Column(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(10.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.name, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${vm.approxSizeLabel} · ${vm.license}", color = colors.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(vm.description, color = colors.textSecondary, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 3.dp)) {
                            if (installed) {
                                val isAsr = vm.kind == VoiceEngineKind.ASR_ZIPFORMER || vm.kind == VoiceEngineKind.ASR_SENSEVOICE
                                if (isAsr) PillChip("Aligner Ready", true) { }
                                else PillChip("Test Voice", true) { onTestVoice(vm.id) }
                                PillChip("Remove", false) { onDeleteVoice(vm.id) }
                            } else {
                                PillChip(if (busy) "Busy..." else "Download", !busy) { if (!busy) onInstallVoice(vm.id) }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.hairline)
            SectionTitle("Pro Studio Tools")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                PillChip("Brand Kit & Blueprint", true) { onDismiss(); onOpenBrandKit() }
                PillChip("Git Panel", false) { onDismiss(); onOpenGitPanel() }
                PillChip("Batch Queue", false) { onDismiss(); onOpenBatchQueue() }
            }

            HorizontalDivider(color = colors.hairline)
            SectionTitle("Diagnostics, Maintenance & Runtime")
            OutlinedButton(onClick = { onDismiss(); onOpenInspector() }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Studio Inspector & Crash Reports", color = colors.accent, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onClearManimCache, modifier = Modifier.weight(1f)) {
                    Text("Clear Render Cache", color = colors.textPrimary, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { onDismiss(); onOpenRuntimeSetup() }, modifier = Modifier.weight(1f)) {
                    Text("Runtime Status", color = colors.textPrimary, fontSize = 12.sp)
                }
            }
            OutlinedButton(
                onClick = { onDismiss(); onReinstallRuntime() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reinstall Offline Runtime", color = colors.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = LocalAtelierColors.current
    Text(text, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun MetaRow(label: String, value: String) {
    val colors = LocalAtelierColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Text(value, color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalAtelierColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 13.sp)
            Text(subtitle, color = colors.textMuted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAtelierColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accent else colors.surfaceElevated)
            .border(1.dp, if (selected) colors.accent else colors.hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AtelierInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalAtelierColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceElevated)
            .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = colors.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            inner()
        }
    )
}

private fun formatTimestamp(ms: Long): String {
    return runCatching {
        SimpleDateFormat("MMM d · HH:mm", Locale.US).format(Date(ms))
    }.getOrDefault("")
}
