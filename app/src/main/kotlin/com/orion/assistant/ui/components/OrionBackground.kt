package com.orion.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.orion.assistant.ui.theme.OrionColors

/**
 * 全局背景：浅色柔和渐变 + 几个缓慢游动的柔光色块。
 *
 * 它同时也是「液态玻璃的采样源」——玻璃组件会折射它的内容，
 * 所以颜色块要足够有层次，折射/色散才看得出来。
 */
@Composable
fun OrionBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orion-background")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    Canvas(modifier) {
        drawRect(
            Brush.verticalGradient(
                colors = listOf(
                    OrionColors.BackgroundTop,
                    OrionColors.BackgroundMid,
                    OrionColors.BackgroundBottom
                )
            )
        )

        val min = size.minDimension
        drawSoftBlob(
            center = Offset(size.width * (0.08f + 0.10f * phase), size.height * 0.13f),
            radius = min * 0.66f,
            color = OrionColors.AccentSoft.copy(alpha = 0.40f)
        )
        drawSoftBlob(
            center = Offset(size.width * (0.96f - 0.08f * phase), size.height * 0.32f),
            radius = min * 0.52f,
            color = OrionColors.Peach.copy(alpha = 0.34f)
        )
        drawSoftBlob(
            center = Offset(size.width * (0.22f + 0.06f * phase), size.height * 0.80f),
            radius = min * 0.58f,
            color = OrionColors.Rose.copy(alpha = 0.30f)
        )
        drawSoftBlob(
            center = Offset(size.width * 0.88f, size.height * (0.90f - 0.07f * phase)),
            radius = min * 0.50f,
            color = OrionColors.Mint.copy(alpha = 0.26f)
        )
    }
}

private fun DrawScope.drawSoftBlob(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}