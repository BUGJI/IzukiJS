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
import com.benton.izukijs.controller.record.RecordingIssue
import com.benton.izukijs.controller.record.UserRecordingOptions
import java.util.concurrent.Executors

/** 用户操作录制期间的前台服务：保持轮询与截图采样存活，并提供停止入口。 */
class UserRecordingService : Service() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "izuki-user-record-ctl").apply { isDaemon = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundInternal()
                val options = UserRecordingOptions(
                    name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "user-recording" },
                    captureScreenshots = intent.getBooleanExtra(EXTRA_CAPTURE_SCREENSHOTS, true),
                    sampleIntervalMs = intent.getLongExtra(EXTRA_SAMPLE_INTERVAL_MS, 700L),
                    captureContext = intent.getBooleanExtra(EXTRA_CAPTURE_CONTEXT, true),
                )
                val container = (application as IzukiApp).container
                worker.execute {
                    val started = try {
                        container.recordingSession.startUserRecording(options) != null
                    } catch (t: Throwable) {
                        container.recordingSession.reportIssue(RecordingIssue.START_FAILED)
                        false
                    }
                    if (!started) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }

            ACTION_STOP -> {
                val container = (application as IzukiApp).container
                worker.execute {
                    try {
                        container.recordingSession.stop()
                    } catch (t: Throwable) {
                        container.recordingSession.reportIssue(RecordingIssue.STOP_FAILED)
                    } finally {
                        // 无论停止是否异常都要撤掉前台通知，避免服务状态卡死。
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 服务被系统 / stopService 销毁时也必须结束录制，否则采集循环会在没有前台通知
        // 的情况下继续运行。shutdown() 会等待已提交的停止任务执行完。
        val session = (application as IzukiApp).container.recordingSession
        if (session.isUserRecording()) {
            runCatching { worker.execute { runCatching { session.stop() } } }
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startForegroundInternal() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(getString(R.string.user_record_notification_title))
            .setContentText(getString(R.string.user_record_notification_text))
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
            .addAction(0, getString(R.string.user_record_stop), stopIntent())
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

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, UserRecordingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.user_record_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.user_record_notification_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "izuki_user_record"
        private const val NOTIFICATION_ID = NotificationIds.USER_RECORDING
        private const val ACTION_START = "com.benton.izukijs.action.START_USER_RECORD"
        private const val ACTION_STOP = "com.benton.izukijs.action.STOP_USER_RECORD"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_CAPTURE_SCREENSHOTS = "capture_screenshots"
        private const val EXTRA_SAMPLE_INTERVAL_MS = "sample_interval_ms"
        private const val EXTRA_CAPTURE_CONTEXT = "capture_context"

        fun start(
            context: Context,
            name: String,
            captureScreenshots: Boolean = true,
            sampleIntervalMs: Long = 700L,
            captureContext: Boolean = true,
        ) {
            val intent = Intent(context, UserRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_CAPTURE_SCREENSHOTS, captureScreenshots)
                putExtra(EXTRA_SAMPLE_INTERVAL_MS, sampleIntervalMs)
                putExtra(EXTRA_CAPTURE_CONTEXT, captureContext)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UserRecordingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
