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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.benton.izukijs.notification.NotificationIntents
import com.benton.izukijs.R
import com.benton.izukijs.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MCP 服务的前台服务：在服务运行期间保活进程并显示常驻通知（含访问地址与停止入口）。
 *
 * 启停由 UI 触发；[ACTION_RESTART] 用于影响监听的配置变更（端口 / 绑定 / 令牌）后重建监听，
 * 并做防抖以合并连续改动。工具开关 / 黑名单由实时配置即时生效，无需重建。
 */
class McpServerService : Service() {

    private val controller get() = applicationContext.appContainer.mcpServerController

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 启停 / 重建都在后台执行：HttpServer 停止会等待在途请求，并做 socket 绑定与网卡枚举，
     * 这些都不应占用主线程（onStartCommand 运行在主线程）。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启停 / 重建串行执行，避免旧的 stop 落在新 start 之后。 */
    private val lifecycleMutex = Mutex()

    /** 防抖后的重建任务：合并短时间内的连续配置改动，避免同一秒内多次 stop/start。 */
    private val pendingRestart = Runnable {
        ioScope.launch {
            lifecycleMutex.withLock { controller.restart() }
            withContext(Dispatchers.Main) { updateNotification() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pendingRestart)
        // 服务被系统回收时也必须关闭监听，避免 socket 存活但没有前台服务。
        runCatching { controller.stop() }
        ioScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                mainHandler.removeCallbacks(pendingRestart)
                stopForeground(STOP_FOREGROUND_REMOVE)
                ioScope.launch {
                    lifecycleMutex.withLock { controller.stop() }
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                // 服务可能因用户停止 / 启动失败而处于未运行状态；startForegroundService
                // 启动后必须在 5 秒内调用 startForeground，否则会被系统直接杀死。
                startForegroundInternal()
                mainHandler.removeCallbacks(pendingRestart)
                mainHandler.postDelayed(pendingRestart, RESTART_DEBOUNCE_MS)
                updateNotification()
                return START_STICKY
            }

            else -> {
                mainHandler.removeCallbacks(pendingRestart)
                startForegroundInternal()
                ioScope.launch {
                    lifecycleMutex.withLock { controller.start() }
                    withContext(Dispatchers.Main) { updateNotification() }
                }
                return START_STICKY
            }
        }
    }

    private fun startForegroundInternal() {
        val notification = buildNotification()
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

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val status = controller.status.value
        val text = when {
            status.needsLocalNetworkPermission -> getString(R.string.mcp_lan_permission_required)
            status.error != null -> status.error
            status.addresses.isNotEmpty() -> status.addresses.first()
            else -> getString(R.string.mcp_notif_starting)
        }
        val contentIntent = NotificationIntents.contentIntent(
            context = this,
            route = com.benton.izukijs.ui.navigation.Routes.MCP,
            requestCode = 10,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, McpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(getString(R.string.mcp_notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.common_stop), stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mcp_notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mcp_notif_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "izuki_mcp_server"
        private const val NOTIFICATION_ID = NotificationIds.MCP_SERVER

        const val ACTION_START = "com.benton.izukijs.action.START_MCP"
        const val ACTION_STOP = "com.benton.izukijs.action.STOP_MCP"
        const val ACTION_RESTART = "com.benton.izukijs.action.RESTART_MCP"

        /** 重建监听的防抖窗口。 */
        private const val RESTART_DEBOUNCE_MS = 400L

        fun start(context: Context) = send(context, ACTION_START)

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun restart(context: Context) = send(context, ACTION_RESTART)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, McpServerService::class.java).setAction(action)
            runCatching {
                if (action == ACTION_STOP) {
                    context.startService(intent)
                } else {
                    context.startForegroundService(intent)
                }
            }
        }
    }
}
