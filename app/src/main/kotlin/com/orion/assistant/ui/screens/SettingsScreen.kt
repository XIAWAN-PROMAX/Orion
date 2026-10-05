package com.orion.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.orion.assistant.data.ModelProvider
import com.orion.assistant.data.OperationSpeed
import com.orion.assistant.engine.VisionClient
import com.orion.assistant.orionApp
import com.orion.assistant.ui.components.GlassCard
import com.orion.assistant.ui.components.GlassSegmented
import com.orion.assistant.ui.components.GlassTextField
import com.orion.assistant.ui.components.GradientStar
import com.orion.assistant.ui.components.OrionToggle
import com.orion.assistant.ui.components.OrionTopBar
import com.orion.assistant.ui.components.SectionTitle
import com.orion.assistant.ui.components.StatusPill
import com.orion.assistant.ui.theme.OrionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：模型与 Key、操作节奏、存储、关于。
 * API Key 每次修改都即时写入 EncryptedSharedPreferences（AES256，密钥在 Android Keystore）。
 */
@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.orionApp
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(app.settings.provider) }
    var apiKey by remember { mutableStateOf(app.settings.apiKey) }
    var model by remember { mutableStateOf(app.settings.model) }
    var baseUrl by remember { mutableStateOf(app.settings.customBaseUrl) }
    var speed by remember { mutableStateOf(app.settings.speed) }
    var maxSteps by remember { mutableFloatStateOf(app.settings.maxSteps.toFloat()) }
    // 拖动步数滑块时给每一档一点振动反馈，方便盲调到想要的轮数
    val haptic = LocalHapticFeedback.current
    var saveScreenshots by remember { mutableStateOf(app.settings.saveScreenshots) }
    var customPrompt by remember { mutableStateOf(app.settings.customPrompt) }
    var selfLearning by remember { mutableStateOf(app.settings.selfLearning) }
    // 已攒下的经验条数，清空后归零
    var learningCount by remember { mutableIntStateOf(0) }
    // 待二次确认的清空操作：null 表示没有弹窗
    var pendingClear by remember { mutableStateOf<ClearTarget?>(null) }

    LaunchedEffect(Unit) {
        learningCount = withContext(Dispatchers.IO) {
            runCatching { app.learning.count() }.getOrDefault(0)
        }
    }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        OrionTopBar(
            title = "设置",
            subtitle = "模型、节奏与数据",
            backdrop = backdrop,
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // ------------------------------------------------------ 模型服务
            SectionTitle("模型服务")

            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选择供应商",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(11.dp))
                GlassSegmented(
                    options = ModelProvider.entries.toList(),
                    selected = provider,
                    onSelect = { picked ->
                        provider = picked
                        app.settings.provider = picked
                        // 换供应商后，模型名回落到新供应商的推荐值
                        app.settings.model = ""
                        model = app.settings.model
                        testResult = null
                    },
                    label = { it.shortLabel },
                    backdrop = backdrop
                )

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = OrionColors.Accent,
                        modifier = Modifier.width(18.dp).height(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "API Key",
                        style = MaterialTheme.typography.titleMedium,
                        color = OrionColors.TextPrimary
                    )
                    Spacer(Modifier.weight(1f))
                    StatusPill(
                        text = if (app.settings.isEncryptionAvailable) "已加密保存" else "未加密",
                        color = if (app.settings.isEncryptionAvailable) OrionColors.Success
                        else OrionColors.Warning
                    )
                }
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        app.settings.apiKey = it
                        testResult = null
                    },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = provider.keyHint,
                    minHeight = 56.dp,
                    singleLine = true,
                    masked = true
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "模型名 / 推理接入点",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = provider.modelHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextTertiary
                )
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        app.settings.model = it
                        testResult = null
                    },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = provider.defaultModel,
                    minHeight = 56.dp,
                    singleLine = true
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "自定义接口地址（可选）",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "留空则用 ${provider.baseUrl}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextTertiary
                )
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        app.settings.customBaseUrl = it
                        testResult = null
                    },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://…/v1",
                    minHeight = 56.dp,
                    singleLine = true
                )

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 测试连接
                    com.orion.assistant.ui.components.GlassButton(
                        text = if (testing) "测试中…" else "测试连接",
                        backdrop = backdrop,
                        leading = Icons.Filled.Refresh,
                        enabled = !testing && apiKey.isNotBlank(),
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val result = VisionClient(app.settings).testConnection()
                                withContext(Dispatchers.Main) {
                                    testResult = result
                                    testing = false
                                }
                            }
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    testResult?.let {
                        val ok = it.contains("成功")
                        StatusPill(
                            text = if (ok) "连接正常" else "连接失败",
                            color = if (ok) OrionColors.Success else OrionColors.Danger
                        )
                    }
                }
                testResult?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.contains("成功")) OrionColors.Success else OrionColors.Danger
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------ 操作节奏
            SectionTitle("操作节奏")

            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "每步之间的间隔",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = speed.hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextTertiary
                )
                Spacer(Modifier.height(11.dp))
                GlassSegmented(
                    options = OperationSpeed.entries.toList(),
                    selected = speed,
                    onSelect = {
                        speed = it
                        app.settings.speed = it
                    },
                    label = { it.label },
                    backdrop = backdrop,
                    // 「智能」档旁边配一颗渐变小星
                    trailing = { option ->
                        if (option == OperationSpeed.SMART) GradientStar()
                    }
                )

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "单次任务最多步数",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = OrionColors.TextPrimary
                    )
                    Text(
                        text = maxSteps.toInt().toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = OrionColors.Accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = maxSteps,
                    onValueChange = {
                        val rounded = it.toInt()
                        // 只在整数档位发生变化时震动一次，避免拖动过程中连续狂震
                        if (rounded != maxSteps.toInt()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        maxSteps = it
                        app.settings.maxSteps = rounded
                    },
                    valueRange = 5f..100f,
                    steps = 94,
                    colors = SliderDefaults.colors(
                        thumbColor = OrionColors.Accent,
                        activeTrackColor = OrionColors.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.6f)
                    )
                )
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------ 智能体提示词
            SectionTitle("智能体行为")

            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "自定义提示词（可选）",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "会追加在系统提示词后面，用来规定 Orion 的做事习惯；与内置规则冲突时以你写的为准。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextTertiary
                )
                Spacer(Modifier.height(10.dp))
                GlassTextField(
                    value = customPrompt,
                    onValueChange = {
                        customPrompt = it
                        app.settings.customPrompt = it
                    },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "例如：涉及付款的操作，一律先停下来问我",
                    minHeight = 96.dp
                )

                Spacer(Modifier.height(20.dp))

                // 自学习：开关旁配一颗渐变小星，和「智能」档的标识一致
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "自学习",
                                style = MaterialTheme.typography.titleMedium,
                                color = OrionColors.TextPrimary
                            )
                            GradientStar()
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "每次任务结束后自动复盘好的和不足的地方，攒成经验用到下次，越用越顺手。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OrionColors.TextTertiary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OrionToggle(
                        checked = selfLearning,
                        onCheckedChange = {
                            selfLearning = it
                            app.settings.selfLearning = it
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------ 数据
            SectionTitle("数据与隐私")

            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "保存每一步的截图",
                            style = MaterialTheme.typography.titleMedium,
                            color = OrionColors.TextPrimary
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "开启后会占用较多存储，仅存本机",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OrionColors.TextTertiary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OrionToggle(
                        checked = saveScreenshots,
                        onCheckedChange = {
                            saveScreenshots = it
                            app.settings.saveScreenshots = it
                        }
                    )
                }

                Spacer(Modifier.height(18.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "清空任务历史",
                            style = MaterialTheme.typography.titleMedium,
                            color = OrionColors.TextPrimary
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "删除本地数据库里的全部任务与步骤",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OrionColors.TextTertiary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    com.orion.assistant.ui.components.GlassButton(
                        text = "清空",
                        backdrop = backdrop,
                        leading = Icons.Filled.Delete,
                        onClick = { pendingClear = ClearTarget.HISTORY }
                    )
                }

                Spacer(Modifier.height(18.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "清空自学习",
                            style = MaterialTheme.typography.titleMedium,
                            color = OrionColors.TextPrimary
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "删除已攒下的全部经验（当前 $learningCount 条），Orion 会退回从头学",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OrionColors.TextTertiary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    com.orion.assistant.ui.components.GlassButton(
                        text = "清空",
                        backdrop = backdrop,
                        leading = Icons.Filled.Delete,
                        onClick = { pendingClear = ClearTarget.LEARNING }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ------------------------------------------------------ 关于
            SectionTitle("关于")

            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "xiawan开发\n" +
                        "orionV2.3.0\n" +
                        "本项目基于 GNU General Public License v3.0 发布，不可商用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextSecondary
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    // 所有「清空」都必须二次确认，避免误触把数据删掉
    pendingClear?.let { target ->
        val isHistory = target == ClearTarget.HISTORY
        ConfirmClearDialog(
            title = if (isHistory) "清空任务历史？" else "清空自学习？",
            message = if (isHistory) {
                "本地数据库里的全部任务与步骤都会被删除，且无法恢复。"
            } else {
                "已攒下的全部经验都会被删除，Orion 会退回「从头开始学」。"
            },
            confirmText = if (isHistory) "确认清空历史" else "确认清空自学习",
            onConfirm = {
                pendingClear = null
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        if (isHistory) app.tasks.clearAll() else app.learning.clearAll()
                    }
                    if (!isHistory) {
                        withContext(Dispatchers.Main) { learningCount = 0 }
                    }
                }
            },
            onDismiss = { pendingClear = null }
        )
    }
}

/** 待二次确认的清空目标 */
private enum class ClearTarget { HISTORY, LEARNING }

/** 清空类操作的二次确认弹窗 */
@Composable
private fun ConfirmClearDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = OrionColors.TextPrimary
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OrionColors.TextSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = OrionColors.Danger,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = OrionColors.TextSecondary)
            }
        }
    )
}