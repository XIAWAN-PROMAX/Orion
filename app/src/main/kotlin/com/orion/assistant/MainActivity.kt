package com.orion.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.orion.assistant.ui.OrionRefresh
import com.orion.assistant.ui.OrionRoot
import com.orion.assistant.ui.theme.OrionTheme

/**
 * 唯一的 Activity。首页 / 引导 / 授权 / 设置 都是 Compose 页面，靠状态切换（见 OrionRoot）。
 * 截屏授权、通知授权的系统弹窗由 OrionRoot 里的 rememberLauncherForActivityResult 发起。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式 + 边到边，让液态玻璃能一直延伸到状态栏
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OrionTheme {
                OrionRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「系统设置 / 截屏授权弹窗」回来时刷新一次权限状态
        OrionRefresh.tick++
    }
}