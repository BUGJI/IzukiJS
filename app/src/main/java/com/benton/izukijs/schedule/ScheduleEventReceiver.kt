package com.benton.izukijs.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benton.izukijs.IzukiApp

/**
 * 系统事件触发：充电、解锁；Wi-Fi 由 [ScheduleManager] 的网络回调统一投递过来。
 */
class ScheduleEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            ACTION_SYSTEM_EVENT -> intent.getStringExtra(EXTRA_EVENT)
                ?.let { runCatching { ScheduleEvent.valueOf(it) }.getOrNull() }

            Intent.ACTION_POWER_CONNECTED -> ScheduleEvent.CHARGING
            Intent.ACTION_USER_PRESENT -> ScheduleEvent.UNLOCK
            else -> null
        } ?: return

        val container = (context.applicationContext as IzukiApp).container
        container.scheduleRunner.onEvent(event)
    }

    companion object {
        const val ACTION_SYSTEM_EVENT = "com.benton.izukijs.action.SCHEDULE_EVENT"
        const val EXTRA_EVENT = "schedule_event"

        /** 供 [ScheduleManager] 的 Wi-Fi 回调投递事件；显式组件广播，无需静态过滤器。 */
        fun dispatch(context: Context, event: ScheduleEvent) {
            val intent = Intent(context, ScheduleEventReceiver::class.java).apply {
                action = ACTION_SYSTEM_EVENT
                putExtra(EXTRA_EVENT, event.name)
            }
            context.sendBroadcast(intent)
        }
    }
}
