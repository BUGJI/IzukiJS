package com.benton.izukijs.ai

import android.view.KeyEvent
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.ocr.OcrBlock
import com.benton.izukijs.ocr.OcrLineSynthesis
import com.benton.izukijs.ocr.OcrMatcher
import com.benton.izukijs.runtime.api.AppApi
import com.benton.izukijs.runtime.api.DeviceApi
import com.benton.izukijs.runtime.api.InputApi
import com.benton.izukijs.runtime.api.NotificationApi
import com.benton.izukijs.runtime.api.OcrApi
import com.benton.izukijs.runtime.api.SelectorApi
import com.benton.izukijs.runtime.api.ShellApi
import com.benton.izukijs.virtualscreen.ScreenTarget
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
    private val notificationApi: NotificationApi,
    private val screenshotPathProvider: () -> String?,
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val policy: ToolPolicy = ToolPolicy(),
) {

    private val shellAvailable: Boolean get() = policy.allowShell && shellApi.available()

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
                "识别当前屏幕文字，返回结构化文本块：每块含文字与中心坐标 (x,y)、宽高、置信度。坐标为屏幕像素，可直接用于 click。" +
                    "注意本地 OCR 有精度边界（0/O、I/l、简繁、生僻字形易混，置信度常为 0.5~0.8），" +
                    "不要用 OCR 文本做精确匹配或关键分支判断，优先用 ui_dump / find(source=ui) 的控件文本交叉验证。",
                objectSchema(emptyMap()),
            ),
        )
        add(
            ToolSpec(
                "ui_dump",
                "读取当前界面的控件树（含文本、控件 id、内容描述与屏幕坐标）。返回 JSON（tree 为缩进文本）；持续动画界面会快速降级而不是白等。当 OCR 不够精确时使用。",
                objectSchema(emptyMap()),
            ),
        )
        add(
            ToolSpec(
                "find",
                "查找屏幕元素并返回匹配项（中心坐标 + 边界）。text 先按 OCR 行聚合做模糊包含匹配（同一行的多个 OCR 块会拼接），未命中再按编辑距离容错；也可用 by=id|desc + value 精确匹配控件。" +
                    "纯文字查找建议 source=auto（默认）：优先走 OCR（约 0.6~1s），未命中再回退控件树；source=ocr 最快，source=ui 只读控件树（较慢但精确），source=both 双源交叉验证。",
                objectSchema(
                    mapOf(
                        "text" to stringProp("要查找的文字（模糊包含）"),
                        "by" to enumProp("按控件属性精确匹配", listOf("id", "desc")),
                        "value" to stringProp("by 对应的匹配值"),
                        "source" to enumProp("查找来源", listOf("auto", "ocr", "ui", "both")),
                        "fuzzy" to booleanProp("OCR 精确匹配失败后是否按编辑距离容错，默认 true"),
                        "min_conf" to numberProp("忽略置信度低于该值的 OCR 块（0~1，默认 0）"),
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
                "读取设备信息。what=device：屏幕尺寸/机型/系统；battery：电量；current_app / foreground：当前前台包名；" +
                    "usage：最近使用过的应用（需使用情况访问）。target 可指定 auto / main / virtual；" +
                    "auto 在虚拟屏激活时同时返回虚拟屏与主屏的前台包名。",
                objectSchema(
                    mapOf(
                        "what" to enumProp(
                            "要读取的信息",
                            listOf("device", "battery", "current_app", "foreground", "usage"),
                        ),
                        "target" to enumProp("前台包名的目标屏幕", listOf("auto", "main", "virtual")),
                    ),
                ),
            ),
        )
        add(
            ToolSpec(
                "notifications",
                "读取通知栏（需在「通知使用权」中开启本应用，只读）。action=list：当前通知列表，支持 package / limit / since_ms / exclude_ongoing 过滤；" +
                    "action=latest：等待 package 指定的应用在本次调用后出现的新通知（用于抓取验证码 / 消息）。",
                objectSchema(
                    mapOf(
                        "action" to enumProp("操作", listOf("list", "latest")),
                        "package" to stringProp("按应用包名过滤；action=latest 时必填"),
                        "limit" to numberProp("action=list 最多返回条数（默认 20，上限 200）"),
                        "since_ms" to numberProp("只返回 postTime 不早于该时间戳的通知"),
                        "exclude_ongoing" to booleanProp("忽略常驻 / 进行中的通知，默认 false"),
                        "detail" to booleanProp("action=list 是否返回完整字段（默认 false，只返回精简字段）"),
                        "timeout_ms" to numberProp("action=latest 的等待超时（毫秒），默认 10000"),
                    ),
                ),
            ),
        )

        // ---- 输入 ----
        add(
            ToolSpec(
                "click",
                "点击或长按。by=coord（默认）用 x/y 像素坐标；normalized=true 时 x/y 为 0~1 比例（推荐，免疫截图缩放）。by=text|id|desc 时按 value 匹配控件。long=true 长按。" +
                    "坐标点击默认不做命中校验，结果含 hit_checked=false；需要命中的控件做自校验时传 hit=true（代价是一次控件树读取），结果会返回 hit 并置 hit_checked=true。",
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
                        "hit" to booleanProp("返回命中的控件（需读控件树，较慢；默认关闭）"),
                        "fuzzy" to booleanProp("by=text 时 OCR 是否按编辑距离容错，默认 true"),
                        "min_conf" to numberProp("by=text 时忽略置信度低于该值的 OCR 块（0~1，默认 0）"),
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
                        "dismiss_ime" to booleanProp("输入后收起软键盘（Shizuku/无线调试/Root 下发送 ESC keyevent 111 生效；无障碍后端忽略）"),
                        "dry_run" to booleanProp("只返回将要执行的操作，不真正输入"),
                    ),
                    required = listOf("text"),
                ),
            ),
        )
        add(
            ToolSpec(
                "press",
                "发送按键。key 可为 back / home / recents / enter / esc，或 Android KeyEvent 键码（整数，如返回=4、回车=66）。收软键盘一般用 esc（Shizuku/无线调试/Root 下 keyevent 111），back 往往无效。",
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
                "等待界面变化。for=text：等待出现包含 text 的文字；for=idle：等待界面稳定（控件树不再变化）。返回 ok 表示是否等到、matched 与耗时；无控件树时 idle 会降级为固定等待并标 degraded。",
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
                    "不适合把 ocr / ui_dump 等大输出放进批内。actions 为 [{tool,arguments}]。" +
                    "批内若含多个会造成界面跳转的 UI 操作（点击 / 按键 / 滑动），后一步常会因前一步的过渡动画尚未结束而落空，" +
                    "请给相关 click 传 settle=true 等待界面稳定。",
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
                    "执行 Shell 命令并返回结构化结果 {ok, stdout, stderr, exit_code, elapsed_ms}（需要 Shizuku 或 Root）。",
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

    private fun dispatch(name: String, args: JSONObject): String {
        // 安全策略必须放在 dispatch 层：batch 内嵌动作也经由这里，无法绕过。
        policyViolation(name, args)?.let { return it }
        return when (name) {
            "ocr" -> ocrApi.recognizeBlocksJson()
                ?: jsonOf { put("ok", false); put("message", "未识别到文字，或截图权限不可用") }

            "ui_dump" -> doUiDump()

            "find" -> doFind(args)

            "screenshot" -> screenshotPathProvider()?.let { "截图已保存：$it" } ?: "截图失败"

            "info" -> doInfo(args)

            "notifications" -> doNotifications(args)

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

            "shell" -> doShell(args)

            else -> "[错误] 未知工具：$name"
        }
    }

    /** 命中安全策略时返回拒绝结果，否则返回 null。 */
    private fun policyViolation(name: String, args: JSONObject): String? = when (name) {
        "shell" -> {
            if (policy.allowDangerousShell) {
                null
            } else {
                ShellGuard.reason(args.optString("command"))?.let { reason ->
                    blockedJson("命令被安全策略拦截（$reason）")
                }
            }
        }

        "app" -> {
            if (args.optString("action") != "launch") {
                null
            } else {
                val pkg = args.optString("package")
                if (policy.isPackageBlocked(pkg)) {
                    blockedJson("目标应用 $pkg 在敏感应用黑名单中，已拒绝启动")
                } else {
                    null
                }
            }
        }

        else -> null
    }

    private fun blockedJson(message: String): String = jsonOf {
        put("ok", false)
        put("blocked", true)
        put("message", message)
    }

    // ---- click ----

    private fun doClick(args: JSONObject): String {
        val by = args.optString("by", "coord").ifBlank { "coord" }
        val long = args.optBoolean("long", false)
        val duration = args.optDouble("duration_ms", DEFAULT_LONG_CLICK_MS)
        val fuzzy = args.optBoolean("fuzzy", true)
        val minConf = args.optDouble("min_conf", 0.0).coerceIn(0.0, 1.0)

        if (by == "text" || by == "id" || by == "desc") {
            val value = args.optString("value")
            if (value.isBlank()) return errorJson("click: by=$by 需要 value")
            if (args.optBoolean("dry_run", false)) {
                val matches = search(
                    by = by,
                    value = value,
                    text = null,
                    source = if (by == "text") SOURCE_AUTO else SOURCE_UI,
                    fuzzy = fuzzy,
                    minConfidence = minConf,
                )
                return jsonOf {
                    put("ok", matches.length() > 0)
                    put("matched", matches.length() > 0)
                    put("dry_run", true)
                    put("by", by)
                    put("value", value)
                    put("count", matches.length())
                    put("matches", matches)
                }
            }
            val uiClicked = when (by) {
                "text" -> if (long) selectorApi.longClickByText(value) else selectorApi.clickByText(value)
                "id" -> if (long) selectorApi.longClickById(value) else selectorApi.clickById(value)
                else -> selectorApi.clickByDesc(value)
            }
            var clicked = uiClicked
            var matchSource = if (uiClicked) "ui" else null
            if (!uiClicked && by == "text") {
                // 无障碍不可用或控件树没有文本时，用 OCR 行匹配兜底，避免直接返回失败。
                val line = ocrLineMatches(value, fuzzy, minConf).firstOrNull()?.line
                if (line != null) {
                    clicked = if (long) {
                        inputApi.longClick(line.x.toDouble(), line.y.toDouble(), duration)
                    } else {
                        inputApi.click(line.x.toDouble(), line.y.toDouble())
                    }
                    if (clicked) matchSource = "ocr"
                }
            }
            if (clicked && args.optBoolean("settle", false)) waitIdle(SETTLE_DEFAULT_MS)
            return jsonOf {
                put("ok", clicked)
                put("matched", clicked)
                put("executed", clicked)
                put("by", by)
                put("value", value)
                put("long", long)
                matchSource?.let { put("match_source", it) }
                if (!clicked) put("message", "未匹配到控件或 OCR 文字")
            }
        }

        val x = resolveX(args)
        val y = resolveY(args)
        if (x == null || y == null) return errorJson("click: 坐标无效（x/y 缺失或 normalized 越界）")
        // 命中测试要读控件树（无无障碍时是一次较慢的 uiautomator dump），仅用于自校验，
        // 默认不做，避免每次坐标点击都白白等一次 dump。
        val dryRun = args.optBoolean("dry_run", false)
        val probe = if (dryRun || args.optBoolean("hit", false)) hitProbe(x, y) else null
        if (dryRun) {
            return jsonOf {
                put("ok", true)
                put("dry_run", true)
                put("x", x)
                put("y", y)
                put("hit_checked", probe?.checked ?: false)
                probe?.hit?.let { put("hit", it) }
                probe?.reason?.let { put("hit_reason", it) }
            }
        }
        val ok = if (long) inputApi.longClick(x, y, duration) else inputApi.click(x, y)
        if (ok && args.optBoolean("settle", false)) waitIdle(SETTLE_DEFAULT_MS)
        return jsonOf {
            put("ok", ok)
            put("executed", ok)
            put("x", x)
            put("y", y)
            put("long", long)
            put("hit_checked", probe?.checked ?: false)
            probe?.hit?.let { put("hit", it) }
            probe?.reason?.let { put("hit_reason", it) }
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
        val dismissIme = args.optBoolean("dismiss_ime", false)
        if (by == "id" && id == null) return errorJson("text: by=id 需要 value")
        if (args.optBoolean("dry_run", false)) {
            return jsonOf {
                put("ok", true)
                put("dry_run", true)
                put("text", text)
                put("by", by ?: "focus")
                put("method", method)
                put("dismiss_ime", dismissIme)
                id?.let { put("value", it) }
            }
        }
        val ok = if (by == "id") {
            selectorApi.setTextById(id!!, text) || focusAndInput(id, text, method)
        } else {
            inputApi.inputWithMethod(text, method)
        }
        // 输入后收起软键盘：ESC（keyevent 111）比 back 更可靠；无障碍后端不支持该键，会静默无效果。
        val imeDismissed = ok && dismissIme && inputApi.press(KeyEvent.KEYCODE_ESCAPE)
        return jsonOf {
            put("ok", ok)
            put("method", method)
            put("dismiss_ime", dismissIme)
            if (dismissIme) put("ime_dismissed", imeDismissed)
        }
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
                "esc", "escape" -> inputApi.press(KeyEvent.KEYCODE_ESCAPE)
                else -> raw.trim().toIntOrNull()?.let { inputApi.press(it) } ?: false
            }
            else -> false
        }
        return okResult(ok, "按键")
    }

    // ---- shell ----

    private fun doShell(args: JSONObject): String {
        if (!shellAvailable) {
            return errorJson("Shell 工具未启用或当前无 Shizuku/Root 后端")
        }
        val started = System.currentTimeMillis()
        val result = shellApi.run(args.optString("command"))
            ?: return jsonOf {
                put("ok", false)
                put("stage", "exec")
                put("message", "Shell 执行失败：无可用后端")
                put("elapsed_ms", System.currentTimeMillis() - started)
            }
        return jsonOf {
            put("ok", result.exitCode == 0)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("exit_code", result.exitCode)
            put("elapsed_ms", System.currentTimeMillis() - started)
        }
    }

    // ---- info ----

    private fun doInfo(args: JSONObject): String {
        val what = args.optString("what", "device")
        val target = ScreenTarget.from(args.optString("target"))
        return when (what) {
            "battery" -> "电量 ${deviceApi.batteryLevel()}%"

            "current_app", "foreground" -> foregroundInfo(target)

            "usage" -> if (!appApi.hasUsageAccess()) {
                "未授予使用情况访问，无法读取应用使用记录"
            } else {
                appApi.usageStats(0.0, 20)
            }

            else -> "屏幕 ${deviceApi.screenWidth()}x${deviceApi.screenHeight()}，" +
                "机型 ${deviceApi.brand()} ${deviceApi.model()}，Android SDK ${deviceApi.androidVersion()}"
        }
    }

    private fun foregroundInfo(target: ScreenTarget?): String {
        when (target) {
            ScreenTarget.VIRTUAL -> {
                val virtual = appApi.virtualPackage()
                    ?: return "无法获取虚拟屏前台包名（虚拟屏未运行或暂无前台应用）"
                return "虚拟屏前台包名：$virtual"
            }

            ScreenTarget.MAIN -> {
                val main = appApi.mainPackage()
                    ?: return "无法获取主屏前台包名（需无障碍 / Shizuku / 使用情况访问）"
                return "主屏前台包名：$main"
            }

            ScreenTarget.AUTO, null -> Unit
        }

        if (!appApi.virtualScreenActive()) {
            val current = appApi.currentPackage()
                ?: return "无法获取当前包名（需无障碍 / Shizuku / 使用情况访问）"
            return "当前前台包名：$current"
        }
        val virtual = appApi.virtualPackage() ?: "（暂无可读前台应用）"
        val main = appApi.mainPackage()?.takeIf { it != virtual } ?: "（未知或与虚拟屏相同）"
        return "虚拟屏前台包名：$virtual；主屏前台包名：$main"
    }

    private fun doNotifications(args: JSONObject): String {
        if (!notificationApi.available()) {
            return errorJson("通知监听未开启，请在「权限与能力」中授予「通知使用权」")
        }
        return when (args.optString("action", "list").ifBlank { "list" }) {
            "list" -> notificationApi.query(
                packageName = args.optString("package").trim().ifBlank { null },
                limit = args.optInt("limit", DEFAULT_NOTIFICATION_LIMIT).coerceIn(1, MAX_NOTIFICATION_LIMIT),
                since = args.optLong("since_ms", 0L),
                excludeOngoing = args.optBoolean("exclude_ongoing", false),
                detail = args.optBoolean("detail", false),
            )

            "latest" -> {
                val pkg = args.optString("package")
                if (pkg.isBlank()) return errorJson("notifications: action=latest 需要 package")
                val timeout = args.optDouble("timeout_ms", 10_000.0).coerceIn(0.0, 60_000.0)
                notificationApi.latest(pkg, timeout) ?: jsonOf {
                    put("ok", false)
                    put("message", "超时未收到来自 $pkg 的新通知")
                }
            }

            else -> errorJson("notifications: 未知 action")
        }
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
                                put("elapsed_ms", step.elapsedMs)
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
        val deadline = started + timeout

        if (mode == "text") {
            val query = args.optString("text")
            if (query.isBlank()) return errorJson("wait: for=text 需要 text")
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val raw = ocrApi.findRaw(query)
                if (raw != null) {
                    return jsonOf {
                        put("ok", true)
                        put("matched", true)
                        put("elapsed_ms", System.currentTimeMillis() - started)
                        put("position", raw)
                    }
                }
                val sleep = minOf(interval, deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                sleepQuietly(sleep)
            }
            return jsonOf {
                put("ok", false)
                put("matched", false)
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("message", "超时未出现「$query」")
            }
        }

        // idle：等待控件树连续两次相同并保持 settle_ms。每次探测都受总 deadline 约束，
        // 单次 dump 预算不超过剩余时间，避免外层 timeout 被一次慢 dump 大幅超支。
        val first = treeProbe(deadline)
        val firstRoot = first?.rootOrNull()
        if (firstRoot == null) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            sleepQuietly(minOf(settleMs, remaining))
            return jsonOf {
                put("ok", false)
                put("matched", false)
                put("degraded", true)
                put("elapsed_ms", System.currentTimeMillis() - started)
                put("message", "无控件树（${failureMessage(first)}），仅固定等待 ${minOf(settleMs, remaining)}ms")
            }
        }
        var last: String? = signature(firstRoot)
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(minOf(interval, deadline - System.currentTimeMillis()).coerceAtLeast(1L))
            val result = treeProbe(deadline) ?: break
            val root = result.rootOrNull()
            if (root == null) {
                last = null
                stableSince = System.currentTimeMillis()
                continue
            }
            val sig = signature(root)
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
            put("ok", false)
            put("matched", false)
            put("timed_out", true)
            put("stage", "probe")
            put("elapsed_ms", System.currentTimeMillis() - started)
        }
    }

    /** 在 [deadline] 内取一次控件树；预算不足时返回 null（调用方应直接放弃本轮）。 */
    private fun treeProbe(deadline: Long): NodeTreeResult? {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining < MIN_PROBE_MS) return null
        return nodeTreeProvider(minOf(remaining, TREE_PROBE_BUDGET_MS))
    }

    private fun failureMessage(result: NodeTreeResult?): String = when (result) {
        null -> "等待超时"
        NodeTreeResult.NoBackend -> "需开启无障碍或 Shizuku"
        is NodeTreeResult.Failure -> result.message
        is NodeTreeResult.Success -> "控件树为空"
    }

    private fun waitIdle(settleMs: Long) {
        val deadline = System.currentTimeMillis() + SETTLE_MAX_MS
        val first = treeProbe(deadline)
        val root = first?.rootOrNull()
        if (root == null) {
            sleepQuietly(minOf(settleMs, SETTLE_MAX_MS))
            return
        }
        var last: String? = signature(root)
        var stableSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(minOf(SETTLE_POLL_MS, deadline - System.currentTimeMillis()).coerceAtLeast(1L))
            val result = treeProbe(deadline) ?: return
            val nextRoot = result.rootOrNull()
            if (nextRoot == null) {
                last = null
                stableSince = System.currentTimeMillis()
                continue
            }
            val next = signature(nextRoot)
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
        val source = args.optString("source").ifBlank { SOURCE_AUTO }
        val fuzzy = args.optBoolean("fuzzy", true)
        val minConf = args.optDouble("min_conf", 0.0).coerceIn(0.0, 1.0)
        val matches = search(by, value, text, source, fuzzy, minConf)
        return jsonOf {
            put("ok", matches.length() > 0)
            put("matched", matches.length() > 0)
            put("query", text ?: value ?: by)
            put("source", source)
            put("count", matches.length())
            put("matches", matches)
        }
    }

    /**
     * 统一的元素查找：by=id/desc 精确匹配控件，否则按 text 模糊匹配控件与 OCR。
     *
     * [source] 控制取数来源：`ui` 只读控件树（较慢但精确），`ocr` 只识别屏幕文字（最快），
     * `both` 双源交叉验证，`auto` 先 OCR、未命中再回退控件树（纯文字查找的推荐默认值，可避免
     * 在有动画的界面上白等一次 `uiautomator dump`）。
     */
    private fun search(
        by: String?,
        value: String?,
        text: String?,
        source: String = SOURCE_BOTH,
        fuzzy: Boolean = true,
        minConfidence: Double = 0.0,
    ): JSONArray {
        val matches = JSONArray()
        if (by == "id" && value != null) {
            collectNodes { it.viewId == value }.forEach { matches.put(nodeLabel(it)) }
            return matches
        }
        if (by == "desc" && value != null) {
            collectNodes { it.contentDescription?.contains(value) == true }.forEach { matches.put(nodeLabel(it)) }
            return matches
        }
        val query = text ?: value ?: return matches
        fun uiMatches() {
            collectNodes {
                it.text?.contains(query) == true || it.contentDescription?.contains(query) == true
            }.forEach { matches.put(nodeLabel(it)) }
        }
        when (source) {
            SOURCE_UI -> uiMatches()
            SOURCE_OCR -> ocrBlocks(query, fuzzy, minConfidence).forEach { matches.put(it) }
            SOURCE_AUTO -> {
                ocrBlocks(query, fuzzy, minConfidence).forEach { matches.put(it) }
                if (matches.length() == 0) uiMatches()
            }
            else -> {
                uiMatches()
                ocrBlocks(query, fuzzy, minConfidence).forEach { matches.put(it) }
            }
        }
        return matches
    }

    /** 解析 OCR 结构化结果并按行聚合匹配，返回统一格式的匹配项。 */
    private fun ocrBlocks(
        query: String,
        fuzzy: Boolean = true,
        minConfidence: Double = 0.0,
    ): List<JSONObject> = ocrLineMatches(query, fuzzy, minConfidence).map { match ->
        val line = match.line
        JSONObject().apply {
            put("source", "ocr")
            put("text", line.text)
            put("center", JSONArray().put(line.x).put(line.y))
            put("bounds", JSONArray().put(line.left).put(line.top).put(line.right).put(line.bottom))
            put("line", true)
            put("block_count", line.blockCount)
            if (match.fuzzy) {
                put("fuzzy", true)
                put("similarity", match.similarity)
            }
            line.confidence?.let { put("conf", it) }
        }
    }

    private fun ocrLineMatches(
        query: String,
        fuzzy: Boolean,
        minConfidence: Double,
    ): List<com.benton.izukijs.ocr.OcrLineMatch> {
        val json = ocrApi.recognizeBlocksJson() ?: return emptyList()
        val array = runCatching { JSONObject(json).optJSONArray("blocks") }.getOrNull() ?: return emptyList()
        val blocks = ArrayList<OcrBlock>(array.length())
        for (i in 0 until array.length()) {
            val block = array.optJSONObject(i) ?: continue
            val text = block.optString("text")
            if (text.isBlank()) continue
            val x = block.optInt("x")
            val y = block.optInt("y")
            val w = block.optInt("w")
            val h = block.optInt("h")
            blocks.add(
                OcrBlock(
                    text = text,
                    left = block.optInt("left", x - w / 2),
                    top = block.optInt("top", y - h / 2),
                    right = block.optInt("right", x + w / 2),
                    bottom = block.optInt("bottom", y + h / 2),
                    confidence = if (block.has("conf")) block.optDouble("conf") else null,
                    suspicious = block.optBoolean("suspicious", false),
                ),
            )
        }
        val lines = OcrLineSynthesis.synthesize(blocks)
        return OcrMatcher.match(lines, query, fuzzy = fuzzy, minConfidence = minConfidence)
    }

    // ---- 命中测试 ----

    private data class HitProbe(
        val checked: Boolean,
        val hit: JSONObject? = null,
        val reason: String? = null,
    )

    /** 返回屏幕坐标 (x,y) 下最深的控件快照标签，并区分「没有控件树」与「有树但无命中」。 */
    private fun hitProbe(x: Double, y: Double): HitProbe {
        val result = nodeTreeProvider(HIT_PROBE_BUDGET_MS)
        val root = result.rootOrNull()
            ?: return HitProbe(checked = false, reason = failureMessage(result))
        val node = deepestAt(root, x.toInt(), y.toInt()) ?: return HitProbe(checked = true)
        return HitProbe(checked = true, hit = nodeLabel(node))
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
        val root = nodeTreeProvider(TREE_PROBE_BUDGET_MS).rootOrNull() ?: return emptyList()
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
    private fun signature(root: NodeSnapshot): String {
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

    private fun doUiDump(): String {
        val result = nodeTreeProvider(TREE_PROBE_BUDGET_MS)
        return when (result) {
            is NodeTreeResult.Success -> {
                val out = StringBuilder()
                appendNode(result.root, 0, out)
                jsonOf {
                    put("ok", true)
                    put("source", "ui")
                    put("node_count", countNodes(result.root))
                    put("tree", out.toString().trimEnd())
                }
            }

            NodeTreeResult.NoBackend -> jsonOf {
                put("ok", false)
                put("stage", "dump")
                put("source", "ui")
                put("message", "无法读取控件树，需开启无障碍或 Shizuku")
            }

            is NodeTreeResult.Failure -> jsonOf {
                put("ok", false)
                put("stage", "dump")
                put("source", "ui")
                put("message", result.message)
            }
        }
    }

    private fun countNodes(root: NodeSnapshot): Int {
        var count = 1
        root.children.forEach { count += countNodes(it) }
        return count
    }

    /** 从读取结果中取出控件树；无后端或读取失败时返回 null。 */
    private fun NodeTreeResult.rootOrNull(): NodeSnapshot? = (this as? NodeTreeResult.Success)?.root

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
        const val TREE_PROBE_BUDGET_MS = 1_500L
        const val HIT_PROBE_BUDGET_MS = 1_200L
        const val MIN_PROBE_MS = 120L
        const val DEFAULT_NOTIFICATION_LIMIT = 20
        const val MAX_NOTIFICATION_LIMIT = 200

        const val SOURCE_AUTO = "auto"
        const val SOURCE_UI = "ui"
        const val SOURCE_OCR = "ocr"
        const val SOURCE_BOTH = "both"
    }
}
