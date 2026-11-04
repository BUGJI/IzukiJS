package com.benton.izukijs.service

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
import com.benton.izukijs.IzukiApp
import com.benton.izukijs.MainActivity
import com.benton.izukijs.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 锁屏录制解锁动作期间的前台服务：保持进程存活，并提示用户完成解锁。 */
class UnlockRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundInternal()
                scope.launch {
                    (application as IzukiApp).container.unlockGestureRecorder.start()
                    stopSelf()
                }
            }

            ACTION_CANCEL -> {
                (application as IzukiApp).container.unlockGestureRecorder.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(getString(R.string.unlock_record_notification_title))
            .setContentText(getString(R.string.unlock_record_notification_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.unlock_record_cancel), cancelIntent())
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

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, UnlockRecordingService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.unlock_record_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.unlock_record_notification_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "izuki_unlock_record"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.benton.izukijs.action.START_UNLOCK_RECORD"
        private const val ACTION_CANCEL = "com.benton.izukijs.action.CANCEL_UNLOCK_RECORD"

        fun start(context: Context) {
            val intent = Intent(context, UnlockRecordingService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }
    }
}
