package io.github.drtron1c.cloudphotos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
fun CloudPhotosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.background, onBackground = p.label,
            surface = p.card, onSurface = p.label, surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = p.secondaryLabel,
            error = p.destructive, surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerLow = p.card,
            surfaceContainer = p.card,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.background, onBackground = p.label,
            surface = p.card, onSurface = p.label, surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = p.secondaryLabel,
            error = p.destructive, surfaceContainerHigh = Color(0xFFF2F2F7), surfaceContainerLow = Color.White,
            surfaceContainer = Color(0xFFF2F2F7),
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
