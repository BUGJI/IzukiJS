package com.benton.izukijs.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.MainActivity
import com.benton.izukijs.R
import com.benton.izukijs.core.notification.ProgressSegment
import com.benton.izukijs.core.notification.ScriptNotificationCenter
import com.benton.izukijs.core.notification.ScriptNotificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 脚本运行期间的前台服务，用于保活并对外提供停止入口。
 *
 * 通知内容由 [ScriptNotificationCenter] 驱动：脚本可以更新标题、文本、阶段、进度、
 * 分段、颜色等；当前系统不支持完整样式时自动降级为普通进度条。
 */
class ScriptForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var observeJob: Job? = null

    private var scriptName: String = ""

    private var lastUpdateAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                scriptName = intent.getStringExtra(EXTRA_SCRIPT_NAME).orEmpty()
                    .ifBlank { getString(R.string.nav_scripts) }
                if (!ScriptNotificationCenter.isActive) {
                    ScriptNotificationCenter.begin(scriptName)
                }
                startForegroundInternal()
                observeNotifications()
            }

            ACTION_STOP -> {
                runCatching {
                    val execution = (application as IzukiApp).container.scriptExecutionManager
                    if (execution.running.value) execution.requestStop()
                }
                ScriptNotificationCenter.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeNotifications() {
        if (observeJob?.isActive == true) return
        val settings = (application as IzukiApp).container.runtimeSettingsRepository.settings
        observeJob = serviceScope.launch {
            combine(ScriptNotificationCenter.state, settings) { state, _ -> state }
                .collect { state ->
                    if (!state.active) return@collect
                    val wait = MIN_UPDATE_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastUpdateAt)
                    if (wait > 0) delay(wait)
                    postNotification(buildNotification(state))
                    lastUpdateAt = SystemClock.elapsedRealtime()
                }
        }
    }

    private fun startForegroundInternal() {
        val notification = buildNotification(ScriptNotificationCenter.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        lastUpdateAt = SystemClock.elapsedRealtime()
    }

    private fun postNotification(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: ScriptNotificationState): Notification {
        val title = state.title.ifBlank { getString(R.string.notif_script_title) }
        val text = state.text.ifBlank {
            state.scriptName.ifBlank { scriptName }.ifBlank { getString(R.string.nav_scripts) }
        }
        val progressEnabled = (application as IzukiApp)
            .container
            .runtimeSettingsRepository
            .current()
            .progressNotification

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(state.visibility)
            .setSilent(state.silent)

        state.stage?.let(builder::setSubText)
        state.color?.let(builder::setColor)
        if (state.colorized) builder.setColorized(true)
        if (state.timeoutMs > 0L) builder.setTimeoutAfter(state.timeoutMs)

        if (progressEnabled) {
            applyProgress(builder, state)
        }

        if (state.showStopAction) {
            builder.addAction(0, getString(R.string.common_stop), stopIntent())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            state.shortCriticalText?.let(builder::setShortCriticalText)
            if (state.promoted && canPostPromotedNotifications()) {
                builder.setRequestPromotedOngoing(true)
            }
        }
        return builder.build()
    }

    private fun applyProgress(builder: NotificationCompat.Builder, state: ScriptNotificationState) {
        if (state.indeterminate) {
            builder.setProgress(0, 0, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val style = NotificationCompat.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressIndeterminate(true)
                val color = state.segments.firstOrNull()?.color ?: state.color
                color?.let {
                    style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(1).setColor(it))
                }
                builder.setStyle(style)
            }
            return
        }

        val max = state.max.coerceAtLeast(1)
        val value = state.value.coerceIn(0, max)
        builder.setProgress(max, value, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return

        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressIndeterminate(false)
            .setProgress(value)
        val segments = state.segments.ifEmpty {
            listOf(ProgressSegment(max, state.color))
        }
        segments.forEach { segment ->
            val styled = NotificationCompat.ProgressStyle.Segment(segment.length)
            segment.color?.let(styled::setColor)
            style.addProgressSegment(styled)
        }
        state.points.forEach { point ->
            val styled = NotificationCompat.ProgressStyle.Point(point.position)
            point.color?.let(styled::setColor)
            style.addProgressPoint(styled)
        }
        iconFor(state.trackerIcon)?.let(style::setProgressTrackerIcon)
        iconFor(state.startIcon)?.let(style::setProgressStartIcon)
        iconFor(state.endIcon)?.let(style::setProgressEndIcon)
        builder.setStyle(style)
    }

    private fun iconFor(key: String?): IconCompat? = when (key) {
        "script" -> IconCompat.createWithResource(this, R.drawable.ic_stat_script)
        "progress" -> IconCompat.createWithResource(this, R.drawable.ic_progress_tracker)
        else -> null
    }

    private fun canPostPromotedNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return false
        return runCatching {
            getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true
        }.getOrDefault(false)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, ScriptForegroundService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_script_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_script_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "izuki_script_running"
        private const val NOTIFICATION_ID = NotificationIds.SCRIPT
        private const val EXTRA_SCRIPT_NAME = "extra_script_name"
        private const val MIN_UPDATE_INTERVAL_MS = 500L

        const val ACTION_START = "com.benton.izukijs.action.START_SCRIPT"
        const val ACTION_STOP = "com.benton.izukijs.action.STOP_SCRIPT"

        fun start(context: Context, scriptName: String) {
            val intent = Intent(context, ScriptForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCRIPT_NAME, scriptName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScriptForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
