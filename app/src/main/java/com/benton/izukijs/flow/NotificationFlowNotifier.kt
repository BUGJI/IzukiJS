package com.benton.izukijs.flow

import android.content.Context
import com.benton.izukijs.R
import com.benton.izukijs.core.log.RunLogSink
import com.benton.izukijs.core.notification.NotifyEvent
import com.benton.izukijs.core.notification.NotifyKind
import com.benton.izukijs.core.notification.NotifyLevel
import com.benton.izukijs.core.notification.NotifySource
import com.benton.izukijs.core.notification.NotifyTemplate
import com.benton.izukijs.notification.NotificationDispatcher

/** 把编排摘要转换为本地 / 远程通知；Android 依赖集中在这里，[FlowRunner] 保持纯逻辑。 */
class NotificationFlowNotifier(
    context: Context,
    private val dispatcher: NotificationDispatcher,
    private val logBus: RunLogSink,
) : FlowNotifier {

    private val appContext = context.applicationContext

    override fun summary(
        flow: ScriptFlow,
        executed: Int,
        succeeded: Int,
        total: Int,
        allSucceeded: Boolean,
        durationMs: Long,
    ) {
        val statusKey = if (allSucceeded) "succeeded" else "failed"
        val level = when {
            allSucceeded -> NotifyLevel.SUCCESS
            succeeded > 0 -> NotifyLevel.WARN
            else -> NotifyLevel.ERROR
        }
        val title = appContext.getString(
            if (allSucceeded) R.string.notif_flow_finished_title else R.string.notif_flow_failed_title,
            flow.name,
        )
        val text = appContext.getString(
            R.string.notif_flow_text,
            succeeded,
            total,
            NotifyTemplate.formatDuration(durationMs),
        )
        val event = NotifyEvent(
            level = level,
            kind = NotifyKind.FLOW_SUMMARY,
            title = title,
            text = text,
            source = NotifySource.FLOW,
            scriptName = flow.name,
            runStatus = statusKey,
            durationMs = durationMs,
            tags = setOf(NotifyKind.FLOW_SUMMARY.key, statusKey),
            data = mapOf(
                "succeeded" to succeeded.toString(),
                "executed" to executed.toString(),
                "total" to total.toString(),
            ),
        )
        runCatching { dispatcher.dispatch(event) }.onFailure {
            logBus.warn("编排结果通知派发失败：${it.message}")
        }
    }
}
