package com.benton.izukijs.mcp.tools

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import com.benton.izukijs.ai.AgentTools
import com.benton.izukijs.ai.ShellGuard
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.core.log.LogLevel
import com.benton.izukijs.core.log.RunLogRegistry
import com.benton.izukijs.core.log.RunStatus
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.mcp.McpConfig
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.ScriptExecutionManager
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.service.ScreenUnlockController
import com.benton.izukijs.service.UnlockResult
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenOptions
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenState
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具目录。
 *
 * 读屏 / 控制类工具直接复用 [AgentTools] 的 schema 与执行逻辑，保证与脚本、AI Agent
 * 行为一致；这里只额外提供 MCP 专属的截图（图像内容块）、异步结果查询与脚本管理。
 *
 * 目录按 [configProvider] 的当前配置惰性重建：`allowShell` / `allowScripts` /
 * `allowUnlock` / `autoUnlock` / `allowDangerousShell` / 黑名单变更后，下一次 `tools/list`
 * 即反映新配置，无需重建监听（也就不会打断在线会话）。
 */
class McpToolRegistry(
    private val bundle: DeviceApiBundle,
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val scriptRepository: ScriptRepository,
    private val executionManager: ScriptExecutionManager,
    private val operations: OperationRegistry,
    private val logFileStore: LogFileStore,
    private val configProvider: () -> McpConfig,
    private val virtualScreen: VirtualScreenManager? = null,
    private val screenUnlock: ScreenUnlockController? = null,
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
            allowShell = config.allowShell,
        )
        val catalog = buildList {
            addAll(sharedTools(agentTools, config))
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

    private fun sharedTools(agentTools: AgentTools, config: McpConfig): List<McpTool> =
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
                ) { args -> runShared(agentTools, config, spec.name, args) }
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

    /** 共用工具的执行入口，叠加安全护栏（危险 shell / 敏感 App 启动）。 */
    private fun runShared(
        agentTools: AgentTools,
        config: McpConfig,
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
        when (name) {
            "shell" -> {
                if (!config.allowDangerousShell) {
                    val reason = ShellGuard.reason(args.optString("command"))
                    if (reason != null) return blocked("命令被安全策略拦截（$reason）")
                }
            }

            "app" -> {
                if (args.optString("action") == "launch") {
                    val pkg = args.optString("package")
                    if (pkg.isNotBlank() && config.isPackageBlocked(pkg)) {
                        return blocked("目标应用 $pkg 在敏感应用黑名单中，已拒绝启动")
                    }
                }
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

    private fun blocked(message: String): McpToolResult = McpToolResult.text(
        JSONObject().apply {
            put("ok", false)
            put("blocked", true)
            put("message", message)
        }.toString(),
    )

    // ---- 自主解锁（受 allowUnlock / autoUnlock 控制） ----

    private fun unlockTool(): McpTool = McpTool(
        name = "unlock",
        description = "尝试解锁当前锁屏，复用「设置 → 安全解锁」中保存的凭据；不会主动锁屏。" +
            "若设备已解锁则直接返回。需先在「设置 → 安全解锁」启用自主解锁，并在 MCP 工具开关中" +
            "开启「允许解锁工具」。",
        inputSchema = McpSchemas.objectSchema(emptyMap()),
    ) {
        val controller = screenUnlock
            ?: return@McpTool unlockResultJson(UnlockResult.UNSUPPORTED)
        unlockResultJson(runBlocking { controller.ensureUnlocked() })
    }

    private fun unlockResultJson(result: UnlockResult): McpToolResult {
        val ok = result == UnlockResult.OK || result == UnlockResult.ALREADY_UNLOCKED
        val payload = JSONObject().apply {
            put("ok", ok)
            put("action", "unlock")
            put("result", result.name)
            put("message", unlockMessage(result))
        }
        return McpToolResult(listOf(McpContent.text(payload.toString())), isError = !ok)
    }

    private fun unlockBlocked(result: UnlockResult): McpToolResult = McpToolResult.text(
        JSONObject().apply {
            put("ok", false)
            put("blocked", true)
            put("reason", if (result == UnlockResult.DISABLED) "unlock_disabled" else "unlock_failed")
            put("result", result.name)
            put("message", unlockMessage(result))
        }.toString(),
    )

    private fun unlockMessage(result: UnlockResult): String = when (result) {
        UnlockResult.OK -> "已解锁"
        UnlockResult.ALREADY_UNLOCKED -> "设备未锁屏"
        UnlockResult.DISABLED -> "自主解锁未启用，请先在「设置 → 安全解锁」中开启；" +
            "若要允许外部 AI 调用，还需在 MCP 工具开关中开启「允许解锁工具」"
        UnlockResult.NO_BACKEND -> "没有可用的解锁后端（需要 Shizuku / 无线调试 / Root）"
        UnlockResult.CREDENTIAL_REQUIRED -> "未配置解锁凭据"
        UnlockResult.CREDENTIAL_INVALID -> "解锁凭据无效"
        UnlockResult.CREDENTIAL_REJECTED -> "解锁凭据被拒绝"
        UnlockResult.PATTERN_SCREEN_MISMATCH -> "图案录制方向与当前屏幕不一致"
        UnlockResult.LOCK_FAILED -> "锁屏失败"
        UnlockResult.WAKE_FAILED -> "唤醒屏幕失败"
        UnlockResult.DISMISS_FAILED -> "收起锁屏失败"
        UnlockResult.UNSUPPORTED -> "当前设备或控制后端不支持自动解锁"
        UnlockResult.TIMEOUT -> "解锁超时"
        UnlockResult.NO_INPUT -> "没有可用的输入设备"
        UnlockResult.RECORDING_TIMEOUT -> "录制超时"
        UnlockResult.CANCELLED -> "解锁已取消"
    }

    /** 操作前自动解锁：仅在设备锁屏或息屏时触发，失败则阻止本次设备操作。 */
    private fun withAutoUnlock(tool: McpTool): McpTool = McpTool(
        name = tool.name,
        description = tool.description,
        inputSchema = tool.inputSchema,
        readOnly = tool.readOnly,
        synchronous = tool.synchronous,
    ) { args ->
        val controller = screenUnlock
        if (controller != null &&
            shouldAutoUnlock(tool.name, args) &&
            (controller.isLocked() || !controller.isInteractive())
        ) {
            val result = runBlocking { controller.ensureUnlocked() }
            when (result) {
                UnlockResult.OK, UnlockResult.ALREADY_UNLOCKED -> Unit
                UnlockResult.DISABLED -> {
                    if (controller.isLocked()) return@McpTool unlockBlocked(result)
                }
                else -> return@McpTool unlockBlocked(result)
            }
        }
        tool.call(args)
    }

    /** 虚拟屏操作不需要解锁主屏；其余自动解锁集合内的工具都针对主屏。 */
    private fun shouldAutoUnlock(name: String, args: JSONObject): Boolean {
        if (name == "screenshot" || name in TARGETED_SHARED) {
            return !args.optString("target").equals("virtual", ignoreCase = true)
        }
        return true
    }

    // ---- 日志 ----

    private fun readLogsTool(): McpTool = McpTool(
        name = "read_logs",
        description = "读取应用最近日志的尾部，可按级别 / 时间 / 关键字过滤；传 run_id 可读取某次脚本运行的配对日志。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "lines" to McpSchemas.integer("返回行数 1~2000，默认 200"),
                "level" to McpSchemas.enum(
                    "最低日志级别（含）",
                    listOf("debug", "info", "success", "warn", "error"),
                ),
                "since_ms" to McpSchemas.integer("只返回不早于该时间戳的日志"),
                "grep" to McpSchemas.string("按关键字过滤（不区分大小写）"),
                "run_id" to McpSchemas.string("只读取该次脚本运行的日志，通常来自 run_script 的返回"),
            ),
        ),
        readOnly = true,
        synchronous = true,
    ) { args ->
        val lines = args.optInt("lines", 200).coerceIn(1, 2000)
        val level = parseLogLevel(args.optString("level"))
        val grep = args.optString("grep").takeIf { it.isNotBlank() }
        val runId = args.optString("run_id").trim()
        if (runId.isNotEmpty()) {
            val record = RunLogRegistry.find(runId)
                ?: return@McpTool McpToolResult.text(
                    JSONObject().put("ok", false).put("message", "找不到运行记录：$runId").toString(),
                )
            return@McpTool McpToolResult.text(runLogJson(record, lines, level, grep).toString())
        }
        McpToolResult.text(
            logFileStore.query(
                maxLines = lines,
                level = level?.name,
                since = args.optLong("since_ms", 0L),
                grep = grep,
            ),
        )
    }

    private fun getRunLogTool(): McpTool = McpTool(
        name = "get_run_log",
        description = "读取一次脚本运行的状态与配对输出（run_id 由 run_script 返回）。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "run_id" to McpSchemas.string("run_script 返回的 run_id"),
                "tail" to McpSchemas.integer("返回最后 N 条日志，默认 200"),
                "level" to McpSchemas.enum(
                    "最低日志级别（含）",
                    listOf("debug", "info", "success", "warn", "error"),
                ),
                "grep" to McpSchemas.string("按关键字过滤（不区分大小写）"),
            ),
            required = listOf("run_id"),
        ),
        readOnly = true,
        synchronous = true,
    ) { args ->
        val runId = args.optString("run_id").trim()
        val record = RunLogRegistry.find(runId)
            ?: return@McpTool McpToolResult.text(
                JSONObject().put("ok", false).put("message", "找不到运行记录：$runId").toString(),
            )
        McpToolResult.text(
            runLogJson(
                record,
                args.optInt("tail", 200).coerceIn(1, 2000),
                parseLogLevel(args.optString("level")),
                args.optString("grep").takeIf { it.isNotBlank() },
            ).toString(),
        )
    }

    private fun runLogJson(
        record: com.benton.izukijs.core.log.RunRecord,
        tail: Int,
        level: LogLevel?,
        grep: String?,
    ): JSONObject {
        val logs = JSONArray()
        RunLogRegistry.filter(record, tail, level, grep).forEach { entry ->
            logs.put(
                JSONObject()
                    .put("time", entry.timeMillis)
                    .put("level", entry.level.name.lowercase())
                    .put("message", entry.message),
            )
        }
        return JSONObject().apply {
            put("ok", record.status != RunStatus.FAILED && record.status != RunStatus.TIMEOUT)
            put("run_id", record.runId)
            put("script_id", record.scriptId)
            put("script", record.scriptName)
            put("status", record.status.name.lowercase())
            put("startedAt", record.startedAt)
            if (record.finishedAt > 0L) put("finishedAt", record.finishedAt)
            put(
                "elapsed_ms",
                if (record.finishedAt > 0L) record.finishedAt - record.startedAt
                else System.currentTimeMillis() - record.startedAt,
            )
            record.error?.let { put("error", it) }
            put("logs", logs)
        }
    }

    private fun parseLogLevel(raw: String): LogLevel? =
        raw.trim().takeIf { it.isNotBlank() }?.let { value ->
            LogLevel.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }

    // ---- 截图（返回图像内容块） ----

    private fun screenshotTool(): McpTool = McpTool(
        name = "screenshot",
        description = "捕获当前屏幕，返回一张图像，并附带 image_size（图像像素）与 " +
            "screen_size（真实屏幕像素）。可用 `scale` 缩小、`region=[l,t,r,b]` 只截局部、`format` 选 " +
            "jpeg/webp。`target` 可选择 auto / main / virtual，按需截取后台虚拟屏。注意 click 使用 " +
            "screen_size 坐标系，或直接用 normalized 坐标，避免按 image_size 误算；带 region 时" +
            "请改用屏幕像素坐标。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "target" to McpSchemas.enum(
                    "截图目标屏幕；默认 auto（跟随当前脚本目标）",
                    listOf("auto", "main", "virtual"),
                ),
                "scale" to McpSchemas.number("缩放比例 0.1~1，默认 1（原图）"),
                "quality" to McpSchemas.integer("压缩质量 1~100，默认 90"),
                "region" to McpSchemas.intArray("裁剪区域 [left,top,right,bottom]（屏幕像素），省略为整屏"),
                "format" to McpSchemas.enum("图像格式", listOf("jpeg", "webp")),
            ),
        ),
        readOnly = true,
    ) { args ->
        val target = ScreenTarget.from(args.optString("target")) ?: ScreenTarget.AUTO
        val source = bundle.captureProvider(target, CAPTURE_WAIT_MS)
            ?: return@McpTool McpToolResult.error(
                if (target == ScreenTarget.VIRTUAL) {
                    "虚拟屏未运行，或已启动但尚未收到画面帧；请先 start 并 launch 一个应用后重试"
                } else {
                    "截图失败：无可用截图后端（需屏幕捕获 / 无障碍 / Shizuku / 无线调试 / Root）"
                },
            )
        encodeCapture(source, args)
    }

    private fun encodeCapture(source: Bitmap, args: JSONObject): McpToolResult {
        val scale = args.optDouble("scale", 1.0).coerceIn(0.1, 1.0)
        val quality = args.optInt("quality", 90).coerceIn(1, 100)
        val region = args.optJSONArray("region")?.let { array ->
            if (array.length() < 4) null else Rect(
                array.optInt(0),
                array.optInt(1),
                array.optInt(2),
                array.optInt(3),
            )
        }
        val format = ScreenshotFormat.from(args.optString("format"))
        val encoded = try {
            ScreenshotEncoder.encode(source, (scale * 100).toInt(), quality, region, format)
        } finally {
            source.recycle()
        }
        val meta = JSONObject().apply {
            put("image_size", sizeOf(encoded.width, encoded.height))
            put("screen_size", sizeOf(encoded.screenWidth, encoded.screenHeight))
            encoded.region?.let { put("region", JSONArray(it)) }
        }
        return McpToolResult(
            listOf(
                McpContent.image(encoded.base64, encoded.mimeType),
                McpContent.text(meta.toString()),
            ),
        )
    }

    private fun sizeOf(width: Int, height: Int): JSONObject =
        JSONObject().put("w", width).put("h", height)

    // ---- 后台虚拟屏 ----

    private fun virtualScreenTool(
        manager: VirtualScreenManager,
        config: McpConfig,
    ): McpTool = McpTool(
        name = "virtual_screen",
        description = "后台虚拟屏：start / stop / status / capture / launch。每次调用自带 target，" +
            "不会改变脚本的全局操作目标。capture 返回图像内容块；虚拟屏不支持 ui_dump / " +
            "find(source=ui)，请改用 capture / ocr / 坐标输入。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "action" to McpSchemas.enum(
                    "操作",
                    listOf("start", "stop", "status", "capture", "launch"),
                ),
                "width" to McpSchemas.integer("start 时的宽度，默认取设置"),
                "height" to McpSchemas.integer("start 时的高度，默认取设置"),
                "dpi" to McpSchemas.integer("start 时的密度，默认取设置"),
                "package" to McpSchemas.string("launch 时的应用包名"),
                "scale" to McpSchemas.number("capture 缩放比例 0.1~1，默认 1"),
                "quality" to McpSchemas.integer("capture 压缩质量 1~100，默认 90"),
                "region" to McpSchemas.intArray("capture 裁剪区域 [l,t,r,b]（虚拟屏像素）"),
                "format" to McpSchemas.enum("capture 图像格式", listOf("jpeg", "webp")),
                "wait_next_frame" to McpSchemas.boolean(
                    "先等待比 after_frame 更新的帧再截图；after_frame 省略时等待当前帧之后的下一帧",
                ),
                "after_frame" to McpSchemas.integer("wait_next_frame 的基准 frameSeq，通常传上一条输入响应里的 frameSeq"),
                "frame_timeout_ms" to McpSchemas.integer("wait_next_frame 的超时（毫秒），默认 1000"),
            ),
            required = listOf("action"),
        ),
    ) { args ->
        when (val action = args.optString("action", "status")) {
            "start" -> {
                val base = manager.configuredResolution()
                val options = VirtualScreenOptions(
                    VirtualScreenResolution(
                        width = args.optInt("width", base.width),
                        height = args.optInt("height", base.height),
                        dpi = args.optInt("dpi", base.dpi),
                    ).normalized(),
                )
                val ok = manager.startBlocking(options)
                McpToolResult.text(
                    virtualScreenStateJson(manager).put("ok", ok).toString(),
                )
            }

            "stop" -> {
                manager.stopBlocking()
                McpToolResult.text(
                    virtualScreenStateJson(manager).put("ok", true).toString(),
                )
            }

            "status" -> McpToolResult.text(virtualScreenStateJson(manager).toString())

            "capture" -> {
                if (!manager.isActive()) {
                    return@McpTool McpToolResult.error("虚拟屏未运行，请先执行 action=start")
                }
                if (args.optBoolean("wait_next_frame", false)) {
                    val baseline = if (args.has("after_frame")) {
                        args.optLong("after_frame", 0L)
                    } else {
                        manager.frameSeq()
                    }
                    val timeout = args.optInt("frame_timeout_ms", DEFAULT_CAPTURE_FRAME_WAIT_MS)
                        .coerceIn(0, MAX_CAPTURE_FRAME_WAIT_MS)
                        .toLong()
                    manager.awaitFrameAfter(baseline, timeout)
                }
                val source = bundle.captureProvider(ScreenTarget.VIRTUAL, CAPTURE_WAIT_MS)
                    ?: return@McpTool McpToolResult.error(
                        "虚拟屏已启动，但尚未收到画面帧；请先用 action=launch 启动一个应用，" +
                            "或等待首帧绘制后重试",
                    )
                encodeCapture(source, args)
            }

            "launch" -> {
                val packageName = args.optString("package").trim()
                if (packageName.isEmpty()) {
                    return@McpTool McpToolResult.error("action=launch 需要 package")
                }
                if (!manager.isActive()) {
                    return@McpTool McpToolResult.error("虚拟屏未运行，请先执行 action=start")
                }
                if (config.isPackageBlocked(packageName)) {
                    return@McpTool blocked("目标应用 $packageName 在敏感应用黑名单中，已拒绝启动")
                }
                val ok = manager.launchBlocking(packageName)
                McpToolResult.text(
                    JSONObject()
                        .put("ok", ok)
                        .put("package", packageName)
                        .put("displayId", manager.displayId())
                        .toString(),
                )
            }

            else -> McpToolResult.error("未知 action：$action")
        }
    }

    private fun virtualScreenInputTool(manager: VirtualScreenManager): McpTool = McpTool(
        name = "virtual_screen_input",
        description = "在后台虚拟屏上输入。坐标为相对虚拟屏的 0~1 归一化值；不会改变脚本的" +
            "全局操作目标。输入成功后默认等待画面重绘（wait_frame=true）。action=key 时 key 可为 " +
            "back / recents / enter / esc 或键码；虚拟屏没有 launcher，home 会返回明确错误。",
        inputSchema = McpSchemas.objectSchema(
            mapOf(
                "action" to McpSchemas.enum(
                    "操作",
                    listOf("tap", "long_press", "swipe", "key"),
                ),
                "x" to McpSchemas.number("tap / long_press 的归一化 x（0~1）"),
                "y" to McpSchemas.number("tap / long_press 的归一化 y（0~1）"),
                "x1" to McpSchemas.number("swipe 起点归一化 x（0~1）"),
                "y1" to McpSchemas.number("swipe 起点归一化 y（0~1）"),
                "x2" to McpSchemas.number("swipe 终点归一化 x（0~1）"),
                "y2" to McpSchemas.number("swipe 终点归一化 y（0~1）"),
                "duration_ms" to McpSchemas.integer("长按 / 滑动时长（毫秒），默认 600 / 300"),
                "key" to McpSchemas.string("按键：back / home / recents / enter / esc 或键码"),
                "wait_frame" to McpSchemas.boolean("输入成功后等待画面重绘再返回，默认 true"),
                "frame_timeout_ms" to McpSchemas.integer("等待新帧的超时（毫秒），默认 600"),
            ),
            required = listOf("action"),
        ),
    ) { args ->
        if (!manager.isActive()) {
            return@McpTool McpToolResult.error("虚拟屏未运行，请先执行 virtual_screen action=start")
        }
        val size = manager.size()
            ?: return@McpTool McpToolResult.error("虚拟屏分辨率不可用")
        val waitFrame = args.optBoolean("wait_frame", true)
        val frameTimeoutMs = args.optInt("frame_timeout_ms", DEFAULT_INPUT_FRAME_WAIT_MS)
            .coerceIn(0, MAX_INPUT_FRAME_WAIT_MS)
            .toLong()

        /** 输入成功后等待画面重绘，响应里带上新的 frameSeq，避免调用方立刻截图拿到上一帧。 */
        fun finishInput(
            action: String,
            ok: Boolean,
            baseline: Long,
            build: JSONObject.() -> Unit,
        ): McpToolResult {
            val advanced = if (ok && waitFrame) manager.awaitFrameAfter(baseline, frameTimeoutMs) else false
            return McpToolResult.text(
                JSONObject().apply {
                    put("ok", ok)
                    put("action", action)
                    put("frameSeq", manager.frameSeq())
                    put("frameAdvanced", advanced)
                    if (ok && waitFrame && !advanced) put("frameWaitMs", frameTimeoutMs)
                    build()
                }.toString(),
            )
        }

        when (val action = args.optString("action")) {
            "tap", "long_press" -> {
                val x = normalizedCoordinate(args, "x")
                val y = normalizedCoordinate(args, "y")
                if (x == null || y == null) {
                    return@McpTool McpToolResult.error("$action 需要 0~1 的 x / y")
                }
                val px = (x * size.width).toFloat()
                val py = (y * size.height).toFloat()
                val duration = args.optInt("duration_ms", if (action == "tap") 300 else 600)
                val baseline = manager.frameSeq()
                val ok = runBlocking {
                    if (action == "tap") {
                        manager.tap(px, py)
                    } else {
                        manager.longPress(px, py, duration.toLong())
                    }
                }
                finishInput(action, ok, baseline) {
                    put("x", px.toInt())
                    put("y", py.toInt())
                }
            }

            "swipe" -> {
                val x1 = normalizedCoordinate(args, "x1")
                val y1 = normalizedCoordinate(args, "y1")
                val x2 = normalizedCoordinate(args, "x2")
                val y2 = normalizedCoordinate(args, "y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    return@McpTool McpToolResult.error("swipe 需要 0~1 的 x1 / y1 / x2 / y2")
                }
                val duration = args.optInt("duration_ms", 300)
                val baseline = manager.frameSeq()
                val ok = runBlocking {
                    manager.swipe(
                        (x1 * size.width).toFloat(),
                        (y1 * size.height).toFloat(),
                        (x2 * size.width).toFloat(),
                        (y2 * size.height).toFloat(),
                        duration.toLong(),
                    )
                }
                finishInput("swipe", ok, baseline) {
                    put("duration_ms", duration)
                }
            }

            "key" -> {
                val keyCode = keyCode(args.optString("key"))
                    ?: return@McpTool McpToolResult.error("无法识别的按键：${args.optString("key")}")
                if (keyCode == KeyEvent.KEYCODE_HOME) {
                    return@McpTool McpToolResult.error(
                        "虚拟屏没有 launcher，home 键不会生效；请改用 back，" +
                            "或用 virtual_screen action=launch 启动桌面应用",
                    )
                }
                val baseline = manager.frameSeq()
                val ok = runBlocking { manager.key(keyCode) }
                finishInput("key", ok, baseline) {
                    put("key_code", keyCode)
                }
            }

            else -> McpToolResult.error("未知 action：$action")
        }
    }

    private fun virtualScreenStateJson(manager: VirtualScreenManager): JSONObject {
        val state = manager.state.value
        val resolution = manager.size() ?: manager.configuredResolution()
        return JSONObject().apply {
            put("available", manager.available())
            put("active", manager.isActive())
            put("displayId", manager.displayId())
            put("target", manager.target().displayName())
            put("resolvedTarget", manager.resolvedTarget().displayName())
            put("frameSeq", manager.frameSeq())
            put("canCapture", manager.canCapture())
            put("width", resolution.width)
            put("height", resolution.height)
            put("dpi", resolution.dpi)
            manager.lastStopReason()?.let { put("lastStopReason", it) }
            if (manager.lastStopAt() > 0L) put("lastStopAt", manager.lastStopAt())
            put("state", when (state) {
                VirtualScreenState.Idle -> "idle"
                VirtualScreenState.Starting -> "starting"
                is VirtualScreenState.Active -> "active"
                is VirtualScreenState.Failed -> "failed"
            })
            if (state is VirtualScreenState.Failed) put("message", state.message)
        }
    }

    private fun normalizedCoordinate(args: JSONObject, name: String): Double? {
        if (!args.has(name)) return null
        val value = args.optDouble(name, Double.NaN)
        return value.takeIf { it.isFinite() && it in 0.0..1.0 }
    }

    private fun keyCode(raw: String): Int? = when (raw.trim().lowercase()) {
        "back" -> KeyEvent.KEYCODE_BACK
        "home" -> KeyEvent.KEYCODE_HOME
        "recents", "recent", "app_switch" -> KeyEvent.KEYCODE_APP_SWITCH
        "enter" -> KeyEvent.KEYCODE_ENTER
        "esc", "escape" -> KeyEvent.KEYCODE_ESCAPE
        else -> raw.trim().toIntOrNull()
    }

    // ---- 异步结果查询（绕过设备队列，直接执行） ----

    private fun getResultTool(): McpTool = McpTool(
        name = "get_result",
        description = "查询一次异步操作的执行结果。当某次工具调用返回 {status:\"running\", request_id} " +
            "时使用——这表示动作已提交但结果未知，请勿盲目重试。",
        inputSchema = McpSchemas.objectSchema(
            mapOf("request_id" to McpSchemas.string("异步操作的 request_id")),
            required = listOf("request_id"),
        ),
        readOnly = true,
        synchronous = true,
    ) { args ->
        val id = args.optString("request_id")
        val op = operations.get(id)
            ?: return@McpTool McpToolResult.text(
                JSONObject().put("status", "not_found").put("request_id", id).toString(),
            )
        McpToolResult.text(operationJson(op))
    }

    private fun listOperationsTool(): McpTool = McpTool(
        name = "list_operations",
        description = "列出最近的异步操作及其状态（running / done / failed）。",
        inputSchema = McpSchemas.objectSchema(emptyMap()),
        readOnly = true,
        synchronous = true,
    ) {
        val array = JSONArray()
        operations.list().forEach { op ->
            array.put(
                JSONObject().apply {
                    put("request_id", op.id)
                    put("tool", op.tool)
                    put("status", op.status.name.lowercase())
                    put("elapsed_ms", op.elapsedMs)
                },
            )
        }
        McpToolResult.text(array.toString())
    }

    private fun operationJson(op: OperationState): String = JSONObject().apply {
        put("request_id", op.id)
        put("tool", op.tool)
        put("status", op.status.name.lowercase())
        put("elapsed_ms", op.elapsedMs)
        when (op.status) {
            OperationStatus.DONE -> op.result?.let { put("result", it.toJson()) }
            OperationStatus.FAILED -> put("error", op.error ?: "执行失败")
            OperationStatus.RUNNING -> put("message", "仍在执行中，请稍后再查询")
        }
    }.toString()

    // ---- 脚本（受 allowScripts 控制） ----

    private fun scriptTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_scripts",
            description = "列出设备上保存的脚本。",
            inputSchema = McpSchemas.objectSchema(emptyMap()),
            readOnly = true,
        ) {
            val array = JSONArray()
            scriptRepository.list().forEach { script ->
                array.put(
                    JSONObject().apply {
                        put("id", script.id)
                        put("name", script.name)
                        put("updatedAt", script.updatedAt)
                    },
                )
            }
            McpToolResult.text(array.toString())
        },
        McpTool(
            name = "read_script",
            description = "读取指定脚本的源代码。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("id" to McpSchemas.string("脚本 id")),
                required = listOf("id"),
            ),
            readOnly = true,
        ) { args ->
            val script = scriptRepository.find(args.getString("id"))
                ?: return@McpTool McpToolResult.error("找不到脚本：${args.getString("id")}")
            McpToolResult.text(scriptRepository.read(script).ifBlank { "（脚本内容为空）" })
        },
        McpTool(
            name = "script_write",
            description = "新建或更新脚本源码。id 命中已有脚本则覆盖并保留历史版本，否则新建；" +
                "返回真实 id（可能因重名加后缀而与传入 id 不同），后续更新请使用该 id。",
            inputSchema = McpSchemas.objectSchema(
                mapOf(
                    "id" to McpSchemas.string("目标脚本 id；已存在则更新"),
                    "name" to McpSchemas.string("新建时的名称，缺省用 id"),
                    "content" to McpSchemas.string("完整脚本源码（UTF-8，最大 1 MiB）"),
                    "createIfMissing" to McpSchemas.boolean("id 不存在时是否新建，默认 true"),
                ),
                required = listOf("content"),
            ),
        ) { args ->
            if (!args.has("content")) {
                return@McpTool McpToolResult.error("缺少脚本源码 content")
            }
            val content = args.optString("content")
            val bytes = content.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_SCRIPT_BYTES) {
                return@McpTool McpToolResult.error(
                    "脚本源码过大：$bytes 字节，上限 $MAX_SCRIPT_BYTES 字节",
                )
            }
            val id = args.optString("id").trim()
            val name = args.optString("name").trim()
            val targetId = id.ifEmpty { name }
            if (targetId.isEmpty()) {
                return@McpTool McpToolResult.error("需要提供 id 或 name")
            }
            val existing = scriptRepository.find(targetId)
            if (existing == null && !args.optBoolean("createIfMissing", true)) {
                return@McpTool McpToolResult.error("找不到脚本：$targetId")
            }
            val created = existing == null
            val script = if (existing != null) {
                scriptRepository.write(existing, content, forceHistory = true)
                scriptRepository.find(existing.id) ?: existing
            } else {
                scriptRepository.create(name.ifEmpty { id }, content)
            }
            McpToolResult.text(
                JSONObject().apply {
                    put("id", script.id)
                    put("name", script.name)
                    put("createdAt", scriptCreatedAt(script))
                    put("updatedAt", script.updatedAt)
                    put("bytes", script.file.length())
                    put("created", created)
                }.toString(),
            )
        },
        McpTool(
            name = "run_script",
            description = "运行指定脚本。立即返回 run_id；用 get_run_log / read_logs(run_id=...) 取回配对的输出与退出状态。",
            inputSchema = McpSchemas.objectSchema(
                mapOf(
                    "id" to McpSchemas.string("脚本 id"),
                    "values" to JSONObject()
                        .put("type", "object")
                        .put("description", "可选的运行参数（字符串键值对）")
                        .put("additionalProperties", JSONObject().put("type", "string")),
                ),
                required = listOf("id"),
            ),
        ) { args ->
            val script = scriptRepository.find(args.getString("id"))
                ?: return@McpTool McpToolResult.error("找不到脚本：${args.getString("id")}")
            if (executionManager.running.value) {
                return@McpTool McpToolResult.error("已有脚本正在运行：${executionManager.runningScript.value}")
            }
            val values = args.optJSONObject("values")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            } ?: emptyMap()
            val started = executionManager.run(script.id, script.name, scriptRepository.read(script), values)
            if (!started) {
                return@McpTool McpToolResult.text(
                    JSONObject().put("ok", false).put("message", "脚本启动失败").toString(),
                )
            }
            val runId = executionManager.lastRunId()
            val record = runId?.let { RunLogRegistry.find(it) }
            McpToolResult.text(
                JSONObject().apply {
                    put("ok", true)
                    put("run_id", runId)
                    put("script_id", script.id)
                    put("name", script.name)
                    put("startedAt", record?.startedAt ?: System.currentTimeMillis())
                }.toString(),
            )
        },
        McpTool(
            name = "delete_script",
            description = "删除指定脚本（同时清理历史版本）。运行中的脚本不可删除。",
            inputSchema = McpSchemas.objectSchema(
                mapOf("id" to McpSchemas.string("脚本 id")),
                required = listOf("id"),
            ),
        ) { args ->
            val id = args.optString("id").trim()
            val script = scriptRepository.find(id)
                ?: return@McpTool McpToolResult.error("找不到脚本：$id")
            if (executionManager.runningScriptId.value == script.id) {
                return@McpTool McpToolResult.error("脚本正在运行，无法删除：${script.name}")
            }
            scriptRepository.delete(script)
            McpToolResult.text(
                JSONObject().put("ok", true).put("id", script.id).put("deleted", true).toString(),
            )
        },
        McpTool(
            name = "rename_script",
            description = "重命名脚本。返回真实新 id（重名时可能加后缀）；运行中的脚本不可改名。",
            inputSchema = McpSchemas.objectSchema(
                mapOf(
                    "id" to McpSchemas.string("当前脚本 id"),
                    "name" to McpSchemas.string("新名称"),
                ),
                required = listOf("id", "name"),
            ),
        ) { args ->
            val id = args.optString("id").trim()
            val name = args.optString("name").trim()
            if (name.isEmpty()) return@McpTool McpToolResult.error("rename_script 需要 name")
            val script = scriptRepository.find(id)
                ?: return@McpTool McpToolResult.error("找不到脚本：$id")
            if (executionManager.runningScriptId.value == script.id) {
                return@McpTool McpToolResult.error("脚本正在运行，无法改名：${script.name}")
            }
            if (name == script.id) {
                return@McpTool McpToolResult.text(
                    JSONObject().put("ok", true).put("id", script.id).put("name", script.name).toString(),
                )
            }
            val renamed = scriptRepository.rename(script, name)
            McpToolResult.text(
                JSONObject().apply {
                    put("ok", true)
                    put("id", renamed.id)
                    put("name", renamed.name)
                }.toString(),
            )
        },
        McpTool(
            name = "stop_script",
            description = "请求停止当前正在运行的脚本。",
            inputSchema = McpSchemas.objectSchema(emptyMap()),
        ) {
            executionManager.requestStop()
            McpToolResult.text("已请求停止脚本")
        },
        McpTool(
            name = "running_script",
            description = "返回当前正在运行的脚本名，没有则返回空。",
            inputSchema = McpSchemas.objectSchema(emptyMap()),
            readOnly = true,
        ) {
            val running = executionManager.runningScript.value
            McpToolResult.text(running ?: "（当前没有运行中的脚本）")
        },
    )

    private fun scriptCreatedAt(script: ScriptInfo): Long {
        val createdAt = runCatching {
            Files.readAttributes(script.file.toPath(), BasicFileAttributes::class.java)
                .creationTime()
                .toMillis()
        }.getOrDefault(0L)
        return if (createdAt > 0L) createdAt else script.updatedAt
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 1024 * 1024
        const val CAPTURE_WAIT_MS = 1_000L
        const val DEFAULT_INPUT_FRAME_WAIT_MS = 600
        const val MAX_INPUT_FRAME_WAIT_MS = 3_000
        const val DEFAULT_CAPTURE_FRAME_WAIT_MS = 1_000
        const val MAX_CAPTURE_FRAME_WAIT_MS = 3_000

        val READ_ONLY_SHARED = setOf(
            "ocr",
            "ui_dump",
            "find",
            "info",
            "notifications",
        )

        val TARGETED_SHARED = setOf("ocr", "ui_dump", "find")

        /** 执行前可按需自动解锁的主屏工具；虚拟屏 / 脚本 / 查询类工具不在此列。 */
        val AUTO_UNLOCK_TOOLS = setOf(
            "screenshot",
            "ocr",
            "ui_dump",
            "find",
            "click",
            "swipe",
            "gesture",
            "text",
            "press",
            "wait",
            "app",
            "batch",
        )
    }
}
