package com.orion.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 视觉模型供应商。都是 OpenAI 兼容的 chat/completions 协议，只是 baseUrl / model 不同。
 * TODO(接入点)：想接入新的视觉模型（如 GPT-4o、Claude、自建 vLLM）时，在这里加一个枚举项即可，
 * 其余代码（VisionClient）无需改动。
 */
enum class ModelProvider(
    val label: String,
    val shortLabel: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val modelHint: String
) {
    DOUBAO(
        label = "豆包视觉 · 火山方舟",
        shortLabel = "豆包",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        defaultModel = "doubao-1.5-vision-pro-32k",
        keyHint = "在火山方舟控制台创建 API Key",
        modelHint = "填模型名或推理接入点 ID，如 doubao-1.5-vision-pro-32k"
    ),
    QWEN(
        label = "通义千问 Qwen-VL · 阿里云百炼",
        shortLabel = "Qwen",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-vl-max-latest",
        keyHint = "在阿里云百炼控制台创建 API Key",
        modelHint = "如 qwen-vl-max-latest / qwen-vl-plus-latest"
    ),

    /** 任意 OpenAI 兼容的视觉模型：GPT-4o、Claude（经中转）、自建 vLLM / One-API 网关等。 */
    CUSTOM(
        label = "自定义 · 任意 OpenAI 兼容视觉模型",
        shortLabel = "自定义",
        baseUrl = "",
        defaultModel = "",
        keyHint = "粘贴该服务的 API Key",
        modelHint = "必填。例如 gpt-4o / claude-3-5-sonnet / 自建模型名"
    );

    companion object {
        fun fromName(name: String?): ModelProvider =
            entries.firstOrNull { it.name == name } ?: DOUBAO
    }
}

