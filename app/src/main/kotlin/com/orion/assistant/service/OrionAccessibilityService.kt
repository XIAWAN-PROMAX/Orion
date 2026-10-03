package com.orion.assistant.service

import android.accessibilityservice.AccessibilityService
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
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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

    /** 点击（归一化坐标 0~1000 由调用方换算为像素） */
    fun tap(x: Float, y: Float): Boolean = stroke(
        start = x to y,
        end = (x + 1f) to (y + 1f),
        durationMs = 60L
    )

    fun longPress(x: Float, y: Float, durationMs: Long = 700L): Boolean = stroke(
        start = x to y,
        end = (x + 1f) to (y + 1f),
        durationMs = durationMs.coerceIn(300L, 3000L)
    )

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean = stroke(
        start = x1 to y1,
        end = x2 to y2,
        durationMs = durationMs.coerceIn(80L, 3000L)
    )

    /** 页面滚动：direction 为 up/down/left/right，理解为「内容往哪个方向滚」。 */
    fun scroll(direction: String): Boolean {
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

    private fun stroke(start: Pair<Float, Float>, end: Pair<Float, Float>, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(start.first, start.second)
            lineTo(end.first, end.second)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
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
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(out::add)
        }
        for (root in roots) {
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

        val pkg = resolvePackage(q) ?: return "没找到叫「$q」的应用"
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
            val score = when {
                label.equals(query, ignoreCase = true) -> 100
                pkg.equals(query, ignoreCase = true) -> 95
                label.contains(query, ignoreCase = true) -> 80
                pkg.contains(query, ignoreCase = true) -> 60
                query.contains(label, ignoreCase = true) && label.length >= 2 -> 50
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
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text.length in 1..80) out.add(text)
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