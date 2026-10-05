package com.orion.assistant.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.orion.assistant.data.LearningRecord
import com.orion.assistant.orionApp
import com.orion.assistant.ui.components.GlassCard
import com.orion.assistant.ui.components.OrionTopBar
import com.orion.assistant.ui.components.StatusPill
import com.orion.assistant.ui.theme.OrionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自学习数据（二级页面）：把「自学习」攒下的经验一条条列出来。
 * 数据来自 LearningRepository（orion_learning.db 的 lessons 表），每条经验一张卡片。
 */
@Composable
fun LearningDataScreen(
    backdrop: Backdrop,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.orionApp

    var records by remember { mutableStateOf<List<LearningRecord>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        records = withContext(Dispatchers.IO) {
            runCatching { app.learning.recent() }.getOrDefault(emptyList())
        }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        OrionTopBar(
            title = "自学习数据",
            subtitle = "已攒下 ${records.size} 条经验记录",
            backdrop = backdrop,
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            when {
                !loaded -> Unit

                records.isEmpty() -> GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "暂无自学习数据",
                        style = MaterialTheme.typography.titleMedium,
                        color = OrionColors.TextPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "开启「自学习」后完成任务，Orion 会自动复盘并攒下经验。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OrionColors.TextSecondary
                    )
                }

                else -> records.forEach { record ->
                    LearningRecordCard(backdrop = backdrop, record = record)
                    Spacer(Modifier.height(12.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 单条自学习经验卡片：复盘的任务 + 好的地方 / 待改进 / 改进要点 + 时间 */
@Composable
private fun LearningRecordCard(backdrop: Backdrop, record: LearningRecord) {
    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.instruction.ifBlank { "未命名任务" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = OrionColors.TextPrimary
            )
            if (record.status.isNotBlank()) {
                Spacer(Modifier.width(9.dp))
                StatusPill(
                    text = statusLabel(record.status),
                    color = statusColor(record.status)
                )
            }
        }

        if (record.good.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            LearningLine(label = "做得好", text = record.good, color = OrionColors.Success)
        }
        if (record.bad.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            LearningLine(label = "待改进", text = record.bad, color = OrionColors.Warning)
        }
        if (record.tip.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            LearningLine(label = "改进要点", text = record.tip, color = OrionColors.Accent)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = formatTime(record.createdAt),
            style = MaterialTheme.typography.labelMedium,
            color = OrionColors.TextTertiary
        )
    }
}

@Composable
private fun LearningLine(label: String, text: String, color: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = OrionColors.TextSecondary
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

private val learningTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = learningTimeFormat.format(Date(millis))
