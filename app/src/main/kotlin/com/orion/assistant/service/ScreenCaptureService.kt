package com.orion.assistant.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.orion.assistant.notify.LiveUpdateNotifier

/**
 * Orion 的「眼」：持有 MediaProjection 的前台服务。
 *
 * Android 14+ 要求 MediaProjection 必须运行在 foregroundServiceType=mediaProjection 的前台服务里，
 * 并且要在 createVirtualDisplay 之前调用 startForeground()。这里的通知同时就是「实况通知」，
 * 所以任务进度会直接出现在灵动岛 / 锁屏实况里。
 */
class ScreenCaptureService : Service() {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /** 最新一帧画面（复用，避免频繁 GC） */
    @Volatile
    private var latestFrame: Bitmap? = null

    @Volatile
    private var frameTimestamp: Long = 0L

    /**
     * 当前帧是否已经交给了外部使用（引擎正在编码/推理）。
     *
     * ImageReader 每来一帧都会替换 latestFrame。如果无脑回收上一帧，就会把引擎
     * 手上那张正在 compress() 的 Bitmap 回收掉，直接抛
     * "Can't call compress() on a recycled bitmap" —— 这就是「截图失败」最常见的原因。
     * 所以这里记一个「是否已外借」的标记，外借中的帧只交给 GC，不主动 recycle。
     */
    @Volatile
    private var frameInUse: Boolean = false

    /** 启动投屏过程中遇到的真实错误，便于 UI 提示具体原因而不是笼统的「截图失败」 */
    @Volatile
    private var lastError: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val thread = HandlerThread("orion-screen-capture").also { it.start() }
        handlerThread = thread
        handler = Handler(thread.looper)
        LiveUpdateNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                startForegroundWithType()
                if (data != null && resultCode == Activity.RESULT_OK) {
                    startProjection(resultCode, data)
                } else {
                    // 没有拿到授权，直接收起服务
                    shutdown()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithType() {
        val notification = LiveUpdateNotifier.build(
            this,
            LiveUpdateNotifier.Snapshot(
                title = "Orion 已就绪",
                text = "等待你的指令",
                criticalText = "待命中",
                indeterminate = true
            )
        )
        runCatching {
            startForeground(
                LiveUpdateNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }.onFailure {
            lastError = "无法启动截屏前台服务：${it.message}"
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        lastError = null

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            lastError = "系统没有提供 MediaProjectionManager"
            return
        }

        val mediaProjection = runCatching { manager.getMediaProjection(resultCode, data) }
            .onFailure { lastError = "创建投屏会话失败：${it.message ?: it.javaClass.simpleName}" }
            .getOrNull() ?: return
        projection = mediaProjection

        val metrics = currentDisplayMetrics()
        val width = metrics.first
        val height = metrics.second
        val density = resources.displayMetrics.densityDpi

        val reader = runCatching { ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2) }
            .onFailure { lastError = "创建画面缓冲区失败：${it.message}" }
            .getOrNull()
        if (reader == null) {
            releaseProjection()
            return
        }
        imageReader = reader

        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)

        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // Android 14+ 要求投屏结束后立刻收掉对应类型的前台服务，否则会被系统判为违规
                    lastError = "投屏已被系统或用户结束，请重新授权截屏"
                    releaseProjection()
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    runCatching { stopSelf() }
                }
            },
            handler
        )

        virtualDisplay = runCatching {
            mediaProjection.createVirtualDisplay(
                "orion-display",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        }.onFailure { lastError = "创建虚拟屏幕失败：${it.message}" }.getOrNull()

        if (virtualDisplay == null) {
            releaseProjection()
        }
    }

    /**
     * 收到一帧新画面。
     * 关键点：只有在「上一帧没有被外部借走」时才回收它，避免把引擎正在读的 Bitmap 回收掉。
     */
    private fun onFrameAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val bitmap = runCatching { imageToBitmap(image) }.getOrNull() ?: return
            val previous = latestFrame
            val previousInUse = frameInUse
            latestFrame = bitmap
            frameTimestamp = System.currentTimeMillis()
            frameInUse = false
            if (previous != null && previous !== bitmap && !previousInUse && !previous.isRecycled) {
                previous.recycle()
            }
        } finally {
            runCatching { image.close() }
        }
    }

    private fun currentDisplayMetrics(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(WindowManager::class.java)
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } catch (t: Throwable) {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }
    }

    /**
     * 把 ImageReader 拿到的一帧 RGBA_8888 转成 Bitmap。
     * 自己实现而不用 androidx 的扩展，是为了显式处理 rowStride 的像素对齐 padding，
     * 否则某些机型截图右侧会出现错位的斜纹。
     */
    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        if (image.format != PixelFormat.RGBA_8888) return null
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    /** 取最新一帧。返回的是内部复用的 Bitmap，调用方不要 recycle 它。 */
    fun latestScreenshot(): Bitmap? {
        val bmp = latestFrame
        if (bmp == null || bmp.isRecycled) return null
        // 标记为「已外借」：在新的帧到来前，不会被回收
        frameInUse = true
        return bmp
    }

    fun lastFrameAgeMs(): Long =
        if (frameTimestamp == 0L) Long.MAX_VALUE else System.currentTimeMillis() - frameTimestamp

    /** 更新实况通知（由任务引擎调用） */
    fun updateLiveUpdate(snapshot: LiveUpdateNotifier.Snapshot) {
        // 统一走 LiveUpdateNotifier.push：它会拦掉「用户已关掉的实况」，不重复打扰
        LiveUpdateNotifier.push(this, snapshot)
    }

    private fun releaseProjection() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        latestFrame = null
        frameTimestamp = 0L
        frameInUse = false
    }

    private fun shutdown() {
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseProjection()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.orion.assistant.action.START_CAPTURE"
        private const val ACTION_STOP = "com.orion.assistant.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA = "extra_result_data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        val current: ScreenCaptureService? get() = instance

        fun isRunning(): Boolean = instance?.projection != null

        /** 用户点了「允许截屏」之后调用，把授权结果交给前台服务 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        /** 取最新一帧屏幕画面 */
        fun screenshot(): Bitmap? = instance?.latestScreenshot()

        /** 最近一次投屏失败的真正原因；没有失败过则为 null */
        fun lastError(): String? = instance?.lastError

        /**
         * 取一张「当前」画面。
         *
         * MediaProjection 只在画面变化时吐新帧，静态页面（填好的表单、阅读中的文章）
         * 会一直停在旧帧上。所以：
         *  1. 投屏帧足够新（[FRESH_FRAME_MS] 内）→ 直接用；
         *  2. 帧过期 → 向无障碍服务要一张当下的截图；
         *  3. 都拿不到 → 退回旧帧（聊胜于无），调用方若拿到 null 再报错。
         */
        suspend fun captureNow(): Bitmap? {
            val age = instance?.lastFrameAgeMs() ?: Long.MAX_VALUE
            if (age <= FRESH_FRAME_MS) {
                instance?.latestScreenshot()?.let { return it }
            }
            OrionAccessibilityService.current?.captureScreen()?.let { return it }
            return instance?.latestScreenshot()
        }

        /** 判定「投屏帧算不算新鲜」的阈值 */
        private const val FRESH_FRAME_MS = 1200L

        /** 更新实况通知；服务没运行时静默忽略 */
        fun pushLiveUpdate(context: Context, snapshot: LiveUpdateNotifier.Snapshot) {
            instance?.updateLiveUpdate(snapshot)
        }
    }
}