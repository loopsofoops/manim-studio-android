package com.atelier.manim.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.atelier.manim.ui.theme.AtelierColors

private val PY_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break",
    "class", "continue", "def", "del", "elif", "else", "except", "finally",
    "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
    "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "self"
)

private val MANIM_TYPES = setOf(
    "Scene", "ThreeDScene", "MovingCameraScene", "ZoomedScene", "VectorScene",
    "MathTex", "Tex", "Text", "Title", "Axes", "ThreeDAxes", "NumberPlane",
    "Circle", "Square", "Rectangle", "Dot", "Line", "DashedLine", "Arrow",
    "Vector", "Polygon", "Surface", "VGroup", "VMobject", "Mobject", "ValueTracker",
    "Write", "Create", "FadeIn", "FadeOut", "Transform", "ReplacementTransform",
    "Indicate", "GrowFromCenter", "Rotate", "always_redraw", "PI", "TAU", "DEGREES",
    "UP", "DOWN", "LEFT", "RIGHT", "UL", "UR", "DL", "DR", "ORIGIN"
)

class AtelierSyntaxTransformation(
    private val language: String,
    private val colors: AtelierColors
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val highlighted = if (language == "LATEX") highlightLatex(raw, colors) else highlightPython(raw, colors)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

private fun highlightPython(src: String, c: AtelierColors): AnnotatedString = buildAnnotatedString {
    append(src)
    val n = src.length
    var i = 0
    while (i < n) {
        val ch = src[i]
        when {
            ch == '#' -> {
                val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                addStyle(SpanStyle(color = c.synComment), i, end)
                i = end
            }
            (ch == 'r' || ch == 'R' || ch == 'f' || ch == 'F') && i + 1 < n && (src[i + 1] == '"' || src[i + 1] == '\'') -> {
                val isRaw = ch == 'r' || ch == 'R'
                val start = i
                val q = src[i + 1]
                val triple = i + 3 < n && src[i + 2] == q && src[i + 3] == q
                i += if (triple) 4 else 2
                while (i < n) {
                    if (triple) {
                        if (i + 2 < n && src[i] == q && src[i + 1] == q && src[i + 2] == q) {
                            i += 3; break
                        }
                        i++
                    } else {
                        if (src[i] == '\\' && i + 1 < n) { i += 2; continue }
                        if (src[i] == q || src[i] == '\n') { if (src[i] == q) i++; break }
                        i++
                    }
                }
                addStyle(SpanStyle(color = if (isRaw) c.synRawLatex else c.synString), start, i)
            }
            ch == '"' || ch == '\'' -> {
                val start = i
                val triple = i + 2 < n && src[i + 1] == ch && src[i + 2] == ch
                i += if (triple) 3 else 1
                while (i < n) {
                    if (triple) {
                        if (i + 2 < n && src[i] == ch && src[i + 1] == ch && src[i + 2] == ch) {
                            i += 3; break
                        }
                        i++
                    } else {
                        if (src[i] == '\\' && i + 1 < n) { i += 2; continue }
                        if (src[i] == ch || src[i] == '\n') { if (src[i] == ch) i++; break }
                        i++
                    }
                }
                addStyle(SpanStyle(color = c.synString), start, i)
            }
            ch == '@' -> {
                val start = i
                i++
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.')) i++
                addStyle(SpanStyle(color = c.synCommand), start, i)
            }
            ch.isDigit() -> {
                val start = i
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '.' || src[i] == '_')) i++
                addStyle(SpanStyle(color = c.synNumber), start, i)
            }
            ch.isLetter() || ch == '_' -> {
                val start = i
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                val word = src.substring(start, i)
                when {
                    word in PY_KEYWORDS -> addStyle(SpanStyle(color = c.synKeyword), start, i)
                    word in MANIM_TYPES -> addStyle(SpanStyle(color = c.synType), start, i)
                }
            }
            else -> i++
        }
    }
}

private fun highlightLatex(src: String, c: AtelierColors): AnnotatedString = buildAnnotatedString {
    append(src)
    val n = src.length
    var i = 0
    while (i < n) {
        val ch = src[i]
        when {
            ch == '%' -> {
                val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                addStyle(SpanStyle(color = c.synComment), i, end)
                i = end
            }
            ch == '\\' -> {
                val start = i
                i++
                if (i < n && src[i].isLetter()) {
                    while (i < n && (src[i].isLetter() || src[i] == '*')) i++
                } else if (i < n) {
                    i++
                }
                addStyle(SpanStyle(color = c.synCommand), start, i)
            }
            ch == '$' -> {
                val start = i
                val dbl = i + 1 < n && src[i + 1] == '$'
                i += if (dbl) 2 else 1
                while (i < n) {
                    if (src[i] == '\\' && i + 1 < n) { i += 2; continue }
                    if (dbl && i + 1 < n && src[i] == '$' && src[i + 1] == '$') { i += 2; break }
                    if (!dbl && src[i] == '$') { i++; break }
                    i++
                }
                addStyle(SpanStyle(color = c.synRawLatex), start, i)
            }
            ch == '{' || ch == '}' || ch == '[' || ch == ']' -> {
                addStyle(SpanStyle(color = c.synType), i, i + 1)
                i++
            }
            else -> i++
        }
    }
}
