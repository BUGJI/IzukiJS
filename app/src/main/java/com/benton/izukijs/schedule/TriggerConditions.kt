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
) {

    private val appContext = context.applicationContext

    fun met(conditions: ScheduleConditions): Boolean {
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
}
