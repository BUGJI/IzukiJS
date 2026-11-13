package com.benton.izukijs.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.benton.izukijs.R
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.notification.NotifyEvent
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.core.notification.NotifySource
import com.benton.izukijs.service.NotificationIds
import com.benton.izukijs.ui.navigation.Routes

/** 把通知事件投递到系统通知栏。 */
internal class LocalNotifier(
    private val context: Context,
    private val logBus: LogBus,
) {

    fun notify(event: NotifyEvent) {
        if (!canPostNotifications()) {
            throw NotifySendException("notification permission not granted")
        }
        val channelId = channelFor(event.level)
        ensureChannel(channelId)
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: throw NotifySendException("notification manager unavailable")
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(event.title)
            .setContentText(event.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.text))
            .setContentIntent(
                NotificationIntents.contentIntent(
                    context = context,
                    route = routeFor(event),
                    requestCode = notificationId(event),
                ),
            )
            .setAutoCancel(true)
            .setGroup("izuki_${event.kind.key}")
            .setPriority(
                when (event.level) {
                    NotifyLevel.ERROR -> NotificationCompat.PRIORITY_HIGH
                    NotifyLevel.WARN, NotifyLevel.SUCCESS -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_LOW
                },
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        manager.notify(notificationId(event), builder.build())
    }

    private fun notificationId(event: NotifyEvent): Int =
        NotificationIds.NOTIFY_BASE + (event.dedupeKey.hashCode() and 0x0FFF)

    /** 运行结果/脚本消息都落到日志页；MCP 来源可以直接打开 MCP 页面。 */
    private fun routeFor(event: NotifyEvent): String =
        if (event.source == NotifySource.MCP) Routes.MCP else Routes.TAB_LOGS

    private fun channelFor(level: NotifyLevel): String = when (level) {
        NotifyLevel.ERROR -> CHANNEL_ERROR
        NotifyLevel.WARN, NotifyLevel.SUCCESS -> CHANNEL_DEFAULT
        NotifyLevel.INFO -> CHANNEL_LOW
    }

    private fun ensureChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val (nameRes, descRes, importance) = when (channelId) {
            CHANNEL_ERROR -> Triple(
                R.string.notif_channel_high,
                R.string.notif_channel_high_desc,
                NotificationManager.IMPORTANCE_HIGH,
            )

            CHANNEL_LOW -> Triple(
                R.string.notif_channel_low,
                R.string.notif_channel_low_desc,
                NotificationManager.IMPORTANCE_LOW,
            )

            else -> Triple(
                R.string.notif_channel_default,
                R.string.notif_channel_default_desc,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        }
        val channel = NotificationChannel(
            channelId,
            context.getString(nameRes),
            importance,
        ).apply {
            description = context.getString(descRes)
            setShowBadge(channelId != CHANNEL_LOW)
        }
        // 已存在时也重新写入名称/描述，跟随应用语言切换。
        manager.createNotificationChannel(channel)
        // 旧版本所有级别共用一个渠道；迁移后删除，避免设置页残留一个不再使用的入口。
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val LEGACY_CHANNEL_ID = "izuki_notify"
        const val CHANNEL_LOW = "izuki_notify_low"
        const val CHANNEL_DEFAULT = "izuki_notify_default"
        const val CHANNEL_ERROR = "izuki_notify_error"
    }
}
