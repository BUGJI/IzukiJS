package com.benton.izukijs.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.R
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.ocr.OcrBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 调试悬浮窗：一键获取当前布局树 / OCR 结果（含每个结果的位置坐标）。
 * 抓取与识别期间会临时隐藏自身，识别结果可用叠加层在屏幕上标出位置。
 */
class DebugOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var output: TextView? = null
    private var boxOverlay: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container get() = (application as IzukiApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isActive = true
        createChannel()
        startForegroundInternal()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onDestroy() {
        isActive = false
        scope.cancel()
        removeBoxOverlay()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        super.onDestroy()
    }

    private fun showPanel() {
        val density = resources.displayMetrics.density
        val panelWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()

        params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (120 * density).toInt()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(getColor(R.color.overlay_surface_strong))
                setStroke(1, getColor(R.color.overlay_stroke))
            }
            elevation = 12f
        }

        val title = TextView(this).apply {
            text = getString(R.string.debug_drag_me)
            setTextColor(getColor(R.color.overlay_text_primary))
            textSize = 14f
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(getColor(R.color.overlay_error))
            contentDescription = getString(R.string.debug_close)
            textSize = 16f
            setPadding(24, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(close)
            },
        )

        val layoutBtn = Button(this).apply {
            text = getString(R.string.debug_tab_layout)
            setOnClickListener { runTask(getString(R.string.debug_capturing_layout), ::captureLayout) }
        }
        val ocrBtn = Button(this).apply {
            text = "OCR"
            setOnClickListener { runTask(getString(R.string.debug_recognizing_text), ::captureOcr) }
        }
        val clearBtn = Button(this).apply {
            text = getString(R.string.common_clear)
            setOnClickListener {
                output?.text = ""
                removeBoxOverlay()
            }
        }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 12)
                addView(layoutBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ocrBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            },
        )

        val text = TextView(this).apply {
            setTextColor(getColor(R.color.overlay_text_secondary))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            text = getString(R.string.debug_start_hint)
        }
        output = text
        root.addView(
            ScrollView(this).apply {
                addView(text)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (240 * density).toInt(),
                )
            },
        )

        attachDrag(root, root.getChildAt(0) as View)

        panel = root
        runCatching { windowManager.addView(root, params) }
    }

    private fun attachDrag(container: LinearLayout, handle: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }

                else -> false
            }
        }
    }

    /** 抓取/识别前隐藏自身，完成后再显示，避免被截入画面。 */
    private fun runTask(progress: String, block: () -> TaskResult) {
        output?.text = progress
        scope.launch {
            panel?.visibility = View.INVISIBLE
            delay(HIDE_DELAY_MS)
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrElse {
                    TaskResult(getString(R.string.debug_task_failed, it.message.orEmpty()), emptyList())
                }
            }
            panel?.visibility = View.VISIBLE
            output?.text = result.text
            showBoxes(result.blocks)
        }
    }

    private data class TaskResult(val text: String, val blocks: List<OcrBlock>)

    private fun captureLayout(): TaskResult {
        val controller = container.controllerManager
            .controllerFor(Capability.NODE_TREE) as? AccessibilityController
            ?: return TaskResult(getString(R.string.debug_no_accessibility), emptyList())
        val root = controller.nodeTree() ?: return TaskResult(getString(R.string.debug_no_node_tree), emptyList())
        val builder = StringBuilder()
        fun walk(node: NodeSnapshot, depth: Int) {
            if (depth > 6 || builder.length > 6000) return
            builder.append("  ".repeat(depth))
            builder.append(node.className?.substringAfterLast('.') ?: "View")
            node.viewId?.let { builder.append(" #").append(it.substringAfterLast('/')) }
            node.text?.takeIf { it.isNotBlank() }?.let {
                builder.append(" \"").append(it.take(24)).append('"')
            }
            if (node.clickable) builder.append(' ').append(getString(R.string.debug_clickable_tag))
            builder.append(" @(${(node.left + node.right) / 2},${(node.top + node.bottom) / 2})")
            builder.append('\n')
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return TaskResult(builder.toString().ifBlank { getString(R.string.debug_empty_layout) }, emptyList())
    }

    private fun captureOcr(): TaskResult {
        val bitmap = container.screenCapture.capture()
            ?: container.controllerManager.controllerFor(Capability.SCREENSHOT)?.screenshot()
            ?: return TaskResult(getString(R.string.debug_no_screenshot), emptyList())

        val result = try {
            container.ocrProcessor.recognize(bitmap)
        } finally {
            bitmap.recycle()
        } ?: return TaskResult(getString(R.string.debug_ocr_no_result), emptyList())

        val builder = StringBuilder()
        builder.append(getString(R.string.debug_ocr_element_count, result.blocks.size))
        result.blocks.forEach { block ->
            builder.append("[${block.x},${block.y} ${block.width}x${block.height}] ")
                .append(block.text)
                .append('\n')
        }
        if (result.blocks.isEmpty()) builder.append(result.text)
        return TaskResult(builder.toString(), result.blocks)
    }

    // ---- 识别结果叠加显示 ----

    private fun showBoxes(blocks: List<OcrBlock>) {
        removeBoxOverlay()
        if (blocks.isEmpty()) return
        val view = ResultOverlayView(this, blocks)
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { windowManager.addView(view, overlayParams) }
        boxOverlay = view
        scope.launch {
            delay(BOX_DISPLAY_MS)
            removeBoxOverlay()
        }
    }

    private fun removeBoxOverlay() {
        boxOverlay?.let { runCatching { windowManager.removeView(it) } }
        boxOverlay = null
    }

    private class ResultOverlayView(
        context: Context,
        private val blocks: List<OcrBlock>,
    ) : View(context) {

        private val fill = Paint().apply { color = 0x3300E5FF }
        private val stroke = Paint().apply {
            color = 0xFF00E5FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        private val label = Paint().apply {
            color = 0xFFFFEB3B.toInt()
            textSize = 30f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            blocks.forEach { block ->
                val halfW = block.width / 2f
                val halfH = block.height / 2f
                val left = block.x - halfW
                val top = block.y - halfH
                val right = block.x + halfW
                val bottom = block.y + halfH
                canvas.drawRect(left, top, right, bottom, fill)
                canvas.drawRect(left, top, right, bottom, stroke)
                canvas.drawText("(${block.x},${block.y})", left, (top - 8f).coerceAtLeast(28f), label)
            }
        }
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_debug_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(getString(R.string.notif_debug_title))
            .setContentText(getString(R.string.notif_debug_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "izuki_debug_overlay"
        private const val NOTIFICATION_ID = 1004
        private const val HIDE_DELAY_MS = 160L
        private const val BOX_DISPLAY_MS = 6000L

        private val _active = MutableStateFlow(false)

        /** 服务运行状态的响应式订阅，供「权限与能力」页同步开关。 */
        val active: StateFlow<Boolean> = _active.asStateFlow()

        @Volatile
        var isActive: Boolean = false
            private set(value) {
                field = value
                _active.value = value
            }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DebugOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DebugOverlayService::class.java))
        }
    }
}
