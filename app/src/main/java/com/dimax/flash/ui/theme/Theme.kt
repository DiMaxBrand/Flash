package com.dimax.flash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary          = OceanAqua,
    onPrimary        = OnAqua,
    background       = PureWhite,
    onBackground     = PureBlack,
    surface          = TrayLight,
    onSurface        = PureBlack,
    surfaceVariant   = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF424242),
)

private val DarkColors = darkColorScheme(
    primary          = OceanAqua,
    onPrimary        = OnAqua,
    background       = PureBlack,
    onBackground     = PureWhite,
    surface          = TrayDark,
    onSurface        = PureWhite,
    surfaceVariant   = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),
)

@Composable
fun FlashTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
