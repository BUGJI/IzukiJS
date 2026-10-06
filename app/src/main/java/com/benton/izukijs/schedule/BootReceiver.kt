package com.benton.izukijs.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benton.izukijs.IzukiApp

/**
 * 开机后重新注册所有定时任务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as IzukiApp).container
        container.scheduleManager.rescheduleAll()
    }
}
