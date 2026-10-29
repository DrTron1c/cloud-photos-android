package io.github.drtron1c.cloudphotos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.drtron1c.cloudphotos.settings.AppSettings

/** iOS-flavoured system colours. */
@Immutable
data class Palette(
    val background: Color,
    val groupedBackground: Color,
    val card: Color,
    val label: Color,
    val secondaryLabel: Color,
    val separator: Color,
    val accent: Color,
    val destructive: Color,
    val placeholder: Color,
    val bar: Color,
    val isDark: Boolean,
)

val LightPalette = Palette(
    background = Color.White,
    groupedBackground = Color(0xFFF2F2F7),
    card = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0xFF8E8E93),
    separator = Color(0x33000000),
    accent = Color(0xFF007AFF),
    destructive = Color(0xFFFF3B30),
    placeholder = Color(0xFFE5E5EA),
    bar = Color(0xF2F9F9F9),
    isDark = false,
)

val DarkPalette = Palette(
    background = Color.Black,
    groupedBackground = Color.Black,
    card = Color(0xFF1C1C1E),
    label = Color.White,
    secondaryLabel = Color(0xFF8E8E93),
    separator = Color(0x40FFFFFF),
    accent = Color(0xFF0A84FF),
    destructive = Color(0xFFFF453A),
    placeholder = Color(0xFF1C1C1E),
    bar = Color(0xF0161616),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun CloudPhotosTheme(themeMode: String = "system", accentIndex: Int = 0, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val base = if (dark) DarkPalette else LightPalette
    val accentArgb = AppSettings.ACCENTS.getOrNull(accentIndex)?.second
    val p = if (accentArgb != null && accentIndex != 0) base.copy(accent = Color(accentArgb)) else base
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.background, onBackground = p.label,
            surface = p.card, onSurface = p.label, surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = p.secondaryLabel,
            error = p.destructive, surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerLow = p.card,
            surfaceContainer = p.card, surfaceContainerHighest = Color(0xFF3A3A3C),
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.background, onBackground = p.label,
            surface = p.card, onSurface = p.label, surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = p.secondaryLabel,
            error = p.destructive, surfaceContainerHigh = Color(0xFFF2F2F7), surfaceContainerLow = Color.White,
            surfaceContainer = Color(0xFFF2F2F7), surfaceContainerHighest = Color(0xFFE5E5EA),
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
