package com.example.screen_shot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.provider.MediaStore
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class OverlayService : Service() {
    companion object {
        private const val ACTION_SET_PROJECTION = "action_set_projection"
        private const val ACTION_SET_PERFORMANCE_MODE = "action_set_performance_mode"
        private const val ACTION_SET_BALL_COLOR = "action_set_ball_color"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_DATA = "extra_data"
        private const val EXTRA_HIGH_PERFORMANCE = "extra_high_performance"
        private const val EXTRA_BALL_COLOR = "extra_ball_color"
        @Volatile
        private var highPerformanceResizeEnabled: Boolean = true
        @Volatile
        private var floatingBallColor: Int = Color.parseColor("#2F6FFF")

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
            )
        }

        fun updateProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SET_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun setHighPerformanceMode(context: Context, enabled: Boolean) {
            highPerformanceResizeEnabled = enabled
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SET_PERFORMANCE_MODE
                putExtra(EXTRA_HIGH_PERFORMANCE, enabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun setBallColor(context: Context, color: Int) {
            floatingBallColor = color
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SET_BALL_COLOR
                putExtra(EXTRA_BALL_COLOR, color)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var captureView: CaptureOverlayView? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var historyView: View? = null
    private var historyParams: WindowManager.LayoutParams? = null
    private val ballIdleAlpha = 0.55f
    private val ballActiveAlpha = 1f
    private var historyListContainer: LinearLayout? = null
    private val stickers = mutableListOf<StickerView>()
    private var activeEditingSticker: StickerView? = null
    private var isStickerLockedMode = false
    private val historyFiles = mutableListOf<File>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isCapturing = AtomicBoolean(false)
    private var projectionResultCode: Int? = null
    private var projectionData: Intent? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureReader: ImageReader? = null
    private var captureDisplay: VirtualDisplay? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            MediaProjectionHolder.mediaProjection = null
            releaseCaptureSession()
            isCapturing.set(false)
            mainHandler.post {
                removeCaptureOverlay()
                showBallIfHidden()
                toast("屏幕录制会话已结束，请重新授权")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundInternal()
        loadHistory()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_PROJECTION) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (resultCode == Activity.RESULT_OK && data != null) {
                bindProjection(resultCode, data)
            }
        } else if (intent?.action == ACTION_SET_PERFORMANCE_MODE) {
            val enabled = intent.getBooleanExtra(
                EXTRA_HIGH_PERFORMANCE,
                highPerformanceResizeEnabled
            )
            highPerformanceResizeEnabled = enabled
            stickers.forEach { it.setResizePreviewOnly(enabled) }
        } else if (intent?.action == ACTION_SET_BALL_COLOR) {
            val color = intent.getIntExtra(EXTRA_BALL_COLOR, floatingBallColor)
            floatingBallColor = color
            (ballView as? FloatingBallView)?.setBallColor(color)
        } else {
            if (ballView == null) {
                showBall()
            } else {
                showBallIfHidden()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeAllViews()
        releaseCaptureSession()
        MediaProjectionHolder.mediaProjection?.unregisterCallback(projectionCallback)
        MediaProjectionHolder.mediaProjection?.stop()
        MediaProjectionHolder.mediaProjection = null
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val channelId = "screen_shot_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Shot",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("截图服务运行中")
            .setContentText("点击悬浮球开始截图")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun bindProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projectionResultCode = resultCode
            projectionData = Intent(data)
            MediaProjectionHolder.mediaProjection?.stop()
            MediaProjectionHolder.mediaProjection =
                projectionManager.getMediaProjection(resultCode, data)
            MediaProjectionHolder.mediaProjection?.registerCallback(
                projectionCallback,
                mainHandler
            )
            releaseCaptureSession()
        } catch (_: SecurityException) {
            toast("当前系统限制，无法开启屏幕录制")
        } catch (_: Exception) {
            toast("屏幕录制授权失败")
        }
    }

    private fun showBall() {
        if (ballView != null) return
        val size = dp(56)
        val view = FloatingBallView(this)
        view.setBallColor(floatingBallColor)
        val params = overlayParams(size, size, focusable = false)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(16)
        params.y = dp(180)
        try {
            windowManager.addView(view, params)
            ballView = view
            ballParams = params
            attachBallDrag(view, params)
            view.post {
                if (ballView === view) {
                    snapBallToEdge(view, params)
                }
            }
            bringControlOverlaysToFront()
        } catch (_: SecurityException) {
            toast("悬浮窗权限未开启")
        } catch (_: Exception) {
            toast("悬浮球显示失败")
        }
    }

    private fun hideBall() {
        ballView?.visibility = View.INVISIBLE
    }

    private fun showBallIfHidden() {
        ballView?.visibility = View.VISIBLE
        bringControlOverlaysToFront()
    }

    private fun attachBallDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = true
            private var didRestoreForDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        didRestoreForDrag = false
                        v.alpha = ballActiveAlpha
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        var dx = (event.rawX - initialTouchX).toInt()
                        var dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 6 || abs(dy) > 6) {
                            isClick = false
                            if (!didRestoreForDrag) {
                                restoreBallForInteraction(v, params)
                                initialX = params.x
                                initialY = params.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                didRestoreForDrag = true
                                dx = 0
                                dy = 0
                            }
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(v, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            restoreBallForInteraction(v, params)
                            toggleMenu()
                        } else {
                            snapBallToEdge(v, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (!isClick) {
                            snapBallToEdge(v, params)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun snapBallToEdge(view: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val ballWidth = if (view.width > 0) view.width else params.width
        val centerX = params.x + ballWidth / 2
        val dockLeft = centerX < screenWidth / 2
        params.x = if (dockLeft) {
            0
        } else {
            screenWidth - ballWidth
        }
        val maxY = max(0, screenHeight - view.height)
        params.y = params.y.coerceIn(0, maxY)
        windowManager.updateViewLayout(view, params)
        view.alpha = ballIdleAlpha
        view.translationX = if (dockLeft) -(ballWidth / 2f) else (ballWidth / 2f)
    }

    private fun restoreBallForInteraction(view: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val ballWidth = if (view.width > 0) view.width else params.width
        val fullVisibleMinX = 0
        val fullVisibleMaxX = max(0, screenWidth - ballWidth)
        params.x = params.x.coerceIn(fullVisibleMinX, fullVisibleMaxX)
        view.alpha = ballActiveAlpha
        view.translationX = 0f
        windowManager.updateViewLayout(view, params)
    }

    private fun toggleMenu() {
        if (menuView == null) {
            showMenu()
        } else {
            removeMenu()
        }
    }

    private fun showMenu() {
        if (menuView != null) return
        val circleSize = dp(44)
        val orbitRadius = dp(52)
        val containerSize = orbitRadius * 2 + circleSize
        val container = FrameLayout(this)
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val ball = ballParams
        val ballX = ball?.x ?: dp(16)
        val ballY = ball?.y ?: dp(180)
        val ballWidth = ballView?.width?.takeIf { it > 0 } ?: dp(56)
        val ballHeight = ballView?.height?.takeIf { it > 0 } ?: dp(56)
        val dockLeft = ballX <= screenWidth / 2
        val ballCenterX = ballX + ballWidth / 2f
        val ballCenterY = ballY + ballHeight / 2f
        val center = containerSize / 2f
        val baseAngle = if (dockLeft) 0.0 else Math.PI
        val angleOffsets = listOf(-1.32, -0.44, 0.44, 1.32)

        val capture = createCircleMenuButton("截", Color.parseColor("#2F6FFF"), circleSize) {
            runMenuAction {
                showCaptureOverlay()
            }
        }
        val history = createCircleMenuButton("史", Color.parseColor("#20B26B"), circleSize) {
            runMenuAction {
                showHistory()
            }
        }
        val modeLabel = if (isStickerLockedMode) "辑" else "锁"
        val modeColor = if (isStickerLockedMode) Color.parseColor("#F59E0B") else Color.parseColor("#374151")
        val mode = createCircleMenuButton(modeLabel, modeColor, circleSize) {
            runMenuAction {
                toggleStickerMode()
            }
        }
        val close = createCircleMenuButton("X", Color.parseColor("#6B7280"), circleSize) {
            runMenuAction {}
        }

        placeOrbitButton(
            container,
            capture,
            center,
            orbitRadius.toFloat(),
            baseAngle + angleOffsets[0],
            circleSize,
            containerSize
        )
        placeOrbitButton(
            container,
            history,
            center,
            orbitRadius.toFloat(),
            baseAngle + angleOffsets[1],
            circleSize,
            containerSize
        )
        placeOrbitButton(
            container,
            mode,
            center,
            orbitRadius.toFloat(),
            baseAngle + angleOffsets[2],
            circleSize,
            containerSize
        )
        placeOrbitButton(
            container,
            close,
            center,
            orbitRadius.toFloat(),
            baseAngle + angleOffsets[3],
            circleSize,
            containerSize
        )

        val params = overlayParams(
            containerSize,
            containerSize,
            focusable = false
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (ballCenterX - containerSize / 2f).toInt().coerceIn(0, max(0, screenWidth - containerSize))
        params.y = (ballCenterY - containerSize / 2f).toInt().coerceIn(0, max(0, screenHeight - containerSize))

        windowManager.addView(container, params)
        menuView = container
        menuParams = params
        animateMenuButtons(listOf(capture, history, mode, close), dockLeft)
    }

    private fun createCircleMenuButton(
        label: String,
        backgroundColor: Int,
        sizePx: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(backgroundColor)
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            elevation = dp(6).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun placeOrbitButton(
        parent: FrameLayout,
        button: View,
        center: Float,
        radius: Float,
        angle: Double,
        sizePx: Int,
        containerSize: Int
    ) {
        val rawX = center + radius * cos(angle).toFloat() - sizePx / 2f
        val rawY = center + radius * sin(angle).toFloat() - sizePx / 2f
        val x = rawX.coerceIn(0f, (containerSize - sizePx).toFloat())
        val y = rawY.coerceIn(0f, (containerSize - sizePx).toFloat())
        val lp = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            leftMargin = x.toInt()
            topMargin = y.toInt()
        }
        parent.addView(button, lp)
    }

    private fun animateMenuButtons(buttons: List<View>, dockLeft: Boolean) {
        val fromX = if (dockLeft) -dp(18).toFloat() else dp(18).toFloat()
        buttons.forEachIndexed { index, view ->
            view.alpha = 0f
            view.scaleX = 0.6f
            view.scaleY = 0.6f
            view.translationX = fromX
            val set = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, 0.6f, 1f),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.6f, 1f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_X, fromX, 0f)
                )
                duration = 220
                startDelay = (index * 45).toLong()
            }
            set.start()
        }
    }

    private fun removeMenu() {
        menuView?.let {
            windowManager.removeView(it)
        }
        menuView = null
        menuParams = null
    }

    private fun runMenuAction(action: () -> Unit) {
        removeMenu()
        snapCurrentBallToEdge()
        action()
        bringControlOverlaysToFront()
    }

    private fun snapCurrentBallToEdge() {
        val view = ballView ?: return
        val params = ballParams ?: return
        snapBallToEdge(view, params)
    }

    private fun toggleStickerMode() {
        isStickerLockedMode = !isStickerLockedMode
        if (isStickerLockedMode) {
            stickers.forEach { it.forceExitEditModeFromHost() }
            activeEditingSticker = null
        }
        val touchable = !isStickerLockedMode
        stickers.forEach { it.setTouchable(touchable) }
        toast(if (isStickerLockedMode) "已切换到锁定模式" else "已切换到编辑模式")
    }

    private fun showHistory() {
        if (historyView != null) return
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(225, 255, 255, 255))
                setStroke(dp(1), Color.argb(50, 47, 111, 255))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "历史截图"
            textSize = 16f
            setTextColor(Color.parseColor("#1F2937"))
        }
        val clearAll = TextView(this).apply {
            text = "清空"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#DC2626"))
            textSize = 12f
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(28, 220, 38, 38))
                setStroke(dp(1), Color.argb(80, 220, 38, 38))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                if (clearHistoryFiles()) {
                    toast("历史截图已清空")
                    refreshHistoryPanel()
                } else {
                    toast("暂无可清空的历史截图")
                }
            }
        }
        val close = TextView(this).apply {
            text = "×"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6B7280"))
            }
            setOnClickListener { removeHistory() }
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            clearAll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        )
        header.addView(close, LinearLayout.LayoutParams(dp(28), dp(28)))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyListContainer = list
        if (historyFiles.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无历史截图"
                setTextColor(Color.GRAY)
                setPadding(0, dp(8), 0, 0)
            }
            list.addView(empty)
        } else {
            for (file in historyFiles) {
                val thumb = decodeThumbnail(file)
                if (thumb != null) {
                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val image = ImageView(this).apply {
                        setImageBitmap(thumb)
                        adjustViewBounds = true
                        background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(Color.argb(245, 248, 250, 252))
                            setStroke(dp(1), Color.argb(35, 31, 41, 55))
                        }
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            val full = decodeFull(file)
                            if (full != null) {
                                addSticker(full, null)
                            }
                        }
                    }
                    val actions = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(8), 0, dp(10))
                    }
                    val saveButton = createHistoryActionButton("保存到相册") {
                        if (saveHistoryFileToAlbum(file)) {
                            toast("已保存到相册")
                        } else {
                            toast("保存失败")
                        }
                    }
                    val deleteButton = createHistoryActionButton("删除") {
                        if (deleteHistoryFile(file)) {
                            toast("已删除")
                            refreshHistoryPanel()
                        } else {
                            toast("删除失败")
                        }
                    }
                    val saveLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(8)
                    }
                    val deleteLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    actions.addView(saveButton, saveLp)
                    actions.addView(deleteButton, deleteLp)
                    item.addView(image)
                    item.addView(actions)
                    list.addView(item)
                }
            }
        }
        scroll.addView(list)

        wrapper.addView(header)
        wrapper.addView(scroll)

        val params = overlayParams(dp(240), dp(360), focusable = false)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(24)
        params.y = dp(120)
        windowManager.addView(wrapper, params)
        wrapper.alpha = 0f
        wrapper.scaleX = 0.92f
        wrapper.scaleY = 0.92f
        wrapper.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
        historyView = wrapper
        historyParams = params
        bringControlOverlaysToFront()
    }

    private fun removeHistory() {
        historyView?.let { windowManager.removeView(it) }
        historyView = null
        historyParams = null
        historyListContainer = null
    }

    private fun refreshHistoryPanel() {
        if (historyView != null) {
            removeHistory()
            showHistory()
        }
    }

    private fun createHistoryActionButton(
        text: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1F2937"))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(190, 255, 255, 255))
                setStroke(dp(1), Color.argb(45, 31, 41, 55))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun deleteHistoryFile(file: File): Boolean {
        val removed = historyFiles.remove(file)
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        return removed || deleted
    }

    private fun clearHistoryFiles(): Boolean {
        if (historyFiles.isEmpty()) return false
        val snapshot = historyFiles.toList()
        var changed = false
        snapshot.forEach { file ->
            val removed = historyFiles.remove(file)
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (removed || deleted) {
                changed = true
            }
        }
        return changed
    }

    private fun saveHistoryFileToAlbum(file: File): Boolean {
        return runCatching {
            val resolver = contentResolver
            val name = "screen_shot_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenShot")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val completeValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, completeValues, null, null)
                }
                true
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }.getOrDefault(false)
    }

    private fun showCaptureOverlay() {
        if (captureView != null) return
        val view = CaptureOverlayView(
            this,
            onComplete = { path, bounds ->
                removeCaptureOverlay()
                hideBall()
                mainHandler.postDelayed({
                    captureAndShow(path, bounds)
                }, 220)
            },
            onCancel = {
                removeCaptureOverlay()
                showBallIfHidden()
            }
        )
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            focusable = true
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(view, params)
        captureView = view
        captureParams = params
        bringControlOverlaysToFront()
    }

    private fun removeCaptureOverlay() {
        captureView?.let {
            windowManager.removeView(it)
        }
        captureView = null
        captureParams = null
    }

    private fun captureAndShow(path: Path, bounds: RectF) {
        if (isCapturing.getAndSet(true)) return
        val projection = ensureProjection()
        if (projection == null) {
            toast("请先授予屏幕录制权限")
            showBallIfHidden()
            isCapturing.set(false)
            return
        }
        captureScreen(projection) { bitmap ->
            try {
                if (bitmap == null) {
                    toast("截图失败")
                    return@captureScreen
                }
                val safeBounds = clampBounds(bounds, bitmap.width, bitmap.height)
                val clipped = clipBitmap(bitmap, path, safeBounds)
                bitmap.recycle()
                if (clipped == null) {
                    toast("截图区域过小")
                    return@captureScreen
                }
                saveHistory(clipped)
                addSticker(clipped, safeBounds)
            } catch (_: Exception) {
                toast("截图失败")
            } finally {
                showBallIfHidden()
                isCapturing.set(false)
            }
        }
    }

    private fun addSticker(bitmap: Bitmap, bounds: RectF?) {
        val extra = dp(14)
        val params = overlayParams(
            max(dp(80), bitmap.width + extra),
            max(dp(80), bitmap.height + extra),
            focusable = false
        )
        if (isStickerLockedMode) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        params.gravity = Gravity.TOP or Gravity.START
        if (bounds != null) {
            val halfExtra = extra / 2
            params.x = bounds.left.toInt() - halfExtra
            params.y = bounds.top.toInt() - halfExtra
        } else {
            params.x = dp(60)
            params.y = dp(120)
        }
        val sticker = StickerView(
            this,
            windowManager,
            params,
            resizePreviewOnly = highPerformanceResizeEnabled,
            onEnterEditMode = { target ->
                if (activeEditingSticker !== target) {
                    activeEditingSticker?.forceExitEditModeFromHost()
                }
                activeEditingSticker = target
                target.setTouchable(true)
            },
            onExitEditMode = { view ->
                if (activeEditingSticker === view) {
                    activeEditingSticker = null
                }
                view.setTouchable(!isStickerLockedMode)
            },
            onRemoved = { view ->
                if (activeEditingSticker === view) {
                    activeEditingSticker = null
                }
                stickers.remove(view)
            }
        )
        sticker.setImageBitmap(bitmap)
        windowManager.addView(sticker, params)
        stickers.add(sticker)
        bringControlOverlaysToFront()
    }

    private fun saveHistory(bitmap: Bitmap) {
        val file = File(cacheDir, "shot_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            historyFiles.add(0, file)
            if (historyFiles.size > 20) {
                val removed = historyFiles.removeAt(historyFiles.size - 1)
                removed.delete()
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun loadHistory() {
        val files = cacheDir.listFiles { file ->
            file.name.startsWith("shot_") && file.name.endsWith(".png")
        } ?: return
        historyFiles.clear()
        historyFiles.addAll(files.sortedByDescending { it.lastModified() })
    }

    private fun decodeThumbnail(file: File): Bitmap? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 4
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFull(file: File): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun captureScreen(
        projection: MediaProjection,
        callback: (Bitmap?) -> Unit
    ) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        if (!ensureCaptureSession(projection, width, height, density)) {
            callback(null)
            return
        }
        val reader = captureReader
        val handler = captureHandler
        if (reader == null || handler == null) {
            callback(null)
            return
        }
        handler.post {
            var bitmap: Bitmap? = null
            try {
                var image: Image? = null
                var attempts = 0
                while (image == null && attempts < 30) {
                    image = runCatching { reader.acquireLatestImage() }.getOrNull()
                    if (image == null) {
                        Thread.sleep(20)
                        attempts++
                    }
                }
                bitmap = image?.let {
                    runCatching { toBitmap(it, width, height) }.getOrNull()
                }
                image?.close()
            } catch (_: Exception) {
                bitmap = null
            }
            mainHandler.post { callback(bitmap) }
        }
    }

    private fun ensureProjection(): MediaProjection? {
        MediaProjectionHolder.mediaProjection?.let { return it }
        val code = projectionResultCode ?: return null
        val data = projectionData ?: return null
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            val projection = projectionManager.getMediaProjection(code, Intent(data))
            projection?.registerCallback(projectionCallback, mainHandler)
            MediaProjectionHolder.mediaProjection = projection
            projection
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureCaptureSession(
        projection: MediaProjection,
        width: Int,
        height: Int,
        density: Int
    ): Boolean {
        if (
            captureReader != null &&
            captureDisplay != null &&
            captureWidth == width &&
            captureHeight == height &&
            captureDensity == density
        ) {
            return true
        }
        releaseCaptureSession()
        val thread = HandlerThread("ScreenCapture")
        thread.start()
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = try {
            projection.createVirtualDisplay(
                "screen_shot_capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (_: Exception) {
            reader.close()
            thread.quitSafely()
            return false
        }
        if (display == null) {
            reader.close()
            thread.quitSafely()
            return false
        }
        captureThread = thread
        captureHandler = handler
        captureReader = reader
        captureDisplay = display
        captureWidth = width
        captureHeight = height
        captureDensity = density
        return true
    }

    private fun releaseCaptureSession() {
        captureDisplay?.release()
        captureReader?.close()
        captureThread?.quitSafely()
        captureDisplay = null
        captureReader = null
        captureHandler = null
        captureThread = null
        captureWidth = 0
        captureHeight = 0
        captureDensity = 0
    }

    private fun toBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun clipBitmap(source: Bitmap, path: Path, bounds: RectF): Bitmap? {
        val outWidth = bounds.width().toInt()
        val outHeight = bounds.height().toInt()
        if (outWidth <= 8 || outHeight <= 8) return null
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.translate(-bounds.left, -bounds.top)
        val closedPath = Path(path).apply {
            close()
            fillType = Path.FillType.WINDING
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        canvas.drawPath(closedPath, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        paint.xfermode = null
        return output
    }

    private fun clampBounds(bounds: RectF, width: Int, height: Int): RectF {
        val left = bounds.left.coerceAtLeast(0f)
        val top = bounds.top.coerceAtLeast(0f)
        val right = bounds.right.coerceAtMost(width.toFloat())
        val bottom = bounds.bottom.coerceAtMost(height.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun overlayParams(
        width: Int,
        height: Int,
        focusable: Boolean
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun removeAllViews() {
        ballView?.let { windowManager.removeView(it) }
        menuView?.let { windowManager.removeView(it) }
        captureView?.let { windowManager.removeView(it) }
        historyView?.let { windowManager.removeView(it) }
        stickers.forEach { runCatching { windowManager.removeView(it) } }
        stickers.clear()
        ballView = null
        menuView = null
        captureView = null
        historyView = null
        historyParams = null
        menuParams = null
        captureParams = null
        ballParams = null
    }

    private fun bringControlOverlaysToFront() {
        reattachViewOnTop(ballView, ballParams)
        reattachViewOnTop(menuView, menuParams)
        reattachViewOnTop(historyView, historyParams)
        reattachViewOnTop(captureView, captureParams)
    }

    private fun reattachViewOnTop(view: View?, params: WindowManager.LayoutParams?) {
        if (view == null || params == null || !view.isAttachedToWindow) return
        runCatching {
            windowManager.removeView(view)
            windowManager.addView(view, params)
        }
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private class FloatingBallView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F6FFF")
    }

    fun setBallColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
}

private class CaptureOverlayView(
    context: Context,
    private val onComplete: (Path, RectF) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {
    private enum class CaptureMode {
        FREE,
        RECT
    }

    private enum class TouchState {
        IDLE,
        DRAW_FREE,
        DRAW_RECT,
        DRAG_SELECTION
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val shadePaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
    }
    private val drawPath = Path()
    private val drawRect = RectF()
    private val drawPoints = mutableListOf<PointF>()
    private val modeButtonRect = RectF()
    private val cancelButtonRect = RectF()
    private val confirmButtonRect = RectF()
    private val dragBaseBounds = RectF()
    private val selectionPoints = mutableListOf<PointF>()
    private val dragBasePoints = mutableListOf<PointF>()

    private var captureMode = CaptureMode.FREE
    private var touchState = TouchState.IDLE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var selectionPath: Path? = null
    private var selectionBounds: RectF? = null
    private var selectionMode: CaptureMode? = null

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        selectionPath?.let { canvas.drawPath(it, selectionPaint) }
        if (touchState == TouchState.DRAW_FREE) {
            canvas.drawPath(drawPath, selectionPaint)
        } else if (touchState == TouchState.DRAW_RECT) {
            canvas.drawRect(drawRect, selectionPaint)
        }
        drawHints(canvas)
        drawButtons(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = dp(16f)
        val buttonWidth = dp(104f)
        val buttonHeight = dp(44f)
        val spacing = dp(10f)
        modeButtonRect.set(
            margin,
            h - margin - buttonHeight,
            margin + buttonWidth,
            h - margin
        )
        confirmButtonRect.set(
            w - margin - buttonWidth,
            h - margin - buttonHeight,
            w - margin,
            h - margin
        )
        cancelButtonRect.set(
            confirmButtonRect.left - spacing - buttonWidth,
            confirmButtonRect.top,
            confirmButtonRect.left - spacing,
            confirmButtonRect.bottom
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (modeButtonRect.contains(event.x, event.y)) {
                    toggleMode()
                    invalidate()
                    return true
                }
                if (cancelButtonRect.contains(event.x, event.y)) {
                    onCancel()
                    return true
                }
                if (hasSelection() && confirmButtonRect.contains(event.x, event.y)) {
                    val finalPath = Path(selectionPath)
                    val finalBounds = RectF(selectionBounds)
                    onComplete(finalPath, finalBounds)
                    return true
                }
                val bounds = selectionBounds
                if (bounds != null && bounds.contains(event.x, event.y)) {
                    touchState = TouchState.DRAG_SELECTION
                    touchStartX = event.x
                    touchStartY = event.y
                    dragBaseBounds.set(bounds)
                    dragBasePoints.clear()
                    selectionPoints.forEach { point ->
                        dragBasePoints.add(PointF(point.x, point.y))
                    }
                    return true
                }
                clearSelection()
                touchStartX = event.x
                touchStartY = event.y
                if (captureMode == CaptureMode.RECT) {
                    touchState = TouchState.DRAW_RECT
                    drawRect.set(event.x, event.y, event.x, event.y)
                } else {
                    touchState = TouchState.DRAW_FREE
                    drawPath.reset()
                    drawPath.moveTo(event.x, event.y)
                    drawPoints.clear()
                    drawPoints.add(PointF(event.x, event.y))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (touchState) {
                    TouchState.DRAW_FREE -> {
                        drawPath.lineTo(event.x, event.y)
                        drawPoints.add(PointF(event.x, event.y))
                    }
                    TouchState.DRAW_RECT -> {
                        drawRect.set(
                            min(touchStartX, event.x),
                            min(touchStartY, event.y),
                            max(touchStartX, event.x),
                            max(touchStartY, event.y)
                        )
                    }
                    TouchState.DRAG_SELECTION -> {
                        updateDraggedSelection(event.x, event.y)
                    }
                    TouchState.IDLE -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                when (touchState) {
                    TouchState.DRAW_FREE -> finalizeFreeSelection()
                    TouchState.DRAW_RECT -> finalizeRectSelection()
                    TouchState.DRAG_SELECTION -> Unit
                    TouchState.IDLE -> Unit
                }
                touchState = TouchState.IDLE
                drawPath.reset()
                drawPoints.clear()
                return true
            }
        }
        return false
    }

    private fun drawHints(canvas: Canvas) {
        val modeText = if (captureMode == CaptureMode.FREE) "模式: 自由绘制" else "模式: 矩形"
        val actionText = if (hasSelection()) {
            "拖动选区后点击右下角确认"
        } else {
            "拖动屏幕绘制截图区域"
        }
        canvas.drawText(modeText, dp(16f), dp(34f), hintPaint)
        canvas.drawText(actionText, dp(16f), dp(62f), hintPaint)
    }

    private fun drawButtons(canvas: Canvas) {
        val modeLabel = if (captureMode == CaptureMode.FREE) "切到矩形" else "切到自由"
        drawButton(canvas, modeButtonRect, modeLabel, Color.parseColor("#1F2937"), 235)
        drawButton(canvas, cancelButtonRect, "取消", Color.parseColor("#6B7280"), 225)
        val confirmEnabled = hasSelection()
        val confirmColor = if (confirmEnabled) Color.parseColor("#16A34A") else Color.parseColor("#6B7280")
        val confirmAlpha = if (confirmEnabled) 235 else 170
        drawButton(canvas, confirmButtonRect, "确认", confirmColor, confirmAlpha)
    }

    private fun drawButton(
        canvas: Canvas,
        rect: RectF,
        label: String,
        color: Int,
        alpha: Int
    ) {
        buttonPaint.color = color
        buttonPaint.alpha = alpha
        val radius = dp(12f)
        canvas.drawRoundRect(rect, radius, radius, buttonPaint)
        val centerY = rect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText(label, rect.centerX(), centerY, buttonTextPaint)
    }

    private fun toggleMode() {
        captureMode = if (captureMode == CaptureMode.FREE) {
            CaptureMode.RECT
        } else {
            CaptureMode.FREE
        }
        clearSelection()
        touchState = TouchState.IDLE
        drawPath.reset()
        drawPoints.clear()
    }

    private fun hasSelection(): Boolean {
        val bounds = selectionBounds
        return selectionPath != null &&
            bounds != null &&
            bounds.width() > dp(8f) &&
            bounds.height() > dp(8f)
    }

    private fun clearSelection() {
        selectionPath = null
        selectionBounds = null
        selectionMode = null
        selectionPoints.clear()
    }

    private fun finalizeFreeSelection() {
        if (drawPoints.size < 3) {
            clearSelection()
            invalidate()
            return
        }
        val closedPoints = drawPoints.toMutableList().apply {
            val first = firstOrNull()
            if (first != null) add(PointF(first.x, first.y))
        }
        setFreeSelection(closedPoints)
        invalidate()
    }

    private fun finalizeRectSelection() {
        if (drawRect.width() <= dp(8f) || drawRect.height() <= dp(8f)) {
            clearSelection()
            invalidate()
            return
        }
        setRectSelection(RectF(drawRect))
        invalidate()
    }

    private fun setFreeSelection(points: List<PointF>) {
        if (points.size < 3) {
            clearSelection()
            return
        }
        val path = Path()
        path.moveTo(points.first().x, points.first().y)
        for (index in 1 until points.size) {
            path.lineTo(points[index].x, points[index].y)
        }
        path.close()
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() <= dp(8f) || bounds.height() <= dp(8f)) {
            clearSelection()
            return
        }
        selectionMode = CaptureMode.FREE
        selectionPath = path
        selectionBounds = bounds
        selectionPoints.clear()
        points.forEach { point ->
            selectionPoints.add(PointF(point.x, point.y))
        }
    }

    private fun setRectSelection(rect: RectF) {
        if (rect.width() <= dp(8f) || rect.height() <= dp(8f)) {
            clearSelection()
            return
        }
        val normalized = RectF(
            min(rect.left, rect.right),
            min(rect.top, rect.bottom),
            max(rect.left, rect.right),
            max(rect.top, rect.bottom)
        )
        val path = Path().apply {
            addRect(normalized, Path.Direction.CW)
            close()
        }
        selectionMode = CaptureMode.RECT
        selectionBounds = normalized
        selectionPath = path
        selectionPoints.clear()
    }

    private fun updateDraggedSelection(currentX: Float, currentY: Float) {
        val mode = selectionMode ?: return
        val base = dragBaseBounds
        val rawDx = currentX - touchStartX
        val rawDy = currentY - touchStartY
        val maxLeft = (width.toFloat() - base.width()).coerceAtLeast(0f)
        val maxTop = (height.toFloat() - base.height()).coerceAtLeast(0f)
        val clampedLeft = (base.left + rawDx).coerceIn(0f, maxLeft)
        val clampedTop = (base.top + rawDy).coerceIn(0f, maxTop)
        val appliedDx = clampedLeft - base.left
        val appliedDy = clampedTop - base.top
        if (mode == CaptureMode.RECT) {
            setRectSelection(
                RectF(
                    base.left + appliedDx,
                    base.top + appliedDy,
                    base.right + appliedDx,
                    base.bottom + appliedDy
                )
            )
        } else {
            if (dragBasePoints.isEmpty()) return
            val movedPoints = dragBasePoints.map { point ->
                PointF(point.x + appliedDx, point.y + appliedDy)
            }
            setFreeSelection(movedPoints)
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}

private class StickerView(
    context: Context,
    private val windowManager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private var resizePreviewOnly: Boolean,
    private val onEnterEditMode: (StickerView) -> Unit,
    private val onExitEditMode: (StickerView) -> Unit,
    private val onRemoved: (StickerView) -> Unit
) : ImageView(context) {
    private enum class TouchMode {
        NONE,
        DRAG,
        RESIZE,
        ALPHA
    }
    private enum class OutlineMode {
        WHITE,
        BLACK,
        NONE
    }

    private val gestureDetector: GestureDetector
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F6FFF")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val underlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 220
        isFilterBitmap = true
    }
    private val underlayMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(dp(4.2f), BlurMaskFilter.Blur.NORMAL)
    }
    private val closeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4D4F")
    }
    private val resizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F6FFF")
    }
    private val alphaHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C3AED")
    }
    private val alphaTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 17, 24, 39)
    }
    private val alphaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A78BFA")
    }
    private val alphaValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(10f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val doneHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
    }
    private val topHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
    }
    private val outlineToggleOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
    }
    private val outlineToggleBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
    }
    private val outlineToggleOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
    }
    private val outlineToggleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(9.5f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val handleIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val minSizePx = dp(72f).toInt()
    private val outlinePaddingPx = dp(7f).toInt()
    private val handleRadius = dp(12f)
    private val handleHitRadius = dp(20f)

    private var editMode = false
    private var touchMode = TouchMode.NONE
    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0
    private var initialHeight = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var outlineMask: Bitmap? = null
    private var outlineMaskWidth = -1
    private var outlineMaskHeight = -1
    private var outlineMaskOffsetX = 0
    private var outlineMaskOffsetY = 0
    private var hitMask: Bitmap? = null
    private var hitMaskWidth = -1
    private var hitMaskHeight = -1
    private var outlineMode = OutlineMode.WHITE
    private var editFrameOverlayView: View? = null
    private var editFrameOverlayParams: WindowManager.LayoutParams? = null
    private var isLiveResizing = false
    private var resizePreviewWidth = -1
    private var resizePreviewHeight = -1

    init {
        scaleType = ImageView.ScaleType.FIT_XY
        setPadding(outlinePaddingPx, outlinePaddingPx, outlinePaddingPx, outlinePaddingPx)
        setWillNotDraw(false)
        gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!editMode) {
                        onEnterEditMode(this@StickerView)
                        editMode = true
                        showEditFrameOverlay()
                    }
                    invalidate()
                    return true
                }
            }
        )
    }

    override fun onDraw(canvas: Canvas) {
        drawStickerUnderlay(canvas)
        super.onDraw(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            clearMaskCaches()
        }
    }

    private fun drawStickerUnderlay(canvas: Canvas) {
        // Hide underlay when sticker is translucent to avoid visual contamination.
        if (alpha < 0.999f) return
        if (outlineMode == OutlineMode.NONE) return
        when (outlineMode) {
            OutlineMode.WHITE -> {
                underlayPaint.color = Color.WHITE
                underlayPaint.alpha = 220
            }
            OutlineMode.BLACK -> {
                underlayPaint.color = Color.BLACK
                underlayPaint.alpha = 170
            }
            OutlineMode.NONE -> Unit
        }
        val mask = ensureOutlineMask() ?: return
        canvas.drawBitmap(
            mask,
            outlineMaskOffsetX.toFloat(),
            outlineMaskOffsetY.toFloat(),
            underlayPaint
        )
    }

    private fun ensureOutlineMask(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val d = drawable ?: return null
        if (outlineMask != null && outlineMaskWidth == width && outlineMaskHeight == height) {
            return outlineMask
        }
        val contentLeft = paddingLeft
        val contentTop = paddingTop
        val contentRight = width - paddingRight
        val contentBottom = height - paddingBottom
        if (contentRight <= contentLeft || contentBottom <= contentTop) return null
        outlineMask?.recycle()
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        drawDrawableForMask(d, c, contentLeft, contentTop, contentRight, contentBottom)
        val offset = IntArray(2)
        outlineMask = src.extractAlpha(underlayMaskPaint, offset)
        outlineMaskOffsetX = offset[0]
        outlineMaskOffsetY = offset[1]
        src.recycle()
        outlineMaskWidth = width
        outlineMaskHeight = height
        return outlineMask
    }

    override fun setImageBitmap(bm: Bitmap?) {
        clearMaskCaches()
        super.setImageBitmap(bm)
    }

    override fun onDetachedFromWindow() {
        outlineMask?.recycle()
        hitMask?.recycle()
        outlineMask = null
        hitMask = null
        removeEditFrameOverlay()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!isOnInteractiveArea(event.x, event.y)) {
                    return false
                }
                gestureDetector.onTouchEvent(event)
                if (!editMode) return true
                initialX = params.x
                initialY = params.y
                initialWidth = params.width
                initialHeight = params.height
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                touchMode = TouchMode.DRAG
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                gestureDetector.onTouchEvent(event)
                if (!editMode) return true
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (touchMode == TouchMode.DRAG) {
                    params.x = initialX + dx
                    params.y = initialY + dy
                } else if (touchMode == TouchMode.RESIZE) {
                    val (newWidth, newHeight) = computeResizedSize(initialWidth, initialHeight, dx, dy)
                    params.width = newWidth
                    params.height = newHeight
                }
                runCatching { windowManager.updateViewLayout(this, params) }
                updateEditFrameOverlay()
                return true
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                gestureDetector.onTouchEvent(event)
                touchMode = TouchMode.NONE
                return true
            }
        }
        return true
    }

    private fun isOnInteractiveArea(x: Float, y: Float): Boolean {
        return isOnOpaquePixel(x, y)
    }

    private fun isOnOpaquePixel(x: Float, y: Float): Boolean {
        val mask = ensureHitMask() ?: return false
        val px = x.toInt().coerceIn(0, mask.width - 1)
        val py = y.toInt().coerceIn(0, mask.height - 1)
        val alpha = (mask.getPixel(px, py) ushr 24) and 0xFF
        return alpha > 20
    }

    private fun ensureHitMask(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val d = drawable ?: return null
        if (hitMask != null && hitMaskWidth == width && hitMaskHeight == height) {
            return hitMask
        }
        val contentLeft = paddingLeft
        val contentTop = paddingTop
        val contentRight = width - paddingRight
        val contentBottom = height - paddingBottom
        if (contentRight <= contentLeft || contentBottom <= contentTop) return null
        hitMask?.recycle()
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        drawDrawableForMask(d, c, contentLeft, contentTop, contentRight, contentBottom)
        hitMask = src
        hitMaskWidth = width
        hitMaskHeight = height
        return hitMask
    }

    private fun drawDrawableForMask(
        source: Drawable,
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val clone = source.constantState?.newDrawable()?.mutate()
        val target = clone ?: source
        val restoreBounds = if (clone == null) {
            Rect(source.bounds)
        } else {
            null
        }
        target.setBounds(left, top, right, bottom)
        target.draw(canvas)
        if (restoreBounds != null) {
            source.bounds = restoreBounds
        }
    }

    private fun clearMaskCaches() {
        outlineMask?.recycle()
        hitMask?.recycle()
        outlineMask = null
        hitMask = null
        outlineMaskWidth = -1
        outlineMaskHeight = -1
        hitMaskWidth = -1
        hitMaskHeight = -1
        outlineMaskOffsetX = 0
        outlineMaskOffsetY = 0
    }


    private fun computeResizedSize(
        baseWidth: Int,
        baseHeight: Int,
        dx: Int,
        dy: Int
    ): Pair<Int, Int> {
        val safeBaseWidth = max(baseWidth, 1)
        val safeBaseHeight = max(baseHeight, 1)
        val scaleDelta = max(dx.toFloat() / safeBaseWidth, dy.toFloat() / safeBaseHeight)
        val nextScale = 1f + scaleDelta

        var newWidth = (safeBaseWidth * nextScale).toInt().coerceAtLeast(minSizePx)
        var newHeight = (safeBaseHeight * nextScale).toInt().coerceAtLeast(minSizePx)

        if (safeBaseWidth > safeBaseHeight) {
            newHeight = (newWidth * (safeBaseHeight.toFloat() / safeBaseWidth)).toInt().coerceAtLeast(minSizePx)
        } else {
            newWidth = (newHeight * (safeBaseWidth.toFloat() / safeBaseHeight)).toInt().coerceAtLeast(minSizePx)
        }
        return Pair(newWidth, newHeight)
    }

    private fun removeSelf() {
        try {
            removeEditFrameOverlay()
            windowManager.removeView(this)
            onRemoved(this)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun bringToTop() {
        runCatching {
            windowManager.removeView(this)
            windowManager.addView(this, params)
            if (editMode) {
                removeEditFrameOverlay()
                showEditFrameOverlay()
            }
        }
    }

    private fun toggleOutline() {
        outlineMode = when (outlineMode) {
            OutlineMode.WHITE -> OutlineMode.BLACK
            OutlineMode.BLACK -> OutlineMode.NONE
            OutlineMode.NONE -> OutlineMode.WHITE
        }
        invalidate()
        Toast.makeText(
            context,
            when (outlineMode) {
                OutlineMode.WHITE -> "白色边框"
                OutlineMode.BLACK -> "黑色边框"
                OutlineMode.NONE -> "无边框"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun exitEditMode() {
        editMode = false
        touchMode = TouchMode.NONE
        isLiveResizing = false
        removeEditFrameOverlay()
        invalidate()
        onExitEditMode(this)
    }

    private fun showEditFrameOverlay() {
        if (editFrameOverlayView != null) {
            updateEditFrameOverlay()
            return
        }
        val overlay = object : View(context) {
            private var overlayMode = TouchMode.NONE
            private var startRawX = 0f
            private var startRawY = 0f
            private var baseX = 0
            private var baseY = 0
            private var baseW = 0
            private var baseH = 0
            private var baseAlpha = 1f

            private fun closeCenter() = Pair(handleRadius, handleRadius)
            private fun doneCenter() = Pair(width - handleRadius, handleRadius)
            private fun topCenter() = Pair(handleRadius, height / 2f)
            private fun outlineCenter() = Pair(handleRadius, height - handleRadius)
            private fun alphaCenter() = Pair(width / 2f, height - handleRadius)
            private fun resizeCenter() = Pair(width - handleRadius, height - handleRadius)

            private fun isNear(x: Float, y: Float, c: Pair<Float, Float>): Boolean {
                val dx = x - c.first
                val dy = y - c.second
                return dx * dx + dy * dy <= handleHitRadius * handleHitRadius
            }

            override fun onDraw(canvas: Canvas) {
                val half = framePaint.strokeWidth / 2f
                canvas.drawRect(half, half, width - half, height - half, framePaint)

                val (closeX, closeY) = closeCenter()
                val (doneX, doneY) = doneCenter()
                val (topX, topY) = topCenter()
                val (outlineX, outlineY) = outlineCenter()
                val (alphaX, alphaY) = alphaCenter()
                val (resizeX, resizeY) = resizeCenter()

                canvas.drawCircle(closeX, closeY, handleRadius, closeHandlePaint)
                canvas.drawCircle(doneX, doneY, handleRadius, doneHandlePaint)
                canvas.drawCircle(topX, topY, handleRadius, topHandlePaint)
                canvas.drawCircle(
                    outlineX,
                    outlineY,
                    handleRadius,
                    when (outlineMode) {
                        OutlineMode.WHITE -> outlineToggleOnPaint
                        OutlineMode.BLACK -> outlineToggleBlackPaint
                        OutlineMode.NONE -> outlineToggleOffPaint
                    }
                )
                canvas.drawCircle(alphaX, alphaY, handleRadius, alphaHandlePaint)
                canvas.drawCircle(resizeX, resizeY, handleRadius, resizeHandlePaint)

                val closeMarkOffset = handleRadius * 0.45f
                canvas.drawLine(closeX - closeMarkOffset, closeY - closeMarkOffset, closeX + closeMarkOffset, closeY + closeMarkOffset, handleIconPaint)
                canvas.drawLine(closeX + closeMarkOffset, closeY - closeMarkOffset, closeX - closeMarkOffset, closeY + closeMarkOffset, handleIconPaint)

                val doneMarkOffset = handleRadius * 0.5f
                canvas.drawLine(doneX - doneMarkOffset * 0.95f, doneY + doneMarkOffset * 0.05f, doneX - doneMarkOffset * 0.2f, doneY + doneMarkOffset * 0.75f, handleIconPaint)
                canvas.drawLine(doneX - doneMarkOffset * 0.2f, doneY + doneMarkOffset * 0.75f, doneX + doneMarkOffset, doneY - doneMarkOffset * 0.65f, handleIconPaint)

                val topMarkOffset = handleRadius * 0.56f
                canvas.drawLine(topX - topMarkOffset * 0.72f, topY + topMarkOffset * 0.15f, topX, topY - topMarkOffset * 0.78f, handleIconPaint)
                canvas.drawLine(topX + topMarkOffset * 0.72f, topY + topMarkOffset * 0.15f, topX, topY - topMarkOffset * 0.78f, handleIconPaint)
                canvas.drawLine(topX, topY - topMarkOffset * 0.78f, topX, topY + topMarkOffset * 0.7f, handleIconPaint)

                val modeLabel = when (outlineMode) {
                    OutlineMode.WHITE -> "W"
                    OutlineMode.BLACK -> "B"
                    OutlineMode.NONE -> "-"
                }
                canvas.drawText(modeLabel, outlineX, outlineY + dp(3.1f), outlineToggleLabelPaint)
                canvas.drawText("A", alphaX, alphaY + dp(3.1f), outlineToggleLabelPaint)

                val resizeMarkOffset = handleRadius * 0.55f
                canvas.drawLine(resizeX - resizeMarkOffset, resizeY + resizeMarkOffset, resizeX + resizeMarkOffset, resizeY - resizeMarkOffset, handleIconPaint)
                canvas.drawLine(resizeX - resizeMarkOffset * 0.2f, resizeY + resizeMarkOffset, resizeX + resizeMarkOffset, resizeY - resizeMarkOffset * 0.2f, handleIconPaint)

                if (overlayMode == TouchMode.ALPHA) {
                    val progress = this@StickerView.alpha.coerceIn(0f, 1f)
                    val trackWidth = dp(6f)
                    val trackHeight = min(dp(150f), height - dp(56f))
                    val trackLeft = alphaX + handleRadius + dp(12f)
                    val trackTop = alphaY - trackHeight / 2f
                    val trackRadius = trackWidth / 2f
                    val trackRect = RectF(
                        trackLeft,
                        trackTop,
                        trackLeft + trackWidth,
                        trackTop + trackHeight
                    )
                    canvas.drawRoundRect(trackRect, trackRadius, trackRadius, alphaTrackPaint)
                    if (progress > 0f) {
                        val fillRect = RectF(
                            trackLeft,
                            trackTop + trackHeight * (1f - progress),
                            trackLeft + trackWidth,
                            trackTop + trackHeight
                        )
                        canvas.drawRoundRect(fillRect, trackRadius, trackRadius, alphaFillPaint)
                    }
                    val percent = (progress * 100f).toInt()
                    canvas.drawText("${percent}%", trackLeft + trackWidth / 2f, trackTop - dp(6f), alphaValuePaint)
                }
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        baseX = params.x
                        baseY = params.y
                        baseW = params.width
                        baseH = params.height
                        baseAlpha = this@StickerView.alpha.coerceIn(0f, 1f)

                        if (isNear(event.x, event.y, closeCenter())) {
                            removeSelf()
                            return true
                        }
                        if (isNear(event.x, event.y, doneCenter())) {
                            exitEditMode()
                            return true
                        }
                        if (isNear(event.x, event.y, topCenter())) {
                            bringToTop()
                            return true
                        }
                        if (isNear(event.x, event.y, outlineCenter())) {
                            toggleOutline()
                            invalidate()
                            return true
                        }
                        if (isNear(event.x, event.y, alphaCenter())) {
                            overlayMode = TouchMode.ALPHA
                            return true
                        }
                        if (isNear(event.x, event.y, resizeCenter())) {
                            overlayMode = TouchMode.RESIZE
                            isLiveResizing = true
                            resizePreviewWidth = baseW
                            resizePreviewHeight = baseH
                            return true
                        }
                        overlayMode = TouchMode.DRAG
                        isLiveResizing = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).toInt()
                        val dy = (event.rawY - startRawY).toInt()
                        if (overlayMode == TouchMode.DRAG) {
                            params.x = baseX + dx
                            params.y = baseY + dy
                            runCatching { windowManager.updateViewLayout(this@StickerView, params) }
                            updateEditFrameOverlay()
                        } else if (overlayMode == TouchMode.ALPHA) {
                            val deltaY = event.rawY - startRawY
                            val nextAlpha = (baseAlpha - deltaY / dp(180f)).coerceIn(0f, 1f)
                            this@StickerView.alpha = nextAlpha
                        } else if (overlayMode == TouchMode.RESIZE) {
                            val (newWidth, newHeight) = computeResizedSize(baseW, baseH, dx, dy)
                            if (resizePreviewOnly) {
                                resizePreviewWidth = newWidth
                                resizePreviewHeight = newHeight
                                updateEditFrameOverlayPreview(baseX, baseY, newWidth, newHeight)
                            } else {
                                params.width = newWidth
                                params.height = newHeight
                                runCatching { windowManager.updateViewLayout(this@StickerView, params) }
                                updateEditFrameOverlay()
                            }
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_UP -> {
                        if (resizePreviewOnly &&
                            overlayMode == TouchMode.RESIZE &&
                            resizePreviewWidth > 0 &&
                            resizePreviewHeight > 0
                        ) {
                            params.width = resizePreviewWidth
                            params.height = resizePreviewHeight
                            runCatching { windowManager.updateViewLayout(this@StickerView, params) }
                        }
                        overlayMode = TouchMode.NONE
                        if (isLiveResizing) {
                            isLiveResizing = false
                            this@StickerView.invalidate()
                        }
                        resizePreviewWidth = -1
                        resizePreviewHeight = -1
                        runCatching { windowManager.updateViewLayout(this@StickerView, params) }
                        updateEditFrameOverlay()
                        return true
                    }
                }
                return false
            }
        }
        val lp = WindowManager.LayoutParams(
            params.width,
            params.height,
            params.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = params.gravity
            x = params.x
            y = params.y
        }
        runCatching {
            windowManager.addView(overlay, lp)
            editFrameOverlayView = overlay
            editFrameOverlayParams = lp
        }
    }

    private fun updateEditFrameOverlay() {
        val view = editFrameOverlayView ?: return
        val lp = editFrameOverlayParams ?: return
        lp.width = params.width
        lp.height = params.height
        lp.x = params.x
        lp.y = params.y
        runCatching { windowManager.updateViewLayout(view, lp) }
        view.invalidate()
    }

    private fun updateEditFrameOverlayPreview(x: Int, y: Int, width: Int, height: Int) {
        val view = editFrameOverlayView ?: return
        val lp = editFrameOverlayParams ?: return
        lp.width = width
        lp.height = height
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(view, lp) }
        view.invalidate()
    }

    private fun removeEditFrameOverlay() {
        editFrameOverlayView?.let { runCatching { windowManager.removeView(it) } }
        editFrameOverlayView = null
        editFrameOverlayParams = null
    }

    fun forceExitEditModeFromHost() {
        if (!editMode) return
        exitEditMode()
    }

    fun setResizePreviewOnly(enabled: Boolean) {
        resizePreviewOnly = enabled
        if (!enabled) {
            resizePreviewWidth = -1
            resizePreviewHeight = -1
        }
    }

    fun setTouchable(touchable: Boolean) {
        val newFlags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (newFlags == params.flags) return
        params.flags = newFlags
        runCatching { windowManager.updateViewLayout(this, params) }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}


