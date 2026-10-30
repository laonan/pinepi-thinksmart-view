package com.pinepi.thinksmart.launcher

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DayColors = lightColorScheme(
    primary = Color(0xFF326449), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EBD9), onPrimaryContainer = Color(0xFF234C35),
    secondary = Color(0xFF5C7162), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDE2), onSecondaryContainer = Color(0xFF354C3A),
    tertiary = Color(0xFF786A47),
    background = Color(0xFFF3F5EF), onBackground = Color(0xFF233028),
    surface = Color(0xFFFAFCF7), onSurface = Color(0xFF233028),
    surfaceVariant = Color(0xFFE9EEE4), onSurfaceVariant = Color(0xFF69756A),
    outline = Color(0xFF9AA69A), outlineVariant = Color(0xFFDCE3D7),
    error = Color(0xFF9E4A40), errorContainer = Color(0xFFF6E4DF),
)
private val NightColors = darkColorScheme(
    primary = Color(0xFFA7D4B3), onPrimary = Color(0xFF183F29),
    primaryContainer = Color(0xFF254B35), onPrimaryContainer = Color(0xFFD3EBD5),
    secondary = Color(0xFFB6C9B8), onSecondary = Color(0xFF243C2D),
    secondaryContainer = Color(0xFF2A3D31), onSecondaryContainer = Color(0xFFD5E4D5),
    tertiary = Color(0xFFD4C59F),
    background = Color(0xFF101B16), onBackground = Color(0xFFE3EAE1),
    surface = Color(0xFF18271F), onSurface = Color(0xFFE3EAE1),
    surfaceVariant = Color(0xFF23342A), onSurfaceVariant = Color(0xFFA5B4A7),
    outline = Color(0xFF748879), outlineVariant = Color(0xFF33483A),
    error = Color(0xFFE4A79B), errorContainer = Color(0xFF4E302A),
)
private val HomeTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 36.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 23.sp, lineHeight = 36.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
)

@Composable
fun PinePiTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors, typography = HomeTypography, content = content)
}
