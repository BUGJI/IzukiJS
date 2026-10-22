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
        container.scheduleRunner.run(schedule)
    }

    companion object {
        const val ACTION_FIRE = "com.benton.izukijs.action.SCHEDULE_FIRE"
        const val EXTRA_ID = "schedule_id"
        const val EXTRA_RETRY = "schedule_retry"
    }
}
