package com.orion.assistant.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 浅色、柔和、无边框的配色。刻意避开紫色与黑色科技风。
 */
object OrionColors {
    val TextPrimary = Color(0xFF16181F)
    val TextSecondary = Color(0xFF5C6274)
    val TextTertiary = Color(0xFF9AA0B0)

    val Accent = Color(0xFF3D6BFF)
    val AccentSoft = Color(0xFF87AAFF)
    val Mint = Color(0xFF2FC0A0)
    val Peach = Color(0xFFFFB27A)
    val Rose = Color(0xFFFF93A8)

    val Danger = Color(0xFFE2534F)
    val Success = Color(0xFF26A06B)
    val Warning = Color(0xFFE79A2B)

    val GlassTint = Color(0x3DFFFFFF)
    val GlassTintStrong = Color(0x66FFFFFF)
    val SurfaceSoft = Color(0xFFF2F4FA)

    val BackgroundTop = Color(0xFFFDFDFF)
    val BackgroundMid = Color(0xFFEEF3FF)
    val BackgroundBottom = Color(0xFFFBEEF7)
}

/**
 * 全局动效风格：一律使用弹性插值，禁止生硬跳变。
 * [overshoot] 等价于 Android 的 OvershootInterpolator(1.0f)，用三次贝塞尔表达，
 * 这样可以和 Compose 的 tween 无缝配合。
 *
 * 弹簧做成泛型函数，这样 Dp / Color / Offset / IntSize 等目标值都能直接用同一套手感。
 */
object OrionMotion {
    fun <T> springSoft(): SpringSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)

    fun <T> springSmooth(): SpringSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)

    fun <T> springBouncy(): SpringSpec<T> =
        spring(dampingRatio = 0.45f, stiffness = 420f)

    fun <T> springPress(): SpringSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = 900f)

    /** OvershootInterpolator(1.0f) 的等价缓动 */
    val overshoot: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    /** 面板/卡片的入场缓动 */
    val gentleOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

private val OrionTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun OrionTheme(content: @Composable () -> Unit) {
    // 目前只做浅色主题（设计目标就是浅色玻璃），保留 isSystemInDarkTheme 以便后续扩展
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    val scheme = lightColorScheme(
        primary = OrionColors.Accent,
        onPrimary = Color.White,
        primaryContainer = OrionColors.AccentSoft,
        onPrimaryContainer = OrionColors.TextPrimary,
        secondary = OrionColors.Mint,
        onSecondary = Color.White,
        tertiary = OrionColors.Peach,
        background = Color(0xFFF7F8FC),
        onBackground = OrionColors.TextPrimary,
        surface = Color.White,
        onSurface = OrionColors.TextPrimary,
        surfaceVariant = OrionColors.SurfaceSoft,
        onSurfaceVariant = OrionColors.TextSecondary,
        outline = Color(0x1F000000),
        outlineVariant = Color(0x14000000),
        error = OrionColors.Danger,
        onError = Color.White
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = OrionTypography,
        content = content
    )
}