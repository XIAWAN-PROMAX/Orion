package com.orion.assistant.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.orion.assistant.data.SettingsRepository
import com.orion.assistant.data.TaskRepository
import com.orion.assistant.notify.LiveUpdateNotifier
import com.orion.assistant.service.OrionAccessibilityService
import com.orion.assistant.service.ScreenCaptureService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class TaskStatus {
    IDLE, RUNNING, PAUSED, COMPLETED, FAILED, STOPPED;

    val isActive: Boolean get() = this == RUNNING || this == PAUSED
}

/** 时间线上的一条记录，供首页「任务状态区」展示 */
data class TimelineItem(
    val step: Int,
    val action: String,
    val result: String,
    val thought: String
)

/**
 * 操作引擎的主循环：截屏 → 理解 → 规划 → 执行 → 等待 → 再截屏。
 *
 * 它是整个 App 的中枢，UI 直接观察这里的 Compose 状态即可；
 * 进程活着的时候任务就能继续跑，所以退出 App 界面也不会中断。
 */
object TaskOrchestrator {

    private lateinit var appContext: Context
    private lateinit var settings: SettingsRepository
    private lateinit var repository: TaskRepository
    private lateinit var vision: VisionClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    @Volatile private var pauseRequested = false
    @Volatile private var stopRequested = false

    // ------------------------------------------------------------ 可观察状态

    var status by mutableStateOf(TaskStatus.IDLE)
        private set

    var instruction by mutableStateOf("")
        private set

    var currentThought by mutableStateOf("")
        private set

    var currentActionText by mutableStateOf("")
        private set

    var stepIndex by mutableIntStateOf(0)
        private set

    var maxSteps by mutableIntStateOf(25)
        private set

    var resultSummary by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf("")
        private set

    /** 每一步的执行记录，最新在前 */
    var timeline by mutableStateOf<List<TimelineItem>>(emptyList())
        private set

    /** 当前屏幕缩略图（已缩放到 360px 内，安全持有） */
    var previewFrame by mutableStateOf<Bitmap?>(null)
        private set

    private var currentTaskId: Long = -1L

    fun init(context: Context, settings: SettingsRepository, repository: TaskRepository) {
        appContext = context.applicationContext
        this.settings = settings
        this.repository = repository
        this.vision = VisionClient(settings)
    }

    // ------------------------------------------------------------ 外部控制

    /** 前置条件检查；返回 null 表示可以启动，否则返回需要提示用户的原因 */
    fun checkPrerequisites(): String? = when {
        OrionAccessibilityService.current == null -> "还没开启无障碍服务，Orion 没法操作屏幕"
        !ScreenCaptureService.isRunning() -> "还没授权截屏，Orion 看不到屏幕"
        settings.apiKey.isBlank() -> "还没填 API Key，Orion 想不明白该怎么做"
        else -> null
    }

    fun start(rawInstruction: String) {
        if (job?.isActive == true) return
        val problem = checkPrerequisites()
        if (problem != null) {
            status = TaskStatus.FAILED
            errorMessage = problem
            resultSummary = ""
            return
        }

        val text = rawInstruction.trim()
        if (text.isEmpty()) return

        instruction = text
        resultSummary = ""
        errorMessage = ""
        timeline = emptyList()
        stepIndex = 0
        currentThought = "正在看你的屏幕…"
        currentActionText = ""
        maxSteps = settings.maxSteps
        pauseRequested = false
        stopRequested = false
        status = TaskStatus.RUNNING
        settings.lastInstruction = text

        // 新任务开始：清掉「上一条实况被用户关掉」的记录，允许重新发布
        LiveUpdateNotifier.resetDismissal()

        pushLiveUpdate(
            title = "Orion 开始执行",
            text = text,
            criticalText = "准备中",
            progress = 0,
            indeterminate = true
        )

        job = scope.launch { runTask(text) }
    }

    fun pause() {
        if (status != TaskStatus.RUNNING) return
        pauseRequested = true
        status = TaskStatus.PAUSED
        pushLiveUpdate("Orion 已暂停", "点击通知回到 App 继续", "已暂停", stepPercent(), false)
    }

    fun resume() {
        if (status != TaskStatus.PAUSED) return
        pauseRequested = false
        status = TaskStatus.RUNNING
        pushLiveUpdate("Orion 继续执行", currentActionText.ifBlank { currentThought }, "第 ${stepIndex + 1} 步", stepPercent(), false)
    }

