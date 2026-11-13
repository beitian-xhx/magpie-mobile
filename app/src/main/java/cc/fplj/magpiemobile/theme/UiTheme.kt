package cc.fplj.magpiemobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PC Magpie 风格的黑白界面：
 * 纯白/纯黑基底、黑色/白色主操作、细灰边框、无彩色强调、
 * 系统无衬线字体、近直角卡片。只保留路由与用量两页时使用。
 */

private val Ink = Color(0xFF111111)
private val Paper = Color(0xFFFFFFFF)
private val GrayLine = Color(0xFFD6D6D6)
private val GrayLineSoft = Color(0xFFE8E8E8)
private val GrayText = Color(0xFF5C5C5C)
private val SoftGray = Color(0xFFF5F5F5)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Ink,
    secondary = Color(0xFF444444),
    onSecondary = Paper,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF444444),
    onTertiary = Paper,
    error = Ink,
    onError = Paper,
    errorContainer = SoftGray,
    onErrorContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = SoftGray,
    onSurfaceVariant = GrayText,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Paper,
    surfaceContainer = SoftGray,
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE9E9E9),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Color(0xFFE8E8E8),
    outline = GrayLine,
    outlineVariant = GrayLineSoft,
    scrim = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Paper,
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF262626),
    onPrimaryContainer = Paper,
    secondary = Color(0xFFCCCCCC),
    onSecondary = Color(0xFF0A0A0A),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Paper,
    tertiary = Color(0xFFCCCCCC),
    onTertiary = Color(0xFF0A0A0A),
    error = Paper,
    onError = Color(0xFF0A0A0A),
    errorContainer = Color(0xFF262626),
    onErrorContainer = Paper,
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF262626),
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF0A0A0A),
    inversePrimary = Color(0xFF222222),
    outline = Color(0xFF3C3C3C),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color(0xFF000000),
)

private val MonoType = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 46.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val MonoShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(2.dp),
)

@Composable
fun MagpieTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MonoType,
        shapes = MonoShapes,
        content = content,
    )
}
