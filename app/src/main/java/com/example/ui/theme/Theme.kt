package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = SlateBlack,
    primaryContainer = ElectricCyanDeep,
    onPrimaryContainer = ElectricCyan,
    secondary = NeonEmerald,
    onSecondary = SlateBlack,
    secondaryContainer = SlateMedium,
    onSecondaryContainer = NeonEmerald,
    tertiary = TechIce,
    background = SlateBlack,
    onBackground = TextPrimaryDark,
    surface = SlateDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateCard,
    onSurfaceVariant = TextSecondaryDark,
    outline = SlateBorder,
    error = AccentError,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricCyanDeep,
    onPrimary = Color.White,
    primaryContainer = TechIce,
    onPrimaryContainer = SlateBlack,
    secondary = NeonEmeraldDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F9E8),
    onSecondaryContainer = Color(0xFF003919),
    tertiary = ElectricCyanDark,
    background = SlateLightBg,
    onBackground = TextPrimaryLight,
    surface = SlateLightCard,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFCBD5E1),
    error = AccentError,
    onError = Color.White
)

@Composable
fun ResignTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Preserve high-tech custom identity by default
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
