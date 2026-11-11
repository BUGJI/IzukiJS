package com.benton.izukijs.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.benton.izukijs.MainActivity
import com.benton.izukijs.R
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.notification.NotifyEvent
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.service.NotificationIds

/** 把通知事件投递到系统通知栏。 */
internal class LocalNotifier(
    private val context: Context,
    private val logBus: LogBus,
) {

    fun notify(event: NotifyEvent) {
        if (!canPostNotifications()) {
            throw NotifySendException("notification permission not granted")
        }
        ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: throw NotifySendException("notification manager unavailable")
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_script)
            .setContentTitle(event.title)
            .setContentText(event.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.text))
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setPriority(
                when (event.level) {
                    NotifyLevel.ERROR -> NotificationCompat.PRIORITY_HIGH
                    NotifyLevel.WARN -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_LOW
                },
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        manager.notify(notificationId(event), builder.build())
    }

    private fun notificationId(event: NotifyEvent): Int =
        NotificationIds.NOTIFY_BASE + (event.dedupeKey.hashCode() and 0x0FFF)

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_event_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_event_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val CHANNEL_ID = "izuki_notify"
    }
}
