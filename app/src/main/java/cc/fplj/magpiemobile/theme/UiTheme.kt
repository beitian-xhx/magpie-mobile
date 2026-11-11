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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.fplj.magpiemobile.R

/**
 * Anthropic 风格设计令牌（anthropic-style-frontend-cn 技能 · Compose 适配 · 工具优先模式）。
 *
 * 设计语言：技术性 × 人文温度 —— 暖米色大地调基底、衬线/无衬线混排、
 * 唯一强调色陶土橙。管理工具界面保持信息密度优先，仅重设色彩/字体/圆角。
 * 字体：Lora（标题与正文，SIL OFL）、Poppins（UI/按钮/标签，SIL OFL），
 * 许可文本随包分发在 assets/licenses/。全应用零硬编码颜色，统一经 MaterialTheme 取用。
 */

// —— 字体族 ——
internal val Serif = FontFamily(
    Font(R.font.lora, FontWeight.Normal),
    Font(R.font.lora, FontWeight.Bold),
)
internal val SerifItalic = FontFamily(
    Font(R.font.lora_italic, FontWeight.Normal),
    Font(R.font.lora_italic, FontWeight.Bold),
)
internal val Sans = FontFamily(
    Font(R.font.poppins, FontWeight.Normal),
    Font(R.font.poppins, FontWeight.Medium),
    Font(R.font.poppins, FontWeight.SemiBold),
    Font(R.font.poppins, FontWeight.Bold),
)
internal val SansItalic = FontFamily(
    Font(R.font.poppins_italic, FontWeight.Normal),
)

// —— 亮色令牌 ——
private val LightColors = lightColorScheme(
    primary = Color(0xFFD97757),              // 唯一强调色：陶土橙
    onPrimary = Color(0xFFFCFAF5),
    primaryContainer = Color(0xFFEBD3C5),
    onPrimaryContainer = Color(0xFF3E2418),
    secondary = Color(0xFF7A6F5D),            // 暖灰褐：次级动作
    onSecondary = Color(0xFFFCFAF5),
    secondaryContainer = Color(0xFFE7E0D2),
    onSecondaryContainer = Color(0xFF2A261E),
    tertiary = Color(0xFF5C7265),             // 鼠尾草绿：图表/次强调
    onTertiary = Color(0xFFF5F3EC),
    error = Color(0xFFC0453A),                // 危险操作强调
    onError = Color(0xFFF5F3EC),
    errorContainer = Color(0xFFF1D4CF),
    onErrorContainer = Color(0xFF3B1210),
    background = Color(0xFFECE9E0),           // 页面基底：暖米色，不用纯白
    onBackground = Color(0xFF141413),
    surface = Color(0xFFECE9E0),
    onSurface = Color(0xFF141413),
    surfaceVariant = Color(0xFFE1DDD1),
    onSurfaceVariant = Color(0xFF6B6860),
    surfaceContainerLowest = Color(0xFFF7F5EE),
    surfaceContainerLow = Color(0xFFF5F3EC),  // 卡片/面板抬升色
    surfaceContainer = Color(0xFFF0EDE3),
    surfaceContainerHigh = Color(0xFFECE8DD),
    surfaceContainerHighest = Color(0xFFE6E2D6),
    inverseSurface = Color(0xFF141413),       // 深色反相区块
    inverseOnSurface = Color(0xFFECE9E0),
    inversePrimary = Color(0xFFE08A69),
    outline = Color(0xFFBFB9AC),
    outlineVariant = Color(0xFFD8D4C8),
    scrim = Color(0xFF141413),
)

// —— 暗色令牌 ——
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE08A69),
    onPrimary = Color(0xFF3B1F12),
    primaryContainer = Color(0xFF6E3B26),
    onPrimaryContainer = Color(0xFFF2D9CC),
    secondary = Color(0xFFB5AE9D),
    onSecondary = Color(0xFF2A261E),
    secondaryContainer = Color(0xFF3E3A31),
    onSecondaryContainer = Color(0xFFE7E0D2),
    tertiary = Color(0xFFA4BCAE),
    onTertiary = Color(0xFF1C2822),
    error = Color(0xFFE0645A),
    onError = Color(0xFF2A1210),
    errorContainer = Color(0xFF5E2420),
    onErrorContainer = Color(0xFFF1D4CF),
    background = Color(0xFF141413),
    onBackground = Color(0xFFECE9E0),
    surface = Color(0xFF141413),
    onSurface = Color(0xFFECE9E0),
    surfaceVariant = Color(0xFF2A2825),
    onSurfaceVariant = Color(0xFFA9A398),
    surfaceContainerLowest = Color(0xFF191816),
    surfaceContainerLow = Color(0xFF1E1C1A),
    surfaceContainer = Color(0xFF23211E),
    surfaceContainerHigh = Color(0xFF292722),
    surfaceContainerHighest = Color(0xFF302D27),
    inverseSurface = Color(0xFFECE9E0),
    inverseOnSurface = Color(0xFF141413),
    inversePrimary = Color(0xFFD97757),
    outline = Color(0xFF57534A),
    outlineVariant = Color(0xFF3A3731),
    scrim = Color(0xFF0A0A09),
)

// —— 排版：标题/正文衬线（Lora），UI/按钮/标签无衬线（Poppins）——
private val AnthropicTypography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 46.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 38.sp, lineHeight = 44.sp),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

// —— 圆角：克制的中等圆角，管理界面保持信息密度 ——
private val AnthropicShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 应用统一主题入口：Anthropic 风格令牌 + 明暗双模式。 */
@Composable
fun MagpieTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AnthropicTypography,
        shapes = AnthropicShapes,
        content = content,
    )
}
