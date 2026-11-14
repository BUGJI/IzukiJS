package com.benton.izukijs.schedule

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** 定时任务 / 脚本编排共用的运行前条件检查。 */
class TriggerConditions(
    context: Context,
    private val foregroundProvider: () -> String?,
) : RunConditions {

    private val appContext = context.applicationContext

    override fun met(conditions: ScheduleConditions): Boolean =
        conditions.matches(
            object : ConditionProbe {
                override fun isCharging(): Boolean = this@TriggerConditions.isCharging()

                override fun isOnWifi(): Boolean = this@TriggerConditions.isOnWifi()

                override fun isLocked(): Boolean = this@TriggerConditions.isLocked()

                override fun foregroundPackage(): String? = foregroundProvider()
            },
        )

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
}

/** 条件求值所需的系统状态；只在对应条件启用时才会被调用（保持短路语义）。 */
internal interface ConditionProbe {
    fun isCharging(): Boolean

    fun isOnWifi(): Boolean

    fun isLocked(): Boolean

    fun foregroundPackage(): String?
}

internal fun ScheduleConditions.matches(probe: ConditionProbe): Boolean {
    if (charging && !probe.isCharging()) return false
    if (wifi && !probe.isOnWifi()) return false
    if (unlocked && probe.isLocked()) return false
    if (foregroundPackage.isNotBlank() && probe.foregroundPackage() != foregroundPackage) return false
    return true
}
