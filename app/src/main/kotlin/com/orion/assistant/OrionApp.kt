package com.orion.assistant

import android.app.Application
import android.content.Context
import com.orion.assistant.data.LearningRepository
import com.orion.assistant.data.SettingsRepository
import com.orion.assistant.data.TaskRepository
import com.orion.assistant.engine.TaskOrchestrator
import com.orion.assistant.notify.LiveUpdateNotifier

/**
 * 应用入口。在这里把「设置仓库 / 任务仓库 / 操作引擎」三件套装配起来，
 * 并提前建好实况通知渠道（这样即使 App 界面没打开，任务也能推实况通知）。
 *
 * TODO(接入点)：如果以后要接入「云端下发配置」「账号体系」，在 onCreate 里加一次初始化即可。
 */
class OrionApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val tasks: TaskRepository by lazy { TaskRepository(this) }
    val learning: LearningRepository by lazy { LearningRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 操作引擎需要长期持有仓库，退出界面也不影响正在跑的任务
        TaskOrchestrator.init(this, settings, tasks, learning)
        LiveUpdateNotifier.ensureChannel(this)
    }

    companion object {
        @Volatile
        lateinit var instance: OrionApp
            private set
    }
}

/** 取到应用级单例的便捷写法 */
val Context.orionApp: OrionApp
    get() = applicationContext as OrionApp