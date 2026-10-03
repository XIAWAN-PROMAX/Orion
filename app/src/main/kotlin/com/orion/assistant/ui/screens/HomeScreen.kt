package com.orion.assistant.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.orion.assistant.engine.TaskOrchestrator
import com.orion.assistant.engine.TaskStatus
import com.orion.assistant.orionApp
import com.orion.assistant.ui.components.GlassCard
import com.orion.assistant.ui.components.GlassChip
import com.orion.assistant.ui.components.GlassIconButton
import com.orion.assistant.ui.components.GlassSurface
import com.orion.assistant.ui.components.GlassTextField
import com.orion.assistant.ui.components.OrionIcons
import com.orion.assistant.ui.components.OrionProgressBar
import com.orion.assistant.ui.components.rememberLiquidInteraction
import com.orion.assistant.ui.rememberOrionStatus
import com.orion.assistant.ui.theme.OrionColors
import com.orion.assistant.ui.theme.OrionMotion

private val EXAMPLE_INSTRUCTIONS = listOf(
    "打开小管家做题",
    "帮我点个外卖",
    "刷抖音",
    "打开微信，给妈妈发条消息",
    "帮我把刚才的截图存到相册"
)

/**
 * 首页：指令输入 → 开始 → 任务状态区（可暂停 / 继续 / 停止）。
 * 指令内容完全自由，Orion 不预设任何具体 App。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    backdrop: Backdrop,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onRequestCapture: () -> Unit
) {
    val context = LocalContext.current
    val app = context.orionApp
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val permissionStatus = rememberOrionStatus()
    var instruction by remember { mutableStateOf(app.settings.lastInstruction) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- 顶栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(OrionColors.Accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = OrionIcons.Sparkles,
                    contentDescription = null,
                    tint = OrionColors.Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Orion",
                    style = MaterialTheme.typography.titleLarge,
                    color = OrionColors.TextPrimary
                )
                Text(
                    text = "说一句话，我替你操作手机",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextSecondary
                )
            }
            GlassIconButton(backdrop = backdrop, onClick = onOpenHistory, size = 42.dp) {
                Icon(
                    imageVector = OrionIcons.History,
                    contentDescription = "历史记录",
                    tint = OrionColors.TextPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(9.dp))
            GlassIconButton(backdrop = backdrop, onClick = onOpenPermissions, size = 42.dp) {
                Icon(
                    imageVector = OrionIcons.Accessibility,
                    contentDescription = "授权管理",
                    tint = if (permissionStatus.allReady) OrionColors.Success else OrionColors.Warning,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(9.dp))
            GlassIconButton(backdrop = backdrop, onClick = onOpenSettings, size = 42.dp) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = OrionColors.TextPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---------------------------------------------------------- 可滚动内容
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .animateContentSize(animationSpec = OrionMotion.springSmooth())
        ) {
            GlassTextField(
                value = instruction,
                onValueChange = { instruction = it },
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                placeholder = "想让我做什么？例如「打开小管家做题」",
                minHeight = 132.dp,
                enabled = !TaskOrchestrator.status.isActive
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                EXAMPLE_INSTRUCTIONS.forEach { sample ->
                    GlassChip(
                        text = sample,
                        backdrop = backdrop,
                        onClick = {
                            if (!TaskOrchestrator.status.isActive) instruction = sample
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            val active = TaskOrchestrator.status.isActive
            WideActionButton(
                text = if (active) "任务进行中…" else "开始执行",
                backdrop = backdrop,
                leading = Icons.Filled.PlayArrow,
                prominent = true,
                enabled = instruction.isNotBlank() && !active,
                onClick = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    app.settings.lastInstruction = instruction.trim()
                    TaskOrchestrator.start(instruction)
                }
            )

            Spacer(Modifier.height(20.dp))

            // 任务状态区：就绪检查 / 执行中 / 结果
            TaskStatusArea(
                backdrop = backdrop,
                permissionStatus = permissionStatus,
                onOpenPermissions = onOpenPermissions,
                onRequestCapture = onRequestCapture
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 通栏的主操作按钮（GlassButton 是自适应宽度，这里需要撑满并居中） */
@Composable
private fun WideActionButton(
    text: String,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
    leading: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val interaction = rememberLiquidInteraction()
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(50),
        tint = if (prominent) OrionColors.Accent.copy(alpha = 0.9f) else OrionColors.GlassTint,
        interaction = if (enabled) interaction else null,
        onTap = if (enabled) onClick else null,
        contentPadding = PaddingValues(vertical = 17.dp, horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                Icon(
                    imageVector = leading,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = if (prominent) Color.White else OrionColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 随任务状态切换的状态区（弹性淡入淡出 + 缩放） */
@Composable
private fun TaskStatusArea(
    backdrop: Backdrop,
    permissionStatus: com.orion.assistant.ui.OrionStatus,
    onOpenPermissions: () -> Unit,
    onRequestCapture: () -> Unit
) {
    AnimatedContent(
        targetState = TaskOrchestrator.status,
        transitionSpec = {
            (fadeIn(tween(260)) + scaleIn(tween(420, easing = OrionMotion.overshoot), initialScale = 0.94f))
                .togetherWith(fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.98f))
        },
        label = "task-status"
    ) { status ->
        when (status) {
            TaskStatus.IDLE -> IdleChecklist(
                backdrop = backdrop,
                permissionStatus = permissionStatus,
                onOpenPermissions = onOpenPermissions,
                onRequestCapture = onRequestCapture
            )

            TaskStatus.RUNNING, TaskStatus.PAUSED -> RunningCard(backdrop = backdrop)

            TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.STOPPED -> ResultCard(
                backdrop = backdrop,
                onFix = onOpenPermissions
            )
        }
    }
}

/** 空闲时：把「三件套」的开启状态铺出来，缺哪个点哪个 */
@Composable
private fun IdleChecklist(
    backdrop: Backdrop,
    permissionStatus: com.orion.assistant.ui.OrionStatus,
    onOpenPermissions: () -> Unit,
    onRequestCapture: () -> Unit
) {
    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (permissionStatus.allReady) "一切就绪" else "开始前先备好这三样",
            style = MaterialTheme.typography.titleMedium,
            color = OrionColors.TextPrimary
        )
        Spacer(Modifier.height(12.dp))
        ChecklistRow(
            label = "无障碍服务",
            done = permissionStatus.accessibility,
            onClick = onOpenPermissions
        )
        Spacer(Modifier.height(9.dp))
        ChecklistRow(
            label = "截屏授权",
            done = permissionStatus.capture,
            onClick = onRequestCapture
        )
        Spacer(Modifier.height(9.dp))
        ChecklistRow(
            label = "视觉模型 API Key",
            done = permissionStatus.apiKey,
            onClick = onOpenPermissions
        )
    }
}

