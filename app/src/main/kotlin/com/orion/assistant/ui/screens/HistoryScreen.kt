package com.orion.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.orion.assistant.data.StepRecord
import com.orion.assistant.data.TaskRecord
import com.orion.assistant.orionApp
import com.orion.assistant.ui.components.GlassButton
import com.orion.assistant.ui.components.GlassCard
import com.orion.assistant.ui.components.OrionTopBar
import com.orion.assistant.ui.components.StatusPill
import com.orion.assistant.ui.theme.OrionColors
import com.orion.assistant.ui.theme.OrionMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录：本地 SQLite 里的任务与每一步明细。
 * 数据来自 TaskRepository（tasks / steps 两张表）。
 */
@Composable
fun HistoryScreen(
    backdrop: Backdrop,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.orionApp

    var tasks by remember { mutableStateOf<List<TaskRecord>>(emptyList()) }
    var steps by remember { mutableStateOf<List<StepRecord>>(emptyList()) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // 拉取任务列表
    LaunchedEffect(refreshKey) {
        tasks = withContext(Dispatchers.IO) { runCatching { app.tasks.recentTasks(40) }.getOrDefault(emptyList()) }
        loaded = true
    }

    // 展开某条任务时拉取它的步骤
    LaunchedEffect(expandedId) {
        val id = expandedId
        steps = if (id == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { runCatching { app.tasks.stepsOf(id) }.getOrDefault(emptyList()) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        OrionTopBar(
            title = "历史记录",
            subtitle = "本地保存的任务过程与结果",
            backdrop = backdrop,
            onBack = onBack,
            trailing = {
                if (tasks.isNotEmpty()) {
                    GlassButton(
                        text = "清空",
                        backdrop = backdrop,
                        leading = Icons.Filled.Delete,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                runCatching { app.tasks.clearAll() }
                                withContext(Dispatchers.Main) {
                                    expandedId = null
                                    refreshKey++
                                }
                            }
                        }
                    )
                }
            }
        )

        Spacer(Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                !loaded -> Unit

                tasks.isEmpty() -> GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "还没有任务记录",
                        style = MaterialTheme.typography.titleMedium,
                        color = OrionColors.TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "回到首页下达第一条指令，执行过程会自动记录在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OrionColors.TextSecondary
                    )
                }

                else -> tasks.forEach { task ->
                    TaskHistoryCard(
                        backdrop = backdrop,
                        task = task,
                        expanded = expandedId == task.id,
                        steps = if (expandedId == task.id) steps else emptyList(),
                        onToggle = {
                            expandedId = if (expandedId == task.id) null else task.id
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TaskHistoryCard(
    backdrop: Backdrop,
    task: TaskRecord,
    expanded: Boolean,
    steps: List<StepRecord>,
    onToggle: () -> Unit
) {
    GlassCard(
        backdrop = backdrop,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.instruction,
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(text = statusLabel(task.status), color = statusColor(task.status))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "${task.stepCount} 步 · ${formatTime(task.createdAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OrionColors.TextTertiary
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = OrionColors.TextTertiary,
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }

        if (task.summary.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = task.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = OrionColors.TextSecondary
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(320, easing = OrionMotion.gentleOut)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(140))
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                if (steps.isEmpty()) {
                    Text(
                        text = "这一步没有留下明细",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OrionColors.TextTertiary
                    )
                } else {
                    steps.forEach { step ->
                        StepRow(step)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: StepRecord) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(OrionColors.Accent.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "第 ${step.stepIndex} 步",
                    style = MaterialTheme.typography.labelMedium,
                    color = OrionColors.Accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = step.action,
                style = MaterialTheme.typography.bodyMedium,
                color = OrionColors.TextPrimary
            )
        }
        if (step.thought.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = step.thought,
                style = MaterialTheme.typography.bodyMedium,
                color = OrionColors.TextSecondary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = step.result,
            style = MaterialTheme.typography.labelMedium,
            color = OrionColors.TextTertiary
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "COMPLETED" -> "已完成"
    "RUNNING" -> "执行中"
    "FAILED" -> "失败"
    "STOPPED" -> "已停止"
    "PAUSED" -> "已暂停"
    else -> status
}

private fun statusColor(status: String): Color = when (status) {
    "COMPLETED" -> OrionColors.Success
    "FAILED" -> OrionColors.Danger
    "STOPPED", "PAUSED" -> OrionColors.Warning
    else -> OrionColors.Accent
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))