    fun stop() {
        if (!status.isActive) return
        stopRequested = true
        pauseRequested = false
        // 立刻给出可见反馈：否则界面还停在「正在执行」，会被当成按钮没反应而反复点
        currentThought = "正在停止…"
        currentActionText = ""

        val running = job
        if (running == null || !running.isActive) {
            conclude(TaskStatus.STOPPED, "已手动停止")
        } else {
            // 直接取消协程：正在等待的轮询 / 步间隔延时会被立刻打断，
            // 不用等当前这一步（模型调用最长可能要几十秒）跑完才停。
            running.cancel()
        }
    }

    /** 任务结束后清空状态区，回到初始界面 */
    fun dismissResult() {
        if (status.isActive) return
        status = TaskStatus.IDLE
        resultSummary = ""
        errorMessage = ""
        timeline = emptyList()
        stepIndex = 0
        currentThought = ""
        currentActionText = ""
        previewFrame?.takeIf { !it.isRecycled }?.recycle()
        previewFrame = null
        pushLiveUpdate("Orion 已就绪", "等待你的指令", "待命中", 0, true)
    }

    // ------------------------------------------------------------ 主循环

    private suspend fun runTask(text: String) {
        currentTaskId = withContext(Dispatchers.IO) {
            runCatching { repository.createTask(text) }.getOrDefault(-1L)
        }

        val history = mutableListOf<String>()
        // 模型在第一步列出的整体子目标：每一步都带回给它，防止刚进入某个页面就误判「完成」
        val overallPlan = mutableListOf<String>()
        var step = 0
        var lastSignature = ""
        var repeated = 0

        try {
            while (step < maxSteps) {
                if (stopRequested) {
                    conclude(TaskStatus.STOPPED, "已手动停止")
                    return
                }
                while (pauseRequested) {
                    delay(150)
                    if (stopRequested) {
                        conclude(TaskStatus.STOPPED, "已手动停止")
                        return
                    }
                }

                val screen = awaitScreenshot()
                if (screen == null) {
                    val reason = ScreenCaptureService.lastError()
                    conclude(
                        TaskStatus.FAILED,
                        if (reason.isNullOrBlank()) {
                            "拿不到屏幕画面，请重新授权截屏后再试"
                        } else {
                            "截屏失败：$reason"
                        }
                    )
                    return
                }
                updatePreview(screen)

                // 界面文字只截一小段：太长会拖慢模型，而且容易分散它的注意力
                val screenText = OrionAccessibilityService.current?.screenTextSnippet(25).orEmpty()
                val outcome = vision.plan(
                    instruction = text,
                    screenshot = screen,
                    stepIndex = step,
                    maxSteps = maxSteps,
                    history = history,
                    screenText = screenText,
                    overallPlan = overallPlan
                )

                when (outcome) {
                    is PlanOutcome.Failure -> {
                        conclude(TaskStatus.FAILED, outcome.message)
                        return
                    }

                    is PlanOutcome.Success -> {
                        val plan = outcome.plan
                        currentThought = plan.thought

                        // 第一步模型会给出整体子目标清单，把它记下来，之后每一步都带回给模型，
                        // 提醒它「还有哪些没做完」，避免刚进入某个页面就误判完成。
                        if (overallPlan.isEmpty() && plan.plan.isNotEmpty()) {
                            overallPlan += plan.plan
                        }

                        if (plan.isFinish) {
                            conclude(
                                TaskStatus.COMPLETED,
                                plan.summary.ifBlank { "任务已完成" }
                            )
                            return
                        }

                        currentActionText = plan.action.describe()
                        // 实况通知里写「Orion 自己输出的内容」：优先展示模型的 thought（它的实时解说），
                        // thought 为空时才退回动作描述。
                        pushLiveUpdate(
                            title = "Orion · 第 ${step + 1} 步",
                            text = plan.thought.ifBlank { plan.action.describe() },
                            criticalText = "第 ${step + 1} 步",
                            progress = stepPercent(step + 1),
                            indeterminate = false
                        )

                        val result = ActionExecutor.execute(plan.action)
                        timeline = listOf(
                            TimelineItem(
                                step = step + 1,
                                action = plan.action.describe(),
                                result = result,
                                thought = plan.thought
                            )
                        ) + timeline
                        history += "${plan.action.describe()} → $result"

                        withContext(Dispatchers.IO) {
                            runCatching {
                                if (currentTaskId > 0) {
                                    repository.addStep(
                                        taskId = currentTaskId,
                                        stepIndex = step + 1,
                                        thought = plan.thought,
                                        action = plan.action.describe(),
                                        result = result
                                    )
                                }
                            }
                        }
                        if (currentTaskId > 0) {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    repository.updateTask(
                                        taskId = currentTaskId,
                                        status = "RUNNING",
                                        summary = "",
                                        stepCount = step + 1
                                    )
                                }
                            }
                        }

                        step++
                        stepIndex = step

                        // 死循环检测：同一个动作连着做 3 次，就提醒模型换思路
                        val signature = plan.action.key + "|" + plan.action.describe()
                        repeated = if (signature == lastSignature) repeated + 1 else 0
                        lastSignature = signature
                        if (repeated >= 2) {
                            history += "注意：上一个动作已经重复了 ${repeated + 1} 次，请换一种做法"
                            repeated = 0
                        }
                    }
                }

                delay(randomStepDelay())
            }

