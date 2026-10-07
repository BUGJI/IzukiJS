package com.benton.izukijs.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 脚本日志悬浮窗。脚本运行时自动显示，默认展示 INFO 及以上级别的日志，
 * 提供最小化与停止按钮；脚本结束（且非手动常驻）后自动关闭。
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

    /** 手动开启后常驻，脚本结束也不自动关闭。 */
    private var pinned = false

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
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isActive = true
        createChannel()
        startForegroundInternal()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
        observeState()
        observeLogs()
        OverlayCoordinator.register(overlayListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCRIPT_FINISHED -> if (!pinned) stopSelf()
            ACTION_START -> if (intent.getBooleanExtra(EXTRA_PINNED, false)) pinned = true
            else -> Unit
        }
        return START_NOT_STICKY
    }

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
                setColor(Color.parseColor("#EE1F1F1F"))
                setStroke(1, Color.parseColor("#55FFFFFF"))
            }
            elevation = 8f
        }

        val status = TextView(this).apply {
            text = "● 就绪"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 14f
            setPadding(4, 8, 16, 8)
            maxLines = 1
        }
        statusView = status

        val minimize = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(20, 8, 20, 8)
            setOnClickListener { setMinimized(!minimized) }
        }
        minimizeButton = minimize

        val stop = TextView(this).apply {
            text = "■"
            setTextColor(Color.parseColor("#FF8A80"))
            textSize = 14f
            setPadding(20, 8, 4, 8)
            setOnClickListener {
                val manager = executionManager
                if (manager.running.value) manager.requestStop() else stopSelf()
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status)
            addView(minimize)
            addView(stop)
        }
        root.addView(header)

        val log = TextView(this).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = "等待脚本日志…"
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
        minimizeButton?.text = if (value) "▢" else "—"
    }

    private fun observeState() {
        scope.launch {
            combine(executionManager.running, executionManager.runningScript) { running, name ->
                running to name
            }.collectLatest { (running, name) ->
                val status = statusView ?: return@collectLatest
                if (running) {
                    status.text = name?.takeIf { it.isNotBlank() }?.let { "● $it" } ?: "● 运行中"
                    status.setTextColor(Color.parseColor("#69F0AE"))
                } else {
                    status.text = "● 就绪"
                    status.setTextColor(Color.parseColor("#9E9E9E"))
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
        LogLevel.DEBUG -> Color.parseColor("#9E9E9E")
        LogLevel.INFO -> Color.parseColor("#E0E0E0")
        LogLevel.SUCCESS -> Color.parseColor("#69F0AE")
        LogLevel.WARN -> Color.parseColor("#FFD54F")
        LogLevel.ERROR -> Color.parseColor("#FF8A80")
    }

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle("Izuki JS 悬浮窗")
            .setContentText("运行日志正在显示")
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
                "悬浮窗日志",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "izuki_floating"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_PINNED = "extra_pinned"
        private const val MAX_LOG_LINES = 200

        private const val ACTION_START = "com.benton.izukijs.action.SHOW_FLOATING"
        private const val ACTION_SCRIPT_FINISHED = "com.benton.izukijs.action.SCRIPT_FINISHED"

        @Volatile
        var isActive: Boolean = false
            private set

        /** 手动开启悬浮窗。[pinned] 为 true 时常驻，脚本结束后不自动关闭。 */
        fun start(context: Context, pinned: Boolean = false) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PINNED, pinned)
            }
            runCatching { context.startForegroundService(intent) }
        }

        /** 脚本开始时确保悬浮窗显示（临时，脚本结束后自动关闭）。 */
        fun showForRun(context: Context) {
            if (isActive) return
            start(context, pinned = false)
        }

        /** 脚本结束时关闭临时悬浮窗；手动常驻时保持显示。 */
        fun onScriptFinished(context: Context) {
            if (!isActive) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SCRIPT_FINISHED
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
