package com.atelier.manim.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class AtelierColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val synKeyword: Color,
    val synType: Color,
    val synString: Color,
    val synRawLatex: Color,
    val synComment: Color,
    val synNumber: Color,
    val synCommand: Color,
    val diffAddedBg: Color,
    val diffRemovedBg: Color
)

val ObsidianColors = AtelierColors(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF0D0D10),
    surfaceElevated = Color(0xFF17171C),
    hairline = Color(0xFF26262E),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF98989F),
    textMuted = Color(0xFF5A5A64),
    accent = Color(0xFF0A84FF),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    error = Color(0xFFFF453A),
    synKeyword = Color(0xFFFF79C6),
    synType = Color(0xFF64D2FF),
    synString = Color(0xFF98E39A),
    synRawLatex = Color(0xFFFFD60A),
    synComment = Color(0xFF63636E),
    synNumber = Color(0xFFBF5AF2),
    synCommand = Color(0xFF5AC8FA),
    diffAddedBg = Color(0x2630D158),
    diffRemovedBg = Color(0x26FF453A)
)

val AlabasterColors = AtelierColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF5F5F7),
    surfaceElevated = Color(0xFFEBEBF0),
    hairline = Color(0xFFDCDCE2),
    textPrimary = Color(0xFF111114),
    textSecondary = Color(0xFF63636A),
    textMuted = Color(0xFF8E8E95),
    accent = Color(0xFF007AFF),
    success = Color(0xFF248A3D),
    warning = Color(0xFFB25000),
    error = Color(0xFFD70015),
    synKeyword = Color(0xFFAD1457),
    synType = Color(0xFF0055B8),
    synString = Color(0xFF1E7E34),
    synRawLatex = Color(0xFF9A6700),
    synComment = Color(0xFF8E8E95),
    synNumber = Color(0xFF6F42C1),
    synCommand = Color(0xFF0066CC),
    diffAddedBg = Color(0x22248A3D),
    diffRemovedBg = Color(0x22D70015)
)

val LocalAtelierColors = staticCompositionLocalOf { ObsidianColors }

@Composable
fun AtelierTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) ObsidianColors else AlabasterColors
    val m3Scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surfaceElevated,
            onPrimary = Color.White,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            outline = palette.hairline,
            error = palette.error
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surfaceElevated,
            onPrimary = Color.White,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            outline = palette.hairline,
            error = palette.error
        )
    }

    CompositionLocalProvider(LocalAtelierColors provides palette) {
        MaterialTheme(colorScheme = m3Scheme, content = content)
    }
}
