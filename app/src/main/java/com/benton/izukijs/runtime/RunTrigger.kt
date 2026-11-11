package com.benton.izukijs.runtime

import com.benton.izukijs.core.notification.NotifyKind
import com.benton.izukijs.core.notification.NotifySource

/** 一次脚本运行的触发来源，用于区分手动 / 自动并生成结果通知。 */
enum class RunTrigger {
    MANUAL,
    TILE,
    SCHEDULE,
    FLOW,
    MCP,
    ;

    val source: NotifySource
        get() = when (this) {
            MANUAL -> NotifySource.MANUAL
            TILE -> NotifySource.TILE
            SCHEDULE -> NotifySource.SCHEDULE
            FLOW -> NotifySource.FLOW
            MCP -> NotifySource.MCP
        }

    val kind: NotifyKind
        get() = if (source.automatic) NotifyKind.RUN_AUTO else NotifyKind.RUN_MANUAL
}
