package com.benton.izukijs.schedule

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.runtime.ScriptExecutionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 定时任务执行器：检查条件、读取脚本、交给 [ScriptExecutionManager] 执行，
 * 并在结束后由 [ScheduleManager] 安排下一次 / 重试。
 */
class ScheduleRunner(
    context: Context,
    private val repository: ScheduleRepository,
    private val scriptRepository: ScriptRepository,
    private val scheduleManager: ScheduleManager,
    private val executionManager: ScriptExecutionManager,
    private val foregroundProvider: () -> String?,
    private val logBus: LogBus,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventMutex = Mutex()

    fun run(schedule: Schedule) {
        scope.launch { runInternal(schedule) }
    }

    fun onEvent(event: ScheduleEvent) {
        scope.launch {
            eventMutex.withLock {
                val now = System.currentTimeMillis()
                repository.list()
                    .filter { it.kind == ScheduleKind.EVENT && it.event == event }
                    .filter { now - it.lastTriggeredAtMillis >= EVENT_DEBOUNCE_MS }
                    .forEach { schedule ->
                        val updated = schedule.copy(lastTriggeredAtMillis = now)
                        repository.upsert(updated)
                        logBus.info("⚡ 事件触发: ${schedule.scriptName} (${event.name.lowercase()})")
                        runInternal(updated)
                    }
            }
        }
    }

    private fun runInternal(schedule: Schedule) {
        if (executionManager.running.value) {
            logBus.warn("定时任务跳过：已有脚本正在运行（${schedule.scriptName}）")
            scheduleManager.onSkipped(schedule)
            return
        }
        if (!conditionsMet(schedule)) {
            logBus.info("定时条件未满足，跳过本次: ${schedule.scriptName}")
            scheduleManager.onSkipped(schedule)
            return
        }
        val script = scriptRepository.find(schedule.scriptId)
        if (script == null) {
            logBus.warn("定时任务的脚本不存在，已移除: ${schedule.scriptName}")
            scheduleManager.cancel(schedule.id)
            return
        }
        val now = System.currentTimeMillis()
        val updated = schedule.copy(lastTriggeredAtMillis = now)
        repository.upsert(updated)
        logBus.info("⏰ 定时触发: ${script.name}")
        val started = executionManager.run(
            scriptName = script.name,
            source = scriptRepository.read(script),
            onFinished = { success -> scheduleManager.onFinished(updated, success) },
        )
        if (!started) {
            logBus.warn("定时任务未能启动（已有脚本运行）: ${schedule.scriptName}")
            scheduleManager.onSkipped(schedule)
        }
    }

    private fun conditionsMet(schedule: Schedule): Boolean {
        val conditions = schedule.conditions
        if (conditions.charging && !isCharging()) return false
        if (conditions.wifi && !isOnWifi()) return false
        if (conditions.unlocked && isLocked()) return false
        if (conditions.foregroundPackage.isNotBlank() &&
            foregroundProvider() != conditions.foregroundPackage
        ) {
            return false
        }
        return true
    }

    private fun isCharging(): Boolean = runCatching {
        val status = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    private fun isOnWifi(): Boolean = runCatching {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private fun isLocked(): Boolean = runCatching {
        val manager = appContext.getSystemService(KeyguardManager::class.java) ?: return true
        manager.isKeyguardLocked
    }.getOrDefault(true)

    private companion object {
        /** 事件触发去抖：同一任务 60 秒内只执行一次。 */
        const val EVENT_DEBOUNCE_MS = 60_000L
    }
}
