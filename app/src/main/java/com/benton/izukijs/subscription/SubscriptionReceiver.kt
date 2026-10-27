package com.benton.izukijs.subscription

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.benton.izukijs.IzukiApp

class SubscriptionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC) return
        val scriptId = intent.getStringExtra(EXTRA_ID) ?: return
        val container = (context.applicationContext as IzukiApp).container
        container.subscriptionManager.onAlarm(scriptId)
    }

    companion object {
        const val ACTION_SYNC = "com.benton.izukijs.action.SUBSCRIPTION_SYNC"
        const val EXTRA_ID = "subscription_script_id"
    }
}
