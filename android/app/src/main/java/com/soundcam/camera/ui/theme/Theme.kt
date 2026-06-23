package com.soundcam.camera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF3B82F6)
private val Red = Color(0xFFEF4444)
private val Green = Color(0xFF22C55E)

private val DarkColors = darkColorScheme(
    primary = Blue,
    secondary = Green,
    error = Red,
)

private val LightColors = lightColorScheme(
    primary = Blue,
    secondary = Green,
    error = Red,
)

@Composable
fun SoundCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
