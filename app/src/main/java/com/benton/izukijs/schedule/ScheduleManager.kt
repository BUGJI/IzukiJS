package com.benton.izukijs.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务调度。时间 / 间隔 / Cron 使用 AlarmManager；事件触发由 [ScheduleEventReceiver]
 * 与 Wi-Fi 网络回调驱动。支持条件过滤和失败重试。
 */
class ScheduleManager(
    context: Context,
    private val repository: ScheduleRepository,
    private val logBus: LogBus,
) {

    private val appContext = context.applicationContext

    private val alarmManager: AlarmManager =
        appContext.getSystemService(AlarmManager::class.java)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var eventReceiver: BroadcastReceiver? = null

    @Volatile
    private var wasOnWifi = false

    /** 注册 Wi-Fi / 充电 / 解锁事件监听；应在应用启动时调用。 */
    fun start() {
        if (networkCallback != null) return
        if (eventReceiver == null) {
            runCatching {
                val receiver = ScheduleEventReceiver()
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                eventReceiver = receiver
            }.onFailure { logBus.warn("充电 / 解锁事件监听注册失败: ${it.message}") }
        }

        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyWifiIfNeeded()

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = notifyWifiIfNeeded()

            override fun onLost(network: Network) {
                wasOnWifi = false
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            notifyWifiIfNeeded()
        }.onFailure { logBus.warn("Wi-Fi 事件监听注册失败: ${it.message}") }
    }

    private fun notifyWifiIfNeeded() {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return
        val onWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (onWifi && !wasOnWifi) {
            ScheduleEventReceiver.dispatch(appContext, ScheduleEvent.WIFI)
        }
        wasOnWifi = onWifi
    }

    fun schedule(schedule: Schedule) {
        val now = System.currentTimeMillis()
        val normalized = when (schedule.kind) {
            ScheduleKind.CRON -> {
                val next = CronExpression.parse(schedule.cron)?.nextAfter(now)
                if (next == null) {
                    logBus.error("Cron 表达式无效，未保存: ${schedule.cron}")
                    return
                }
                schedule.copy(triggerAtMillis = next)
            }

            ScheduleKind.TIME, ScheduleKind.INTERVAL -> schedule.copy(
                triggerAtMillis = schedule.triggerAtMillis.coerceAtLeast(now + 1_000L),
            )

            ScheduleKind.EVENT -> schedule
        }
        repository.upsert(normalized)
        if (normalized.kind == ScheduleKind.EVENT) {
            cancelAlarm(normalized.id)
            logBus.info("已注册事件触发: ${normalized.scriptName} (${eventLabel(normalized.event)})")
        } else {
            setAlarm(normalized, normalized.triggerAtMillis, retry = false)
            logBus.info("已安排定时: ${normalized.scriptName} @ ${formatTime(normalized.triggerAtMillis)}")
        }
    }

    fun cancel(id: String) {
        cancelAlarm(id)
        repository.delete(id)
    }

    /** 后台线程版本，避免 JSON 读写阻塞主线程 / 组合。 */
    suspend fun scheduleAsync(schedule: Schedule) = withContext(Dispatchers.IO) { schedule(schedule) }

    suspend fun cancelAsync(id: String) = withContext(Dispatchers.IO) { cancel(id) }

    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        repository.list().forEach { schedule ->
            when {
                schedule.retryAttempt > 0 && schedule.triggerAtMillis > 0 -> {
                    val at = schedule.triggerAtMillis.coerceAtLeast(now + 5_000L)
                    repository.upsert(schedule.copy(triggerAtMillis = at))
                    setAlarm(schedule, at, retry = true)
                }

                schedule.kind == ScheduleKind.EVENT -> Unit

                schedule.kind == ScheduleKind.CRON -> {
                    val next = CronExpression.parse(schedule.cron)?.nextAfter(now)
                    if (next == null) {
                        logBus.warn("跳过无效 Cron 定时: ${schedule.scriptName} (${schedule.cron})")
                    } else {
                        val updated = schedule.copy(triggerAtMillis = next)
                        repository.upsert(updated)
                        setAlarm(updated, next, retry = false)
                    }
                }

                else -> {
                    val at = schedule.triggerAtMillis.coerceAtLeast(now + 5_000L)
                    val updated = schedule.copy(triggerAtMillis = at)
                    repository.upsert(updated)
                    setAlarm(updated, at, retry = false)
                }
            }
        }
    }

    /** 脚本执行完成后的调度：成功则安排下一次，失败则重试或跳过。 */
    fun onFinished(schedule: Schedule, success: Boolean) {
        val now = System.currentTimeMillis()
        if (success) {
            val reset = schedule.copy(retryAttempt = 0)
            repository.upsert(reset)
            scheduleNext(reset, now)
            return
        }

        if (schedule.retryAttempt < schedule.retryCount) {
            val delayMs = schedule.retryDelayMinutes.coerceAtLeast(1L) * 60_000L
            val at = now + delayMs
            val retry = schedule.copy(
                retryAttempt = schedule.retryAttempt + 1,
                triggerAtMillis = at,
            )
            repository.upsert(retry)
            setAlarm(retry, at, retry = true)
            logBus.warn(
                "定时任务失败，${delayMs / 60_000} 分钟后重试 " +
                    "(${retry.retryAttempt}/${retry.retryCount}): ${schedule.scriptName}",
            )
        } else {
            logBus.error("定时任务失败且重试次数用尽: ${schedule.scriptName}")
            val reset = schedule.copy(retryAttempt = 0)
            repository.upsert(reset)
            scheduleNext(reset, now)
        }
    }

    /** 条件不满足时跳过本次，但仍安排下一次（不消耗重试次数）。 */
    fun onSkipped(schedule: Schedule) {
        val now = System.currentTimeMillis()
        val reset = schedule.copy(retryAttempt = 0)
        repository.upsert(reset)
        scheduleNext(reset, now)
    }

    private fun scheduleNext(schedule: Schedule, now: Long) {
        when (schedule.kind) {
            ScheduleKind.TIME -> repository.delete(schedule.id)
            ScheduleKind.EVENT -> Unit
            ScheduleKind.INTERVAL -> {
                val next = now + schedule.intervalMinutes.coerceAtLeast(1L) * 60_000L
                val updated = schedule.copy(triggerAtMillis = next)
                repository.upsert(updated)
                setAlarm(updated, next, retry = false)
            }

            ScheduleKind.CRON -> {
                val next = CronExpression.parse(schedule.cron)?.nextAfter(now)
                if (next == null) {
                    logBus.warn("Cron 定时无法计算下次触发，已停用: ${schedule.scriptName}")
                } else {
                    val updated = schedule.copy(triggerAtMillis = next)
                    repository.upsert(updated)
                    setAlarm(updated, next, retry = false)
                }
            }
        }
    }

    private fun setAlarm(schedule: Schedule, triggerAt: Long, retry: Boolean) {
        val pendingIntent = pendingIntent(schedule.id, retry)
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
    }

    private fun cancelAlarm(id: String) {
        alarmManager.cancel(pendingIntent(id, retry = false))
        alarmManager.cancel(pendingIntent(id, retry = true))
    }

    private fun pendingIntent(id: String, retry: Boolean): PendingIntent {
        val intent = Intent(appContext, ScheduleReceiver::class.java).apply {
            action = ScheduleReceiver.ACTION_FIRE
            putExtra(ScheduleReceiver.EXTRA_ID, id)
            putExtra(ScheduleReceiver.EXTRA_RETRY, retry)
        }
        return PendingIntent.getBroadcast(
            appContext,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun eventLabel(event: ScheduleEvent?): String = when (event) {
        ScheduleEvent.CHARGING -> "充电"
        ScheduleEvent.WIFI -> "Wi-Fi 连接"
        ScheduleEvent.UNLOCK -> "解锁"
        null -> "未知事件"
    }
}
