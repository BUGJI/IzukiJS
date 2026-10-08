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
import androidx.core.app.NotificationCompat
import com.benton.izukijs.MainActivity
import com.benton.izukijs.R
import com.benton.izukijs.di.appContainer

/**
 * MCP 服务的前台服务：在服务运行期间保活进程并显示常驻通知（含访问地址与停止入口）。
 *
 * 启停由 UI 触发；[ACTION_RESTART] 用于配置变更（端口 / 绑定 / 工具开关）后重建监听。
 */
class McpServerService : Service() {

    private val controller get() = applicationContext.appContainer.mcpServerController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> controller.restart()

            else -> {
                startForegroundInternal()
                controller.start()
            }
        }
        updateNotification()
        return START_STICKY
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
            status.error != null -> status.error
            status.addresses.isNotEmpty() -> status.addresses.first()
            else -> getString(R.string.mcp_notif_starting)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
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
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.benton.izukijs.action.START_MCP"
        const val ACTION_STOP = "com.benton.izukijs.action.STOP_MCP"
        const val ACTION_RESTART = "com.benton.izukijs.action.RESTART_MCP"

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
