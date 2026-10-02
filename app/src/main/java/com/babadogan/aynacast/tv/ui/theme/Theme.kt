package com.babadogan.aynacast.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.babadogan.aynacast.tv.model.AppThemeMode
import com.babadogan.aynacast.tv.util.isTelevisionDevice

private val DarkColorScheme = darkColorScheme(
    primary = AppleAccentBlue,
    secondary = AppleAccentGreen,
    background = AppleDarkBackground,
    surface = AppleSurfaceDark,
    surfaceVariant = AppleSurfaceVariantDark,
    onPrimary = AppleTextPrimary,
    onSecondary = AppleTextPrimary,
    onBackground = AppleTextPrimary,
    onSurface = AppleTextPrimary,
    onSurfaceVariant = AppleTextSecondary
)

private val LightColorScheme = lightColorScheme(
    primary = AppleAccentBlue,
    secondary = AppleAccentGreen,
    background = AppleLightBackground,
    surface = AppleLightSurface,
    surfaceVariant = AppleLightSurfaceVariant,
    onPrimary = AppleTextPrimary,
    onSecondary = AppleTextPrimary,
    onBackground = AppleLightTextPrimary,
    onSurface = AppleLightTextPrimary,
    onSurfaceVariant = AppleLightTextSecondary
)

@Composable
fun AirPlayTvTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isTv = remember(context) { context.isTelevisionDevice() }
    val systemInDark = isSystemInDarkTheme()

    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> if (isTv) true else systemInDark
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
