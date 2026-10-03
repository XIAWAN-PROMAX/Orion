package com.orion.assistant.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.orion.assistant.orionApp
import com.orion.assistant.ui.components.GlassCard
import com.orion.assistant.ui.components.GlassChip
import com.orion.assistant.ui.components.GlassSurface
import com.orion.assistant.ui.components.GlassTextField
import com.orion.assistant.ui.components.StatusPill
import com.orion.assistant.ui.components.rememberLiquidInteraction
import com.orion.assistant.ui.rememberOrionStatus
import com.orion.assistant.ui.theme.OrionColors
import com.orion.assistant.ui.theme.OrionMotion
import kotlin.math.absoluteValue

private const val PAGE_COUNT = 3

/**
 * 新手引导：3 页，依次教用户开启无障碍、授权截屏、填写 API Key，最后一页「开始使用」。
 * 每页都有一张自绘动画插图；翻页时用缩放 + 淡出做纵深，配合弹性缓动，避免生硬。
 */
@Composable
fun OnboardingScreen(
    backdrop: Backdrop,
    onRequestCapture: () -> Unit,
    onRequestNotification: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val app = context.orionApp
    val status = rememberOrionStatus()
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })

    var apiKey by remember { mutableStateOf(app.settings.apiKey) }
    var pendingScroll by remember { mutableStateOf<Int?>(null) }

    // 需要翻页时用弹性动画滚过去
    LaunchedEffect(pendingScroll) {
        val target = pendingScroll ?: return@LaunchedEffect
        pagerState.animateScrollToPage(target)
        pendingScroll = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
    ) {
        // 顶部：跳过
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Orion",
                style = MaterialTheme.typography.titleLarge,
                color = OrionColors.TextPrimary
            )
            Spacer(Modifier.weight(1f))
            GlassChip(
                text = "跳过",
                backdrop = backdrop,
                onClick = {
                    app.settings.onboardingCompleted = true
                    onFinish()
                }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            // 翻页时的纵深效果：离中心越远越小、越淡
            val distance = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - (distance * 0.09f).coerceIn(0f, 0.16f)
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - distance * 0.55f).coerceIn(0.25f, 1f)
                    }
                    .padding(horizontal = 26.dp)
            ) {
                when (page) {
                    0 -> OnboardingPermissionPage(
                        backdrop = backdrop,
                        art = OnboardingArt.Accessibility,
                        title = "先让 Orion 长出一双手",
                        description = "开启「无障碍服务」后，Orion 才能代替你点击、滑动、输入。" +
                            "它只在你按下「开始」后动手，随时可以停。",
                        done = status.accessibility,
                        doneText = "无障碍服务已开启",
                        actionText = "去开启无障碍服务",
                        onAction = {
                            runCatching { context.startActivity(com.orion.assistant.ui.OrionPermissions.accessibilitySettingsIntent()) }
                        }
                    )

                    1 -> OnboardingPermissionPage(
                        backdrop = backdrop,
                        art = OnboardingArt.Screen,
                        title = "再给它一双眼睛",
                        description = "授权「屏幕录制」后，Orion 才能看到当前画面，理解你在哪个 App、哪一页。" +
                            "截图只用于当下这一步的判断。",
                        done = status.capture,
                        doneText = "截屏授权已完成",
                        actionText = "允许截屏",
                        onAction = onRequestCapture
                    )

                    else -> ApiKeyPage(
                        backdrop = backdrop,
                        apiKey = apiKey,
                        onApiKeyChange = {
                            apiKey = it
                            app.settings.apiKey = it
                        },
                        onRequestNotification = onRequestNotification
                    )
                }
            }
        }

        // 底部：页码指示 + 主按钮
        val currentPage = pagerState.currentPage
        Column(Modifier.padding(horizontal = 26.dp)) {
            PageIndicator(current = currentPage, count = PAGE_COUNT)
            Spacer(Modifier.height(18.dp))

            val (label, enabled, action) = when (currentPage) {
                0 -> Triple(
                    if (status.accessibility) "下一步" else "开启无障碍服务",
                    true,
                    {
                        if (status.accessibility) {
                            // 已经开好了，进入下一页
                        } else {
                            runCatching {
                                context.startActivity(
                                    com.orion.assistant.ui.OrionPermissions.accessibilitySettingsIntent()
                                )
                            }
                        }
                    }
                )

                1 -> Triple(
                    if (status.capture) "下一步" else "允许截屏",
                    true,
                    { if (!status.capture) onRequestCapture() }
                )

                else -> Triple(
                    "开始使用",
                    apiKey.isNotBlank(),
                    {
                        app.settings.onboardingCompleted = true
                        onFinish()
                    }
                )
            }

            OnboardingPrimaryButton(
                text = label,
                backdrop = backdrop,
                enabled = enabled,
                leading = if (currentPage == 2) Icons.Filled.PlayArrow else null,
                onClick = {
                    action()
                    if (currentPage < PAGE_COUNT - 1 && label == "下一步") {
                        // 条件已满足，弹性翻到下一页
                        pendingScroll = (currentPage + 1).coerceAtMost(PAGE_COUNT - 1)
                    }
                }
            )

            if (currentPage == 2 && apiKey.isBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = "填好 API Key 就可以开始了",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextTertiary
                )
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun OnboardingPermissionPage(
    backdrop: Backdrop,
    art: OnboardingArt,
    title: String,
    description: String,
    done: Boolean,
    doneText: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OnboardingArtwork(art, Modifier.fillMaxWidth(0.72f).aspectRatio(1f))
        Spacer(Modifier.height(26.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = OrionColors.TextPrimary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = OrionColors.TextSecondary
        )
        Spacer(Modifier.height(20.dp))
        if (done) {
            StatusPill(text = doneText, color = OrionColors.Success)
        } else {
            GlassChip(text = actionText, backdrop = backdrop, onClick = onAction)
        }
    }
}

@Composable
private fun ApiKeyPage(
    backdrop: Backdrop,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onRequestNotification: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OnboardingArtwork(OnboardingArt.Spark, Modifier.fillMaxWidth(0.62f).aspectRatio(1f))
        Spacer(Modifier.height(20.dp))
        Text(
            text = "最后，给 Orion 装上大脑",
            style = MaterialTheme.typography.headlineMedium,
            color = OrionColors.TextPrimary
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "填一个视觉模型的 API Key（豆包视觉 / 通义千问 Qwen-VL 任选）。" +
                "它会加密保存在这台手机上，模型可以在「设置」里随时更换。",
            style = MaterialTheme.typography.bodyLarge,
            color = OrionColors.TextSecondary
        )
        Spacer(Modifier.height(18.dp))

        GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = OrionColors.Accent,
                    modifier = Modifier.width(20.dp).height(20.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "API Key（加密存储）",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
            }
            Spacer(Modifier.height(12.dp))
            GlassTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                placeholder = "粘贴你的 API Key",
                minHeight = 58.dp,
                singleLine = true,
                masked = true
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = OrionColors.TextTertiary,
                    modifier = Modifier.width(16.dp).height(16.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "首次开启通知，方便在灵动岛看进度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextTertiary
                )
                Spacer(Modifier.weight(1f))
                GlassChip(text = "开通知", backdrop = backdrop, onClick = onRequestNotification)
            }
        }
    }
}

