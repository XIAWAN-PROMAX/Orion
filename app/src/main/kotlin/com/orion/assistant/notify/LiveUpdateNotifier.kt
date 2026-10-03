package com.orion.assistant.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import com.orion.assistant.MainActivity
import com.orion.assistant.R

/**
 * 实况通知（Android 16 Live Updates / 灵动岛）。
 *
 * 要让通知真正被系统提升为 Live Update（状态栏胶囊、锁屏实况区），官方要求同时满足：
 *  1. 样式属于标准样式之一（这里用 [Notification.ProgressStyle]）；
 *  2. 清单里声明非运行时权限 android.permission.POST_PROMOTED_NOTIFICATIONS；
 *  3. 用 [EXTRA_REQUEST_PROMOTED_ONGOING] 主动「请求提升」—— 这是关键，缺了它系统永远不会提升；
 *  4. setOngoing(true) 且设置了 contentTitle；
 *  5. 不使用自定义 RemoteViews、不是群组摘要、不 setColorized(true)；
 *  6. 通知渠道重要性不能是 IMPORTANCE_MIN。
 *
 * 注意：[Notification.FLAG_PROMOTED_ONGOING] 是系统写上去的「只读」状态位，
 * 应用自己设它没有任何作用。之前灵动岛不生效，正是因为只设了它、却漏了第 3 条。
 *
 * 提升为 Live Update 的实际展示还需要设备为 Android 16 QPR1（API 36.1）及以上；
 * 在 Android 16.0 上通知仍会就地更新，只是不会被提升。低版本（API 33~35）退化为普通的常驻进度通知。
 */
object LiveUpdateNotifier {

    const val NOTIFICATION_ID = 4201

    /** 用户划掉 / 取消固定实况通知时发出的广播动作 */
    const val ACTION_DISMISSED = "com.orion.assistant.action.LIVE_UPDATE_DISMISSED"

    /**
     * 渠道 id。
     *
     * 渠道的重要性一旦创建就无法由应用在代码里修改，所以这里用一个新的 id 承载
     * IMPORTANCE_DEFAULT —— 官方示例同样使用 DEFAULT（提升要求仅禁止 IMPORTANCE_MIN）。
     * 旧渠道 orion_task_progress 是 IMPORTANCE_LOW，已弃用。
     */
    private const val CHANNEL_ID = "orion_live_updates"
    private const val SDK_36 = 36

    /**
     * 请求把通知提升为 Live Update 的 extras 键。
     *
     * 框架里的 Notification.EXTRA_REQUEST_PROMOTED_ONGOING 与
     * Notification.Builder#setRequestPromotedOngoing 都是 API 36.1 才加入的，
     * 在 compileSdk 36 下无法直接引用，因此按官方文档改用它的字符串键写入 extras。
     */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /** 通知内容快照 */
    data class Snapshot(
        val title: String,
        val text: String,
        /** 状态栏胶囊里的一行短文案（建议 ≤7 个字符，超出可能只显示图标；仅 API 36+ 生效） */
        val criticalText: String,
        /** 0~100；indeterminate 为 true 时忽略 */
        val progress: Int = 0,
        val indeterminate: Boolean = false
    )

    /** 用户是否已手动关掉这条实况；关掉后不得再发布（官方要求） */
    @Volatile
    private var dismissedByUser = false

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            // DEFAULT 重要性 + 静音：既满足提升条件，又不会响铃 / 震动打扰
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    /** 当前系统是否允许发布「提升的实况通知」（API 36+ 才有意义） */
    fun canPostPromoted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < SDK_36) return false
        return context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true
    }

    /** 开始一个新任务前调用：清掉上一条被用户关掉的记录，允许重新发布 */
    fun resetDismissal() {
        dismissedByUser = false
    }

    /** 用户划掉实况通知时由 [NotificationDismissReceiver] 回调 */
    fun onDismissedByUser() {
        dismissedByUser = true
    }

    fun build(context: Context, snapshot: Snapshot): Notification {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 用户划掉 / 取消固定时回调，用来停止后续更新
        val deleteIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, NotificationDismissReceiver::class.java).setAction(ACTION_DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_orion)
            .setContentTitle(snapshot.title)
            .setContentText(snapshot.text)
            .setSubText(snapshot.criticalText)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)

        if (Build.VERSION.SDK_INT >= SDK_36) {
            applyLiveUpdate(context, builder, snapshot)
        } else {
            builder.setProgress(100, snapshot.progress, snapshot.indeterminate)
        }

        return builder.build()
    }

    @RequiresApi(SDK_36)
    private fun applyLiveUpdate(
        context: Context,
        builder: Notification.Builder,
        snapshot: Snapshot
    ) {
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressIndeterminate(snapshot.indeterminate)
            .setProgress(snapshot.progress.coerceIn(0, 100))
            .setProgressTrackerIcon(
                Icon.createWithResource(context, R.drawable.ic_stat_orion)
            )

        builder
            .setStyle(style)
            .setShortCriticalText(snapshot.criticalText)
            // 前台服务类型：本通知同时作为 ScreenCaptureService 的前台通知
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            // 主动「请求提升」为 Live Update（API 36.1+ 生效）
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
    }

    /**
     * 更新已存在的实况通知。
     * 用户若已关掉这条实况则跳过，避免重新打扰（官方要求）。
     */
    fun push(context: Context, snapshot: Snapshot) {
        if (dismissedByUser) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFICATION_ID, build(context, snapshot)) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
