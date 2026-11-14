package com.benton.izukijs.flow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.time.formatMonthDayTime
import com.benton.izukijs.schedule.CronExpression
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 脚本编排调度。时间 / 间隔 / Cron 使用 AlarmManager；事件触发由现有系统事件接收器
 * 转交给 [FlowRunner]。
 */
class FlowManager(
    context: Context,
    private val repository: FlowRepository,
    private val logBus: LogBus,
) : FlowScheduler {

    private val appContext = context.applicationContext

    private val alarmManager: AlarmManager =
        appContext.getSystemService(AlarmManager::class.java)

    fun schedule(flow: ScriptFlow) {
        if (!flow.enabled) {
            cancelAlarm(flow.id)
            repository.upsert(flow)
            logBus.info("编排已停用: ${flow.name}")
            return
        }
        val now = System.currentTimeMillis()
        val normalized = when (flow.kind) {
            ScheduleKind.CRON -> {
                val next = CronExpression.parse(flow.cron)?.nextAfter(now)
                if (next == null) {
                    logBus.error("Cron 表达式无效，未保存编排: ${flow.cron}")
                    return
                }
                flow.copy(triggerAtMillis = next)
            }

            ScheduleKind.TIME, ScheduleKind.INTERVAL -> flow.copy(
                triggerAtMillis = flow.triggerAtMillis.coerceAtLeast(now + 1_000L),
            )

            ScheduleKind.EVENT -> flow
        }
        repository.upsert(normalized)
        if (normalized.kind == ScheduleKind.EVENT) {
            cancelAlarm(normalized.id)
            logBus.info("已注册编排事件触发: ${normalized.name} (${eventLabel(normalized.event)})")
        } else {
            setAlarm(normalized, normalized.triggerAtMillis)
            logBus.info("已安排编排: ${normalized.name} @ ${formatTime(normalized.triggerAtMillis)}")
        }
    }

    fun cancel(id: String) {
        cancelAlarm(id)
        repository.delete(id)
    }

    suspend fun scheduleAsync(flow: ScriptFlow) = withContext(Dispatchers.IO) { schedule(flow) }

    suspend fun cancelAsync(id: String) = withContext(Dispatchers.IO) { cancel(id) }

    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        repository.list().forEach { flow ->
            if (!flow.enabled) {
                cancelAlarm(flow.id)
                return@forEach
            }
            when (flow.kind) {
                ScheduleKind.EVENT -> Unit

                ScheduleKind.CRON -> {
                    val next = CronExpression.parse(flow.cron)?.nextAfter(now)
                    if (next == null) {
                        logBus.warn("跳过无效 Cron 编排: ${flow.name} (${flow.cron})")
                    } else {
                        val updated = flow.copy(triggerAtMillis = next)
                        repository.upsert(updated)
                        setAlarm(updated, next)
                    }
                }

                else -> {
                    val at = flow.triggerAtMillis.coerceAtLeast(now + 5_000L)
                    val updated = flow.copy(triggerAtMillis = at)
                    repository.upsert(updated)
                    setAlarm(updated, at)
                }
            }
        }
    }

    /** 编排执行完成后的调度：成功与否都安排下一次；一次性编排直接移除。 */
    override fun onFinished(flow: ScriptFlow, success: Boolean) {
        val current = repository.get(flow.id) ?: return
        if (!current.enabled) {
            cancelAlarm(current.id)
            return
        }
        if (!success) {
            logBus.warn("编排存在失败步骤: ${current.name}")
        }
        scheduleNext(current, System.currentTimeMillis())
    }

    /** 条件不满足或已有脚本运行导致跳过时，仍安排下一次。 */
    override fun onSkipped(flow: ScriptFlow) {
        val current = repository.get(flow.id) ?: return
        if (!current.enabled) {
            cancelAlarm(current.id)
            return
        }
        scheduleNext(current, System.currentTimeMillis())
    }

    private fun scheduleNext(flow: ScriptFlow, now: Long) {
        when (flow.kind) {
            ScheduleKind.TIME -> repository.delete(flow.id)
            ScheduleKind.EVENT -> Unit
            ScheduleKind.INTERVAL -> {
                val next = now + flow.intervalMinutes.coerceAtLeast(1L) * 60_000L
                val updated = flow.copy(triggerAtMillis = next)
                repository.upsert(updated)
                setAlarm(updated, next)
            }

            ScheduleKind.CRON -> {
                val next = CronExpression.parse(flow.cron)?.nextAfter(now)
                if (next == null) {
                    logBus.warn("Cron 编排无法计算下次触发，已停用: ${flow.name}")
                } else {
                    val updated = flow.copy(triggerAtMillis = next)
                    repository.upsert(updated)
                    setAlarm(updated, next)
                }
            }
        }
    }

    private fun setAlarm(flow: ScriptFlow, triggerAt: Long) {
        val pendingIntent = pendingIntent(flow.id)
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
        alarmManager.cancel(pendingIntent(id))
    }

    private fun pendingIntent(id: String): PendingIntent {
        val intent = Intent(appContext, FlowReceiver::class.java).apply {
            action = FlowReceiver.ACTION_FIRE
            putExtra(FlowReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            appContext,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun formatTime(millis: Long): String = formatMonthDayTime(millis)

    private fun eventLabel(event: ScheduleEvent?): String = when (event) {
        ScheduleEvent.CHARGING -> "充电"
        ScheduleEvent.WIFI -> "Wi-Fi 连接"
        ScheduleEvent.UNLOCK -> "解锁"
        null -> "未知事件"
    }
}
