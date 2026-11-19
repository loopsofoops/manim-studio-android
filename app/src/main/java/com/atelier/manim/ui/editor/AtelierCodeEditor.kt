package com.atelier.manim.ui.editor

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atelier.manim.runtime.DiagnosticItem
import com.atelier.manim.ui.icons.AtelierIcons
import com.atelier.manim.ui.theme.LocalAtelierColors
import kotlin.math.max
import kotlin.math.min

@Composable
fun AtelierCodeEditor(
    value: TextFieldValue,
    language: String,
    fontSizeSp: Int,
    diagnostics: List<DiagnosticItem>,
    canUndo: Boolean,
    canRedo: Boolean,
    jumpToLineRequest: Int?,
    onValueChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onJumpHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAtelierColors.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }

    val lines = remember(value.text) { value.text.split("\n") }
    val lineCount = max(1, lines.size)
    val maxLineChars = remember(lines) { lines.maxOfOrNull { it.length } ?: 60 }
    val errorLines = remember(diagnostics) { diagnostics.map { it.line }.toSet() }
    val activeLine = remember(value.text, value.selection) {
        val pos = min(value.selection.start, value.text.length)
        value.text.substring(0, pos).count { it == '\n' } + 1
    }

    val lineHeightSp = (fontSizeSp * 1.48f)
    val codeStyle = remember(fontSizeSp, colors.textPrimary) {
        TextStyle(
            color = colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp
        )
    }
    val visualTransform = remember(language, colors) {
        AtelierSyntaxTransformation(language, colors)
    }

    LaunchedEffect(jumpToLineRequest) {
        if (jumpToLineRequest != null && jumpToLineRequest > 0) {
            val targetIdx = min(jumpToLineRequest - 1, lines.lastIndex)
            var offset = 0
            for (i in 0 until targetIdx) offset += lines[i].length + 1
            onValueChange(value.copy(selection = TextRange(min(offset, value.text.length))))
            val estPx = (targetIdx * lineHeightSp * 2.5f).toInt()
            vScroll.animateScrollTo(max(0, estPx - 120))
            onJumpHandled()
        }
    }

    Column(modifier = modifier.background(colors.background)) {
        if (showFind) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = findQuery,
                    onValueChange = { findQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    decorationBox = { inner ->
                        if (findQuery.isEmpty()) Text("Find in file...", color = colors.textMuted, fontSize = 13.sp)
                        inner()
                    }
                )
                Text(
                    text = "Next",
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            if (findQuery.isNotEmpty()) {
                                val from = min(value.selection.end, value.text.length)
                                var idx = value.text.indexOf(findQuery, from, ignoreCase = true)
                                if (idx < 0) idx = value.text.indexOf(findQuery, 0, ignoreCase = true)
                                if (idx >= 0) {
                                    onValueChange(value.copy(selection = TextRange(idx, idx + findQuery.length)))
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                Icon(
                    imageVector = AtelierIcons.Close,
                    contentDescription = "Close Find",
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { showFind = false }
                )
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }

        // Synchronized Gutter + Code Area
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(vScroll)
        ) {
            // Line number gutter
            Column(
                modifier = Modifier
                    .width(46.dp)
                    .background(colors.surface)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.End
            ) {
                for (ln in 1..lineCount) {
                    val isErr = ln in errorLines
                    val isCur = ln == activeLine
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isErr) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(colors.error)
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(
                            text = ln.toString(),
                            style = TextStyle(
                                color = when {
                                    isErr -> colors.error
                                    isCur -> colors.textPrimary
                                    else -> colors.textMuted
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSizeSp.sp,
                                lineHeight = lineHeightSp.sp,
                                fontFeatureSettings = "tnum",
                                textAlign = TextAlign.End
                            )
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.hairline)
            )

            // Non-wrapping horizontal-scroll code viewport for exact 1:1 line-number alignment
            val minContentWidth = max(620, maxLineChars * (fontSizeSp * 0.68f).toInt() + 140).dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { incoming ->
                        onValueChange(applySmartIndent(value, incoming))
                    },
                    textStyle = codeStyle,
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = visualTransform,
                    modifier = Modifier.widthIn(min = minContentWidth)
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        // Compact Keyboard Accessory Bar
        val snippets = remember(language) {
            if (language == "LATEX") {
                listOf("    " to "Tab", "\\" to "\\", "{}" to "{}", "$" to "$", "\\frac{}{}" to "frac", "\\int_{}^{}" to "int", "\\sum_{}^{}" to "sum", "\\begin{}\n\n\\end{}" to "env")
            } else {
                listOf("    " to "Tab", "self.play(" to "play(", "MathTex(r\"\")" to "MathTex", "r\"\"" to "r\"\"", "()" to "()", "[]" to "[]", "{}" to "{}", "\\" to "\\", ":" to ":", "=" to "=")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(colors.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AccessoryIconBtn(AtelierIcons.Undo, enabled = canUndo, onClick = onUndo)
            AccessoryIconBtn(AtelierIcons.Redo, enabled = canRedo, onClick = onRedo)
            AccessoryIconBtn(AtelierIcons.Search, enabled = true, onClick = { showFind = !showFind })
            Box(Modifier.width(1.dp).height(18.dp).background(colors.hairline))
            for ((insertText, label) in snippets) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.hairline, RoundedCornerShape(6.dp))
                        .clickable { onValueChange(insertSnippet(value, insertText)) }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = label,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessoryIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAtelierColors.current
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colors.textPrimary else colors.textMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun applySmartIndent(old: TextFieldValue, incoming: TextFieldValue): TextFieldValue {
    if (incoming.text.length == old.text.length + 1 && incoming.selection.collapsed) {
        val pos = incoming.selection.start
        if (pos > 0 && incoming.text[pos - 1] == '\n') {
            val prevLineStart = incoming.text.lastIndexOf('\n', pos - 2).let { if (it < 0) 0 else it + 1 }
            val prevLine = incoming.text.substring(prevLineStart, pos - 1)
            val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
            val extra = if (prevLine.trimEnd().endsWith(":")) "    " else ""
            val indent = baseIndent + extra
            if (indent.isNotEmpty()) {
                val newText = incoming.text.substring(0, pos) + indent + incoming.text.substring(pos)
                return incoming.copy(text = newText, selection = TextRange(pos + indent.length))
            }
        }
    }
    return incoming
}

private fun insertSnippet(cur: TextFieldValue, snippet: String): TextFieldValue {
    val start = min(cur.selection.start, cur.selection.end)
    val end = max(cur.selection.start, cur.selection.end)
    val newText = cur.text.substring(0, start) + snippet + cur.text.substring(end)
    val cursorBack = when {
        snippet == "{}" || snippet == "()" || snippet == "[]" -> 1
        snippet == "r\"\"" -> 1
        snippet == "MathTex(r\"\")" -> 2
        else -> 0
    }
    val targetPos = start + snippet.length - cursorBack
    return cur.copy(text = newText, selection = TextRange(targetPos))
}
