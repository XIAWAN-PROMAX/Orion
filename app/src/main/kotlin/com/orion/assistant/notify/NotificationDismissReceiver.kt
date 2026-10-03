package com.orion.assistant.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收「实况通知被用户划掉 / 取消固定」的广播（由 LiveUpdateNotifier 的 deleteIntent 触发）。
 *
 * 官方要求：不重新发布用户已关闭的实时更新。这里把状态记到 [LiveUpdateNotifier]，
 * 后续的任务进度更新会跳过发布，直到开始下一个新任务时再重置。
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != LiveUpdateNotifier.ACTION_DISMISSED) return
        LiveUpdateNotifier.onDismissedByUser()
    }
}
