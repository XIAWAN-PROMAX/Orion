package com.orion.assistant.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.orion.assistant.ui.theme.OrionMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.tanh

/**
 * 液态玻璃的「按压 + 拖拽回弹」状态机。
 *
 * 交互时同时做两件事：
 *  - 内容做轻微缩放，模拟按下去的手感；
 *  - 指针拖动时内容跟随位移，松手后用弹性插值弹回原位；
 * 这个位移会通过 layerBlock 传给 drawBackdrop，反向作用到折射采样上，
 * 于是玻璃里的背景也一起「被拽动」，产生果冻般的回弹（这就是参考库里 LiquidButton 的思路）。
 */
@Stable
class LiquidInteraction internal constructor(private val scope: CoroutineScope) {

    private val press = Animatable(0f)
    private val drag = Animatable(Offset.Zero, Offset.VectorConverter)

    val pressProgress: Float by press.asState()
    val offset: Offset by drag.asState()

    internal fun pressDown() {
        scope.launch { press.animateTo(1f, OrionMotion.springPress()) }
    }

    internal fun dragBy(delta: Offset) {
        if (delta == Offset.Zero) return
        scope.launch { drag.snapTo(drag.value + delta) }
    }

    internal fun release() {
        scope.launch { press.animateTo(0f, OrionMotion.springSmooth()) }
        scope.launch { drag.animateTo(Offset.Zero, OrionMotion.springSmooth()) }
    }

    /** 交给 drawBackdrop 的 layerBlock：缩放 + 位移 + 反向补偿 */
    val layerBlock: GraphicsLayerScope.() -> Unit = {
        val p = pressProgress
        val d = offset

        val scale = 1f + 0.022f * p
        scaleX = scale
        scaleY = scale

        val maxOffset = size.minDimension.coerceAtLeast(1f)
        // tanh 让位移有「越拉越沉」的物理感，不会无限跑偏
        translationX = maxOffset * tanh(0.06f * d.x / maxOffset)
        translationY = maxOffset * tanh(0.06f * d.y / maxOffset)
    }
}

@Composable
fun rememberLiquidInteraction(): LiquidInteraction {
    val scope = rememberCoroutineScope()
    return remember(scope) { LiquidInteraction(scope) }
}

/**
 * 手势识别：按下高亮、拖动跟手、松手回弹，位移足够小才算点击。
 *
 * 两个关键细节（之前「停止按钮不灵敏」的根因就在这里）：
 *  1. onTap 每次重组都是新的 lambda。如果把它当作 pointerInput 的 key，
 *     手势识别会被反复重建 —— 手指还没抬起、恰好碰上一次重组（任务执行时
 *     进度 / 缩略图刷新非常频繁），这次点击就被直接丢掉了。
 *     所以这里用 rememberUpdatedState 兜住最新回调，key 固定为 Unit。
 *  2. 用「按下点到抬起点的净位移」和 touchSlop 比较来判定点击，
 *     比累计整条轨迹更宽容，轻微手抖不会把点击吃掉。
 */
@Composable
fun Modifier.liquidGestures(
    interaction: LiquidInteraction?,
    onTap: (() -> Unit)? = null
): Modifier {
    if (interaction == null && onTap == null) return this
    val latestTap by rememberUpdatedState(onTap)
    val latestInteraction by rememberUpdatedState(interaction)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            latestInteraction?.pressDown()

            var upPosition: Offset? = null
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    upPosition = change.position
                    break
                }
                val delta = change.positionChange()
                if (delta != Offset.Zero) {
                    latestInteraction?.dragBy(delta)
                    change.consume()
                }
            }

            latestInteraction?.release()
            val up = upPosition
            if (up != null && (up - down.position).getDistance() <= viewConfiguration.touchSlop) {
                latestTap?.invoke()
            }
        }
    }
}