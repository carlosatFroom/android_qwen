package com.example.qwenchat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Blue tones
private val Blue50 = Color(0xFFE3F2FD)
private val Blue100 = Color(0xFFBBDEFB)
private val Blue400 = Color(0xFF42A5F5)
private val Blue600 = Color(0xFF1E88E5)
private val Blue700 = Color(0xFF1976D2)
private val Blue900 = Color(0xFF0D47A1)

// Forest green tones
private val Green50 = Color(0xFFE8F5E9)
private val Green100 = Color(0xFFC8E6C9)
private val Green600 = Color(0xFF2E7D32)
private val Green700 = Color(0xFF388E3C)
private val Green800 = Color(0xFF1B5E20)
private val Green900 = Color(0xFF1B5E20)

private val LightColorScheme = lightColorScheme(
    primary = Blue700,
    onPrimary = Color.White,
    primaryContainer = Blue100,
    onPrimaryContainer = Blue900,
    secondary = Green700,
    onSecondary = Color.White,
    secondaryContainer = Green100,
    onSecondaryContainer = Green800,
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Green50,
    onSurfaceVariant = Color(0xFF1C1B1F),
    surfaceContainerLow = Color(0xFFF5F5F5),
)

private val DarkColorScheme = darkColorScheme(
    primary = Blue400,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = Blue100,
    secondary = Color(0xFF66BB6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1B3D1F),
    onSecondaryContainer = Green100,
    surfaceVariant = Color(0xFF1B3D1F),
    onSurfaceVariant = Color(0xFFE0E0E0),
    surfaceContainerLow = Color(0xFF1E1E1E),
)

@Composable
fun QwenChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
