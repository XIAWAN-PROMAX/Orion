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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.orion.assistant.ui.OrionPermissions
import com.orion.assistant.ui.OrionStatus
import com.orion.assistant.ui.components.GlassCard
import com.orion.assistant.ui.components.GlassChip
import com.orion.assistant.ui.components.GlassListRow
import com.orion.assistant.ui.components.OrionIcons
import com.orion.assistant.ui.components.OrionProgressBar
import com.orion.assistant.ui.components.OrionTopBar
import com.orion.assistant.ui.components.StatusPill
import com.orion.assistant.ui.rememberOrionStatus
import com.orion.assistant.ui.theme.OrionColors

/**
 * 授权管理页：无障碍 / 截屏 / 通知 / API Key 四项的真实状态 +
 * 一键跳转系统设置。状态每 700ms 刷新一次，从系统设置回来立刻就能看到变化。
 */
@Composable
fun PermissionScreen(
    backdrop: Backdrop,
    onBack: () -> Unit,
    onRequestCapture: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val status = rememberOrionStatus()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        OrionTopBar(
            title = "授权管理",
            subtitle = "缺一样，Orion 就动不了手",
            backdrop = backdrop,
            onBack = onBack
        )

        Spacer(Modifier.height(18.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            SummaryCard(backdrop = backdrop, status = status)

            Spacer(Modifier.height(16.dp))

            GlassListRow(
                backdrop = backdrop,
                title = "无障碍服务",
                subtitle = "让 Orion 能点击、滑动、输入",
                icon = OrionIcons.Accessibility,
                iconTint = OrionColors.Accent,
                onClick = {
                    runCatching {
                        context.startActivity(OrionPermissions.accessibilitySettingsIntent())
                    }
                },
                trailing = {
                    if (status.accessibility) {
                        StatusPill("已开启", OrionColors.Success)
                    } else {
                        GlassChip(
                            text = "去开启",
                            backdrop = backdrop,
                            onClick = {
                                runCatching {
                                    context.startActivity(OrionPermissions.accessibilitySettingsIntent())
                                }
                            }
                        )
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            GlassListRow(
                backdrop = backdrop,
                title = "截屏授权",
                subtitle = "让 Orion 看得到当前画面",
                icon = OrionIcons.ScreenCast,
                iconTint = OrionColors.Mint,
                onClick = onRequestCapture,
                trailing = {
                    if (status.capture) {
                        StatusPill("已授权", OrionColors.Success)
                    } else {
                        GlassChip("去授权", backdrop, onRequestCapture)
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            GlassListRow(
                backdrop = backdrop,
                title = "通知权限",
                subtitle = "在实况通知 / 灵动岛里看进度",
                icon = Icons.Filled.Notifications,
                iconTint = OrionColors.Peach,
                onClick = onRequestNotification,
                trailing = {
                    if (status.notifications) {
                        StatusPill("已开启", OrionColors.Success)
                    } else {
                        GlassChip("去开启", backdrop, onRequestNotification)
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            GlassListRow(
                backdrop = backdrop,
                title = "视觉模型 API Key",
                subtitle = "决定 Orion 能不能想明白",
                icon = Icons.Filled.Lock,
                iconTint = OrionColors.Rose,
                onClick = onOpenSettings,
                trailing = {
                    if (status.apiKey) {
                        StatusPill("已配置", OrionColors.Success)
                    } else {
                        GlassChip("去填写", backdrop, onOpenSettings)
                    }
                }
            )

            Spacer(Modifier.height(18.dp))

            GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "为什么需要这些权限？",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(10.dp))
                BulletText("无障碍：把 AI 的决策变成真实的手指动作（dispatchGesture）。")
                Spacer(Modifier.height(7.dp))
                BulletText("截屏：用 MediaProjection 拿到画面，交给视觉模型理解。截图只用于当步判断。")
                Spacer(Modifier.height(7.dp))
                BulletText("通知：把「当前步骤 / 结果」推成实况通知，退出 App 也能看到进度。")
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SummaryCard(backdrop: Backdrop, status: OrionStatus) {
    val items = listOf(
        status.accessibility to "无障碍",
        status.capture to "截屏",
        status.notifications to "通知",
        status.apiKey to "API Key"
    )
    val readyCount = items.count { it.first }

    GlassCard(backdrop = backdrop, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (status.allReady) "可以开始使用了" else "还差 ${3 - minOf(readyCount, 3)} 项关键授权",
                    style = MaterialTheme.typography.titleLarge,
                    color = OrionColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "关键三项：无障碍 · 截屏 · API Key",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OrionColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        OrionProgressBar(progress = readyCount / items.size.toFloat())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (done, label) ->
                StatusPill(
                    text = "$label ${if (done) "✓" else "·"}",
                    color = if (done) OrionColors.Success else OrionColors.TextTertiary
                )
            }
        }
    }
}

@Composable
private fun BulletText(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyLarge,
            color = OrionColors.Accent
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = OrionColors.TextSecondary
        )
    }
}