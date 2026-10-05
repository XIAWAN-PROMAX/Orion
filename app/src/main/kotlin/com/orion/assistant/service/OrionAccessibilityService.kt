package com.orion.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.orion.assistant.engine.AgentAction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Orion 的「手」：无障碍服务。
 *
 * 负责把 AI 的决策翻译成真实的手指动作：
 *  - dispatchGesture → 点击 / 长按 / 滑动
 *  - ACTION_SET_TEXT → 在已聚焦输入框里打字
 *  - performGlobalAction → 返回 / Home / 多任务
 *  - startActivity(launchIntent) → 打开目标 App
 *  - takeScreenshot → 按需取当前画面（MediaProjection 帧过期时兜底）
 */
class OrionAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 屏幕尺寸

    /** 返回 [宽, 高]（像素）。模型输出的 0~1000 归一化坐标会按这个尺寸换算。 */
    fun screenSize(): IntArray {
        return try {
            val wm = getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            intArrayOf(bounds.width(), bounds.height())
        } catch (t: Throwable) {
            intArrayOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
    }

    fun currentPackageName(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    // ---------------------------------------------------------------- 按需截屏

    /**
     * 取一张「此刻」的屏幕画面。
     *
     * MediaProjection 只在画面变化时才吐新帧，遇到静态页面（填好的表单、读文章的页面）
     * 会一直停在旧帧上，导致模型看到的不是当前画面。无障碍服务的 takeScreenshot 可以
     * 随时取到当前画面，所以拿它作为「画面过期」时的兜底手段。
     *
     * 需要 accessibility_service_config 里声明 canTakeScreenshot="true"。
     */
    suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { cont ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val software = runCatching {
                    Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull()
                runCatching { result.hardwareBuffer.close() }
                if (cont.isActive) cont.resume(software)
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
        }
        val dispatched = runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }.isSuccess
        if (!dispatched && cont.isActive) cont.resume(null)
    }

    // ---------------------------------------------------------------- 手势

    /** 手势派发超时兜底：个别机型回调不来，避免协程永久挂起 */
    private val gestureHandler = Handler(Looper.getMainLooper())

    /**
     * 派发一个手势，并等它真正结束再返回。
     *
     * dispatchGesture 是异步的：以前只看它的 Boolean 返回值，结果上一段手势（长按 / 拖动）
     * 还没结束，下一个动作就派发出去了，系统直接丢弃 → 表现为「有时候点了没反应」。
     * 现在接上 GestureResultCallback，等 onCompleted / onCancelled 后才返回。
     */
    private suspend fun awaitGesture(gesture: GestureDescription, timeoutMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val timer = Runnable { if (cont.isActive) cont.resume(false) }
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    gestureHandler.removeCallbacks(timer)
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    gestureHandler.removeCallbacks(timer)
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = runCatching {
                dispatchGesture(gesture, callback, null)
            }.getOrDefault(false)
            if (!dispatched) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            gestureHandler.postDelayed(timer, timeoutMs)
            cont.invokeOnCancellation { gestureHandler.removeCallbacks(timer) }
        }

    /** 点击（归一化坐标 0~1000 由调用方换算为像素） */
    suspend fun tap(x: Float, y: Float): Boolean =
        stroke(x to y, (x + 1f) to (y + 1f), 60L)

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 700L): Boolean =
        stroke(x to y, (x + 1f) to (y + 1f), durationMs.coerceIn(300L, 15000L))

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean =
        stroke(x1 to y1, x2 to y2, durationMs.coerceIn(80L, 15000L))

    /**
     * 按住拖动：先从起点快速划到终点，然后**手指停在终点不抬起**，持续 holdMs。
     *
     * 这是操作虚拟摇杆 / 持续移动的关键——普通 swipe 划完立刻抬手，摇杆只会「点一下」；
     * 必须让手指停在偏移点上，游戏才会认为你一直在推杆，人物才会持续移动。
     *
     * 续接笔画（continueStroke）必须作为**另一个** GestureDescription 单独派发；
     * 把原笔画和它的续接笔画塞进同一个手势是非法用法，会直接失败。
     */
    suspend fun dragHold(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        moveMs: Long = 120L,
        holdMs: Long = 1500L
    ): Boolean {
        val move = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x1, y1); lineTo(x2, y2) },
            0L,
            moveMs.coerceIn(30L, 1000L),
            true
        )
        // 续接笔画：停在终点原地不动，实现「按住不放」
        val hold = move.continueStroke(
            Path().apply { moveTo(x2, y2); lineTo(x2 + 1f, y2 + 1f) },
            move.duration,
            holdMs.coerceIn(100L, 15000L),
            false
        )
        // 第一段：快速移动到终点，声明「还会继续」
        val first = runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(move).build(), null, null)
        }.getOrDefault(false)
        if (!first) return false

        // 第二段：续接笔画必须单独作为下一个 gesture 派发
        val done = awaitGesture(
            GestureDescription.Builder().addStroke(hold).build(),
            move.duration + hold.duration + 1500L
        )
        if (!done) {
            // 续接没成功时补一段极短的续接把手势通道收尾，避免它一直悬着、毒化后续所有手势
            val close = move.continueStroke(
                Path().apply { moveTo(x2, y2); lineTo(x2, y2) },
                move.duration,
                1L,
                false
            )
            runCatching {
                dispatchGesture(GestureDescription.Builder().addStroke(close).build(), null, null)
            }
        }
        return done
    }

    /** 页面滚动：direction 为 up/down/left/right，理解为「内容往哪个方向滚」。 */
    suspend fun scroll(direction: String): Boolean {
        val (w, h) = screenSize().let { it[0].toFloat() to it[1].toFloat() }
        val cx = w / 2f
        val cy = h / 2f
        val dx = w * 0.30f
        val dy = h * 0.28f
        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy + dy, cx, cy - dy, 320L)
            "down" -> swipe(cx, cy - dy, cx, cy + dy, 320L)
            "left" -> swipe(cx + dx, cy, cx - dx, cy, 300L)
            "right" -> swipe(cx - dx, cy, cx + dx, cy, 300L)
            else -> swipe(cx, cy + dy, cx, cy - dy, 320L)
        }
    }

    private suspend fun stroke(start: Pair<Float, Float>, end: Pair<Float, Float>, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(start.first, start.second)
            lineTo(end.first, end.second)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return awaitGesture(gesture, durationMs + 1500L)
    }

    // ---------------------------------------------------------------- 系统按键

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ---------------------------------------------------------------- 输入文字

    /**
     * 在屏幕上已聚焦（或可编辑）的输入框里输入文字。
     *
     * 三方 App 的输入框千奇百怪，「只试一次」很容易失败，所以这里按可靠性从高到低依次尝试：
     *  1. 逐个候选输入框（有焦点的优先，其次各窗口里的第一个可编辑框）：
     *     先刷新节点，必要时用 ACTION_FOCUS + ACTION_CLICK 把焦点抢过来，再用 ACTION_SET_TEXT 整段写入；
     *  2. 都写不进去时，退回「剪贴板 + ACTION_PASTE」——对自绘输入框 / WebView 特别管用。
     */
    fun inputText(text: String): Boolean {
        if (text.isEmpty()) return false

        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val candidates = inputCandidates()
        for (node in candidates) {
            // 节点可能已过期（上一层界面刷新过），先 refresh 再操作
            runCatching { node.refresh() }
            if (!runCatching { node.isEnabled }.getOrDefault(true)) continue
            // 只有确认是可编辑控件时才敢主动点它，避免误触到别的按钮
            if (runCatching { node.isEditable }.getOrDefault(false)) {
                focusForInput(node)
            }
            val ok = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
            }.getOrDefault(false)
            if (ok) return true
        }

        // 兜底：写剪贴板后粘贴（Android 10+ 后台写剪贴板可能被系统拒绝，失败就老实返回 false）
        val target = candidates.firstOrNull { runCatching { it.isEditable }.getOrDefault(false) }
            ?: return false
        return runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("orion", text))
            focusForInput(target)
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    /** 把输入焦点抢到目标框上：很多 App 只认「已聚焦 + 已挂上输入法」的输入框。 */
    private fun focusForInput(node: AccessibilityNodeInfo) {
        if (runCatching { node.isFocused }.getOrDefault(false)) return
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    /**
     * 收集候选输入框：各窗口的「焦点输入框」优先，其次是各窗口里第一个可编辑节点。
     *
     * 输入框不一定在 rootInActiveWindow 里 —— 弹窗、底部输入条、悬浮窗往往自成窗口，
     * 所以要把所有窗口都扫一遍，只查 rootInActiveWindow 是「在三方 App 里打不进字」的主因之一。
     */
    private fun inputCandidates(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let(roots::add)
        for (window in windowsOrEmpty()) window.root?.let(roots::add)

        val out = ArrayList<AccessibilityNodeInfo>()
        for (root in roots) {
            // 别把 Orion 自己的输入框（首页那句指令的输入框）当成目标框
            if (root.packageName?.toString() == packageName) continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(out::add)
        }
        for (root in roots) {
            if (root.packageName?.toString() == packageName) continue
            findFirstEditable(root)?.let(out::add)
        }
        return out
    }

    private fun windowsOrEmpty(): List<AccessibilityWindowInfo> =
        runCatching { windows }.getOrDefault(emptyList())

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            // 别把 Orion 自己的输入框（首页那句指令的输入框）当成目标框
            if (node.packageName?.toString() == packageName) continue
            if (node.isEditable && node.isEnabled) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 启动应用

    /**
     * 通过「应用名 / 包名 / 别名」找到并打开目标 App。
     * 返回结果描述，写进任务日志。
     */
    fun openApp(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "打开应用失败：应用名为空"

        val pkg = resolvePackage(q) ?: run {
            // 找不到时把名字相近的已安装应用列出来，让模型照着候选重试，
            // 而不是自己随便换个相似的 App 打开（曾出现「小管家」被换成「手机管家」）。
            val similar = similarAppLabels(q)
            return if (similar.isEmpty()) {
                "没找到叫「$q」的应用"
            } else {
                "没找到叫「$q」的应用。已安装名称相近的有：${similar.joinToString("、")}；" +
                    "如果要打开的是其中之一，请用它的准确名称重新 open_app，不要换成别的应用。"
            }
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return "「$q」没有可启动的入口（$pkg）"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            startActivity(intent)
            "已打开「$q」（$pkg）"
        }.getOrElse { "打开「$q」失败：${it.message}" }
    }

    private fun resolvePackage(query: String): String? {
        // 1) 直接就是包名
        if (hasLaunchEntry(query)) return query
        // 2) 命中内置别名 / 用户自定义别名
        aliasMap()[query.trim()]?.let { if (hasLaunchEntry(it)) return it }

        // 3) 在所有已安装应用里按「名称包含」打分
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrDefault(emptyList())

        var bestPkg: String? = null
        var bestScore = 0
        for (info in activities) {
            val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault("")
            val pkg = info.activityInfo?.packageName ?: continue
            // 名称包含式的匹配要足够贴合，否则「管家」这种两字词会匹到「手机管家」，
            // 把用户要的小程序 / 别的应用张冠李戴地打开。要求：查询词够长，或候选名不比它长太多。
            val looseOk = query.length >= 3 || label.length <= query.length + 1
            val score = when {
                label.equals(query, ignoreCase = true) -> 100
                pkg.equals(query, ignoreCase = true) -> 95
                label.startsWith(query, ignoreCase = true) && query.length >= 2 -> 85
                label.contains(query, ignoreCase = true) && query.length >= 2 && looseOk -> 70
                pkg.contains(query, ignoreCase = true) && query.length >= 3 -> 55
                query.contains(label, ignoreCase = true) && label.length >= 3 -> 45
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestPkg = pkg
            }
        }
        return bestPkg
    }

    private fun hasLaunchEntry(pkg: String): Boolean =
        runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

    /** 名字相近的已安装应用（按与查询词的重合字数排序），用于 open_app 失败时给模型纠错 */
    private fun similarAppLabels(query: String, limit: Int = 5): List<String> {
        val chars = query.trim().toSet()
        if (chars.isEmpty()) return emptyList()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrDefault(emptyList())
        return activities
            .map { runCatching { it.loadLabel(packageManager).toString() }.getOrDefault("") }
            .filter { it.isNotBlank() && it != query.trim() }
            .map { label -> label to label.count { it in chars } }
            .filter { (_, hits) -> hits >= 2 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
    }

    /** 通过 alpha / bounds 拿到界面上的文字（辅助模型理解，可选能力） */
    fun screenTextSnippet(limit: Int = 40): String {
        val root = rootInActiveWindow ?: return ""
        val out = ArrayList<String>(limit)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500 && out.size < limit) {
            val node = queue.removeFirst()
            visited++
            // 跳过 Orion 自己的界面节点：那上面写着用户那句指令，喂给模型只会让它去点自己的命令
            if (node.packageName?.toString() == packageName) continue
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text.length in 1..80) out.add(text)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return out.joinToString(" | ")
    }

    /**
     * 读界面文字，并带上每个节点的中心坐标（0~1000 归一化）。
     *
     * 只给文字不给坐标时，模型只能纯靠截图猜位置，做题这种「要精确点到某个选项」的场景就会
     * 犹豫不敢点（表现为一直 wait）。带上坐标后它可以直接照着点。
     * 格式：「文字」@(x,y)，x/y 可以直接填进 tap。
     */
    fun screenTextWithBounds(limit: Int = 60): String {
        val root = rootInActiveWindow ?: return ""
        val size = screenSize()
        val width = size[0].toFloat()
        val height = size[1].toFloat()
        if (width <= 0f || height <= 0f) return ""

        val out = ArrayList<String>(limit)
        val seen = HashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        // 访问上限压到 400：列表页节点多，遍历越深越慢；40~60 个可点文字已经够模型定位。
        while (queue.isNotEmpty() && visited < 400 && out.size < limit) {
            val node = queue.removeFirst()
            visited++
            // 跳过 Orion 自己的界面节点：那上面写着用户那句指令，喂给模型只会让它去点自己的命令
            if (node.packageName?.toString() == packageName) continue
            val raw = (node.text ?: node.contentDescription)?.toString()
                ?.replace('\n', ' ')?.trim().orEmpty()
            // 跳过整段正文：长文本截前 24 字会被当成「可点标签」，反而误导模型。
            if (raw.isNotEmpty() && raw.length <= 40) {
                val label = raw.replace('|', '/').take(24)
                val rect = Rect().also { node.getBoundsInScreen(it) }
                if (rect.width() > 0 && rect.height() > 0 && rect.right > 0 && rect.bottom > 0) {
                    val cx = ((rect.centerX() / width) * AgentAction.COORD_MAX).toInt().coerceIn(0, AgentAction.COORD_MAX)
                    val cy = ((rect.centerY() / height) * AgentAction.COORD_MAX).toInt().coerceIn(0, AgentAction.COORD_MAX)
                    // 父子节点常带同一段文字，按「文字+坐标」去重，避免重复噪声
                    if (seen.add("$label@$cx,$cy")) out.add("「$label」@($cx,$cy)")
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return out.joinToString(" | ")
    }

    /** 目标控件中心点（若模型希望按文字点击，可作为坐标兜底）。 */
    fun findNodeCenterByText(text: String): Rect? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val node = queue.removeFirst()
            visited++
            if (node.packageName?.toString() == packageName) continue
            val label = (node.text ?: node.contentDescription)?.toString().orEmpty()
            if (label.contains(text, ignoreCase = true)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.width() > 0 && bounds.height() > 0) return bounds
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: OrionAccessibilityService? = null

        /** 外部（操作引擎）拿到的当前服务实例，未连接时为 null */
        val current: OrionAccessibilityService? get() = instance

        fun isConnected(): Boolean = instance != null

        /**
         * 常见应用别名表：中文名/俗称 → 包名。
         * TODO(接入点)：这里可以扩展为「云端下发的别名表」，或让用户在设置里自定义常用 App。
         */
        private fun aliasMap(): Map<String, String> = mapOf(
            "抖音" to "com.ss.android.ugc.aweme",
            "抖音极速版" to "com.ss.android.ugc.aweme.lite",
            "快手" to "com.smile.gifmaker",
            "小红书" to "com.xingin.xhs",
            "微信" to "com.tencent.mm",
            "QQ" to "com.tencent.mobileqq",
            "支付宝" to "com.eg.android.AlipayGphone",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "美团" to "com.sankuai.meituan",
            "美团外卖" to "com.sankuai.meituan.takeoutnew",
            "饿了么" to "me.ele",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "哔哩哔哩" to "tv.danmaku.bili",
            "B站" to "tv.danmaku.bili",
            "微博" to "com.sina.weibo",
            "知乎" to "com.zhihu.android",
            "网易云音乐" to "com.netease.cloudmusic",
            "QQ音乐" to "com.tencent.qqmusic",
            "腾讯视频" to "com.tencent.qqlive",
            "爱奇艺" to "com.qiyi.video",
            "优酷" to "com.youku.phone",
            "钉钉" to "com.alibaba.android.rimet",
            "飞书" to "com.ss.android.lark",
            "企业微信" to "com.tencent.wework",
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "相册" to "com.android.gallery3d",
            "日历" to "com.android.calendar",
            "计算器" to "com.android.calculator2",
            "时钟" to "com.android.deskclock",
            "浏览器" to "com.android.browser"
        )
    }
}