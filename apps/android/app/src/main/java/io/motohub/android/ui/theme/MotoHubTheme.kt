package io.motohub.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val MotoHubLive = Color(0xFF2DD881)
val MotoHubMirror = Color(0xFF5BA8F0)
val MotoHubDashboard = Color(0xFF2DD881)
val MotoHubAndroidAuto = Color(0xFF3EC8D0)

private val MotoHubColors = darkColorScheme(
    primary = Color(0xFFC8F240),
    onPrimary = Color(0xFF0B0D09),
    secondary = Color(0xFFB9C4AB),
    background = Color(0xFF0B0D09),
    onBackground = Color(0xFFE8ECE2),
    surface = Color(0xFF151913),
    onSurface = Color(0xFFE8ECE2),
    surfaceVariant = Color(0xFF1C211A),
    onSurfaceVariant = Color(0xFF7E876E),
    tertiary = Color(0xFF2DD881),
    onTertiary = Color(0xFF07140C),
    outline = Color(0xFF2A3124),
    outlineVariant = Color(0xFF1F241B),
    error = Color(0xFFF05545),
    surfaceContainer = Color(0xFF262C22)
)

private val MotoHubTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.7.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.7.sp
    )
)

private val MotoHubShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun MotoHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MotoHubColors,
        typography = MotoHubTypography,
        shapes = MotoHubShapes,
        content = content
    )
}
