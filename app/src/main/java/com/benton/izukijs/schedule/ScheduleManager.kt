package com.benton.izukijs.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务调度。使用 AlarmManager，支持精确/非精确自动降级。
 */
class ScheduleManager(
    context: Context,
    private val repository: ScheduleRepository,
    private val logBus: LogBus,
) {

    private val appContext = context.applicationContext

    private val alarmManager: AlarmManager =
        appContext.getSystemService(AlarmManager::class.java)

    fun schedule(schedule: Schedule) {
        repository.upsert(schedule)
        val pendingIntent = pendingIntent(schedule)
        val triggerAt = schedule.triggerAtMillis
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        } catch (t: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        logBus.info("已安排定时: ${schedule.scriptName} @ ${formatTime(triggerAt)}")
    }

    fun cancel(id: String) {
        val schedule = repository.get(id) ?: return
        alarmManager.cancel(pendingIntent(schedule))
        repository.delete(id)
    }

    /** 后台线程版本，避免 JSON 读写阻塞主线程 / 组合。 */
    suspend fun scheduleAsync(schedule: Schedule) = withContext(Dispatchers.IO) { schedule(schedule) }

    suspend fun cancelAsync(id: String) = withContext(Dispatchers.IO) { cancel(id) }

    fun rescheduleAll() {
        repository.list().forEach { schedule(it) }
    }

    private fun pendingIntent(schedule: Schedule): PendingIntent {
        val intent = Intent(appContext, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_FIRE
            putExtra(ScheduleReceiver.EXTRA_ID, schedule.id)
        }
        return PendingIntent.getBroadcast(
            appContext,
            schedule.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
