package com.benton.izukijs.controller.record

/**
 * 录制期间临时屏蔽内部动作。
 *
 * 用户录制为了读取命中控件会调用 `uiautomator dump` 等内部 Shell 命令；
 * 这些命令不是用户操作，不应污染时间线。这里改成按线程作用域抑制：
 * 采集触摸的 `getevent` / `test -r` 等命令会持续数秒，若用进程级计数会把
 * 并发脚本的 Shell 动作也误吞掉。
 *
 * 跨线程的内部命令（例如 `UiautomatorDumper` 的 dump 工作线程）由调用方
 * 在对应线程内使用 [withoutRecording] 包裹；不要依赖命令前缀做全局黑名单。
 */
object RecordingSuppression {

    private val depth = ThreadLocal.withInitial { 0 }

    fun isSuppressed(): Boolean = (depth.get() ?: 0) > 0

    fun <T> withoutRecording(block: () -> T): T {
        val current = depth.get() ?: 0
        depth.set(current + 1)
        return try {
            block()
        } finally {
            if (current == 0) depth.remove() else depth.set(current)
        }
    }
}
