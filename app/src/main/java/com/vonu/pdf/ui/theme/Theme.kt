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

// Vonu brand: deep indigo with an amber "light".
private val Indigo = Color(0xFF4338CA)
private val IndigoNight = Color(0xFF1E1B4B)
private val IndigoLight = Color(0xFFC3C0FF)
private val Amber = Color(0xFFFFB627)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DFFF),
    onPrimaryContainer = IndigoNight,
    secondary = Color(0xFF5C5D72),
    tertiary = Color(0xFF8A5300),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2C1700),
    background = Color(0xFFF8F7FD),
    surface = Color(0xFFF8F7FD),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF2A2179),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE2DFFF),
    secondary = Color(0xFFC5C4DD),
    tertiary = Amber,
    tertiaryContainer = Color(0xFF6A3F00),
    background = Color(0xFF13121C),
    surface = Color(0xFF13121C),
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
