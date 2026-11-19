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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelier.manim.runtime.CrashReportEntry
import com.atelier.manim.ui.theme.LocalAtelierColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioInspectorSheet(
    crashReports: List<CrashReportEntry>,
    initialTab: Int = 0,
    loadSectionText: suspend (Int) -> String,
    readCrashReport: (CrashReportEntry) -> String,
    onCopyText: (String, String) -> Unit,
    onExportTextFile: (String, String) -> Unit,
    onClearCrashReports: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var previewText by remember { mutableStateOf("Loading live diagnostics...") }
    var selectedCrash by remember { mutableStateOf<CrashReportEntry?>(null) }

    val tabs = listOf("Full Snapshot", "File Tree", "Active + Errors", "Status & Settings", "Crashes (${crashReports.size})")

    LaunchedEffect(tab, crashReports.size) {
        if (tab < 4) {
            selectedCrash = null
            previewText = loadSectionText(tab)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text("Studio Inspector & Diagnostics", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                Text("Query and copy complete app state, file tree, settings, logs, or crashes", fontSize = 11.sp, color = colors.textSecondary)
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEachIndexed { idx, label -> PillChip(label, tab == idx) { tab = idx } }
            }

            if (tab == 4) {
                if (selectedCrash != null) {
                    val cr = selectedCrash!!
                    val body = remember(cr.id) { readCrashReport(cr) }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Back to Crashes", color = colors.accent, fontSize = 12.sp, modifier = Modifier.clickable { selectedCrash = null })
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PillChip("Copy Report", true) { onCopyText(cr.title, body) }
                            PillChip("Save .txt", false) { onExportTextFile(cr.fileName.substringBeforeLast('.'), body) }
                        }
                    }
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(colors.background).border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState()).padding(10.dp)
                    ) {
                        Text(body.ifBlank { "Empty report." }, color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto-saved in Documents/Atelier/CrashReports", color = colors.textSecondary, fontSize = 11.sp)
                        if (crashReports.isNotEmpty()) {
                            Text("Clear", color = colors.error, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onClearCrashReports))
                        }
                    }
                    if (crashReports.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No crashes or failed runs recorded.", color = colors.textMuted, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                            items(crashReports, key = { it.id }) { cr ->
                                Column(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                                        .clickable { selectedCrash = cr }.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(cr.title, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text(cr.kind, color = colors.error, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }
                                    Text(cr.summary, color = colors.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(SimpleDateFormat("MMM d · HH:mm:ss", Locale.US).format(Date(cr.timestampMs)), color = colors.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            PillChip("View", false) { selectedCrash = cr }
                                            PillChip("Copy", true) { onCopyText(cr.title, readCrashReport(cr)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillChip("Copy ${tabs[tab]}", true) { onCopyText(tabs[tab], previewText) }
                    PillChip("Save .txt to Docs", false) {
                        val slug = tabs[tab].lowercase().replace(Regex("[^a-z0-9]+"), "_")
                        onExportTextFile("atelier_$slug", previewText)
                    }
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(colors.background).border(1.dp, colors.hairline, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState()).padding(10.dp)
                ) {
                    Text(previewText, color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun AnimRangeDialog(
    initialStart: Int,
    initialEnd: Int,
    onApply: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAtelierColors.current
    var startStr by remember(initialStart) { mutableStateOf(initialStart.toString()) }
    var endStr by remember(initialEnd) { mutableStateOf(initialEnd.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Animation Range (-n)", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Render only animations Start..End (0-indexed) to skip early sections of long scenes.", color = colors.textSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Start #" to startStr, "End #" to endStr).forEachIndexed { idx, (lbl, v) ->
                        Column(Modifier.weight(1f)) {
                            Text(lbl, color = colors.textMuted, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            BasicTextField(
                                value = v,
                                onValueChange = { s -> val d = s.filter { it.isDigit() }; if (idx == 0) startStr = d else endStr = d },
                                singleLine = true,
                                textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceElevated).border(1.dp, colors.hairline, RoundedCornerShape(8.dp)).padding(10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = startStr.toIntOrNull() ?: 0
                val e = (endStr.toIntOrNull() ?: s).coerceAtLeast(s)
                onApply(s, e); onDismiss()
            }) { Text("Apply", color = colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } }
    )
}
