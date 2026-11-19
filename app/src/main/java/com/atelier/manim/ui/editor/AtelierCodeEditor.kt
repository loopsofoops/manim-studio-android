package com.atelier.manim.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
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
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val lines = remember(value.text) { value.text.split("\n") }
    val lineStartOffsets = remember(value.text) {
        val arr = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) { arr[i] = acc; acc += lines[i].length + 1 }
        arr
    }
    val maxLineChars = remember(lines) { lines.maxOfOrNull { it.length } ?: 60 }
    val errorLines = remember(diagnostics) { diagnostics.map { it.line }.toSet() }
    val activeLine = remember(value.text, value.selection) {
        val pos = min(value.selection.start, value.text.length)
        value.text.substring(0, pos).count { it == '\n' } + 1
    }

    val lineHeightSp = (fontSizeSp * 1.48f)
    @Suppress("DEPRECATION")
    val noPad = remember { PlatformTextStyle(includeFontPadding = false) }
    val codeStyle = remember(fontSizeSp, colors.textPrimary) {
        TextStyle(color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, lineHeight = lineHeightSp.sp, platformStyle = noPad)
    }
    val gutterStyleNormal = remember(fontSizeSp, colors.textMuted) {
        TextStyle(color = colors.textMuted, fontFamily = FontFamily.Monospace, fontSize = (fontSizeSp - 1).coerceAtLeast(9).sp, fontFeatureSettings = "tnum", platformStyle = noPad)
    }
    val gutterStyleActive = remember(gutterStyleNormal, colors.textPrimary) {
        gutterStyleNormal.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium)
    }
    val gutterStyleError = remember(gutterStyleNormal, colors.error) {
        gutterStyleNormal.copy(color = colors.error, fontWeight = FontWeight.SemiBold)
    }
    val visualTransform = remember(language, colors) { AtelierSyntaxTransformation(language, colors) }

    LaunchedEffect(jumpToLineRequest, textLayoutResult) {
        if (jumpToLineRequest != null && jumpToLineRequest > 0) {
            val targetIdx = (jumpToLineRequest - 1).coerceIn(0, max(0, lines.lastIndex))
            val offset = lineStartOffsets.getOrElse(targetIdx) { 0 }.coerceIn(0, value.text.length)
            onValueChange(value.copy(selection = TextRange(offset)))
            val layout = textLayoutResult
            val scrollPx = if (layout != null && layout.lineCount > 0) {
                max(0, layout.getLineTop(layout.getLineForOffset(offset).coerceIn(0, layout.lineCount - 1)).toInt() - 100)
            } else max(0, (targetIdx * lineHeightSp * 2.4f).toInt() - 100)
            vScroll.animateScrollTo(scrollPx)
            onJumpHandled()
        }
    }

    Column(modifier = modifier.background(colors.background)) {
        if (showFind) {
            Column(
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = findQuery, onValueChange = { findQuery = it }, singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                        decorationBox = { inner -> if (findQuery.isEmpty()) Text("Find...", color = colors.textMuted, fontSize = 12.sp); inner() }
                    )
                    Text(
                        "Next", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            if (findQuery.isNotEmpty()) {
                                val from = min(value.selection.end, value.text.length)
                                var idx = value.text.indexOf(findQuery, from, ignoreCase = true)
                                if (idx < 0) idx = value.text.indexOf(findQuery, 0, ignoreCase = true)
                                if (idx >= 0) onValueChange(value.copy(selection = TextRange(idx, idx + findQuery.length)))
                            }
                        }.padding(horizontal = 7.dp, vertical = 5.dp)
                    )
                    Icon(AtelierIcons.Close, "Close Find", tint = colors.textSecondary, modifier = Modifier.size(18.dp).clickable { showFind = false })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = replaceQuery, onValueChange = { replaceQuery = it }, singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                        decorationBox = { inner -> if (replaceQuery.isEmpty()) Text("Replace with...", color = colors.textMuted, fontSize = 12.sp); inner() }
                    )
                    Text(
                        "Replace", color = colors.textPrimary, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, RoundedCornerShape(6.dp)).clickable {
                                if (findQuery.isNotEmpty()) {
                                    val s = min(value.selection.start, value.selection.end)
                                    val e = max(value.selection.start, value.selection.end)
                                    val sel = if (s < e && e <= value.text.length) value.text.substring(s, e) else ""
                                    if (sel.equals(findQuery, true)) {
                                        val u = value.text.substring(0, s) + replaceQuery + value.text.substring(e)
                                        onValueChange(value.copy(text = u, selection = TextRange(s + replaceQuery.length)))
                                    } else {
                                        val idx = value.text.indexOf(findQuery, min(e, value.text.length), true)
                                            .let { if (it < 0) value.text.indexOf(findQuery, 0, true) else it }
                                        if (idx >= 0) {
                                            val u = value.text.substring(0, idx) + replaceQuery + value.text.substring(idx + findQuery.length)
                                            onValueChange(value.copy(text = u, selection = TextRange(idx + replaceQuery.length)))
                                        }
                                    }
                                }
                            }.padding(horizontal = 7.dp, vertical = 5.dp)
                    )
                    Text(
                        "All", color = colors.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            if (findQuery.isNotEmpty()) {
                                val u = value.text.replace(findQuery, replaceQuery, ignoreCase = true)
                                if (u != value.text) onValueChange(value.copy(text = u, selection = TextRange(min(value.selection.start, u.length))))
                            }
                        }.padding(horizontal = 6.dp, vertical = 5.dp)
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        }

        val gutterWidthDp = remember(lines.size) { when { lines.size >= 10000 -> 58.dp; lines.size >= 1000 -> 52.dp; else -> 46.dp } }
        val contentHeightDp = with(density) {
            val measuredPx = textLayoutResult?.size?.height ?: 0
            val fallbackPx = (max(1, lines.size) * lineHeightSp.sp.toPx()).toInt()
            max(measuredPx, fallbackPx).toDp() + 24.dp
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll)) {
            Canvas(modifier = Modifier.width(gutterWidthDp).height(contentHeightDp).background(colors.surface)) {
                val topPad = 12.dp.toPx()
                val rightPad = 8.dp.toPx()
                val dotR = 2.6.dp.toPx()
                val dotX = 6.dp.toPx()
                val minVisY = vScroll.value.toFloat() - 300f
                val maxVisY = vScroll.value.toFloat() + 3200f

                val layout = textLayoutResult
                if (layout != null && layout.lineCount > 0) {
                    val textLen = value.text.length
                    for (idx in lines.indices) {
                        val visLine = layout.getLineForOffset(min(lineStartOffsets[idx], textLen)).coerceIn(0, layout.lineCount - 1)
                        val lTop = topPad + layout.getLineTop(visLine)
                        val lBot = topPad + layout.getLineBottom(visLine)
                        if (lBot < minVisY || lTop > maxVisY) continue

                        val ln = idx + 1
                        val isErr = ln in errorLines
                        val isCur = ln == activeLine
                        if (isCur) drawRect(colors.surfaceElevated, Offset(0f, lTop), Size(size.width, lBot - lTop))
                        if (isErr) drawCircle(colors.error, dotR, Offset(dotX, (lTop + lBot) * 0.5f))
                        val style = when { isErr -> gutterStyleError; isCur -> gutterStyleActive; else -> gutterStyleNormal }
                        val m = textMeasurer.measure(ln.toString(), style)
                        val y = topPad + layout.getLineBaseline(visLine) - m.firstBaseline
                        val x = max(dotX + dotR + 2f, size.width - rightPad - m.size.width)
                        drawText(m, topLeft = Offset(x, y))
                    }
                }
                drawLine(colors.hairline, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
            }

            val minContentWidth = max(680, maxLineChars * (fontSizeSp * 0.72f).toInt() + 180).dp
            Box(modifier = Modifier.weight(1f).horizontalScroll(hScroll).padding(horizontal = 12.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { onValueChange(applySmartIndent(value, it)) },
                    textStyle = codeStyle,
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = visualTransform,
                    onTextLayout = { textLayoutResult = it },
                    modifier = Modifier.widthIn(min = minContentWidth)
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.hairline)

        val snippets = remember(language) {
            if (language == "LATEX") {
                listOf("    " to "Tab", "\\" to "\\", "{}" to "{}", "$" to "$", "\\frac{}{}" to "frac", "\\int_{}^{}" to "int", "\\sum_{}^{}" to "sum", "\\begin{}\n\n\\end{}" to "env")
            } else {
                listOf("    " to "Tab", "self.play(" to "play(", "MathTex(r\"\")" to "MathTex", "r\"\"" to "r\"\"", "()" to "()", "[]" to "[]", "{}" to "{}", "\\" to "\\", ":" to ":", "=" to "=")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).background(colors.surface)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AccessoryIconBtn(AtelierIcons.Undo, canUndo, onUndo)
            AccessoryIconBtn(AtelierIcons.Redo, canRedo, onRedo)
            AccessoryIconBtn(AtelierIcons.Search, true) { showFind = !showFind }
            Box(Modifier.width(1.dp).height(18.dp).background(colors.hairline))
            for ((insertText, label) in snippets) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.surfaceElevated)
                        .border(1.dp, colors.hairline, RoundedCornerShape(6.dp))
                        .clickable { onValueChange(insertSnippet(value, insertText)) }.padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(label, color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun AccessoryIconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAtelierColors.current
    Box(modifier = Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (enabled) colors.textPrimary else colors.textMuted.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
    }
}

private fun applySmartIndent(old: TextFieldValue, incoming: TextFieldValue): TextFieldValue {
    if (incoming.text.length == old.text.length + 1 && incoming.selection.collapsed) {
        val pos = incoming.selection.start
        if (pos > 0 && incoming.text[pos - 1] == '\n') {
            val prevStart = incoming.text.lastIndexOf('\n', pos - 2).let { if (it < 0) 0 else it + 1 }
            val prevLine = incoming.text.substring(prevStart, pos - 1)
            val indent = prevLine.takeWhile { it == ' ' || it == '\t' } + if (prevLine.trimEnd().endsWith(":")) "    " else ""
            if (indent.isNotEmpty()) {
                return incoming.copy(text = incoming.text.substring(0, pos) + indent + incoming.text.substring(pos), selection = TextRange(pos + indent.length))
            }
        }
    }
    return incoming
}

private fun insertSnippet(cur: TextFieldValue, snippet: String): TextFieldValue {
    val start = min(cur.selection.start, cur.selection.end)
    val end = max(cur.selection.start, cur.selection.end)
    val newText = cur.text.substring(0, start) + snippet + cur.text.substring(end)
    val back = when (snippet) { "{}", "()", "[]", "r\"\"" -> 1; "MathTex(r\"\")" -> 2; else -> 0 }
    return cur.copy(text = newText, selection = TextRange(start + snippet.length - back))
}
