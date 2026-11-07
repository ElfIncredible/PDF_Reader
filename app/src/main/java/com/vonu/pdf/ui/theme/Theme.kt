package com.vonu.pdf.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Vonu brand: tech blue with the amber Vonu dot.
private val Blue = Color(0xFF1D4ED8)
private val Navy = Color(0xFF0B1F44)
private val BlueLight = Color(0xFFA8C4FF)
private val Amber = Color(0xFFFFB627)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE6FF),
    onPrimaryContainer = Navy,
    secondary = Color(0xFF565E71),
    tertiary = Color(0xFF8A5300),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFF8FAFE),
    surface = Color(0xFFF8FAFE),
)

private val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF0A2D6E),
    primaryContainer = Color(0xFF1D4ED8),
    onPrimaryContainer = Color(0xFFDBE6FF),
    secondary = Color(0xFFBEC6DC),
    tertiary = Amber,
    tertiaryContainer = Color(0xFF6A3F00),
    background = Color(0xFF0F131A),
    surface = Color(0xFF0F131A),
)

@Composable
fun VonuTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
