package com.orion.assistant.engine

import android.graphics.Bitmap
import android.util.Base64
import com.orion.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 视觉理解：把「截图 + 指令 + 历史」丢给多模态大模型，拿回下一步动作。
 *
 * 豆包视觉（火山方舟）与 Qwen-VL（阿里云百炼）都提供 OpenAI 兼容的
 * POST {baseUrl}/chat/completions 接口，因此这里一套代码通吃。
 */
class VisionClient(private val settings: SettingsRepository) {

    suspend fun plan(
        instruction: String,
        screenshot: Bitmap,
        stepIndex: Int,
        maxSteps: Int,
        history: List<String>,
        screenText: String,
        overallPlan: List<String>,
        lessons: List<String> = emptyList()
    ): PlanOutcome = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) {
            return@withContext PlanOutcome.Failure("还没有配置 API Key，请到「设置」里填写")
        }

        val dataUrl = try {
            screenshot.toJpegDataUrl(MAX_IMAGE_EDGE)
        } catch (t: Throwable) {
            return@withContext PlanOutcome.Failure("屏幕截图编码失败：${t.message}")
        }

        val payload = buildRequestBody(
            instruction = instruction,
            dataUrl = dataUrl,
            stepIndex = stepIndex,
            maxSteps = maxSteps,
            history = history,
            screenText = screenText,
            overallPlan = overallPlan,
            lessons = lessons
        )

        val endpoint = settings.effectiveBaseUrl.trimEnd('/') + "/chat/completions"
        // runInterruptible：用户点「停止」时，协程取消会去中断这个阻塞的 HTTP 读，
        // 不用一直干等到 90 秒超时。ensureActive() 保证取消异常不会被下面的
        // catch 吞掉、误报成「调用模型失败」。
        val raw = try {
            runInterruptible { postJson(endpoint, apiKey, payload) }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return@withContext PlanOutcome.Failure(humanizeHttpError(e.message))
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return@withContext PlanOutcome.Failure("调用模型失败：${t.message}")
        }

        recordUsage(raw)
        parseResponse(raw)
    }

    /**
     * 设置页「用量信息 · 更新」：发一个极小的探针请求，读回本次的 token 用量并累计，
     * 用来刷新「用量信息」里的数字。返回一句给人看的结果。
     */
    suspend fun refreshUsage(): String = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) return@withContext "请先填写 API Key"
        val payload = JSONObject()
            .put("model", settings.model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "usage"))
            )
            .put("max_tokens", 8)
            .put("stream", false)
            .toString()
        val endpoint = settings.effectiveBaseUrl.trimEnd('/') + "/chat/completions"
        try {
            val raw = postJson(endpoint, apiKey, payload)
            recordUsage(raw)
            "用量已更新"
        } catch (e: IOException) {
            humanizeHttpError(e.message)
        } catch (t: Throwable) {
            "更新失败：${t.message}"
        }
    }

    /** 连一下模型服务，用于设置页的「测试连接」 */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) return@withContext "请先填写 API Key"
        val payload = JSONObject()
            .put("model", settings.model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
            )
            .put("max_tokens", 8)
            .toString()
        val endpoint = settings.effectiveBaseUrl.trimEnd('/') + "/chat/completions"
        try {
            val raw = postJson(endpoint, apiKey, payload)
            recordUsage(raw)
            "连接成功，模型可以正常调用"
        } catch (e: IOException) {
            humanizeHttpError(e.message)
        } catch (t: Throwable) {
            "连接失败：${t.message}"
        }
    }

    /**
     * 自学习复盘：把一次任务的过程交给模型，总结「好的地方 / 不足 / 下次改进要点」。
     * 纯文本调用（不带截图，省流量）。任何失败都返回 null —— 复盘失败绝不影响主流程。
     */
    suspend fun summarizeExperience(
        instruction: String,
        status: String,
        steps: List<String>
    ): Experience? = withContext(Dispatchers.IO) {
        val apiKey = settings.apiKey
        if (apiKey.isBlank()) return@withContext null
        if (steps.isEmpty()) return@withContext null

        val payload = JSONObject()
            .put("model", settings.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", LEARNING_SYSTEM))
                    .put(
                        JSONObject().put("role", "user")
                            .put("content", buildLearningUser(instruction, status, steps))
                    )
            )
            .put("temperature", 0.3)
            .put("max_tokens", 512)
            .put("stream", false)
            .toString()

        val endpoint = settings.effectiveBaseUrl.trimEnd('/') + "/chat/completions"
        val raw = try {
            runInterruptible { postJson(endpoint, apiKey, payload) }
        } catch (e: IOException) {
            return@withContext null
        } catch (t: Throwable) {
            return@withContext null
        }
        recordUsage(raw)
        parseExperience(raw)
    }

    private fun buildLearningUser(instruction: String, status: String, steps: List<String>): String {
        val sb = StringBuilder()
        sb.append("任务指令：").append(instruction.trim()).append('\n')
        sb.append("结束状态：").append(status).append('\n')
        sb.append("执行过程（按先后顺序）：\n")
        steps.takeLast(30).forEachIndexed { index, item ->
            sb.append("  ").append(index + 1).append(". ").append(item).append('\n')
        }
        sb.append("\n请复盘这次任务，按约定输出 JSON。")
        return sb.toString()
    }

    // ------------------------------------------------------------------ 请求

    private fun buildRequestBody(
        instruction: String,
        dataUrl: String,
        stepIndex: Int,
        maxSteps: Int,
        history: List<String>,
        screenText: String,
        overallPlan: List<String>,
        lessons: List<String>
    ): String {
        val userParts = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        OrionPrompt.userMessage(
                            instruction, stepIndex, maxSteps, history, screenText, overallPlan
                        )
                    )
            )
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl))
            )

        val messages = JSONArray()
            .put(
                JSONObject().put("role", "system")
                    .put("content", OrionPrompt.system(settings.customPrompt, lessons))
            )
            .put(JSONObject().put("role", "user").put("content", userParts))

        return JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 2048)
            .put("stream", false)
            .toString()
    }

    private fun postJson(endpoint: String, apiKey: String, payload: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code ${text.take(400)}")
            }
            return text
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 解析

    /** 从模型返回里读出 usage 并累计到本地用量统计；没有 usage 字段就静默跳过 */
    private fun recordUsage(raw: String) {
        val usage = runCatching { JSONObject(raw).optJSONObject("usage") }.getOrNull() ?: return
        settings.recordUsage(
            prompt = usage.optInt("prompt_tokens", 0),
            completion = usage.optInt("completion_tokens", 0),
            total = usage.optInt("total_tokens", 0)
        )
    }

    private fun parseResponse(raw: String): PlanOutcome {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return PlanOutcome.Failure("模型返回的不是合法 JSON：${raw.take(160)}")
        }
        val choices = root.optJSONArray("choices")
            ?: return PlanOutcome.Failure("模型返回里没有 choices 字段")
        if (choices.length() == 0) return PlanOutcome.Failure("模型没有返回任何候选结果")

        val message = choices.getJSONObject(0).optJSONObject("message")
        val content = extractContent(message)
        if (content.isBlank()) return PlanOutcome.Failure("模型返回内容为空")

        val json = extractJsonObject(content)
            ?: return PlanOutcome.Failure("模型的输出里找不到 JSON：${content.take(160)}")

        val thought = json.optString("thought").ifBlank { "正在观察屏幕…" }
        val action = AgentAction.parse(json.optJSONObject("action"))
        val summary = json.optString("summary")
        // 模型列出的整体子目标清单：任务开始时给一次即可，之后会被带回每一步
        val plan = json.optJSONArray("plan")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.orEmpty()

        return PlanOutcome.Success(
            AgentPlan(
                thought = thought,
                action = action,
                summary = summary.ifBlank { if (action is AgentAction.Finish) "任务结束" else "" },
                plan = plan
            )
        )
    }

    private fun extractContent(message: JSONObject?): String {
        if (message == null) return ""
        val direct = message.optString("content")
        if (direct.isNotBlank()) return direct
        // 少数实现把 content 拆成数组
        val array = message.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            val part = array.optJSONObject(i) ?: continue
            sb.append(part.optString("text"))
        }
        return sb.toString()
    }

    /** 解析复盘结果；任一字段有内容即可，全空按失败处理 */
    private fun parseExperience(raw: String): Experience? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val content = extractContent(choices.getJSONObject(0).optJSONObject("message"))
        if (content.isBlank()) return null
        val json = extractJsonObject(content) ?: return null
        val good = cleanLine(json.optString("good"))
        val bad = cleanLine(json.optString("bad"))
        val tip = cleanLine(json.optString("tip"))
        if (good.isEmpty() && bad.isEmpty() && tip.isEmpty()) return null
        return Experience(good = good, bad = bad, tip = tip)
    }

    /** 统一成单行短句，并限制长度，避免经验越攒越长把提示词撑爆 */
    private fun cleanLine(text: String): String =
        text.trim().trim('"').replace('\n', ' ').replace(Regex("\\s+"), " ").take(160)

    /** 从模型输出里抠出第一个完整的 JSON 对象（兼容 ```json 代码块、前后废话） */
    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = text.substring(start, end + 1)
        // 先按原样解析；不行再修掉模型常见手误后重试
        runCatching { JSONObject(candidate) }.getOrNull()?.let { return it }
        return runCatching { JSONObject(repairJson(candidate)) }.getOrNull()
    }

    /**
     * 修掉模型手写 JSON 时的常见错误。目前处理两类：
     *  1. 坐标丢了键名：`"x": 150, 140` —— 模型把 y 的值直接跟在 x 后面忘了写 `"y":`。
     *     按 x→y、x1→y1、x2→y2 补回键名。（实际踩到过这个）
     *  2. 末尾多逗号：`{"a": 1,}` / `[1, 2,]` —— 去掉紧挨 `}` `]` 前的逗号。
     * 修不好就原样返回，交给调用方按解析失败处理。
     */
    private fun repairJson(source: String): String {
        var text = source

        // 1. 补回丢失的 y / y1 / y2 键名（x1、x2 先处理，避免被 x 的规则误伤）
        for ((xKey, yKey) in listOf("x1" to "y1", "x2" to "y2", "x" to "y")) {
            val pattern = Regex(""""$xKey"\s*:\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
            text = pattern.replace(text) { m ->
                """"$xKey": ${m.groupValues[1]}, "$yKey": ${m.groupValues[2]}"""
            }
        }

        // 2. 去掉 } 和 ] 前多余的逗号
        text = text.replace(Regex(""",\s*([}\]])""")) { it.groupValues[1] }

        return text
    }

    // ------------------------------------------------------------------ 工具

    private fun Bitmap.toJpegDataUrl(maxEdge: Int): String {
        val longest = maxOf(width, height)
        val scaled = if (longest > maxEdge) {
            val ratio = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
        } else {
            this
        }
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (scaled !== this) scaled.recycle()
            out.toByteArray()
        }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    private fun humanizeHttpError(message: String?): String {
        val text = message.orEmpty()
        return when {
            text.contains("HTTP 401") || text.contains("HTTP 403") ->
                "API Key 无效或没有该模型的权限，请到「设置」里检查"

            text.contains("HTTP 404") ->
                "接口地址或模型名不对（404）。检查「模型名」是否填了正确的推理接入点"

            text.contains("HTTP 429") ->
                "调用太频繁或额度用完了，稍后再试"

            text.contains("HTTP 5") ->
                "模型服务暂时不可用，稍后再试"

            text.contains("Unable to resolve host") || text.contains("Failed to connect") ->
                "连不上模型服务，请检查网络"

            else -> "调用模型失败：$text"
        }
    }

    private companion object {
        /** 送给模型前先把截图缩到这个边长。做题要读小字，清晰度优先；卡顿根因已挪到后台线程 */
        const val MAX_IMAGE_EDGE = 1440

        /** 自学习复盘的系统提示词：只让它产出可执行的改进要点 */
        val LEARNING_SYSTEM = """
你是 Orion（手机操作助手）的复盘助手。会给你一次任务的指令、结束状态，以及它按顺序做过的动作。
请客观复盘，帮它下次面对同类任务做得更快、更准。只输出严格 JSON，不要 markdown、不要多余文字：
{
  "good": "这次做得好的地方，一句话，没有就留空",
  "bad": "这次不足 / 卡住 / 绕远的地方，一句话，没有就留空",
  "tip": "下次同类任务的改进要点，一句话，必须具体、可执行"
}
要求：
- tip 面向下一次执行，写成能直接照做的短句，例如「答题 App 选完选项后记得点『检查 / 继续』」。
- 不要空话（避免「要更细心」这类），每条 60 字以内。
""".trimIndent()
    }
}

/** 一次自学习复盘的结果 */
data class Experience(
    val good: String,
    val bad: String,
    val tip: String
)