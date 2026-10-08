package com.benton.izukijs.ai

import android.view.KeyEvent
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.runtime.api.AppApi
import com.benton.izukijs.runtime.api.DeviceApi
import com.benton.izukijs.runtime.api.InputApi
import com.benton.izukijs.runtime.api.OcrApi
import com.benton.izukijs.runtime.api.SelectorApi
import com.benton.izukijs.runtime.api.ShellApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 可调用的设备工具集。全部复用脚本层已有的 API 对象，保证行为与脚本一致。
 *
 * 工具经过聚合，面向 LLM 使用：同类操作收敛为单个工具，用 `by` / `action` / `key` 等
 * 判别参数区分，减少选错。每个工具返回一段结构化 JSON 文本，作为 tool 消息回填给模型。
 *
 * 坐标约定：默认使用「全分辨率屏幕像素」，与截图、控件树、OCR 一致；`normalized=true`
 * 时使用 0~1 归一化比例，可免疫截图缩放（等比缩放下归一化坐标不变）。
 */
class AgentTools(
    private val inputApi: InputApi,
    private val selectorApi: SelectorApi,
    private val ocrApi: OcrApi,
    private val appApi: AppApi,
    private val shellApi: ShellApi,
    private val deviceApi: DeviceApi,
    private val screenshotPathProvider: () -> String?,
    private val nodeTreeProvider: () -> NodeSnapshot?,
    private val allowShell: Boolean,
) {

    private val shellAvailable: Boolean get() = allowShell && shellApi.available()

    private var cachedShellAvailable: Boolean? = null
    private var cachedSpecs: List<ToolSpec> = emptyList()

    /** 工具 schema 只随 shell 可用性变化，缓存避免每步都重建整份 JSON。 */
    fun specs(): List<ToolSpec> {
        val shell = shellAvailable
        if (cachedShellAvailable == shell) return cachedSpecs
        return buildSpecs(shell).also {
            cachedShellAvailable = shell
            cachedSpecs = it
        }
    }

    private fun buildSpecs(shellAvailable: Boolean): List<ToolSpec> = buildList {
        // ---- 观察 ----
        add(
            ToolSpec(
                "ocr",
                "识别当前屏幕文字，返回结构化文本块：每块含文字与中心坐标 (x,y)、宽高、置信度。坐标为屏幕像素，可直接用于 click。",
                objectSchema(emptyMap()),
            ),
        )
        add(
            ToolSpec(
                "ui_dump",
                "读取当前界面的控件树（含文本、控件 id、内容描述与屏幕坐标）。当 OCR 不够精确时使用。",
                objectSchema(emptyMap()),
            ),
        )
        add(
            ToolSpec(
                "find",
                "查找屏幕元素并返回匹配项（中心坐标 + 边界）。text 为模糊包含匹配（OCR 文字与控件文本/描述）；也可用 by=id|desc + value 精确匹配控件。",
                objectSchema(
                    mapOf(
                        "text" to stringProp("要查找的文字（模糊包含）"),
                        "by" to enumProp("按控件属性精确匹配", listOf("id", "desc")),
                        "value" to stringProp("by 对应的匹配值"),
                    ),
                ),
            ),
        )
        add(
            ToolSpec(
                "screenshot",
                "保存一张当前屏幕截图到文件，返回文件路径。",
                objectSchema(emptyMap()),
            ),
        )
        add(
            ToolSpec(
                "info",
                "读取设备信息。what=device：屏幕尺寸/机型/系统；battery：电量；current_app：当前前台包名。",
                objectSchema(
                    mapOf("what" to enumProp("要读取的信息", listOf("device", "battery", "current_app"))),
                ),
            ),
        )

        // ---- 输入 ----
        add(
            ToolSpec(
                "click",
                "点击或长按。by=coord（默认）用 x/y 像素坐标；normalized=true 时 x/y 为 0~1 比例（推荐，免疫截图缩放）。by=text|id|desc 时按 value 匹配控件。long=true 长按。返回实际位置与命中的元素，便于自校验。",
                objectSchema(
                    mapOf(
                        "by" to enumProp("定位方式", listOf("coord", "text", "id", "desc")),
                        "value" to stringProp("by=text/id/desc 时的匹配值"),
                        "x" to numberProp("横坐标"),
                        "y" to numberProp("纵坐标"),
                        "normalized" to booleanProp("x/y 是否为 0~1 归一化比例"),
                        "long" to booleanProp("是否长按"),
                        "duration_ms" to numberProp("长按时长（毫秒）"),
                        "settle" to booleanProp("点击后等待界面稳定再返回"),
                        "dry_run" to booleanProp("只返回将要点击的位置，不真正执行"),
                    ),
                ),
            ),
        )
        add(
            ToolSpec(
                "swipe",
                "从一点滑动到另一点，可用于滚动列表。normalized=true 时坐标为 0~1 比例。",
                objectSchema(
                    mapOf(
                        "x1" to numberProp("起点横坐标"),
                        "y1" to numberProp("起点纵坐标"),
                        "x2" to numberProp("终点横坐标"),
                        "y2" to numberProp("终点纵坐标"),
                        "duration_ms" to numberProp("滑动时长（毫秒）"),
                        "normalized" to booleanProp("坐标是否为 0~1 归一化比例"),
                        "dry_run" to booleanProp("只返回将要滑动的起止点，不真正执行"),
                    ),
                    required = listOf("x1", "y1", "x2", "y2"),
                ),
            ),
        )
        add(
            ToolSpec(
                "gesture",
                "注入一条复杂手势轨迹（曲线 / 多段 / 多指 / 按住停顿）。strokes 为笔画数组，每个点可写 [x,y] 或 [x,y,时间ms]。",
                objectSchema(
                    mapOf(
                        "strokes" to gestureStrokesProp("手势轨迹，例如 [[[100,200],[300,400,120]]]"),
                        "dry_run" to booleanProp("只回显将要注入的轨迹，不真正执行"),
                    ),
                    required = listOf("strokes"),
                ),
            ),
        )
        add(
            ToolSpec(
                "text",
                "输入文本。默认向当前焦点输入框输入；提供 by=id + value 时向匹配控件设值。" +
                    "无障碍可用时用 ACTION_SET_TEXT，否则用控件树定位并聚焦后输入。" +
                    "method 选择输入方式：auto（默认）/ input / clipboard（剪贴板粘贴，兼容性最好）/ broadcast。",
                objectSchema(
                    mapOf(
                        "text" to stringProp("要输入的文本"),
                        "by" to enumProp("按控件属性设置", listOf("id")),
                        "value" to stringProp("by=id 时的控件 id"),
                        "method" to enumProp("输入方式", listOf("auto", "input", "clipboard", "broadcast")),
                        "dry_run" to booleanProp("只返回将要执行的操作，不真正输入"),
                    ),
                    required = listOf("text"),
                ),
            ),
        )
        add(
            ToolSpec(
                "press",
                "发送按键。key 可为 back / home / recents / enter，或 Android KeyEvent 键码（整数，如返回=4、回车=66）。",
                objectSchema(
                    mapOf(
                        "key" to anyKeyProp("按键名称或键码"),
                        "dry_run" to booleanProp("只返回将要发送的按键，不真正执行"),
                    ),
                    required = listOf("key"),
                ),
            ),
        )
        add(
            ToolSpec(
                "wait",
                "等待界面变化。for=text：等待出现包含 text 的文字；for=idle：等待界面稳定（控件树不再变化）。返回是否满足与耗时。",
                objectSchema(
                    mapOf(
                        "for" to enumProp("等待类型", listOf("text", "idle")),
                        "text" to stringProp("for=text 时要等待的文字"),
                        "timeout_ms" to numberProp("超时（毫秒），默认 10000"),
                        "interval_ms" to numberProp("轮询间隔（毫秒），默认 400"),
                        "settle_ms" to numberProp("for=idle 时判定稳定的持续时长（毫秒），默认 600"),
                    ),
                ),
            ),
        )
        add(
            ToolSpec(
                "app",
                "应用操作。action=launch：启动 package；action=open_url：用系统方式打开 url。",
                objectSchema(
                    mapOf(
                        "action" to enumProp("操作", listOf("launch", "open_url")),
                        "package" to stringProp("action=launch 时的应用包名"),
                        "url" to stringProp("action=open_url 时的链接"),
                        "dry_run" to booleanProp("只返回将要执行的操作，不真正启动 / 打开"),
                    ),
                    required = listOf("action"),
                ),
            ),
        )
        add(
            ToolSpec(
                "batch",
                "顺序执行多个动作，一次调用完成，省去多轮往返。默认遇错即停并返回已完成步骤；" +
                    "不适合把 ocr / ui_dump 等大输出放进批内。actions 为 [{tool,arguments}]。",
                objectSchema(
                    mapOf(
                        "actions" to batchActionsProp(),
                        "continue_on_error" to booleanProp("遇错是否继续执行后续动作，默认 false（即失败即停）"),
                    ),
                    required = listOf("actions"),
                ),
            ),
        )
        if (shellAvailable) {
            add(
                ToolSpec(
                    "shell",
                    "执行 Shell 命令并返回输出（需要 Shizuku 或 Root）。",
                    objectSchema(mapOf("command" to stringProp("要执行的命令")), required = listOf("command")),
                ),
            )
        }
    }

    /** 执行一个工具调用，返回结果文本（内部异常会被捕获为错误提示）。 */
    fun execute(name: String, argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson.ifBlank { "{}" })
        dispatch(name, args)
    }.getOrElse { "[错误] ${it.message}" }

    private fun dispatch(name: String, args: JSONObject): String = when (name) {
        "ocr" -> ocrApi.recognizeBlocksJson()
            ?: jsonOf { put("ok", false); put("message", "未识别到文字，或截图权限不可用") }

        "ui_dump" -> formatNodeTree(nodeTreeProvider())

        "find" -> doFind(args)

        "screenshot" -> screenshotPathProvider()?.let { "截图已保存：$it" } ?: "截图失败"

        "info" -> doInfo(args.optString("what", "device"))

        "click" -> doClick(args)

        "swipe" -> doSwipe(args)

        "gesture" -> {
            val strokes = args.optJSONArray("strokes")
            if (args.optBoolean("dry_run", false)) {
                jsonOf {
                    put("ok", true)
                    put("dry_run", true)
                    put("strokes", strokes ?: JSONArray())
                }
            } else {
                okResult(inputApi.gestureRaw(strokes?.toString() ?: "[]"), "手势")
            }
        }

        "text" -> doText(args)

        "press" -> doPress(args)

        "wait" -> doWait(args)

        "app" -> doApp(args)

        "batch" -> doBatch(args)

        "shell" -> if (shellAvailable) {
            shellApi.exec(args.optString("command"))?.takeIf { it.isNotBlank() } ?: "（命令无输出）"
        } else {
            "Shell 工具未启用或当前无 Shizuku/Root 后端"
        }

        else -> "[错误] 未知工具：$name"
    }

    // ---- click ----

    private fun doClick(args: JSONObject): String {
        val by = args.optString("by", "coord").ifBlank { "coord" }
        val long = args.optBoolean("long", false)
        val duration = args.optDouble("duration_ms", DEFAULT_LONG_CLICK_MS)

        if (by == "text" || by == "id" || by == "desc") {
            val value = args.optString("value")
            if (value.isBlank()) return errorJson("click: by=$by 需要 value")
            if (args.optBoolean("dry_run", false)) {
                val matches = search(by, value, null)
                return jsonOf {
                    put("ok", true)
                    put("dry_run", true)
                    put("by", by)
                    put("value", value)
                    put("count", matches.length())
                    put("matches", matches)
                }
            }
            val ok = when (by) {
                "text" -> if (long) selectorApi.longClickByText(value) else selectorApi.clickByText(value)
                "id" -> if (long) selectorApi.longClickById(value) else selectorApi.clickById(value)
                else -> selectorApi.clickByDesc(value)
            }
            if (ok && args.optBoolean("settle", false)) waitIdle(SETTLE_DEFAULT_MS)
            return jsonOf {
                put("ok", ok)
                put("by", by)
                put("value", value)
                put("long", long)
            }
        }

        val x = resolveX(args)
        val y = resolveY(args)
        if (x == null || y == null) return errorJson("click: 坐标无效（x/y 缺失或 normalized 越界）")
        val hit = hitAt(x, y)
        if (args.optBoolean("dry_run", false)) {
            return jsonOf {
                put("ok", true)
                put("dry_run", true)
                put("x", x)
                put("y", y)
                hit?.let { put("hit", it) }
            }
        }
        val ok = if (long) inputApi.longClick(x, y, duration) else inputApi.click(x, y)
        if (ok && args.optBoolean("settle", false)) waitIdle(SETTLE_DEFAULT_MS)
        return jsonOf {
            put("ok", ok)
            put("x", x)
            put("y", y)
            put("long", long)
            hit?.let { put("hit", it) }
        }
    }

    // ---- swipe ----

    private fun doSwipe(args: JSONObject): String {
        val normalized = args.optBoolean("normalized", false)
        val x1 = coordinate(args, "x1", normalized) ?: return errorJson("swipe: x1 无效")
        val y1 = coordinate(args, "y1", normalized) ?: return errorJson("swipe: y1 无效")
        val x2 = coordinate(args, "x2", normalized) ?: return errorJson("swipe: x2 无效")
        val y2 = coordinate(args, "y2", normalized) ?: return errorJson("swipe: y2 无效")
        val duration = args.optDouble("duration_ms", 300.0)
        if (args.optBoolean("dry_run", false)) {
            return jsonOf {
                put("ok", true)
                put("dry_run", true)
                put("from", JSONArray().put(x1).put(y1))
                put("to", JSONArray().put(x2).put(y2))
                put("duration_ms", duration)
            }
        }
        val ok = inputApi.swipe(x1, y1, x2, y2, duration)
        return jsonOf {
            put("ok", ok)
            put("from", JSONArray().put(x1).put(y1))
            put("to", JSONArray().put(x2).put(y2))
        }
    }

    // ---- text / press ----

    private fun doText(args: JSONObject): String {
        val text = args.optString("text")
        val by = args.optString("by").takeIf { it.isNotBlank() }
        val id = args.optString("value").takeIf { it.isNotBlank() }
        val method = args.optString("method").ifBlank { "auto" }
        if (by == "id" && id == null) return errorJson("text: by=id 需要 value")
        if (args.optBoolean("dry_run", false)) {
            return jsonOf {
                put("ok", true)
                put("dry_run", true)
                put("text", text)
                put("by", by ?: "focus")
                put("method", method)
                id?.let { put("value", it) }
            }
        }
        val ok = if (by == "id") {
            selectorApi.setTextById(id!!, text) || focusAndInput(id, text, method)
        } else {
            inputApi.inputWithMethod(text, method)
        }
        return jsonOf { put("ok", ok); put("method", method) }
    }

    /**
     * 无障碍不可用时的设值兜底：用控件树（可能是 Shizuku 下的 `uiautomator dump`）定位控件，
     * 点击聚焦后按 [method] 输入（Shizuku / Root 下为 `input text` / 剪贴板粘贴 / ADBKeyboard 广播）。
     */
    private fun focusAndInput(viewId: String, text: String, method: String): Boolean {
        val node = collectNodes { it.viewId == viewId }.firstOrNull() ?: return false
        val cx = ((node.left + node.right) / 2).toDouble()
        val cy = ((node.top + node.bottom) / 2).toDouble()
        inputApi.click(cx, cy)
        return inputApi.inputWithMethod(text, method)
    }

    private fun doPress(args: JSONObject): String {
        val raw = args.opt("key")
        if (args.optBoolean("dry_run", false)) {
            return jsonOf { put("ok", true); put("dry_run", true); put("key", raw ?: JSONObject.NULL) }
        }
        val ok = when (raw) {
            is Number -> inputApi.press(raw.toInt())
            is String -> when (raw.trim().lowercase()) {
                "back" -> inputApi.back()
                "home" -> inputApi.home()
                "recents", "recent", "app_switch" -> inputApi.recents()
                "enter" -> inputApi.press(KeyEvent.KEYCODE_ENTER)
                else -> raw.trim().toIntOrNull()?.let { inputApi.press(it) } ?: false
            }
            else -> false
        }
        return okResult(ok, "按键")
    }

    // ---- info ----

    private fun doInfo(what: String): String = when (what) {
        "battery" -> "电量 ${deviceApi.batteryLevel()}%"

        "current_app" -> appApi.currentPackage()?.let { "当前前台包名：$it" }
            ?: "无法获取当前包名（需无障碍或 Shizuku）"

        else -> "屏幕 ${deviceApi.screenWidth()}x${deviceApi.screenHeight()}，" +
            "机型 ${deviceApi.brand()} ${deviceApi.model()}，Android SDK ${deviceApi.androidVersion()}"
    }

    // ---- app ----

    private fun doApp(args: JSONObject): String {
        val dryRun = args.optBoolean("dry_run", false)
        return when (val action = args.optString("action")) {
            "launch" -> {
                val pkg = args.optString("package")
                if (pkg.isBlank()) return errorJson("app: action=launch 需要 package")
                if (dryRun) return jsonOf { put("ok", true); put("dry_run", true); put("action", action); put("package", pkg) }
                okResult(appApi.launch(pkg), "启动应用")
            }

            "open_url" -> {
                val url = args.optString("url")
                if (url.isBlank()) return errorJson("app: action=open_url 需要 url")
                if (dryRun) return jsonOf { put("ok", true); put("dry_run", true); put("action", action); put("url", url) }
                okResult(appApi.openUrl(url), "打开链接")
            }

            else -> errorJson("app: 未知 action=$action")
        }
    }

    // ---- batch ----

    private fun doBatch(args: JSONObject): String {
        val raw = args.optJSONArray("actions") ?: return errorJson("batch: 需要 actions 数组")
        val actions = ArrayList<BatchRunner.Action<JSONObject>>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: return errorJson("batch: 第 ${i + 1} 个动作不是对象")
            val tool = item.optString("tool")
            if (tool.isBlank()) return errorJson("batch: 第 ${i + 1} 个动作缺少 tool")
            if (tool == "batch") return errorJson("batch: 不支持嵌套 batch")
            actions.add(BatchRunner.Action(tool, item.optJSONObject("arguments") ?: JSONObject()))
        }
        val outcome = BatchRunner.run(
            actions = actions,
            continueOnError = args.optBoolean("continue_on_error", false),
            execute = { tool, actionArgs -> dispatch(tool, actionArgs) },
            isFailure = ::resultIsFailure,
        )
        return jsonOf {
            put("ok", outcome.ok)
            put("executed", outcome.steps.size)
            put("stopped_early", outcome.stoppedEarly)
            put("truncated", outcome.truncated)
            put(
                "steps",
                JSONArray().apply {
                    outcome.steps.forEach { step ->
                        put(
                            JSONObject().apply {
                                put("index", step.index)
                                put("tool", step.tool)
                                put("ok", step.ok)
                                put("result", step.result)
                            },
                        )
                    }
                },
            )
        }
    }

    /** 工具返回文本是否代表失败：空、以 [错误] 开头，或 JSON 中 ok=false。 */
    private fun resultIsFailure(result: String): Boolean {
        val trimmed = result.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith("[错误]")) return true
        if (!trimmed.startsWith("{")) return false
        return runCatching { !JSONObject(trimmed).optBoolean("ok", true) }.getOrDefault(false)
    }

    // ---- wait ----

    private fun doWait(args: JSONObject): String {
        val mode = args.optString("for", "idle").ifBlank { "idle" }
        val timeout = args.optLong("timeout_ms", DEFAULT_WAIT_TIMEOUT_MS).coerceIn(0L, MAX_WAIT_TIMEOUT_MS)
        val interval = args.optLong("interval_ms", DEFAULT_WAIT_INTERVAL_MS).coerceIn(50L, 5_000L)
        val settleMs = args.optLong("settle_ms", SETTLE_DEFAULT_MS).coerceIn(0L, 10_000L)
        val started = System.currentTimeMillis()

        if (mode == "text") {
            val query = args.optString("text")
            if (query.isBlank()) return errorJson("wait: for=text 需要 text")
            while (System.currentTimeMillis() - started <= timeout) {
                val raw = ocrApi.findRaw(query)
                if (raw != null) {
                    return jsonOf {
                        put("ok", true)
                        put("matched", true)
                        put("elapsed_ms", System.currentTimeMillis() - started)
                        put("position", raw)
                    }
                }
                sleepQuietly(interval)
            }
            return jsonOf {
                put("ok", false)
                put("matched", false)
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("message", "超时未出现「$query」")
            }
        }

        // idle：等待控件树连续两次相同并保持 settle_ms。
        val first = treeSignature()
        if (first == null) {
            sleepQuietly(settleMs)
            return jsonOf {
                put("ok", true)
                put("matched", false)
                put("message", "无控件树，仅固定等待 ${settleMs}ms")
            }
        }
        var last: String? = first
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() - started <= timeout) {
            sleepQuietly(interval)
            val sig = treeSignature()
            if (sig == last) {
                if (System.currentTimeMillis() - stableSince >= settleMs) {
                    return jsonOf {
                        put("ok", true)
                        put("matched", true)
                        put("elapsed_ms", System.currentTimeMillis() - started)
                    }
                }
            } else {
                last = sig
                stableSince = System.currentTimeMillis()
            }
        }
        return jsonOf {
            put("ok", true)
            put("matched", false)
            put("timed_out", true)
            put("elapsed_ms", System.currentTimeMillis() - started)
        }
    }

    private fun waitIdle(settleMs: Long) {
        val sig = treeSignature() ?: run {
            sleepQuietly(settleMs)
            return
        }
        val deadline = System.currentTimeMillis() + SETTLE_MAX_MS
        var last: String? = sig
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(SETTLE_POLL_MS)
            val next = treeSignature()
            if (next == last) {
                if (System.currentTimeMillis() - stableSince >= settleMs) return
            } else {
                last = next
                stableSince = System.currentTimeMillis()
            }
        }
    }

    // ---- 查找 ----

    private fun doFind(args: JSONObject): String {
        val by = args.optString("by").takeIf { it.isNotBlank() }
        val value = args.optString("value").takeIf { it.isNotBlank() }
        val text = args.optString("text").takeIf { it.isNotBlank() }
        if (by == null && value == null && text == null) {
            return errorJson("find: 需要 text，或 by + value")
        }
        val matches = search(by, value, text)
        return jsonOf {
            put("ok", true)
            put("query", text ?: value ?: by)
            put("count", matches.length())
            put("matches", matches)
        }
    }

    /** 统一的元素查找：by=id/desc 精确匹配控件，否则按 text 模糊匹配控件与 OCR。 */
    private fun search(by: String?, value: String?, text: String?): JSONArray {
        val matches = JSONArray()
        if (by == "id" && value != null) {
            collectNodes { it.viewId == value }.forEach { matches.put(nodeLabel(it)) }
        } else if (by == "desc" && value != null) {
            collectNodes { it.contentDescription?.contains(value) == true }.forEach { matches.put(nodeLabel(it)) }
        } else {
            val query = text ?: value ?: return matches
            collectNodes {
                it.text?.contains(query) == true || it.contentDescription?.contains(query) == true
            }.forEach { matches.put(nodeLabel(it)) }
            ocrBlocks(query).forEach { matches.put(it) }
        }
        return matches
    }

    /** 解析 OCR 结构化结果并按 query 过滤，返回统一格式的匹配项。 */
    private fun ocrBlocks(query: String): List<JSONObject> {
        val json = ocrApi.recognizeBlocksJson() ?: return emptyList()
        val blocks = runCatching { JSONObject(json).optJSONArray("blocks") }.getOrNull() ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val text = block.optString("text")
            if (!text.contains(query)) continue
            out.add(
                JSONObject().apply {
                    put("source", "ocr")
                    put("text", text)
                    val x = block.optInt("x")
                    val y = block.optInt("y")
                    val w = block.optInt("w")
                    val h = block.optInt("h")
                    put("center", JSONArray().put(x).put(y))
                    put("bounds", JSONArray().put(x - w / 2).put(y - h / 2).put(x + w / 2).put(y + h / 2))
                    if (block.has("conf")) put("conf", block.optDouble("conf"))
                },
            )
        }
        return out
    }

    // ---- 命中测试 ----

    /** 返回屏幕坐标 (x,y) 下最深的控件快照标签；无控件树或无命中返回 null。 */
    private fun hitAt(x: Double, y: Double): JSONObject? {
        val root = nodeTreeProvider() ?: return null
        val node = deepestAt(root, x.toInt(), y.toInt()) ?: return null
        return nodeLabel(node)
    }

    private fun deepestAt(node: NodeSnapshot, x: Int, y: Int): NodeSnapshot? {
        if (x < node.left || x > node.right || y < node.top || y > node.bottom) return null
        var best: NodeSnapshot = node
        for (child in node.children) {
            deepestAt(child, x, y)?.let { best = it }
        }
        return best
    }

    private fun collectNodes(predicate: (NodeSnapshot) -> Boolean): List<NodeSnapshot> {
        val root = nodeTreeProvider() ?: return emptyList()
        val out = ArrayList<NodeSnapshot>()
        fun walk(node: NodeSnapshot) {
            if (predicate(node)) out.add(node)
            node.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun nodeLabel(node: NodeSnapshot): JSONObject = JSONObject().apply {
        put("source", "ui")
        node.text?.takeIf { it.isNotBlank() }?.let { put("text", it.take(MAX_TEXT_CHARS)) }
        node.viewId?.takeIf { it.isNotBlank() }?.let { put("id", it) }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { put("desc", it.take(MAX_TEXT_CHARS)) }
        node.className?.substringAfterLast('.')?.takeIf { it.isNotBlank() }?.let { put("class", it) }
        put("center", JSONArray().put((node.left + node.right) / 2).put((node.top + node.bottom) / 2))
        put("bounds", JSONArray().put(node.left).put(node.top).put(node.right).put(node.bottom))
        put("clickable", node.clickable)
    }

    /** 控件树签名，用于判定界面是否稳定。 */
    private fun treeSignature(): String? {
        val root = nodeTreeProvider() ?: return null
        val out = StringBuilder()
        fun walk(node: NodeSnapshot) {
            out.append(node.viewId).append('|')
                .append(node.text).append('|')
                .append(node.left).append(',').append(node.top).append(',')
                .append(node.right).append(',').append(node.bottom).append(';')
            node.children.forEach(::walk)
        }
        walk(root)
        return out.toString()
    }

    // ---- 坐标 ----

    private fun resolveX(args: JSONObject): Double? = coordinate(args, "x", args.optBoolean("normalized", false))

    private fun resolveY(args: JSONObject): Double? = coordinate(args, "y", args.optBoolean("normalized", false))

    private fun coordinate(args: JSONObject, key: String, normalized: Boolean): Double? {
        if (!args.has(key)) return null
        val raw = args.optDouble(key, Double.NaN)
        if (!raw.isFinite()) return null
        return if (normalized) raw * axisSize(key) else raw
    }

    private fun axisSize(key: String): Int =
        if (key == "x" || key == "x1" || key == "x2") deviceApi.screenWidth() else deviceApi.screenHeight()

    // ---- 结果构造 ----

    private fun okResult(success: Boolean, action: String): String = jsonOf {
        put("ok", success)
        if (!success) put("message", "$action 失败（当前控制模式不支持或目标无效）")
    }

    private fun jsonOf(block: JSONObject.() -> Unit): String = JSONObject().apply(block).toString()

    private fun errorJson(message: String): String = jsonOf {
        put("ok", false)
        put("message", message)
    }

    private fun sleepQuietly(millis: Long) {
        if (millis <= 0) return
        runCatching { Thread.sleep(millis) }
    }

    private fun formatNodeTree(root: NodeSnapshot?): String {
        root ?: return "（无法读取控件树，需开启无障碍或 Shizuku）"
        val out = StringBuilder()
        appendNode(root, 0, out)
        return if (out.isEmpty()) "（控件树为空）" else out.toString().trimEnd()
    }

    private fun appendNode(node: NodeSnapshot, depth: Int, out: StringBuilder) {
        if (out.length > MAX_DUMP_CHARS) return
        val label = buildString {
            node.className?.substringAfterLast('.')?.let { append(it) }
            node.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { append("#").append(it) }
            node.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it.take(MAX_TEXT_CHARS)).append("\"") }
            node.contentDescription?.takeIf { it.isNotBlank() }
                ?.let { append(" desc=\"").append(it.take(MAX_TEXT_CHARS)).append("\"") }
        }
        if (label.isNotBlank()) {
            out.append("  ".repeat(depth)).append(label)
            if (node.clickable) out.append(" [可点]")
            val cx = (node.left + node.right) / 2
            val cy = (node.top + node.bottom) / 2
            out.append(" @(").append(cx).append(",").append(cy).append(")\n")
        }
        node.children.forEach { appendNode(it, depth + 1, out) }
    }

    // ---- Schema 辅助 ----

    private fun objectSchema(
        properties: Map<String, JSONObject>,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } })
        if (required.isNotEmpty()) put("required", JSONArray(required))
    }

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun numberProp(description: String): JSONObject =
        JSONObject().put("type", "number").put("description", description)

    private fun booleanProp(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun enumProp(description: String, values: List<String>): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .put("enum", JSONArray(values))

    /** key 允许字符串（名称）或整数（KeyEvent 键码）。 */
    private fun anyKeyProp(description: String): JSONObject = JSONObject()
        .put("description", description)
        .put("type", JSONArray().put("string").put("integer"))

    private fun gestureStrokesProp(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "array").put("items", JSONObject().put("type", "number")))

    /** batch 的动作数组：`[{tool, arguments}]`。 */
    private fun batchActionsProp(): JSONObject = JSONObject()
        .put("type", "array")
        .put("description", "要顺序执行的动作列表")
        .put(
            "items",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("tool", stringProp("工具名"))
                        .put(
                            "arguments",
                            JSONObject().put("type", "object").put("description", "该工具的参数"),
                        ),
                )
                .put("required", JSONArray().put("tool")),
        )

    private companion object {
        const val MAX_DUMP_CHARS = 4000
        const val MAX_TEXT_CHARS = 48
        const val DEFAULT_LONG_CLICK_MS = 600.0
        const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
        const val MAX_WAIT_TIMEOUT_MS = 120_000L
        const val DEFAULT_WAIT_INTERVAL_MS = 400L
        const val SETTLE_DEFAULT_MS = 600L
        const val SETTLE_POLL_MS = 150L
        const val SETTLE_MAX_MS = 4_000L
    }
}
