package com.orion.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.orion.assistant.ui.theme.OrionColors
import com.orion.assistant.ui.theme.OrionMotion

/**
 * 液态玻璃的基础容器：折射 + 色散 + 模糊 + 高光 + 内阴影，全部来自参考库 backdrop。
 *
 * 折射（lens）负责「透过玻璃看到的内容被弯曲」，
 * 色散（chromaticAberration）负责边缘的彩虹分离，
 * 高光/阴影由 drawBackdrop 的默认 highlight / shadow 提供。
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    blurRadius: Dp = 7.dp,
    refractionHeight: Dp = 22.dp,
    refractionAmount: Dp = 30.dp,
    dispersion: Boolean = true,
    depthEffect: Boolean = true,
    tint: Color? = null,
    interaction: LiquidInteraction? = null,
    onTap: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(
                        refractionHeight = refractionHeight.toPx(),
                        refractionAmount = refractionAmount.toPx(),
                        depthEffect = depthEffect,
                        chromaticAberration = dispersion
                    )
                },
                layerBlock = interaction?.layerBlock,
                onDrawSurface = { tint?.let { drawRect(it) } }
            )
            .then(
                if (interaction != null || onTap != null) {
                    Modifier.liquidGestures(interaction, onTap)
                } else {
                    Modifier
                }
            )
            .padding(contentPadding)
    ) {
        content()
    }
}

/** 主按钮：胶囊形玻璃，可加彩色 tint 变成「实心玻璃」 */
@Composable
fun GlassButton(
    text: String,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    leading: ImageVector? = null
) {
    val interaction = rememberLiquidInteraction()
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(50),
        tint = if (prominent) OrionColors.Accent.copy(alpha = 0.88f) else OrionColors.GlassTint,
        interaction = if (enabled) interaction else null,
        onTap = if (enabled) onClick else null,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (leading != null) {
                Icon(
                    imageVector = leading,
                    contentDescription = null,
                    tint = if (prominent) Color.White else OrionColors.TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = text,
                color = if (prominent) Color.White else OrionColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** 圆形玻璃图标按钮 */
@Composable
fun GlassIconButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    tint: Color? = OrionColors.GlassTint,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = rememberLiquidInteraction()
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.size(size),
        shape = CircleShape,
        blurRadius = 5.dp,
        refractionHeight = 14.dp,
        refractionAmount = 20.dp,
        tint = tint,
        interaction = interaction,
        onTap = onClick
    ) {
        Box(Modifier.align(Alignment.Center)) { content() }
    }
}

/** 小胶囊标签，例如「试试这些」下面的示例指令 */
@Composable
fun GlassChip(
    text: String,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = rememberLiquidInteraction()
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        blurRadius = 4.dp,
        refractionHeight = 12.dp,
        refractionAmount = 16.dp,
        dispersion = false,
        depthEffect = false,
        tint = OrionColors.GlassTint,
        interaction = interaction,
        onTap = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            color = OrionColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 玻璃里的输入框：用 BasicTextField 自己画，避免 Material 的下划线和紫色高亮 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: Dp = 120.dp,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    masked: Boolean = false,
    textStyle: TextStyle = TextStyle(
        color = OrionColors.TextPrimary,
        fontSize = 17.sp,
        lineHeight = 25.sp
    )
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        tint = OrionColors.GlassTintStrong,
        blurRadius = 8.dp,
        refractionHeight = 20.dp,
        refractionAmount = 26.dp
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = textStyle,
            cursorBrush = SolidColor(OrionColors.Accent),
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = OrionColors.TextTertiary,
                            style = textStyle
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/**
 * 分段选择器（玻璃版）。选中项背后有一块会「弹」过去的高光胶囊。
 */
@Composable
fun <T> GlassSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)

    GlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        tint = OrionColors.GlassTint,
        blurRadius = 6.dp,
        refractionHeight = 16.dp,
        refractionAmount = 22.dp,
        dispersion = false,
        contentPadding = PaddingValues(5.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val itemWidth = maxWidth / options.size
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = OrionMotion.springBouncy(),
                label = "segment-offset"
            )

            // 会弹过去的高光胶囊
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.72f))
            )

            Row(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    val textColor by animateColorAsState(
                        targetValue = if (index == selectedIndex) {
                            OrionColors.TextPrimary
                        } else {
                            OrionColors.TextSecondary
                        },
                        animationSpec = OrionMotion.springSmooth(),
                        label = "segment-color"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(option) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label(option),
                            color = textColor,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

/** 状态小胶囊：已开启 / 未开启 */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 圆形数字/图标徽标 */
@Composable
fun GlassBadge(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = OrionColors.Accent.copy(alpha = 0.16f),
    content: @Composable BoxScope.() -> Unit
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.size(size),
        shape = CircleShape,
        blurRadius = 3.dp,
        refractionHeight = 10.dp,
        refractionAmount = 14.dp,
        dispersion = false,
        depthEffect = false,
        tint = tint
    ) {
        Box(Modifier.align(Alignment.Center)) { content() }
    }
}