package com.benton.izukijs.flow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benton.izukijs.IzukiApp

class FlowReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val container = (context.applicationContext as IzukiApp).container
        val flow = container.flowRepository.get(id) ?: return
        container.flowRunner.run(flow)
    }

    companion object {
        const val ACTION_FIRE = "com.benton.izukijs.action.FLOW_FIRE"
        const val EXTRA_ID = "flow_id"
    }
}