            conclude(TaskStatus.STOPPED, "已经执行 $maxSteps 步还没完成，先停下来，你可以把任务拆得更小一些")
        } catch (cancel: CancellationException) {
            // 用户点了「停止」：协程被取消，这里要落到「已停止」，不能当成失败
            conclude(TaskStatus.STOPPED, "已手动停止")
        } catch (t: Throwable) {
            conclude(TaskStatus.FAILED, "执行过程中出错：${t.message}")
        }
    }

    private fun conclude(finalStatus: TaskStatus, summary: String) {
        status = finalStatus
        resultSummary = summary
        errorMessage = if (finalStatus == TaskStatus.FAILED) summary else ""
        currentActionText = ""
        currentThought = ""

        val taskId = currentTaskId
        if (taskId > 0) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    repository.updateTask(
                        taskId = taskId,
                        status = finalStatus.name,
                        summary = summary,
                        stepCount = stepIndex
                    )
                }
            }
        }
        currentTaskId = -1L

        when (finalStatus) {
            TaskStatus.COMPLETED -> pushLiveUpdate("Orion 完成了任务", summary, "已完成", 100, false)
            TaskStatus.STOPPED -> pushLiveUpdate("Orion 已停止", summary, "已停止", stepPercent(), true)
            else -> pushLiveUpdate("Orion 遇到问题", summary, "已中断", stepPercent(), false)
        }
    }

    // ------------------------------------------------------------ 工具函数

    /**
     * 取一张「当前」屏幕画面。
     *
     * 投屏刚授权的那一瞬间可能还没有第一帧，所以这里短暂轮询等待；
     * 之后每一步都通过 captureNow() 拿当下的画面（静态页面会自动走无障碍截图兜底）。
     */
    private suspend fun awaitScreenshot(timeoutMs: Long = 4000L): Bitmap? {
        val startAt = System.currentTimeMillis()
        while (true) {
            val shot = ScreenCaptureService.captureNow()
            if (shot != null && !shot.isRecycled) return shot
            if (System.currentTimeMillis() - startAt >= timeoutMs) return null
            delay(150)
        }
    }

    /** 生成一张 UI 用的缩略图（服务会回收原图，所以必须拷贝一份小的） */
    private fun updatePreview(source: Bitmap) {
        runCatching {
            val longest = maxOf(source.width, source.height)
            val scale = if (longest > PREVIEW_EDGE) PREVIEW_EDGE.toFloat() / longest else 1f
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            val thumb = Bitmap.createScaledBitmap(source, w, h, true)
            previewFrame?.takeIf { !it.isRecycled && it !== thumb }?.recycle()
            previewFrame = thumb
        }
    }

    /** 每步之间的随机间隔，避免机械节奏 */
    private fun randomStepDelay(): Long {
        val speed = settings.speed
        return Random.nextLong(speed.minDelayMs, speed.maxDelayMs + 1)
    }

    private fun stepPercent(step: Int = stepIndex): Int {
        if (maxSteps <= 0) return 0
        return (step * 100 / maxSteps).coerceIn(0, 100)
    }

    private fun pushLiveUpdate(
        title: String,
        text: String,
        criticalText: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        ScreenCaptureService.pushLiveUpdate(
            appContext,
            LiveUpdateNotifier.Snapshot(
                title = title,
                text = text,
                criticalText = criticalText,
                progress = progress,
                indeterminate = indeterminate
            )
        )
    }

    private const val PREVIEW_EDGE = 420
}