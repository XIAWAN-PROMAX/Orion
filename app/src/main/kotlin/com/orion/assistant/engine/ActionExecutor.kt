package com.orion.assistant.engine

import com.orion.assistant.service.OrionAccessibilityService

/**
 * 动作执行器：把 [AgentAction] 落到真实屏幕上。
 * 所有动作都通过 [OrionAccessibilityService] 的 dispatchGesture / ACTION_SET_TEXT 完成。
 */
object ActionExecutor {

    /** 执行动作，返回一句可写入日志的结果描述 */
    fun execute(action: AgentAction): String {
        val service = OrionAccessibilityService.current
            ?: return "无障碍服务没有连接，无法执行动作"

        val screen = service.screenSize()
        val width = screen[0].toFloat()
        val height = screen[1].toFloat()

        return when (action) {
            is AgentAction.Tap -> {
                val x = action.x.toPx(width)
                val y = action.y.toPx(height)
                if (service.tap(x, y)) "已点击 (${x.toInt()}, ${y.toInt()})" else "点击没有生效"
            }

            is AgentAction.LongPress -> {
                val x = action.x.toPx(width)
                val y = action.y.toPx(height)
                if (service.longPress(x, y, action.durationMs.toLong())) "已长按 (${x.toInt()}, ${y.toInt()})"
                else "长按没有生效"
            }

            is AgentAction.Swipe -> {
                val ok = service.swipe(
                    action.x1.toPx(width),
                    action.y1.toPx(height),
                    action.x2.toPx(width),
                    action.y2.toPx(height),
                    action.durationMs.toLong()
                )
                if (ok) "已滑动" else "滑动没有生效"
            }

            is AgentAction.Scroll -> {
                if (service.scroll(action.direction)) "已滚动 ${action.direction}" else "滚动没有生效"
            }

            is AgentAction.InputText -> {
                if (action.text.isEmpty()) {
                    "要输入的内容是空的，已跳过"
                } else if (service.inputText(action.text)) {
                    "已输入「${action.text}」"
                } else {
                    "没找到可输入的输入框"
                }
            }

            is AgentAction.OpenApp -> service.openApp(action.app)

            AgentAction.Back -> if (service.pressBack()) "已返回上一页" else "返回操作没有生效"

            AgentAction.Home -> if (service.pressHome()) "已回到桌面" else "回桌面没有生效"

            AgentAction.Recents -> if (service.pressRecents()) "已打开多任务" else "打开多任务没有生效"

            AgentAction.Wait -> "等待画面加载"

            is AgentAction.Finish -> action.describe()

            is AgentAction.Unknown -> "跳过无法识别的动作（${action.raw}）"
        }
    }

    /** 0~1000 归一化坐标 → 像素 */
    private fun Int.toPx(screenEdge: Float): Float =
        (this.coerceIn(0, AgentAction.COORD_MAX) / AgentAction.COORD_MAX.toFloat()) * screenEdge
}