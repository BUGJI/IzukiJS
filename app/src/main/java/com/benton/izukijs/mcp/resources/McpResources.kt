package com.benton.izukijs.mcp.resources

import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.mcp.core.McpAuditLog
import com.benton.izukijs.mcp.protocol.McpResourceProvider
import com.benton.izukijs.mcp.tools.ScreenshotEncoder
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import org.json.JSONArray
import org.json.JSONObject

/** 一条 MCP 资源或资源模板的内容；二选一使用 [text] 或 [blob]（Base64）。 */
data class ResourceContent(
    val uri: String,
    val mimeType: String,
    val text: String = "",
    val blob: String? = null,
)

/**
 * 暴露给 MCP 客户端的只读资源：设备信息、运行状态、脚本列表与源码、最近日志、
 * 以及仓库 `docs/` 镜像的开发文档（[docs] 为空时省略后者）。
 */
class McpResources(
    private val bundle: DeviceApiBundle,
    private val controllers: ControllerManager,
    private val scriptRepository: ScriptRepository,
    private val executionManager: ScriptExecutionManager,
    private val logFileStore: LogFileStore,
    private val auditLog: McpAuditLog,
    private val docs: McpDocCatalog? = null,
) : McpResourceProvider {

    private data class Definition(
        val uri: String,
        val name: String,
        val description: String,
        val mimeType: String,
    )

    private val definitions = buildList {
        add(Definition("izuki://device", "设备信息", "屏幕尺寸、机型、Android 版本与电量", JSON))
        add(Definition("izuki://status", "运行状态", "各控制后端就绪状态与当前运行的脚本", JSON))
        add(Definition("izuki://scripts", "脚本列表", "已保存脚本的 id / 名称 / 更新时间", JSON))
        add(Definition("izuki://logs", "最近日志", "应用最近日志（尾部）", "text/plain"))
        add(Definition("izuki://audit", "MCP 审计日志", "最近的工具调用记录（JSONL，敏感参数已脱敏）", "application/x-ndjson"))
        add(Definition("izuki://screenshot", "当前屏幕", "当前屏幕截图（JPEG Base64）", "image/jpeg"))
        if (docs != null) {
            add(
                Definition(
                    "izuki://docs",
                    "开发文档索引",
                    "仓库 docs/ 镜像的文档清单；文档可能滞后于当前版本",
                    JSON,
                ),
            )
        }
    }

    private val templates = buildList {
        add(
            JSONObject()
                .put("uriTemplate", "izuki://scripts/{id}")
                .put("name", "脚本源码")
                .put("description", "按 id 读取脚本源代码")
                .put("mimeType", "text/plain"),
        )
        if (docs != null) {
            add(
                JSONObject()
                    .put("uriTemplate", "izuki://docs/{name}")
                    .put("name", "开发文档")
                    .put("description", "按文件名（可省略 .md）读取开发文档全文；分页请用 docs 工具")
                    .put("mimeType", McpDocCatalog.MIME_MARKDOWN),
            )
        }
    }


    override fun listJson(): JSONArray = JSONArray().apply {
        definitions.forEach { def ->
            put(
                JSONObject().apply {
                    put("uri", def.uri)
                    put("name", def.name)
                    put("description", def.description)
                    put("mimeType", def.mimeType)
                },
            )
        }
    }

    override fun templatesJson(): JSONArray = JSONArray().apply { templates.forEach { put(it) } }

    /** 读取资源；未知 URI 返回 null。 */
    override fun read(uri: String): ResourceContent? = when {
        uri == "izuki://device" -> ResourceContent(uri, JSON, deviceInfo().toString())
        uri == "izuki://status" -> ResourceContent(uri, JSON, status().toString())
        uri == "izuki://scripts" -> ResourceContent(uri, JSON, scriptList().toString())
        uri == "izuki://logs" -> ResourceContent(uri, "text/plain", logFileStore.tail())
        uri == "izuki://audit" -> ResourceContent(uri, "application/x-ndjson", auditLog.tail())
        uri == "izuki://screenshot" -> readScreenshot(uri)
        uri == "izuki://docs" -> readDocIndex(uri)
        uri.startsWith("izuki://docs/") -> readDoc(uri)
        uri.startsWith("izuki://scripts/") -> readScript(uri)
        else -> null
    }

    private fun readDocIndex(uri: String): ResourceContent? =
        docs?.let { ResourceContent(uri, JSON, it.indexJson().toString()) }

    private fun readDoc(uri: String): ResourceContent? {
        val catalog = docs ?: return null
        val entry = catalog.find(uri.removePrefix("izuki://docs/")) ?: return null
        return ResourceContent(entry.uri, McpDocCatalog.MIME_MARKDOWN, entry.text)
    }


    private fun readScreenshot(uri: String): ResourceContent {
        val source = bundle.screenshotProvider()
            ?: return ResourceContent(uri, "text/plain", "截图失败：无可用截图后端")
        return try {
            val encoded = ScreenshotEncoder.encode(
                source = source,
                scalePercent = 75,
                jpegQuality = 80,
            )
            ResourceContent(uri, encoded.mimeType, blob = encoded.base64)
        } finally {
            source.recycle()
        }
    }

    private fun readScript(uri: String): ResourceContent? {
        val id = uri.removePrefix("izuki://scripts/").substringBefore('?')
        val script = scriptRepository.find(id) ?: return null
        return ResourceContent(uri, "text/plain", scriptRepository.read(script))
    }

    private fun deviceInfo(): JSONObject = JSONObject().apply {
        put("width", bundle.deviceApi.screenWidth())
        put("height", bundle.deviceApi.screenHeight())
        put("brand", bundle.deviceApi.brand())
        put("model", bundle.deviceApi.model())
        put("androidVersion", bundle.deviceApi.androidVersion())
        put("battery", bundle.deviceApi.batteryLevel())
    }

    private fun status(): JSONObject = JSONObject().apply {
        put("readyModes", JSONArray(controllers.readyModes.value.map { it.name }))
        put("runningScript", executionManager.runningScript.value ?: JSONObject.NULL)
    }

    private fun scriptList(): JSONArray = JSONArray().apply {
        scriptRepository.list().forEach { script ->
            put(
                JSONObject().apply {
                    put("id", script.id)
                    put("name", script.name)
                    put("updatedAt", script.updatedAt)
                },
            )
        }
    }

    private companion object {
        const val JSON = "application/json"
    }
}
