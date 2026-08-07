package com.xniperbuilds.downloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Riplox TT — one look only: AMOLED black + steel accents (no theme setting, kept lightweight).
private val TtColorScheme = darkColorScheme(
    primary = RiploxSteel,
    secondary = RiploxSteelDim,
    tertiary = RiploxSteel,
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF0B0B0B)
)

@Composable
fun XniperDownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TtColorScheme,
        typography = Typography,
        content = content
    )
}
