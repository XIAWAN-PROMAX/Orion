package com.orion.assistant.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.orion.assistant.data.LearningRepository
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
    private lateinit var learning: LearningRepository
    private lateinit var vision: VisionClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    @Volatile private var pauseRequested = false
    @Volatile private var stopRequested = false

    /**
     * 任务代数：每次 start 自增。
     *
     * 协程取消是异步的，被停掉的旧任务可能还会跑一小段才退出。用代数标识「这一轮」，
     * 旧协程回来调用 conclude 时会因代数不匹配被忽略，绝不覆盖新任务的状态。
     */
    private var runToken = 0
    private var concludedToken = -1

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

    /** 当前任务已做过的动作（供任务结束后复盘学习用） */
    private var runHistory: MutableList<String> = mutableListOf()

    fun init(
        context: Context,
        settings: SettingsRepository,
        repository: TaskRepository,
        learning: LearningRepository
    ) {
        appContext = context.applicationContext
        this.settings = settings
        this.repository = repository
        this.learning = learning
        this.vision = VisionClient(settings)
    }

    // ------------------------------------------------------------ 外部控制

    /** 前置条件检查；返回 null 表示可以启动，否则返回需要提示用户的原因 */
    fun checkPrerequisites(): String? = when {
        OrionAccessibilityService.current == null -> "还没开启无障碍服务，Orion 没法操作屏幕"
        !ScreenCaptureService.isRunning() && !ScreenCaptureService.hasConsent() ->
            "还没授权截屏，Orion 看不到屏幕"
        settings.apiKey.isBlank() -> "还没填 API Key，Orion 想不明白该怎么做"
        else -> null
    }

    fun start(rawInstruction: String) {
        // 正在跑的任务不允许重复启动
        if (status.isActive) return
        // 先换代数，再掐断旧协程：这样无论下面因何提前 return，被停掉的旧任务
        // 之后回来 conclude 都会因代数不匹配被忽略，不会覆盖本次的状态。
        val token = ++runToken
        concludedToken = -1
        // 状态已是终态、但协程可能还在收尾（例如用户刚点过「停止」）：
        // 先掐断旧协程，否则 job.isActive 会让「开始」被静默吞掉、看起来像按钮失灵。
        job?.cancel()

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
        runHistory = mutableListOf()
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

        job = scope.launch { runTask(token, text) }
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

        // 立刻落到「已停止」并刷新实况：模型调用最长要 90 秒才超时返回，
        // 若只发一个 cancel()，界面会一直停在「正在执行」，用户就会觉得按钮没反应。
        // conclude 内部带代数去重，协程随后抛出的 CancellationException 不会再收尾第二次。
        conclude(runToken, TaskStatus.STOPPED, "已手动停止")

        // 再掐断协程：正在等待的轮询 / 步间隔延时会被立刻打断。
        // 卡在模型网络请求里时由 VisionClient 的 runInterruptible 负责中断。
        job?.cancel()
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
        // 只丢掉引用，不做 recycle：Compose 可能仍在绘制上一张缩略图，
        // 主动 recycle 会偶发 "Canvas: trying to use a recycled bitmap" 崩溃，交给 GC 回收即可。
        previewFrame = null
        pushLiveUpdate("Orion 已就绪", "等待你的指令", "待命中", 0, true)
    }

    // ------------------------------------------------------------ 主循环

    private suspend fun runTask(token: Int, text: String) {
        currentTaskId = withContext(Dispatchers.IO) {
            runCatching { repository.createTask(text) }.getOrDefault(-1L)
        }

        val history = mutableListOf<String>()
        // 供任务结束后复盘学习用：持有同一个引用，读到的一定是本轮最新动作
        runHistory = history
        // 自学习攒下的经验：任务开始时读一次，注入到每一步的提示词里
        val lessons = if (settings.selfLearning) {
            withContext(Dispatchers.IO) {
                runCatching { learning.tips() }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
        // 模型在第一步列出的整体子目标：每一步都带回给它，防止刚进入某个页面就误判「完成」
        val overallPlan = mutableListOf<String>()
        var step = 0
        var lastSignature = ""
        var repeated = 0
        // 连续「不能改变屏幕」的动作次数（wait / 无法识别的类型 / 空输入）。
        // 这类动作执行了也等于没做，绝不能让它们当成进度一步步混过去。
        var noopStreak = 0

        // 任务是从 Orion 自己的界面里发起的：此刻画面还是 Orion 首页，上面写着用户那句指令。
        // 直接截屏分析的话，模型会把「指令文字」当成可点按钮，一直点自己的命令。
        // 先退回桌面，保证第一次看到的是真实的目标界面。
        if (OrionAccessibilityService.current?.currentPackageName() == appContext.packageName) {
            OrionAccessibilityService.current?.pressHome()
            delay(600)
        }

        try {
            while (step < maxSteps) {
                if (stopRequested) {
                    conclude(token, TaskStatus.STOPPED, "已手动停止")
                    return
                }
                while (pauseRequested) {
                    delay(150)
                    if (stopRequested) {
                        conclude(token, TaskStatus.STOPPED, "已手动停止")
                        return
                    }
                }

                val screen = awaitScreenshot()
                if (screen == null) {
                    val reason = ScreenCaptureService.lastError()
                    conclude(
                        token,
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

                // 界面文字带中心坐标：做题要点准某个选项，只给文字模型只能靠截图猜位置，
                // 带上坐标它可以直接照着点。
                //
                // 但遍历无障碍节点是逐个子节点 IPC，节点多时能跑几百毫秒甚至更久。
                // 之前它在主线程跑，每一步都卡住 UI，表现就是「暂停 / 停止按钮按不动」，
                // 所以这里必须挪到后台线程。
                val screenText = withContext(Dispatchers.Default) {
                    OrionAccessibilityService.current?.screenTextWithBounds(60).orEmpty()
                }
                val outcome = vision.plan(
                    instruction = text,
                    screenshot = screen,
                    stepIndex = step,
                    maxSteps = maxSteps,
                    history = history,
                    screenText = screenText,
                    overallPlan = overallPlan,
                    lessons = lessons
                )

                // 这一步实际执行的动作，用于自适应步间隔
                var stepAction: AgentAction = AgentAction.Wait

                when (outcome) {
                    is PlanOutcome.Failure -> {
                        conclude(token, TaskStatus.FAILED, outcome.message)
                        return
                    }

                    is PlanOutcome.Success -> {
                        val plan = outcome.plan
                        stepAction = plan.action
                        currentThought = plan.thought

                        // 第一步模型会给出整体子目标清单，把它记下来，之后每一步都带回给模型，
                        // 提醒它「还有哪些没做完」，避免刚进入某个页面就误判完成。
                        if (overallPlan.isEmpty() && plan.plan.isNotEmpty()) {
                            overallPlan += plan.plan
                        }

                        if (plan.isFinish) {
                            conclude(
                                token,
                                TaskStatus.COMPLETED,
                                plan.summary.ifBlank { "任务已完成" }
                            )
                            return
                        }

                        // 「只说不做」的根因：模型把答案写在 thought 里，action 却给了 wait /
                        // 列表外的类型 / 空的 input_text。这类动作执行了屏幕也不会变，之前却会被
                        // 当成一步混过去（wait 更是重问一次后就一路空转到结束）。
                        // 现在统一按「无效动作」处理：不计步数、带着强硬提醒重问，直到它真的动手；
                        // 连续多次仍不动手才停下并如实说明，绝不再假装在推进。
                        val noop = plan.action is AgentAction.Wait ||
                            plan.action is AgentAction.Unknown ||
                            (plan.action is AgentAction.InputText && plan.action.text.isBlank())

                        if (noop) {
                            noopStreak++
                            if (noopStreak <= 3) {
                                history += "你上一步给出的动作「${plan.action.describe()}」不会改变屏幕，" +
                                    "无效。你 thought 里已经想好的做法，必须直接变成 tap / input_text / " +
                                    "scroll 这类真实动作；禁止再用 wait，也不要输出列表以外的类型。"
                                currentThought = plan.thought.ifBlank { "正在把想法变成真实动作…" }
                                currentActionText = plan.action.describe()
                                delay(200)
                                continue
                            }
                            conclude(token, TaskStatus.FAILED, "模型连续给出无法执行的动作，先停下，换个说法再试")
                            return
                        }
                        noopStreak = 0

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
                            history += "注意：同一个动作「${plan.action.describe()}」已经连续做了 ${repeated + 1} 次，" +
                                "说明它没生效（画面毫无变化）。请换一种做法：换坐标、换动作类型，" +
                                "或先 scroll / back 看看当前到底在哪个页面。"
                            repeated = 0
                        }
                    }
                }

                delay(adaptiveStepDelay(screenText, stepAction))
            }

            conclude(token, TaskStatus.STOPPED, "已经执行 $maxSteps 步还没完成，先停下来，你可以把任务拆得更小一些")
        } catch (cancel: CancellationException) {
            // 用户点了「停止」：协程被取消，这里要落到「已停止」，不能当成失败
            conclude(token, TaskStatus.STOPPED, "已手动停止")
        } catch (t: Throwable) {
            conclude(token, TaskStatus.FAILED, "执行过程中出错：${t.message}")
        }
    }

    /**
     * 统一收尾。带任务代数做去重：
     *  - 代数不匹配 → 说明是「已被停止/替换掉的旧任务」回来收尾，直接忽略，绝不覆盖新任务状态；
     *  - 同一代数已收尾过 → 忽略，避免「停止」时手动收尾一次、协程取消又收尾一次，通知闪两下。
     */
    private fun conclude(token: Int, finalStatus: TaskStatus, summary: String) {
        if (token != runToken || concludedToken == token) return
        concludedToken = token
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

        learnFrom(finalStatus)
    }

    /**
     * 自学习：任务结束后，把这次的动作序列交给模型复盘，攒一条经验（好的 / 不足 / 改进要点），
     * 下次同类任务时注入提示词。整个过程在后台跑、失败静默，绝不影响任务收尾与界面。
     */
    private fun learnFrom(finalStatus: TaskStatus) {
        if (!settings.selfLearning) return
        val steps = runHistory.toList()
        if (steps.isEmpty()) return
        val text = instruction
        if (text.isBlank()) return

        val statusLabel = when (finalStatus) {
            TaskStatus.COMPLETED -> "完成"
            TaskStatus.STOPPED -> "中途停止"
            TaskStatus.FAILED -> "失败"
            else -> finalStatus.name
        }

        scope.launch(Dispatchers.IO) {
            runCatching {
                val experience = vision.summarizeExperience(text, statusLabel, steps) ?: return@runCatching
                learning.add(
                    instruction = text,
                    status = finalStatus.name,
                    good = experience.good,
                    bad = experience.bad,
                    tip = experience.tip
                )
            }
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

    /** 生成一张 UI 用的缩略图（source 是截屏服务的内部帧，必须拷一份独立的） */
    private suspend fun updatePreview(source: Bitmap) {
        // 缩放整张截图也要几十毫秒，放后台算，别占用主线程。
        val thumb = withContext(Dispatchers.Default) {
            runCatching {
                val longest = maxOf(source.width, source.height)
                if (longest <= PREVIEW_EDGE) {
                    // 尺寸本来就够小：createScaledBitmap 可能直接把原图返回，导致缩略图与
                    // 截屏服务的内部帧是同一个对象，稍后被服务回收就崩。这里强制拷贝一份。
                    source.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    val scale = PREVIEW_EDGE.toFloat() / longest
                    Bitmap.createScaledBitmap(
                        source,
                        (source.width * scale).toInt().coerceAtLeast(1),
                        (source.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                }
            }.getOrNull()
        } ?: return
        // 旧缩略图只丢引用不 recycle：Compose 可能仍在绘制，交给 GC。
        previewFrame = thumb
    }

    /**
     * 自适应步间隔：按「当前这张截图」的复杂程度决定这一步之后停多久。
     *
     * 画面越复杂（文字节点越多、总字数越大），说明信息量大、页面可能还在重排，
     * 就多停一点；画面简单（纯列表、空白页）就少停，整体节奏更快。
     * 停顿区间仍由「设置」里的速度偏好决定，所以选「轻快」会整体更快。
     */
    private fun adaptiveStepDelay(screenText: String, action: AgentAction): Long {
        val nodes = screenText.split('|').count { it.isNotBlank() }
        // 文字节点数、总字数 → 0.0~1.0 的复杂度
        val nodeScore = (nodes / 40f).coerceIn(0f, 1f)
        val charScore = (screenText.length / 1200f).coerceIn(0f, 1f)
        val complexity = nodeScore * 0.6f + charScore * 0.4f

        val speed = settings.speed
        val span = (speed.maxDelayMs - speed.minDelayMs).toFloat()
        var delayMs = speed.minDelayMs + span * complexity

        // 页面切换类动作需要一点余量让新页面稳定
        if (action is AgentAction.OpenApp || action is AgentAction.Back ||
            action is AgentAction.Home || action is AgentAction.Recents
        ) {
            delayMs += 180
        }

        // 抖动一点，避免机械节奏
        delayMs += Random.nextLong(-60, 61)
        return delayMs.toLong().coerceAtLeast(150L)
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