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
import androidx.compose.ui.text.font.Font
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vonu.pdf.R

// Vonu brand (Vonu Branding/README.md): Lagoon ground and ink, Vonu PDF red as the product hue.
// Dark mode uses the Reef Night theme. Coral is reserved for the company mark, never the UI.
private val PdfRed = Color(0xFFC2412D)
private val LagoonInk = Color(0xFF0B4F5C)

/** The wordmark face: lowercase "vonu" in Outfit Bold. */
val Wordmark = FontFamily(Font(R.font.outfit_bold))

/** Small labels and figures ("VONU", section labels): Geist Mono, as across the Vonu family. */
val BrandMono = FontFamily(Font(R.font.geist_mono))

// Product UI face, per the Vonu brand (fonts: Outfit wordmark, Archivo UI, Geist Mono labels).
// One variable file (OFL, licences in assets/licenses). Headings ExtraBold, labels SemiBold, tabular
// figures everywhere so page numbers and sizes line up. Same scale as Vonu HD Uploader.
@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int) = Font(
    R.font.archivo,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Archivo = FontFamily(archivo(400), archivo(500), archivo(600), archivo(700), archivo(800))

private fun TextStyle.set(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = copy(
    fontFamily = Archivo,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    fontFeatureSettings = "tnum",
)

private val VonuTypography = Typography().run {
    copy(
        displayLarge = displayLarge.set(57, 64, FontWeight.ExtraBold, -0.5),
        displayMedium = displayMedium.set(45, 52, FontWeight.ExtraBold, -0.5),
        displaySmall = displaySmall.set(36, 44, FontWeight.ExtraBold, -0.25),
        headlineLarge = headlineLarge.set(32, 40, FontWeight.ExtraBold, -0.25),
        headlineMedium = headlineMedium.set(28, 36, FontWeight.ExtraBold, -0.25),
        headlineSmall = headlineSmall.set(24, 32, FontWeight.ExtraBold),
        titleLarge = titleLarge.set(22, 28, FontWeight.ExtraBold),
        titleMedium = titleMedium.set(18, 24, FontWeight.ExtraBold),
        titleSmall = titleSmall.set(15, 20, FontWeight.SemiBold),
        bodyLarge = bodyLarge.set(16, 24, FontWeight.Normal, 0.15),
        bodyMedium = bodyMedium.set(14, 20, FontWeight.Normal, 0.1),
        bodySmall = bodySmall.set(13, 18, FontWeight.Normal, 0.1),
        labelLarge = labelLarge.set(15, 20, FontWeight.SemiBold, 0.1),
        labelMedium = labelMedium.set(12, 16, FontWeight.SemiBold, 0.2),
        labelSmall = labelSmall.set(11, 16, FontWeight.SemiBold, 0.3),
    )
}

private val LightColors = lightColorScheme(
    primary = PdfRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DAD2),
    onPrimaryContainer = Color(0xFF410C03),
    secondary = LagoonInk,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E6E4),
    onSecondaryContainer = Color(0xFF062A31),
    tertiary = LagoonInk,
    tertiaryContainer = Color(0xFFD3E6E4),
    onTertiaryContainer = Color(0xFF062A31),
    background = Color(0xFFF3EEE4),
    onBackground = Color(0xFF14262B),
    surface = Color(0xFFF3EEE4),
    onSurface = Color(0xFF14262B),
    surfaceVariant = Color(0xFFE6DED0),
    onSurfaceVariant = Color(0xFF4E5E61),
    outline = Color(0xFF7D8A8C),
    outlineVariant = Color(0xFFD9CFBE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F4EC),
    surfaceContainer = Color(0xFFEFE8DC),
    surfaceContainerHigh = Color(0xFFEAE2D4),
    surfaceContainerHighest = Color(0xFFE4DBCB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A5),
    onPrimary = Color(0xFF5F1608),
    primaryContainer = Color(0xFF8E2C1C),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFF7FD6CF),
    onSecondary = Color(0xFF003734),
    secondaryContainer = Color(0xFF124A4F),
    onSecondaryContainer = Color(0xFFA6F0E9),
    tertiary = Color(0xFF7FD6CF),
    tertiaryContainer = Color(0xFF124A4F),
    onTertiaryContainer = Color(0xFFA6F0E9),
    background = Color(0xFF0D1B2A),
    onBackground = Color(0xFFE4EEF0),
    surface = Color(0xFF0D1B2A),
    onSurface = Color(0xFFE4EEF0),
    surfaceVariant = Color(0xFF2A3A48),
    onSurfaceVariant = Color(0xFFA9B8BF),
    outline = Color(0xFF74848D),
    outlineVariant = Color(0xFF2C3D4C),
    surfaceContainerLowest = Color(0xFF08131F),
    surfaceContainerLow = Color(0xFF122233),
    surfaceContainer = Color(0xFF15283A),
    surfaceContainerHigh = Color(0xFF1B3044),
    surfaceContainerHighest = Color(0xFF223A50),
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
    MaterialTheme(colorScheme = colors, typography = VonuTypography, content = content)
}
