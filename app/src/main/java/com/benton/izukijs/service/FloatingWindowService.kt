package com.benton.izukijs.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.R
import com.benton.izukijs.runtime.ScriptExecutionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮窗控制条。显示运行状态，并提供运行/停止快捷操作。
 */
class FloatingWindowService : android.app.Service() {

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var statusView: TextView? = null
    private lateinit var params: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val executionManager: ScriptExecutionManager
        get() = (application as IzukiApp).container.scriptExecutionManager

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
        showOverlay()
        observeState()
    }

    override fun onDestroy() {
        isActive = false
        scope.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    private fun showOverlay() {
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

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 12, 12)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#EE1F1F1F"))
                setStroke(1, Color.parseColor("#55FFFFFF"))
            }
            elevation = 8f
        }

        val status = TextView(this).apply {
            text = "● 就绪"
            setTextColor(Color.parseColor("#9E9E9E"))
            textSize = 14f
            setPadding(8, 16, 16, 16)
        }
        statusView = status

        val toggle = TextView(this).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                val manager = executionManager
                if (manager.running.value) {
                    manager.requestStop()
                } else {
                    runMostRecentScript()
                }
            }
        }

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF8A80"))
            textSize = 16f
            setPadding(24, 12, 24, 12)
            setOnClickListener { stopSelf() }
        }

        container.addView(status)
        container.addView(toggle)
        container.addView(close)

        attachDrag(container, status)

        rootView = container
        runCatching { windowManager.addView(container, params) }
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

    private fun observeState() {
        scope.launch {
            executionManager.running.collectLatest { running ->
                val status = statusView ?: return@collectLatest
                if (running) {
                    status.text = "● 运行中"
                    status.setTextColor(Color.parseColor("#69F0AE"))
                } else {
                    status.text = "● 就绪"
                    status.setTextColor(Color.parseColor("#9E9E9E"))
                }
            }
        }
    }

    private fun runMostRecentScript() {
        val container = (application as IzukiApp).container
        val script = container.scriptRepository.list().firstOrNull()
        if (script == null) {
            statusView?.text = "● 无脚本"
            return
        }
        val source = container.scriptRepository.read(script)
        container.scriptExecutionManager.run(script.name, source)
    }

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle("Izuki JS 悬浮窗")
            .setContentText("控制条正在运行")
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
                "悬浮窗控制",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "izuki_floating"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        var isActive: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingWindowService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }
    }
}
