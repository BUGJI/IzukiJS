package com.benton.izukijs.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.benton.izukijs.MainActivity

/** 通知点击后要落到的应用内路由。TAB_* 由主界面 pager 处理，其余交给 NavHost。 */
object NotificationIntents {

    const val EXTRA_ROUTE = "com.benton.izukijs.extra.NOTIFICATION_ROUTE"

    fun contentIntent(
        context: Context,
        route: String,
        requestCode: Int = 0,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ROUTE, route)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun routeFrom(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_ROUTE)?.takeIf { it.isNotBlank() }
}