/** 操作速度偏好：控制每一步之间的随机间隔，避免机械节奏。 */
enum class OperationSpeed(
    val label: String,
    val hint: String,
    val minDelayMs: Long,
    val maxDelayMs: Long
) {
    SMART("智能", "按画面复杂度自动调节，忽快忽慢", 260, 2000),
    CAREFUL("沉稳", "每步停 1.2~2.0 秒，适合填表单", 1200, 2000),
    NORMAL("标准", "每步停 0.6~1.1 秒，推荐", 600, 1100),
    SWIFT("轻快", "每步停 0.25~0.55 秒，适合刷内容", 250, 550);

    companion object {
        fun fromName(name: String?): OperationSpeed =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * 设置仓库。API Key 使用 EncryptedSharedPreferences 加密落盘（AES256-SIV / AES256-GCM，密钥在 Android Keystore）。
 */
class SettingsRepository(context: Context) {

    /** 是否成功用上了 Keystore 加密存储（必须在 prefs 之前初始化） */
    private var usingEncryptedStorage = true
    private val prefs: SharedPreferences = createPrefs(context)

    /**
     * 打开偏好存储。
     *
     * 这里刻意用一个极小的「明文指针文件」记住上次真正可用的存储方案：
     * EncryptedSharedPreferences 依赖 Android Keystore，在少数设备上会偶发创建失败
     * （例如用户改过锁屏密码，导致原有密钥失效）。
     * 如果每次启动都重新试探，就会出现「这次开加密库、下次开明文库」的情况 ——
     * 两个库内容不同，表现出来就是「刚填的 Key 重启后又没了」。
     * 指针文件让方案一旦落定就不再横跳，用户填过的 Key 不会被凭空清掉。
     */
    private fun createPrefs(context: Context): SharedPreferences {
        val pointer = context.getSharedPreferences(FILE_NAME_POINTER, Context.MODE_PRIVATE)
        if (pointer.getString(KEY_SCHEMA, SCHEMA_ENCRYPTED) == SCHEMA_ENCRYPTED) {
            return runCatching { buildEncryptedPrefs(context) }.getOrElse {
                pointer.edit().putString(KEY_SCHEMA, SCHEMA_PLAIN).commit()
                usingEncryptedStorage = false
                context.getSharedPreferences(FILE_NAME_PLAIN, Context.MODE_PRIVATE)
            }
        }
        usingEncryptedStorage = false
        return context.getSharedPreferences(FILE_NAME_PLAIN, Context.MODE_PRIVATE)
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * 统一用 commit() 同步落盘。
     *
     * apply() 是异步写，写盘还没完成时进程一旦被杀（用户划掉后台、系统回收），这次修改就丢了，
     * 表现出来正是「Key 填了却没保存上」。这些设置项都很小、也不在热路径上，用 commit() 更稳。
     */
    private fun write(block: SharedPreferences.Editor.() -> SharedPreferences.Editor) {
        runCatching { prefs.edit().block().commit() }
    }

    /** 加密存储是否可用（不可用时在设置页提示用户） */
    val isEncryptionAvailable: Boolean get() = usingEncryptedStorage

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = write { putString(KEY_API_KEY, value.trim()) }

    var provider: ModelProvider
        get() = ModelProvider.fromName(prefs.getString(KEY_PROVIDER, null))
        set(value) = write { putString(KEY_PROVIDER, value.name) }

    /** 模型名（默认为当前供应商的推荐模型） */
    var model: String
        get() {
            val saved = prefs.getString(KEY_MODEL, null)
            return if (saved.isNullOrBlank()) provider.defaultModel else saved
        }
        set(value) = write { putString(KEY_MODEL, value.trim()) }

    /** 自定义 baseUrl，留空则用供应商默认（便于接入自建网关 / 中转） */
    var customBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(value) = write { putString(KEY_BASE_URL, value.trim()) }

    val effectiveBaseUrl: String
        get() = customBaseUrl.ifBlank { provider.baseUrl }

    var speed: OperationSpeed
        get() = OperationSpeed.fromName(prefs.getString(KEY_SPEED, null))
        set(value) = write { putString(KEY_SPEED, value.name) }

    /** 单次任务最多执行多少步（防止无限循环） */
    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, 25)
        set(value) = write { putInt(KEY_MAX_STEPS, value.coerceIn(5, 100)) }

    /** 是否在任务过程中把屏幕截图存到 App 私有目录（默认关闭，省空间） */
    var saveScreenshots: Boolean
        get() = prefs.getBoolean(KEY_SAVE_SHOTS, false)
        set(value) = write { putBoolean(KEY_SAVE_SHOTS, value) }

    /**
     * 自学习：任务结束后自动复盘这次的好 / 不足，攒成经验，下次执行时注入提示词。
     * 默认开启；关闭后既不总结也不再注入历史经验。
     */
    var selfLearning: Boolean
        get() = prefs.getBoolean(KEY_SELF_LEARNING, true)
        set(value) = write { putBoolean(KEY_SELF_LEARNING, value) }

    /** 新手引导是否已完成 */
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = write { putBoolean(KEY_ONBOARDED, value) }

    var lastInstruction: String
        get() = prefs.getString(KEY_LAST_TASK, "").orEmpty()
        set(value) = write { putString(KEY_LAST_TASK, value) }

    /**
     * 用户自定义的附加提示词：会追加在系统提示词后面，用来规定 Orion 的做事习惯，
     * 例如「涉及付款先停下来问我」「填表优先用语音输入」。
     */
    var customPrompt: String
        get() = prefs.getString(KEY_CUSTOM_PROMPT, "").orEmpty()
        set(value) = write { putString(KEY_CUSTOM_PROMPT, value) }

    /** 只要填了 Key 就算「有 Key」（用于权限清单里的打勾） */
    val isReady: Boolean
        get() = apiKey.isNotBlank()

    /**
     * 配置是否「足以发起一次模型调用」。
     * 自定义供应商要求 baseUrl 和模型名都填齐，否则请求一定失败。
     */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() &&
            model.isNotBlank() &&
            effectiveBaseUrl.startsWith("http")

    companion object {
        private const val FILE_NAME = "orion_secure_settings"
        private const val FILE_NAME_PLAIN = "orion_secure_settings_plain"
        private const val FILE_NAME_POINTER = "orion_secure_settings_schema"
        private const val KEY_SCHEMA = "schema"
        private const val SCHEMA_ENCRYPTED = "encrypted"
        private const val SCHEMA_PLAIN = "plain"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SPEED = "speed"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_SAVE_SHOTS = "save_screenshots"
        private const val KEY_SELF_LEARNING = "self_learning"
        private const val KEY_ONBOARDED = "onboarding_completed"
        private const val KEY_LAST_TASK = "last_instruction"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"
    }
}