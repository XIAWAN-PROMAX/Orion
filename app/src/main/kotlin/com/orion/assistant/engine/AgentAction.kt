package com.orion.assistant.engine

import org.json.JSONObject

/**
 * AI 规划出的一个「原子动作」。坐标统一使用 0~1000 归一化值（跟屏幕分辨率解耦），
 * 执行时再按真实屏幕尺寸换算成像素。
 */
sealed interface AgentAction {

    /** 动作标识，写进日志 / 数据库 */
    val key: String

    /** 给人看的一句话描述 */
    fun describe(): String

    data class Tap(val x: Int, val y: Int) : AgentAction {
        override val key = "tap"
        override fun describe() = "点击 (${x}, ${y})"
    }

    data class LongPress(val x: Int, val y: Int, val durationMs: Int) : AgentAction {
        override val key = "long_press"
        override fun describe() = "长按 (${x}, ${y}) ${durationMs}ms"
    }

    data class Swipe(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Int
    ) : AgentAction {
        override val key = "swipe"
        override fun describe() = "滑动 (${x1}, ${y1}) → (${x2}, ${y2})"
    }

    /** 内容滚动方向：up / down / left / right */
    data class Scroll(val direction: String) : AgentAction {
        override val key = "scroll"
        override fun describe() = "滚动 $direction"
    }

    data class InputText(val text: String) : AgentAction {
        override val key = "input_text"
        override fun describe() = "输入「$text」"
    }

    data class OpenApp(val app: String) : AgentAction {
        override val key = "open_app"
        override fun describe() = "打开「$app」"
    }

    data object Back : AgentAction {
        override val key = "back"
        override fun describe() = "返回上一页"
    }

    data object Home : AgentAction {
        override val key = "home"
        override fun describe() = "回到桌面"
    }

    data object Recents : AgentAction {
        override val key = "recents"
        override fun describe() = "打开多任务"
    }

    data object Wait : AgentAction {
        override val key = "wait"
        override fun describe() = "等待画面加载"
    }

    data class Finish(val summary: String) : AgentAction {
        override val key = "finish"
        override fun describe() = "任务完成：$summary"
    }

    /** 模型返回了不认识的动作，记录原始内容便于排查 */
    data class Unknown(val raw: String) : AgentAction {
        override val key = "unknown"
        override fun describe() = "无法识别的动作：$raw"
    }

    companion object {

        /** 归一化坐标上限 */
        const val COORD_MAX = 1000

        fun parse(json: JSONObject?): AgentAction {
            if (json == null) return Unknown("(空)")
            val type = json.optString("type").trim().lowercase()
            return when (type) {
                "tap", "click" -> Tap(coord(json, "x"), coord(json, "y"))
                "long_press", "longpress" -> LongPress(
                    coord(json, "x"),
                    coord(json, "y"),
                    json.optInt("durationMs", json.optInt("duration", 700)).coerceIn(300, 3000)
                )

                "swipe", "drag" -> Swipe(
                    coord(json, "x1", "x"),
                    coord(json, "y1", "y"),
                    coord(json, "x2"),
                    coord(json, "y2"),
                    json.optInt("durationMs", json.optInt("duration", 320)).coerceIn(80, 3000)
                )

                "scroll" -> Scroll(
                    json.optString("direction", "up").trim().lowercase().ifBlank { "up" }
                )

                "input_text", "input", "type", "set_text" ->
                    InputText(json.optString("text"))

                "open_app", "launch_app", "open" ->
                    OpenApp(json.optString("app", json.optString("package")))

                "back", "press_back" -> Back
                "home", "press_home" -> Home
                "recents", "press_recents" -> Recents
                "wait", "sleep" -> Wait
                "finish", "done", "complete" -> Finish(json.optString("summary"))
                "" -> Unknown(json.toString())
                else -> Unknown(type)
            }
        }

        private fun coord(json: JSONObject, key: String, fallbackKey: String? = null): Int {
            var raw = if (json.has(key)) json.optDouble(key, Double.NaN) else Double.NaN
            if (raw.isNaN() && fallbackKey != null) raw = json.optDouble(fallbackKey, Double.NaN)
            if (raw.isNaN()) return COORD_MAX / 2
            // 兼容模型可能给出的 0~1 浮点坐标
            val scaled = if (raw in 0.0..1.0 && raw % 1.0 != 0.0) raw * COORD_MAX else raw
            return scaled.toInt().coerceIn(0, COORD_MAX)
        }
    }
}

/** 模型对当前屏幕的一次完整判断 */
data class AgentPlan(
    val thought: String,
    val action: AgentAction,
    val summary: String
) {
    val isFinish: Boolean get() = action is AgentAction.Finish
}

/** 视觉模型调用结果：要么拿到计划，要么拿到错误原因 */
sealed interface PlanOutcome {
    data class Success(val plan: AgentPlan) : PlanOutcome
    data class Failure(val message: String) : PlanOutcome
}