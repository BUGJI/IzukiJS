package com.benton.izukijs.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benton.izukijs.IzukiApp

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val container = (context.applicationContext as IzukiApp).container
        val schedule = container.scheduleRepository.get(id) ?: return

        val script = container.scriptRepository.find(schedule.scriptId)
        if (script != null) {
            container.logBus.info("⏰ 定时触发: ${script.name}")
            container.scriptExecutionManager.run(
                script.name,
                container.scriptRepository.read(script),
            )
        } else {
            container.logBus.warn("定时任务的脚本不存在: ${schedule.scriptName}")
        }

        if (schedule.intervalMinutes > 0) {
            container.scheduleManager.schedule(
                schedule.copy(
                    triggerAtMillis =
                        System.currentTimeMillis() + schedule.intervalMinutes * 60_000L,
                ),
            )
        } else {
            container.scheduleRepository.delete(id)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.benton.izukijs.action.SCHEDULE_FIRE"
        const val EXTRA_ID = "schedule_id"
    }
}
