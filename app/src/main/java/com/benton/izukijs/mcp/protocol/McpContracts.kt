package com.benton.izukijs.mcp.protocol

import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.mcp.resources.ResourceContent
import com.benton.izukijs.mcp.tools.McpTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 协议层依赖的最小契约。
 *
 * 处理器只依赖这些接口，不直接依赖工具注册表 / 资源实现 / 文件审计日志，
 * 这样协议链路可以用假目录与假资源做确定性测试，也避免把 Android 依赖带进协议层。
 */
interface McpToolCatalog {
    fun tools(): List<McpTool>

    fun find(name: String): McpTool?
}

interface McpResourceProvider {
    fun listJson(): JSONArray

    fun templatesJson(): JSONArray

    fun read(uri: String): ResourceContent?
}

interface McpAuditSink {
    fun record(
        tool: String,
        arguments: JSONObject,
        ok: Boolean,
        message: String? = null,
    )
}

/** 协议层只需要的日志操作，便于在 JVM 测试中收集日志而不触发 logcat。 */
interface McpLogSink {
    fun debug(message: String)

    fun info(message: String)

    fun warn(message: String)
}

class LogBusMcpLogSink(private val logBus: LogBus) : McpLogSink {

    override fun debug(message: String) = logBus.debug(message)

    override fun info(message: String) = logBus.info(message)

    override fun warn(message: String) = logBus.warn(message)
}