@Composable
private fun ChecklistRow(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (done) OrionColors.Success.copy(alpha = 0.16f)
                    else OrionColors.Warning.copy(alpha = 0.16f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (done) OrionColors.Success else OrionColors.Warning,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = OrionColors.TextPrimary
        )
        Text(
            text = if (done) "已开启" else "去设置",
            style = MaterialTheme.typography.labelLarge,
            color = if (done) OrionColors.TextTertiary else OrionColors.Accent
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = OrionColors.TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 执行中：当前步骤 + 思考 + 进度 + 暂停/停止 */
@Composable
private fun RunningCard(backdrop: Backdrop) {
    val status = TaskOrchestrator.status
    val step = TaskOrchestrator.stepIndex
    val maxSteps = TaskOrchestrator.maxSteps
    val progress = if (maxSteps > 0) step.toFloat() / maxSteps else 0f
    val paused = status == TaskStatus.PAUSED
    val preview = TaskOrchestrator.previewFrame

    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (paused) OrionColors.Warning.copy(alpha = 0.16f)
                        else OrionColors.Accent.copy(alpha = 0.16f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = OrionIcons.Sparkles,
                    contentDescription = null,
                    tint = if (paused) OrionColors.Warning else OrionColors.Accent,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (paused) "已暂停" else "正在执行",
                style = MaterialTheme.typography.titleMedium,
                color = OrionColors.TextPrimary
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "第 $step / $maxSteps 步",
                style = MaterialTheme.typography.labelMedium,
                color = OrionColors.TextSecondary
            )
        }

        Spacer(Modifier.height(13.dp))
        OrionProgressBar(progress = progress)

        val thought = TaskOrchestrator.currentThought
        val action = TaskOrchestrator.currentActionText
        if (thought.isNotBlank()) {
            Spacer(Modifier.height(13.dp))
            Text(
                text = thought,
                style = MaterialTheme.typography.bodyLarge,
                color = OrionColors.TextPrimary
            )
        }
        if (action.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = action,
                style = MaterialTheme.typography.bodyMedium,
                color = OrionColors.Accent
            )
        }

        if (preview != null && !preview.isRecycled) {
            Spacer(Modifier.height(13.dp))
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "当前屏幕",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
            )
        }

        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WideActionButton(
                text = if (paused) "继续" else "暂停",
                backdrop = backdrop,
                leading = if (paused) Icons.Filled.PlayArrow else OrionIcons.Pause,
                modifier = Modifier.weight(1f),
                onClick = { if (paused) TaskOrchestrator.resume() else TaskOrchestrator.pause() }
            )
            WideActionButton(
                text = "停止",
                backdrop = backdrop,
                leading = OrionIcons.Stop,
                modifier = Modifier.weight(1f),
                onClick = { TaskOrchestrator.stop() }
            )
        }
    }
}

/** 结束：结果 / 失败原因 + 回到初始 */
@Composable
private fun ResultCard(backdrop: Backdrop, onFix: () -> Unit) {
    val status = TaskOrchestrator.status
    val summary = TaskOrchestrator.resultSummary
    val error = TaskOrchestrator.errorMessage
    val failed = status == TaskStatus.FAILED

    val accent = when (status) {
        TaskStatus.COMPLETED -> OrionColors.Success
        TaskStatus.STOPPED -> OrionColors.TextSecondary
        else -> OrionColors.Danger
    }

    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (failed) Icons.Filled.Warning else Icons.Filled.Check,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Text(
                text = when (status) {
                    TaskStatus.COMPLETED -> "任务完成"
                    TaskStatus.STOPPED -> "已停止"
                    else -> "没能完成"
                },
                style = MaterialTheme.typography.titleMedium,
                color = OrionColors.TextPrimary
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = (if (failed) error else summary).ifBlank { "任务已结束" },
            style = MaterialTheme.typography.bodyLarge,
            color = OrionColors.TextPrimary
        )

        if (failed) {
            Spacer(Modifier.height(14.dp))
            WideActionButton(
                text = "去检查权限与设置",
                backdrop = backdrop,
                onClick = onFix
            )
        }

        Spacer(Modifier.height(12.dp))
        WideActionButton(
            text = "知道了",
            backdrop = backdrop,
            onClick = { TaskOrchestrator.dismissResult() }
        )
    }
}