package com.tingshu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
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

/** 和网页端 styles.css 里那套暖纸色一致，白天/夜间都跟系统走。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFB4643C),
    onPrimary = Color(0xFFFFF8F0),
    primaryContainer = Color(0xFFF2E2D6),
    onPrimaryContainer = Color(0xFF4A2110),
    secondary = Color(0xFF8A6849),
    onSecondary = Color(0xFFFFF8F0),
    secondaryContainer = Color(0xFFF0E6D9),
    onSecondaryContainer = Color(0xFF3B2A1B),
    background = Color(0xFFF5F1EA),
    onBackground = Color(0xFF241F19),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF241F19),
    surfaceVariant = Color(0xFFF8F4EC),
    onSurfaceVariant = Color(0xFF4D453B),
    surfaceDim = Color(0xFFE7E0D5),
    surfaceBright = Color(0xFFFCF8F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF5EE),
    surfaceContainer = Color(0xFFF6F0E7),
    surfaceContainerHigh = Color(0xFFF1EADF),
    surfaceContainerHighest = Color(0xFFECE4D8),
    inverseSurface = Color(0xFF342F28),
    inverseOnSurface = Color(0xFFF7F1E7),
    outline = Color(0xFFB9AC9A),
    outlineVariant = Color(0xFFE4DBCD),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFF8F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE09A6C),
    onPrimary = Color(0xFF2A1608),
    primaryContainer = Color(0xFF3A2A1E),
    onPrimaryContainer = Color(0xFFF6DCC8),
    secondary = Color(0xFFC9AF9A),
    onSecondary = Color(0xFF2A1608),
    secondaryContainer = Color(0xFF33241A),
    onSecondaryContainer = Color(0xFFF0DCCB),
    background = Color(0xFF14120F),
    onBackground = Color(0xFFF2EBE1),
    surface = Color(0xFF1D1A16),
    onSurface = Color(0xFFF2EBE1),
    surfaceVariant = Color(0xFF26221C),
    onSurfaceVariant = Color(0xFFCFC5B7),
    surfaceDim = Color(0xFF14120F),
    surfaceBright = Color(0xFF3B352D),
    surfaceContainerLowest = Color(0xFF0E0C0A),
    surfaceContainerLow = Color(0xFF1A1713),
    surfaceContainer = Color(0xFF1F1B16),
    surfaceContainerHigh = Color(0xFF26221C),
    surfaceContainerHighest = Color(0xFF302B23),
    inverseSurface = Color(0xFFF2EBE1),
    inverseOnSurface = Color(0xFF342F28),
    outline = Color(0xFF6B5F50),
    outlineVariant = Color(0xFF322C24),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF3B0908),
)

private val TingshuTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 30.sp,
        letterSpacing = 1.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

@Composable
fun TingshuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TingshuTypography,
        content = content,
    )
}
