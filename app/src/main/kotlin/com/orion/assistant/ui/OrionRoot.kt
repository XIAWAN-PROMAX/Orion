package com.orion.assistant.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.orion.assistant.orionApp
import com.orion.assistant.service.ScreenCaptureService
import com.orion.assistant.ui.components.OrionBackground
import com.orion.assistant.ui.screens.HistoryScreen
import com.orion.assistant.ui.screens.HomeScreen
import com.orion.assistant.ui.screens.LearningDataScreen
import com.orion.assistant.ui.screens.OnboardingScreen
import com.orion.assistant.ui.screens.PermissionScreen
import com.orion.assistant.ui.screens.SettingsScreen
import com.orion.assistant.ui.theme.OrionMotion

/** App 内的页面（不引入 navigation 库，一个状态就够了） */
enum class OrionScreen {
    HOME, ONBOARDING, PERMISSIONS, SETTINGS, HISTORY, LEARNING_DATA
}

/** 从系统设置返回时用来触发界面刷新（MainActivity.onResume 里自增） */
object OrionRefresh {
    var tick by mutableIntStateOf(0)
}

/**
 * 根容器：
 *  1. 背景（OrionBackground）被记录进一个 LayerBackdrop，作为所有液态玻璃的采样源；
 *  2. 页面之间用弹性缓动滑入滑出；
 *  3. 统一管理「截屏授权」「通知授权」两个系统弹窗。
 */
@Composable
fun OrionRoot() {
    val context = LocalContext.current
    val app = context.orionApp

    // 液态玻璃的采样源：把背景画进一个 GraphicsLayer，玻璃组件再对这块内容做折射
    val backdrop = rememberLayerBackdrop()

    var screen by remember {
        mutableStateOf(
            if (app.settings.onboardingCompleted) OrionScreen.HOME else OrionScreen.ONBOARDING
        )
    }

    // ---------------- 系统弹窗：截屏授权 ----------------
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data: Intent? = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // 拿到授权后立刻建立投屏会话（用掉一次性 token），但不采集画面；
            // 真正开始截屏要等「开始任务」，任务结束会停止采集。
            ScreenCaptureService.rememberConsent(context, result.resultCode, data)
        }
    }

    val requestCapture: () -> Unit = {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        if (manager != null) {
            captureLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    // ---------------- 系统弹窗：通知权限 ----------------
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果会由 rememberOrionStatus 轮询读到，这里不用处理 */ }

    val requestNotification: () -> Unit = {
        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------- 返回键 / 边缘侧滑 ----------------
    // 之前没有拦截返回，系统的边缘侧滑会被当成「退出 Activity」，所以一滑就直接退出 App。
    // 这里把子页面（授权 / 设置 / 历史）的返回统一收敛到上一级菜单（首页）；
    // 只有首页和引导页不拦截，保留系统默认的「退出」行为。
    val hasParent = screen == OrionScreen.PERMISSIONS ||
        screen == OrionScreen.SETTINGS ||
        screen == OrionScreen.HISTORY ||
        screen == OrionScreen.LEARNING_DATA
    BackHandler(enabled = hasParent) {
        // 自学习数据是设置页的子页面，返回时回到设置；其余子页面回首页
        screen = if (screen == OrionScreen.LEARNING_DATA) {
            OrionScreen.SETTINGS
        } else {
            OrionScreen.HOME
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 背景 + 记录进 backdrop（玻璃的采样源）
        OrionBackground(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        )

        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val direction = if (forward) 1 else -1
                // 用 Overshoot 缓动做「弹性插值」的转场，避免生硬跳变
                (
                    slideInHorizontally(
                        animationSpec = tween(420, easing = OrionMotion.overshoot)
                    ) { full -> direction * full / 6 } +
                        fadeIn(animationSpec = tween(240))
                    ) togetherWith (
                    slideOutHorizontally(
                        animationSpec = tween(300, easing = OrionMotion.gentleOut)
                    ) { full -> -direction * full / 6 } +
                        fadeOut(animationSpec = tween(180))
                    )
            },
            label = "orion-screen"
        ) { target ->
            when (target) {
                OrionScreen.ONBOARDING -> OnboardingScreen(
                    backdrop = backdrop,
                    onRequestCapture = requestCapture,
                    onRequestNotification = requestNotification,
                    onFinish = {
                        app.settings.onboardingCompleted = true
                        screen = OrionScreen.HOME
                    }
                )

                OrionScreen.HOME -> HomeScreen(
                    backdrop = backdrop,
                    onOpenPermissions = { screen = OrionScreen.PERMISSIONS },
                    onOpenSettings = { screen = OrionScreen.SETTINGS },
                    onOpenHistory = { screen = OrionScreen.HISTORY },
                    onRequestCapture = requestCapture
                )

                OrionScreen.PERMISSIONS -> PermissionScreen(
                    backdrop = backdrop,
                    onBack = { screen = OrionScreen.HOME },
                    onRequestCapture = requestCapture,
                    onRequestNotification = requestNotification,
                    onOpenSettings = { screen = OrionScreen.SETTINGS }
                )

                OrionScreen.SETTINGS -> SettingsScreen(
                    backdrop = backdrop,
                    onBack = { screen = OrionScreen.HOME },
                    onOpenLearningData = { screen = OrionScreen.LEARNING_DATA }
                )

                OrionScreen.HISTORY -> HistoryScreen(
                    backdrop = backdrop,
                    onBack = { screen = OrionScreen.HOME }
                )

                OrionScreen.LEARNING_DATA -> LearningDataScreen(
                    backdrop = backdrop,
                    onBack = { screen = OrionScreen.SETTINGS }
                )
            }
        }
    }
}