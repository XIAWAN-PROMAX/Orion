package com.orion.assistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.orion.assistant.data.SettingsRepository
import com.orion.assistant.orionApp
import com.orion.assistant.service.OrionAccessibilityService
import com.orion.assistant.service.ScreenCaptureService
import kotlinx.coroutines.delay

/** Orion 运行所需的四件套状态 */
data class OrionStatus(
    val accessibility: Boolean,
    val capture: Boolean,
    val notifications: Boolean,
    val apiKey: Boolean
) {
    val allReady: Boolean get() = accessibility && capture && apiKey
}

/**
 * 权限与能力的检测 / 跳转工具。
 * TODO(接入点)：若以后需要「悬浮窗」「电池优化白名单」等额外权限，在这里加一个检测方法即可。
 */
object OrionPermissions {

    /** 无障碍服务是否已开启（即使进程刚起、服务实例还没连上，也能从系统设置里读到） */
    fun isAccessibilityEnabled(context: Context): Boolean {
        if (OrionAccessibilityService.isConnected()) return true
        val expected = ComponentName(context, OrionAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
            }
    }

    /** 截屏授权是否仍然有效（MediaProjection 需要 App 进程活着才有意义） */
    fun isCaptureGranted(): Boolean = ScreenCaptureService.isRunning()

    fun isNotificationGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isApiKeyReady(context: Context): Boolean =
        SettingsRepository(context).let { it.apiKey.isNotBlank() }

    fun read(context: Context): OrionStatus = OrionStatus(
        accessibility = isAccessibilityEnabled(context),
        capture = isCaptureGranted(),
        notifications = isNotificationGranted(context),
        apiKey = context.orionApp.settings.apiKey.isNotBlank()
    )

    /** 跳系统「无障碍」设置页 */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 跳本应用的「应用详情」页（用于手动补授权） */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * 轮询式权限状态订阅。
 *
 * 用户在系统设置里开关无障碍、授权截屏，回到 App 时不一定有生命周期回调能精确对应，
 * 所以页面上以 700ms 的频率轻量轮询一次（只在页面可见时进行），保证状态胶囊即时刷新。
 */
@Composable
fun rememberOrionStatus(): OrionStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(OrionPermissions.read(context)) }
    LaunchedEffect(context) {
        while (true) {
            status = OrionPermissions.read(context)
            delay(700)
        }
    }
    return status
}