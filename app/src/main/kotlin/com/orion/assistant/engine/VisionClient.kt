package com.orion.assistant.engine

import android.graphics.Bitmap
import android.util.Base64
import com.orion.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
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
        screenText: String
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
            screenText = screenText
        )

        val endpoint = settings.effectiveBaseUrl.trimEnd('/') + "/chat/completions"
        val raw = try {
            postJson(endpoint, apiKey, payload)
        } catch (e: IOException) {
            return@withContext PlanOutcome.Failure(humanizeHttpError(e.message))
        } catch (t: Throwable) {
            return@withContext PlanOutcome.Failure("调用模型失败：${t.message}")
        }

        parseResponse(raw)
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
            postJson(endpoint, apiKey, payload)
            "连接成功，模型可以正常调用"
        } catch (e: IOException) {
            humanizeHttpError(e.message)
        } catch (t: Throwable) {
            "连接失败：${t.message}"
        }
    }

    // ------------------------------------------------------------------ 请求

    private fun buildRequestBody(
        instruction: String,
        dataUrl: String,
        stepIndex: Int,
        maxSteps: Int,
        history: List<String>,
        screenText: String
    ): String {
        val userParts = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                        OrionPrompt.userMessage(instruction, stepIndex, maxSteps, history, screenText)
                    )
            )
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl))
            )

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", OrionPrompt.system(settings.customPrompt)))
            .put(JSONObject().put("role", "user").put("content", userParts))

        return JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 900)
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

        return PlanOutcome.Success(
            AgentPlan(
                thought = thought,
                action = action,
                summary = summary.ifBlank { if (action is AgentAction.Finish) "任务结束" else "" }
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

    /** 从模型输出里抠出第一个完整的 JSON 对象（兼容 ```json 代码块、前后废话） */
    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = text.substring(start, end + 1)
        return runCatching { JSONObject(candidate) }.getOrNull()
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
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
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
        /** 送给模型前先把截图缩到这个边长，兼顾识别率和速度 */
        const val MAX_IMAGE_EDGE = 1280
    }
}