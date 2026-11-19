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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelier.manim.runtime.ControllerMacro
import com.atelier.manim.runtime.ControllerStep
import com.atelier.manim.runtime.ExecutionState
import com.atelier.manim.ui.editor.AtelierCodeEditor
import com.atelier.manim.ui.icons.AtelierIcons
import com.atelier.manim.ui.theme.LocalAtelierColors

@Composable
fun ControlWorkspace(
    controllerValue: TextFieldValue,
    fontSizeSp: Int,
    execution: ExecutionState,
    consoleLog: String,
    steps: List<ControllerStep>,
    macros: List<ControllerMacro>,
    canRollback: Boolean,
    isRuntimeReady: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onPasteAndRun: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onCopyApiAndContext: () -> Unit,
    onCopyOutput: () -> Unit,
    onRollback: () -> Unit,
    onSelectMacro: (ControllerMacro) -> Unit,
    onSaveMacro: (String, String) -> Unit,
    onDeleteMacro: (String) -> Unit,
    onOpenRuntimeSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAtelierColors.current
    var showSaveDialog by remember { mutableStateOf(false) }
    var macroName by remember { mutableStateOf("") }
    var macroDesc by remember { mutableStateOf("") }
    var outputTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        // Action Bar
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (execution.isRunning) colors.error else colors.accent)
                    .clickable {
                        when {
                            execution.isRunning -> onStop()
                            !isRuntimeReady -> onOpenRuntimeSetup()
                            else -> onPasteAndRun()
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(if (execution.isRunning) AtelierIcons.Stop else AtelierIcons.Paste, null, tint = Color.White, modifier = Modifier.size(13.dp))
                Text(if (execution.isRunning) "Stop" else "Paste & Run", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!execution.isRunning) {
                PillChip("Run Script", false) { if (!isRuntimeReady) onOpenRuntimeSetup() else onRun() }
            }
            PillChip("Copy API + Context", false, onCopyApiAndContext)
            PillChip("Save Macro", false) { showSaveDialog = true }
            if (canRollback && !execution.isRunning) {
                PillChip("Undo Run", false, onRollback)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        // Saved Macros Strip
        if (macros.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Macros", color = colors.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                macros.forEach { m ->
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(7.dp))
                            .clickable { onSelectMacro(m) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(m.name, color = colors.textPrimary, fontSize = 11.sp)
                        if (macros.size > 1) {
                            Icon(AtelierIcons.Close, "Delete", tint = colors.textMuted, modifier = Modifier.size(12.dp).clickable { onDeleteMacro(m.id) })
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }

        // Controller Code Editor
        AtelierCodeEditor(
            value = controllerValue,
            language = "CONTROLLER",
            fontSizeSp = fontSizeSp,
            diagnostics = if (execution.isControllerRun) execution.diagnostics else emptyList(),
            canUndo = false,
            canRedo = false,
            jumpToLineRequest = null,
            onValueChange = onValueChange,
            onUndo = {},
            onRedo = {},
            onJumpHandled = {},
            modifier = Modifier.weight(1.15f).fillMaxWidth()
        )
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        // Live Step Feed & Output Panel
        Column(modifier = Modifier.weight(0.85f).fillMaxWidth().background(colors.surface)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("Steps (${steps.size})", outputTab == 0) { outputTab = 0 }
                    PillChip("Output Log", outputTab == 1) { outputTab = 1 }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(execution.stageLabel.ifBlank { "Ready" }, color = colors.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Copy", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onCopyOutput).padding(horizontal = 4.dp))
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)

            if (outputTab == 0) {
                if (steps.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                        Text("Paste any Python script using `import atelier` and tap Paste & Run. Every API action appears here live.", color = colors.textMuted, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(steps, key = { it.id }) { st ->
                            val badgeColor = when (st.status) { "ok" -> colors.success; "error" -> colors.error; else -> colors.accent }
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.background)
                                    .border(1.dp, colors.hairline, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(st.status.uppercase(), color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                Text(st.method, color = colors.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                if (st.detail.isNotBlank()) {
                                    Text(st.detail, color = colors.textSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(consoleLog.ifBlank { "No controller output yet." }, color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = colors.surface,
            title = { Text("Save Controller Macro", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        value = macroName, onValueChange = { macroName = it }, singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp), cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(10.dp),
                        decorationBox = { inner -> if (macroName.isEmpty()) Text("Macro name", color = colors.textMuted, fontSize = 13.sp); inner() }
                    )
                    BasicTextField(
                        value = macroDesc, onValueChange = { macroDesc = it }, singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp), cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(10.dp),
                        decorationBox = { inner -> if (macroDesc.isEmpty()) Text("Description (optional)", color = colors.textMuted, fontSize = 12.sp); inner() }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (macroName.isNotBlank()) { onSaveMacro(macroName, macroDesc); macroName = ""; macroDesc = ""; showSaveDialog = false }
                }) { Text("Save", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel", color = colors.textSecondary) } }
        )
    }
}
