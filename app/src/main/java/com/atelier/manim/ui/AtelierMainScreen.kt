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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelier.manim.runtime.OutputKind
import com.atelier.manim.runtime.RuntimeState
import com.atelier.manim.ui.editor.AtelierCodeEditor
import com.atelier.manim.ui.icons.AtelierIcons
import com.atelier.manim.ui.preview.ExactPreviewStage
import com.atelier.manim.ui.theme.LocalAtelierColors

@Composable
fun AtelierMainScreen(
    viewModel: AtelierViewModel,
    onRequestNotificationPermission: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val context = LocalContext.current

    val runtimeState by viewModel.runtimeState.collectAsState()
    val execution by viewModel.executionState.collectAsState()
    val files by viewModel.files.collectAsState()
    val activeFile by viewModel.activeFile.collectAsState()
    val revisions by viewModel.revisions.collectAsState()
    val editorValue by viewModel.editorValue.collectAsState()
    val isDirty by viewModel.isDirty.collectAsState()
    val detectedScenes by viewModel.detectedScenes.collectAsState()
    val selectedScene by viewModel.selectedScene.collectAsState()
    val preset by viewModel.preset.collectAsState()
    val outputFormat by viewModel.outputFormat.collectAsState()
    val customW by viewModel.customWidth.collectAsState()
    val customH by viewModel.customHeight.collectAsState()
    val customFps by viewModel.customFps.collectAsState()
    val disableCache by viewModel.disableCache.collectAsState()
    val autoSaveStorage by viewModel.autoSaveStorage.collectAsState()
    val isDark by viewModel.isDarkTheme.collectAsState()
    val fontSizeSp by viewModel.fontSizeSp.collectAsState()
    val replEntries by viewModel.replEntries.collectAsState()
    val replVars by viewModel.replVars.collectAsState()
    val replBusy by viewModel.replBusy.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val toastMsg by viewModel.toastMessage.collectAsState()

    var showStage by remember { mutableStateOf(true) }
    var stageExpanded by remember { mutableStateOf(false) }
    var showRuntimeSheet by remember { mutableStateOf(false) }
    var showLibrarySheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showConsoleSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var jumpToLine by remember { mutableStateOf<Int?>(null) }

    // Automatically open preview stage when a fresh artifact arrives
    LaunchedEffect(execution.activeArtifact.timestamp) {
        if (execution.activeArtifact.kind != OutputKind.NONE) {
            showStage = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Minimalist Apple-Style Top Studio Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(colors.surface)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    TopIconBtn(AtelierIcons.Folder, active = false) { showLibrarySheet = true }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showLibrarySheet = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = activeFile?.name ?: "Atelier",
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isDirty) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TopIconBtn(AtelierIcons.StageToggle, active = showStage) {
                        if (stageExpanded) stageExpanded = false else showStage = !showStage
                    }
                    TopIconBtn(AtelierIcons.History, active = false) { showHistorySheet = true }
                    TopIconBtn(AtelierIcons.Terminal, active = execution.diagnostics.isNotEmpty()) {
                        showConsoleSheet = true
                    }
                    TopIconBtn(AtelierIcons.Settings, active = false) { showSettingsSheet = true }

                    Spacer(Modifier.width(4.dp))

                    val isReady = runtimeState is RuntimeState.Ready
                    val primaryBg = when {
                        execution.isRunning -> colors.error
                        !isReady -> colors.surfaceElevated
                        else -> colors.accent
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(primaryBg)
                            .clickable {
                                when {
                                    execution.isRunning -> viewModel.cancelExecution()
                                    !isReady -> showRuntimeSheet = true
                                    else -> {
                                        onRequestNotificationPermission()
                                        showStage = true
                                        viewModel.runPrimaryAction()
                                    }
                                }
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (execution.isRunning) AtelierIcons.Stop else AtelierIcons.Play,
                            contentDescription = "Execute",
                            tint = if (isReady || execution.isRunning) Color.White else colors.textPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                        val actionText = when {
                            execution.isRunning -> "Stop"
                            !isReady -> "Setup"
                            activeFile?.language == "LATEX" -> "Build"
                            detectedScenes.isNotEmpty() -> "Render"
                            else -> "Run"
                        }
                        Text(
                            text = actionText,
                            color = if (isReady || execution.isRunning) Color.White else colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            // 2. Runtime Banner (only shown if offline runtime is not yet installed)
            if (runtimeState !is RuntimeState.Ready && runtimeState !is RuntimeState.Checking) {
                val promptText = when (val st = runtimeState) {
                    is RuntimeState.Downloading -> "Downloading offline runtime..."
                    is RuntimeState.Verifying -> "Verifying runtime SHA-256..."
                    is RuntimeState.Extracting -> "Extracting runtime (${st.extractedFiles} files)..."
                    is RuntimeState.SelfTesting -> st.step
                    is RuntimeState.Error -> "Runtime error — tap to retry"
                    else -> "Offline Manim / Python / LaTeX / GCC runtime required — tap to install"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceElevated)
                        .clickable { showRuntimeSheet = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(promptText, color = colors.textPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("Open", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            }

            // 3. Context Action Strip (Scene selector + Preset + Pure Python / Compiler quick pills)
            if (!stageExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (detectedScenes.isNotEmpty()) {
                        detectedScenes.forEach { scene ->
                            PillChip(scene, selected = scene == selectedScene) { viewModel.selectScene(scene) }
                        }
                        PillChip(preset.shortLabel, selected = false) { showSettingsSheet = true }
                        PillChip("Python", selected = false) {
                            if (runtimeState !is RuntimeState.Ready) showRuntimeSheet = true else viewModel.runAsPurePython()
                        }
                    } else if (activeFile?.language != "LATEX") {
                        PillChip("AST / Bytecode", selected = false) {
                            if (runtimeState !is RuntimeState.Ready) showRuntimeSheet = true else {
                                viewModel.compileCurrentFile(nativeCython = false)
                                showConsoleSheet = true
                            }
                        }
                        PillChip("Cython / GCC", selected = false) {
                            if (runtimeState !is RuntimeState.Ready) showRuntimeSheet = true else {
                                viewModel.compileCurrentFile(nativeCython = true)
                                showConsoleSheet = true
                            }
                        }
                    } else {
                        PillChip("pdflatex 2-pass · 1:1 PDF", selected = false) { showSettingsSheet = true }
                    }
                    PillChip("Snapshot", selected = false) { viewModel.manualSave() }
                }
                HorizontalDivider(thickness = 1.dp, color = colors.hairline)
            }

            // 4. Collapsible / Expandable Exact 1-to-1 Preview Stage
            if (showStage) {
                ExactPreviewStage(
                    execution = execution,
                    isExpanded = stageExpanded,
                    onToggleExpand = { stageExpanded = !stageExpanded },
                    onSaveToStorage = { viewModel.saveActiveArtifactToStorage() },
                    onShare = { viewModel.shareActiveArtifact(context) },
                    onCancelRender = { viewModel.cancelExecution() },
                    modifier = if (stageExpanded) {
                        Modifier.weight(1f).fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().height(232.dp)
                    }
                )
            }

            // 5. Code Editor Workspace
            if (!stageExpanded) {
                AtelierCodeEditor(
                    value = editorValue,
                    language = activeFile?.language ?: "PYTHON_MANIM",
                    fontSizeSp = fontSizeSp,
                    diagnostics = execution.diagnostics,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    jumpToLineRequest = jumpToLine,
                    onValueChange = { viewModel.onCodeEdited(it) },
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    onJumpHandled = { jumpToLine = null },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }

        // Subtle Status Pill (auto-dismissing toast or execution banner)
        val statusPill = toastMsg.ifBlank { execution.bannerMessage }
        if (statusPill.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 52.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                    .clickable { viewModel.clearBanner() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = statusPill,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showRuntimeSheet) {
        RuntimeSetupSheet(
            state = runtimeState,
            activeManifestUrl = viewModel.getActiveManifestUrl(),
            onInstall = { viewModel.startRuntimeInstall() },
            onCancel = { viewModel.cancelRuntimeInstall() },
            onRefresh = { viewModel.refreshRuntimeState() },
            onSetCustomUrl = { viewModel.setCustomManifestUrl(it) },
            onDismiss = { showRuntimeSheet = false }
        )
    }

    if (showLibrarySheet) {
        LibrarySheet(
            files = files,
            activeFileId = activeFile?.id,
            onSelectFile = { viewModel.openFile(it) },
            onCreateFile = { name, lang -> viewModel.createNewFile(name, lang) },
            onDeleteFile = { viewModel.deleteFile(it) },
            onDismiss = { showLibrarySheet = false }
        )
    }

    if (showHistorySheet) {
        FileHistorySheet(
            fileName = activeFile?.name ?: "",
            currentCode = editorValue.text,
            revisions = revisions,
            readContent = { viewModel.readRevisionContent(it) },
            onRestore = { viewModel.restoreRevision(it) },
            onPreviewOutput = {
                viewModel.previewRevisionOutput(it)
                showStage = true
            },
            onManualSnapshot = { viewModel.manualSave("Manual Checkpoint") },
            onDismiss = { showHistorySheet = false }
        )
    }

    if (showConsoleSheet) {
        ConsoleDrawerSheet(
            execution = execution,
            replEntries = replEntries,
            replVars = replVars,
            replBusy = replBusy,
            onSendStdin = { viewModel.sendStdin(it) },
            onClearConsole = { viewModel.clearConsole() },
            onEvalRepl = { viewModel.evaluateRepl(it) },
            onResetRepl = { viewModel.resetRepl() },
            onRunPip = { act, pkg -> viewModel.runPip(act, pkg) },
            onRunShell = { viewModel.runShell(it) },
            onCompileBytecode = { viewModel.compileCurrentFile(false) },
            onCompileNativeCython = { viewModel.compileCurrentFile(true) },
            onJumpToLine = { jumpToLine = it },
            onDismiss = { showConsoleSheet = false }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            preset = preset,
            outputFormat = outputFormat,
            customW = customW,
            customH = customH,
            customFps = customFps,
            disableCache = disableCache,
            autoSaveStorage = autoSaveStorage,
            isDark = isDark,
            fontSizeSp = fontSizeSp,
            useNoSeccomp = viewModel.getUseNoSeccomp(),
            onSelectPreset = { viewModel.selectPreset(it) },
            onSelectFormat = { viewModel.selectOutputFormat(it) },
            onCustomDims = { w, h, f -> viewModel.setCustomDimensions(w, h, f) },
            onToggleCache = { viewModel.setDisableCache(it) },
            onToggleAutoSave = { viewModel.setAutoSaveStorage(it) },
            onToggleTheme = { viewModel.toggleTheme() },
            onFontSize = { viewModel.setFontSize(it) },
            onToggleNoSeccomp = { viewModel.setUseNoSeccomp(it) },
            onClearManimCache = { viewModel.clearManimCache() },
            onOpenRuntimeSetup = { showRuntimeSheet = true },
            onReinstallRuntime = { viewModel.reinstallRuntime(); showRuntimeSheet = true },
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun TopIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAtelierColors.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.surfaceElevated else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) colors.accent else colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}
