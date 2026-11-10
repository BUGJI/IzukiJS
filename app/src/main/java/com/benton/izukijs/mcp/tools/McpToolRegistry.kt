package com.benton.izukijs.mcp.tools

import com.benton.izukijs.ai.AgentTools
import com.benton.izukijs.ai.ToolPolicy
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.mcp.core.McpConfig
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import org.json.JSONObject

class McpToolRegistry(
    internal val bundle: DeviceApiBundle,
    internal val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    internal val scriptRepository: ScriptRepository,
    internal val executionManager: ScriptExecutionManager,
    internal val operations: OperationRegistry,
    internal val logFileStore: LogFileStore,
    internal val configProvider: () -> McpConfig,
    internal val virtualScreen: VirtualScreenManager? = null,
    internal val screenUnlock: ScreenUnlockController? = null,
) {

    @Volatile
    private var cachedConfig: McpConfig? = null

    @Volatile
    private var cachedTools: List<McpTool> = emptyList()

    fun tools(): List<McpTool> {
        val config = configProvider()
        if (config != cachedConfig) {
            synchronized(this) {
                if (config != cachedConfig) {
                    cachedTools = buildCatalog(config)
                    cachedConfig = config
                }
            }
        }
        return cachedTools
    }

    fun find(name: String): McpTool? = tools().firstOrNull { it.name == name }

    /** 服务启动后在后台预热工具目录，避免首个 tools/list 或 tools/call 承担构建开销。 */
    fun prewarm() {
        runCatching { tools() }
    }

    private fun buildCatalog(config: McpConfig): List<McpTool> {
        val agentTools = AgentTools(
            inputApi = bundle.inputApi,
            selectorApi = bundle.selectorApi,
            ocrApi = bundle.ocrApi,
            appApi = bundle.appApi,
            shellApi = bundle.shellApi,
            deviceApi = bundle.deviceApi,
            notificationApi = bundle.notificationApi,
            screenshotPathProvider = { null },
            nodeTreeProvider = nodeTreeProvider,
            policy = ToolPolicy(
                allowShell = config.allowShell,
                allowDangerousShell = config.allowDangerousShell,
                blockedPackages = config.blockedPackages,
            ),
        )
        val catalog = buildList {
            addAll(sharedTools(agentTools))
            add(screenshotTool())
            virtualScreen?.let { manager ->
                add(virtualScreenTool(manager, config))
                add(virtualScreenInputTool(manager))
            }
            add(readLogsTool())
            add(getRunLogTool())
            add(getResultTool())
            add(listOperationsTool())
            if (config.allowUnlock && screenUnlock != null) add(unlockTool())
            if (config.allowScripts) addAll(scriptTools())
        }
        if (!config.autoUnlock || screenUnlock == null) return catalog
        return catalog.map { tool ->
            if (tool.name in AUTO_UNLOCK_TOOLS) withAutoUnlock(tool) else tool
        }
    }

    // ---- 复用 AgentTools 的工具 ----

    private fun sharedTools(agentTools: AgentTools): List<McpTool> =
        agentTools.specs()
            .filter { it.name != "screenshot" }
            .map { spec ->
                McpTool(
                    name = spec.name,
                    description = spec.description,
                    inputSchema = if (spec.name in TARGETED_SHARED) {
                        withTargetParam(spec.parameters)
                    } else {
                        spec.parameters
                    },
                    readOnly = spec.name in READ_ONLY_SHARED,
                ) { args -> runShared(agentTools, spec.name, args) }
            }

    /** MCP 侧额外支持 per-call `target=main|virtual` 的共用工具。 */
    private fun withTargetParam(schema: JSONObject): JSONObject {
        val copy = JSONObject(schema.toString())
        copy.getJSONObject("properties").put(
            "target",
            McpSchemas.enum("读取目标屏幕；省略时跟随当前脚本目标", listOf("main", "virtual")),
        )
        return copy
    }

    /** 共用工具的执行入口；安全护栏（危险 shell / 敏感 App）已在 [AgentTools] 内部统一处理。 */
    private fun runShared(
        agentTools: AgentTools,
        name: String,
        args: JSONObject,
    ): McpToolResult {
        if (args.optString("target").equals("virtual", ignoreCase = true)) {
            when (name) {
                "ocr" -> return virtualOcr()
                "ui_dump" -> return McpToolResult.error(
                    "后台虚拟屏不支持控件树，请改用 capture / ocr / 坐标输入",
                )
                "find" -> return McpToolResult.error(
                    "虚拟屏暂不支持 find，请先用 ocr(target=virtual) 获取文字坐标，再按坐标输入",
                )
            }
        }
        return McpToolResult.text(agentTools.execute(name, args.toString()))
    }

    private fun virtualOcr(): McpToolResult {
        val bitmap = bundle.captureProvider(ScreenTarget.VIRTUAL, CAPTURE_WAIT_MS)
            ?: return McpToolResult.error(
                "虚拟屏未运行，或已启动但尚未收到画面帧；请先 start 并 launch 一个应用后重试",
            )
        val json = bundle.ocrApi.recognizeBlocksJson(bitmap)
            ?: return McpToolResult.error("OCR 未识别到文字")
        return McpToolResult.text(json)
    }

    internal fun blocked(message: String): McpToolResult = McpToolResult.text(
        JSONObject().apply {
            put("ok", false)
            put("blocked", true)
            put("message", message)
        }.toString(),
    )
}
