package com.benton.izukijs.core.log

/**
 * 编排 / 定时执行器只需要的最小日志面。
 *
 * 独立成接口后，执行器可以脱离 Android `LogBus` 做 JVM 测试；
 * 生产环境用 [LogBusRunLogSink] 适配。
 */
interface RunLogSink {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String)

    fun error(message: String)

    fun success(message: String)
}

class LogBusRunLogSink(private val logBus: LogBus) : RunLogSink {

    override fun debug(message: String) = logBus.debug(message)

    override fun info(message: String) = logBus.info(message)

    override fun warn(message: String) = logBus.warn(message)

    override fun error(message: String) = logBus.error(message)

    override fun success(message: String) = logBus.success(message)
}
