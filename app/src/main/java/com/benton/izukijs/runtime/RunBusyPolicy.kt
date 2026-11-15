package com.benton.izukijs.runtime

/**
 * 已有脚本运行时，新启动请求的处理方式。
 *
 * 默认保持历史行为（拒绝），避免改变定时任务 / 编排 / MCP 的既有语义。
 */
enum class RunBusyPolicy(val key: String) {
    /** 直接拒绝，并提示当前运行中的脚本。 */
    REJECT("reject"),

    /** 只保留一个待执行项；当前脚本结束后自动启动。 */
    QUEUE("queue"),

    /** 中断当前脚本；若超时仍未退出，则放弃等待并启动新脚本。 */
    TERMINATE("terminate"),
    ;

    companion object {
        fun fromKey(key: String?): RunBusyPolicy =
            entries.firstOrNull { it.key == key } ?: REJECT
    }
}
