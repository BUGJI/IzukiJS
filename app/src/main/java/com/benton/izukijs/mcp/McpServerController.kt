package com.benton.izukijs.mcp

import com.benton.izukijs.mcp.net.HttpServer
import com.benton.izukijs.mcp.protocol.McpProtocolHandler
import com.benton.izukijs.mcp.resources.McpResources
import com.benton.izukijs.mcp.tools.McpToolRegistry
import com.benton.izukijs.mcp.tools.OperationRegistry
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** MCP 服务运行状态快照。 */
data class McpStatus(
    val running: Boolean = false,
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    val sessions: Int = 0,
    val error: String? = null,
)

/**
 * MCP 服务生命周期管理器：持有 socket 服务器与工具线程池，对外暴露 [status]。
 *
 * 具体启动 / 停止由前台服务 [com.benton.izukijs.service.McpServerService] 触发，
 * 以便在服务常驻期间保持进程存活。
 */
class McpServerController(
    private val logBus: LogBus,
    private val configProvider: () -> McpConfig,
    private val registryFactory: (McpConfig, OperationRegistry) -> McpToolRegistry,
    private val resourcesFactory: () -> McpResources,
    private val serverVersion: String,
) {

    private val _status = MutableStateFlow(McpStatus())
    val status: StateFlow<McpStatus> = _status.asStateFlow()

    /** 异步操作登记表；跨重启保留，便于客户端重连后回查结果。 */
    private val operations = OperationRegistry()

    private var server: HttpServer? = null
    private var handler: McpHttpHandler? = null
    private var toolDispatcher: ExecutorService? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val config = configProvider()
        if (!config.isConfigured) {
            _status.value = McpStatus(error = "未配置访问令牌")
            return
        }

        val registry = registryFactory(config, operations)
        val resources = resourcesFactory()
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "izuki-mcp-tools").apply { isDaemon = true }
        }
        val protocol = McpProtocolHandler(registry, resources, operations, logBus, dispatcher, serverVersion)
        val httpHandler = McpHttpHandler(protocol, config.token, logBus)
        val bindAddress = if (config.bindMode == McpBindMode.LAN) LAN_BIND else LOOPBACK_BIND

        val httpServer = HttpServer(bindAddress, config.port) { request ->
            val response = httpHandler.handle(request)
            _status.value = _status.value.copy(sessions = httpHandler.sessionCount)
            response
        }

        val port = try {
            httpServer.start()
            httpServer.localPort
        } catch (t: Throwable) {
            logBus.error("MCP 服务启动失败：${t.message}")
            runCatching { httpServer.stop() }
            runCatching { dispatcher.shutdownNow() }
            _status.value = McpStatus(error = "启动失败：${t.message}")
            return
        }

        server = httpServer
        handler = httpHandler
        toolDispatcher = dispatcher

        val urls = buildUrls(config.bindMode, port)
        _status.value = McpStatus(running = true, port = port, addresses = urls)
        logBus.success("🔌 MCP 服务已启动：${urls.joinToString(", ")}")
    }

    @Synchronized
    fun stop() {
        val wasRunning = server != null || toolDispatcher != null
        runCatching { server?.stop() }
        runCatching { toolDispatcher?.shutdownNow() }
        server = null
        handler = null
        toolDispatcher = null
        _status.value = McpStatus()
        if (wasRunning) logBus.info("🔌 MCP 服务已停止")
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    private fun buildUrls(mode: McpBindMode, port: Int): List<String> {
        val endpoint = McpHttpHandler.ENDPOINT
        if (mode == McpBindMode.LOCALHOST) return listOf("http://127.0.0.1:$port$endpoint")
        val lan = lanAddresses().map { "http://$it:$port$endpoint" }
        return if (lan.isEmpty()) listOf("http://127.0.0.1:$port$endpoint") else lan
    }

    /** 枚举局域网内可用的 IPv4 站点本地地址，供 UI 展示连接地址。 */
    private fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private companion object {
        const val LAN_BIND = "0.0.0.0"
        const val LOOPBACK_BIND = "127.0.0.1"
    }
}
