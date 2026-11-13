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
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.benton.izukijs.R
import com.benton.izukijs.notification.NotificationIntents
import com.benton.izukijs.controller.adb.AdbPairingDiscovery
import com.benton.izukijs.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无线调试配对辅助服务。
 *
 * 系统「使用配对码配对设备」对话框在切到其他 App 后通常会关闭，导致无法回到本 App 输入配对码。
 * 这里用一个前台通知承载「内联回复」：用户打开系统配对对话框后，下拉通知栏即可输入 6 位配对码，
 * 配对期间系统对话框保持在前台，配对服务不会被回收。
 */
class AdbPairingService : Service() {

    private val manager get() = applicationContext.appContainer.adbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pendingCode: String? = null
    private var pendingPort: Int? = null
    private var pairJob: Job? = null
    private var discoveryJob: Job? = null
    private var lastEndpoint: AdbPairingDiscovery.Endpoint? = null
    private var statusText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_REPLY -> {
                startForegroundInternal()
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)
                    ?.toString()
                    ?.trim()
                if (reply.isNullOrBlank()) {
                    updateNotification(getString(R.string.adb_notify_pairing_text))
                    return START_STICKY
                }
                val parsed = parseReply(reply)
                if (parsed == null) {
                    updateNotification(getString(R.string.adb_notify_invalid_reply))
                    return START_STICKY
                }
                pendingPort = parsed.first
                pendingCode = parsed.second
                maybePair()
                return START_STICKY
            }

            else -> {
                startForegroundInternal()
                startDiscovery()
                updateNotification(getString(R.string.adb_notify_pairing_text))
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { manager.pairingDiscovery.stop() }
        super.onDestroy()
    }

    private fun startDiscovery() {
        if (!manager.hasLocalNetworkPermission()) {
            updateNotification(getString(R.string.adb_notify_local_network_required))
            return
        }
        manager.pairingDiscovery.start()
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            manager.pairingDiscovery.endpoint.collect { endpoint ->
                lastEndpoint = endpoint
                if (pendingCode == null) {
                    statusText = if (endpoint == null) {
                        getString(R.string.adb_notify_waiting)
                    } else {
                        getString(R.string.adb_notify_hint, endpoint.port)
                    }
                    updateNotification(statusText)
                }
                maybePair()
            }
        }
    }

    private fun maybePair() {
        if (pairJob?.isActive == true) return
        if (!manager.hasLocalNetworkPermission()) {
            updateNotification(getString(R.string.adb_notify_local_network_required))
            return
        }
        val code = pendingCode ?: return
        val endpoint = lastEndpoint ?: manager.pairingDiscovery.endpoint.value
        val port = pendingPort ?: endpoint?.port ?: return
        val host = endpoint?.host ?: manager.preferredHost() ?: return
        pairJob = scope.launch {
            statusText = getString(R.string.adb_notify_pairing)
            updateNotification(statusText)
            val ok = manager.pairAndConnect(host, port, code)
            if (ok) {
                statusText = getString(R.string.adb_notify_success)
                updateNotification(statusText)
                delay(1_200)
                stopEverything()
            } else {
                pendingCode = null
                pendingPort = null
                pairJob = null
                statusText = getString(R.string.adb_notify_failed)
                updateNotification(statusText)
            }
        }
    }

    /**
     * 支持两种回复：
     * - `123456`：只输入配对码，端口由 mDNS 自动发现；
     * - `39815 123456` / `39815:123456`：端口 + 配对码，用于 mDNS 被网络隔离时。
     */
    private fun parseReply(text: String): Pair<Int?, String>? {
        val codeOnly = Regex("""^\d{6}$""")
        if (codeOnly.matches(text)) return null to text
        val withPort = Regex("""^(\d{1,5})[\s:]+(\d{6})$""").find(text) ?: return null
        val port = withPort.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return port to withPort.groupValues[2]
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

    private fun updateNotification(text: String) {
        statusText = text
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val text = statusText.ifBlank { getString(R.string.adb_notify_pairing_text) }
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyIntent = Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY)
        val replyPending = PendingIntent.getService(
            this,
            12,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel(getString(R.string.adb_notify_reply_label))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            0,
            getString(R.string.adb_notify_reply_action),
            replyPending,
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(getString(R.string.adb_notify_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                NotificationIntents.contentIntent(
                    context = this,
                    route = com.benton.izukijs.ui.navigation.Routes.ADB,
                    requestCode = 13,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(replyAction)
            .addAction(0, getString(R.string.adb_notify_stop), stop)
            .build()
    }

    private fun stopEverything() {
        runCatching { manager.pairingDiscovery.stop() }
        pairJob?.cancel()
        pairJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.adb_notify_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.adb_notify_channel_desc)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = NotificationIds.ADB_PAIRING
        private const val KEY_CODE = "pairing_code"
        private const val ACTION_REPLY = "com.benton.izukijs.action.ADB_PAIRING_REPLY"
        private const val ACTION_STOP = "com.benton.izukijs.action.ADB_PAIRING_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdbPairingService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AdbPairingService::class.java))
        }
    }
}