/** 底部主按钮 */
@Composable
private fun OnboardingPrimaryButton(
    text: String,
    backdrop: Backdrop,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leading: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val interaction = rememberLiquidInteraction()
    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!enabled) Modifier.graphicsLayer { alpha = 0.45f } else Modifier),
        shape = RoundedCornerShape(50),
        tint = OrionColors.Accent.copy(alpha = 0.9f),
        interaction = if (enabled) interaction else null,
        onTap = if (enabled) onClick else null,
        contentPadding = PaddingValues(vertical = 17.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                Icon(
                    imageVector = leading,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.width(21.dp).height(21.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 页码指示器：当前页是一条会「弹」过去的长胶囊 */
@Composable
private fun PageIndicator(current: Int, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (selected) 26.dp else 8.dp,
                animationSpec = OrionMotion.springBouncy(),
                label = "indicator-width"
            )
            val color by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) OrionColors.Accent else OrionColors.TextTertiary,
                animationSpec = OrionMotion.springSmooth(),
                label = "indicator-color"
            )
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .width(width)
                    .height(8.dp)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = color,
                        size = Size(size.width, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                    )
                }
            }
        }
    }
}

private enum class OnboardingArt { Accessibility, Screen, Spark }

/** 三张自绘动画插图（不依赖任何图片资源） */
@Composable
private fun OnboardingArtwork(kind: OnboardingArt, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "onboarding-art")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier) {
        val min = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)

        // 底光
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(OrionColors.AccentSoft.copy(alpha = 0.42f), Color.Transparent),
                center = center,
                radius = min * 0.62f
            ),
            radius = min * 0.62f,
            center = center
        )

        when (kind) {
            OnboardingArt.Accessibility -> {
                // 三圈脉冲 + 中心指尖
                repeat(3) { index ->
                    val p = ((phase + index / 3f) % 1f)
                    drawCircle(
                        color = OrionColors.Accent.copy(alpha = (1f - p) * 0.5f),
                        radius = min * (0.16f + p * 0.30f),
                        center = center,
                        style = Stroke(width = min * 0.014f)
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, OrionColors.AccentSoft),
                        center = center,
                        radius = min * 0.16f
                    ),
                    radius = min * 0.16f,
                    center = center
                )
                drawCircle(color = OrionColors.Accent, radius = min * 0.055f, center = center)
            }

            OnboardingArt.Screen -> {
                // 手机外框
                val w = min * 0.52f
                val h = min * 0.86f
                val topLeft = Offset(center.x - w / 2f, center.y - h / 2f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.75f),
                    topLeft = topLeft,
                    size = Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(min * 0.11f)
                )
                drawRoundRect(
                    color = OrionColors.Accent,
                    topLeft = topLeft,
                    size = Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(min * 0.11f),
                    style = Stroke(width = min * 0.018f)
                )
                // 扫描线：在屏幕内上下扫
                val lineY = topLeft.y + h * 0.12f + (h * 0.76f) * ((phase * 2f) % 1f)
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, OrionColors.Mint, Color.Transparent)
                    ),
                    start = Offset(topLeft.x + w * 0.08f, lineY),
                    end = Offset(topLeft.x + w * 0.92f, lineY),
                    strokeWidth = min * 0.02f,
                    cap = StrokeCap.Round
                )
            }

            OnboardingArt.Spark -> {
                // 旋转的闪光 + 环绕的小点
                val star = min * 0.20f
                repeat(2) { ring ->
                    val p = ((phase + ring * 0.5f) % 1f)
                    drawCircle(
                        color = OrionColors.Peach.copy(alpha = (1f - p) * 0.45f),
                        radius = min * (0.20f + p * 0.28f),
                        center = center,
                        style = Stroke(width = min * 0.013f)
                    )
                }
                val angle = phase * 360f
                repeat(6) { index ->
                    val a = Math.toRadians((angle + index * 60f).toDouble())
                    val r = min * 0.34f
                    drawCircle(
                        color = OrionColors.Accent.copy(alpha = 0.55f),
                        radius = min * 0.028f,
                        center = Offset(
                            center.x + (r * kotlin.math.cos(a)).toFloat(),
                            center.y + (r * kotlin.math.sin(a)).toFloat()
                        )
                    )
                }
                // 中心四角星
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x, center.y - star)
                    lineTo(center.x + star * 0.28f, center.y - star * 0.28f)
                    lineTo(center.x + star, center.y)
                    lineTo(center.x + star * 0.28f, center.y + star * 0.28f)
                    lineTo(center.x, center.y + star)
                    lineTo(center.x - star * 0.28f, center.y + star * 0.28f)
                    lineTo(center.x - star, center.y)
                    lineTo(center.x - star * 0.28f, center.y - star * 0.28f)
                    close()
                }
                drawPath(
                    path = path,
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, OrionColors.AccentSoft),
                        center = center,
                        radius = star
                    )
                )
                drawCircle(color = OrionColors.Accent, radius = min * 0.045f, center = center)
            }
        }

        // 通用的高光点，增强液态玻璃质感
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.7f), Color.Transparent),
                center = Offset(center.x - min * 0.22f, center.y - min * 0.24f),
                radius = min * 0.2f
            ),
            radius = min * 0.2f,
            center = Offset(center.x - min * 0.22f, center.y - min * 0.24f)
        )
    }
}