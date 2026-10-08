package com.benton.izukijs.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.R
import com.benton.izukijs.runtime.LogEntry
import com.benton.izukijs.runtime.LogLevel
import com.benton.izukijs.runtime.ScriptExecutionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 脚本日志悬浮窗。脚本运行时自动显示，默认展示 INFO 及以上级别的日志，
 * 提供最小化、停止脚本与关闭窗口按钮；停止脚本后悬浮窗保持显示，需手动关闭。
 *
 * 脚本进行任何截图操作时，会通过 [OverlayCoordinator] 临时隐藏自身，避免被截入画面。
 */
class FloatingWindowService : android.app.Service() {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var bodyView: View? = null
    private var statusView: TextView? = null
    private var logView: TextView? = null
    private var scrollView: ScrollView? = null
    private var minimizeButton: TextView? = null
    private lateinit var params: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否已折叠日志区域。 */
    private var minimized = false

    private val executionManager: ScriptExecutionManager
        get() = (application as IzukiApp).container.scriptExecutionManager

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val overlayListener = object : OverlayCoordinator.Listener {
        override fun hide() {
            mainHandler.post { rootView?.visibility = View.INVISIBLE }
        }

        override fun show() {
            mainHandler.post { rootView?.visibility = View.VISIBLE }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 12+ 要求 startForegroundService() 启动后必须在约 5 秒内调用 startForeground()，
        // 否则进程会被判定超时并崩溃。因此先无条件进入前台，再判断悬浮窗权限。
        createChannel()
        startForegroundInternal()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isActive = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
        observeState()
        observeLogs()
        OverlayCoordinator.register(overlayListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        isActive = false
        OverlayCoordinator.unregister(overlayListener)
        scope.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    private fun showPanel() {
        val density = resources.displayMetrics.density
        val panelWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(getColor(R.color.overlay_surface))
                setStroke(1, getColor(R.color.overlay_stroke))
            }
            elevation = 8f
        }

        val status = TextView(this).apply {
            text = getString(R.string.floating_status_ready)
            setTextColor(getColor(R.color.overlay_text_muted))
            textSize = 14f
            setPadding(4, 8, 16, 8)
            maxLines = 1
        }
        statusView = status

        val minimize = TextView(this).apply {
            text = "—"
            setTextColor(getColor(R.color.overlay_text_primary))
            contentDescription = getString(R.string.floating_collapse)
            textSize = 18f
            setPadding(20, 8, 20, 8)
            setOnClickListener { setMinimized(!minimized) }
        }
        minimizeButton = minimize

        val stop = TextView(this).apply {
            text = "■"
            setTextColor(getColor(R.color.overlay_error))
            contentDescription = getString(R.string.floating_stop)
            textSize = 14f
            setPadding(20, 8, 4, 8)
            setOnClickListener { executionManager.requestStop() }
        }

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(getColor(R.color.overlay_text_primary))
            contentDescription = getString(R.string.floating_close)
            textSize = 14f
            setPadding(20, 8, 4, 8)
            setOnClickListener { stopSelf() }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status)
            addView(minimize)
            addView(stop)
            addView(close)
        }
        root.addView(header)

        val log = TextView(this).apply {
            setTextColor(getColor(R.color.overlay_text_secondary))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = getString(R.string.floating_waiting_log)
        }
        logView = log

        val scroll = ScrollView(this).apply {
            setPadding(0, 12, 0, 0)
            addView(log)
        }
        scrollView = scroll
        bodyView = scroll
        root.addView(
            scroll,
            LinearLayout.LayoutParams(panelWidth, (160 * density).toInt()),
        )

        attachDrag(root, status)

        rootView = root
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

    private fun setMinimized(value: Boolean) {
        minimized = value
        bodyView?.visibility = if (value) View.GONE else View.VISIBLE
        minimizeButton?.apply {
            text = if (value) "▢" else "—"
            contentDescription = getString(
                if (value) R.string.floating_expand else R.string.floating_collapse,
            )
        }
    }

    private fun observeState() {
        scope.launch {
            combine(executionManager.running, executionManager.runningScript) { running, name ->
                running to name
            }.collectLatest { (running, name) ->
                val status = statusView ?: return@collectLatest
                if (running) {
                    status.text = name?.takeIf { it.isNotBlank() }?.let { "● $it" }
                        ?: getString(R.string.floating_status_running)
                    status.setTextColor(getColor(R.color.overlay_success))
                } else {
                    status.text = getString(R.string.floating_status_ready)
                    status.setTextColor(getColor(R.color.overlay_text_muted))
                }
            }
        }
    }

    private fun observeLogs() {
        scope.launch {
            (application as IzukiApp).container.logBus.entries.collectLatest { entries ->
                renderLogs(entries)
            }
        }
    }

    private fun renderLogs(entries: List<LogEntry>) {
        val view = logView ?: return
        val visible = entries.filter { it.level.ordinal >= LogLevel.INFO.ordinal }
        if (visible.isEmpty()) return
        val builder = SpannableStringBuilder()
        visible.takeLast(MAX_LOG_LINES).forEach { entry ->
            val start = builder.length
            builder.append(timeFormat.format(Date(entry.timeMillis)))
                .append(' ')
                .append(tagOf(entry.level))
                .append(' ')
                .append(entry.message)
            builder.setSpan(
                ForegroundColorSpan(colorOf(entry.level)),
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.append('\n')
        }
        view.setText(builder, TextView.BufferType.SPANNABLE)
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun tagOf(level: LogLevel): String = when (level) {
        LogLevel.DEBUG -> "D"
        LogLevel.INFO -> "I"
        LogLevel.SUCCESS -> "✓"
        LogLevel.WARN -> "W"
        LogLevel.ERROR -> "E"
    }

    private fun colorOf(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> getColor(R.color.overlay_text_muted)
        LogLevel.INFO -> getColor(R.color.overlay_text_secondary)
        LogLevel.SUCCESS -> getColor(R.color.overlay_success)
        LogLevel.WARN -> getColor(R.color.overlay_warning)
        LogLevel.ERROR -> getColor(R.color.overlay_error)
    }

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(getString(R.string.notif_floating_title))
            .setContentText(getString(R.string.notif_floating_text))
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

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_floating_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "izuki_floating"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_LOG_LINES = 200

        private val _active = MutableStateFlow(false)

        /** 服务运行状态的响应式订阅，供「权限与能力」页同步开关。 */
        val active: StateFlow<Boolean> = _active.asStateFlow()

        @Volatile
        var isActive: Boolean = false
            private set(value) {
                field = value
                _active.value = value
            }

        /** 开启悬浮窗。开启后常驻，脚本结束也不会自动关闭，需手动点击关闭按钮或开关。 */
        fun start(context: Context) {
            // 无悬浮窗权限时直接不启动，避免白白拉起一个前台服务（也规避 FGS 超时风险）。
            if (!Settings.canDrawOverlays(context)) return
            runCatching { context.startForegroundService(Intent(context, FloatingWindowService::class.java)) }
        }

        /** 脚本开始时确保悬浮窗显示（脚本结束后保持显示）。 */
        fun showForRun(context: Context) {
            if (isActive) return
            start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
