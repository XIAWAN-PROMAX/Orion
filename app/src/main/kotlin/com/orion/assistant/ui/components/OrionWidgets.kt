package com.orion.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.orion.assistant.ui.theme.OrionColors
import com.orion.assistant.ui.theme.OrionMotion

/** 页面顶栏：可返回 + 标题 + 右侧操作区。整条是玻璃的，滚动时也不突兀。 */
@Composable
fun OrionTopBar(
    title: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            GlassIconButton(
                backdrop = backdrop,
                onClick = onBack,
                size = 44.dp
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = OrionColors.TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = OrionColors.TextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextSecondary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

/** 通用玻璃卡片：无边框、大圆角、带柔和投影 */
@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = OrionColors.GlassTintStrong,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        tint = tint,
        blurRadius = 8.dp,
        refractionHeight = 20.dp,
        refractionAmount = 26.dp,
        contentPadding = contentPadding
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** 小节标题 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 6.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = OrionColors.TextTertiary,
        fontWeight = FontWeight.SemiBold
    )
}

/** 自绘进度条：渐变填充 + 弹性动画，避免 Material 默认样式带来的生硬感 */
@Composable
fun OrionProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    trackColor: Color = Color.White.copy(alpha = 0.55f)
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = OrionMotion.springSmooth(),
        label = "progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated.coerceAtLeast(0.02f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(OrionColors.AccentSoft, OrionColors.Accent)
                    )
                )
        )
    }
}

/**
 * 自绘玻璃开关：轨道是柔和色块，圆点带轻微放大回弹。
 * 用自绘而不是 Material Switch，是为了避免任何紫色残留并贴合整体手感。
 */
@Composable
fun OrionToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 2.dp,
        animationSpec = OrionMotion.springBouncy(),
        label = "toggle-knob"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) OrionColors.Accent else Color.White.copy(alpha = 0.6f),
        animationSpec = OrionMotion.springSmooth(),
        label = "toggle-track"
    )
    val knobColor by animateColorAsState(
        targetValue = if (checked) Color.White else Color.White,
        animationSpec = OrionMotion.springSmooth(),
        label = "toggle-knob-color"
    )

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(26.dp)
                .shadow(4.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(knobColor)
        )
    }
}

/**
 * 一条可点击的玻璃列表项：左图标 + 标题/副标题 + 右侧状态胶囊或箭头。
 */
@Composable
fun GlassListRow(
    backdrop: Backdrop,
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = OrionColors.Accent,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    GlassCard(
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        tint = OrionColors.GlassTint,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(iconTint.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OrionColors.TextSecondary
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}