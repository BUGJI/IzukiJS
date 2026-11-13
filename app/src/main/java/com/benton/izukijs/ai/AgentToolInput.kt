package com.benton.izukijs.ai

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

// ---- click ----

internal fun AgentTools.doClick(args: JSONObject): String {
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
            // 与坐标点击保持一致：明确区分「未校验」「校验通过」「无可校验控件」。
            put("hit_checked", matchSource == "ui")
            when {
                matchSource == "ocr" -> put("hit_reason", "文本匹配来自 OCR，无控件可校验")
                matchSource == "ui" -> put("hit_reason", "控件树匹配成功")
                else -> put("hit_reason", "未匹配到控件或 OCR 文字")
            }
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

internal fun AgentTools.doSwipe(args: JSONObject): String {
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

internal fun AgentTools.doText(args: JSONObject): String {
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
    var usedInputApi = false
    val ok = if (by == "id") {
        selectorApi.setTextById(id!!, text) || run {
            usedInputApi = true
            focusAndInput(id, text, method)
        }
    } else {
        usedInputApi = true
        inputApi.inputWithMethod(text, method)
    }
    // 只有真正走了输入 API 才读取剪贴板状态，避免读到上一次调用的残留值。
    val clipboardRestored = if (ok && usedInputApi) inputApi.lastClipboardRestored() else null
    // 输入后收起软键盘：ESC 更可靠，失败时回退到 back，并用 IME 状态确认是否真的收起。
    val imeDismissed = ok && dismissIme && inputApi.dismissIme()
    return jsonOf {
        put("ok", ok)
        put("method", method)
        put("dismiss_ime", dismissIme)
        if (dismissIme) put("ime_dismissed", imeDismissed)
        clipboardRestored?.let { put("clipboard_restored", it) }
    }
}

/**
 * 无障碍不可用时的设值兜底：用控件树（可能是 Shizuku 下的 `uiautomator dump`）定位控件，
 * 点击聚焦后按 [method] 输入（Shizuku / Root 下为 `input text` / 剪贴板粘贴 / ADBKeyboard 广播）。
 */
private fun AgentTools.focusAndInput(viewId: String, text: String, method: String): Boolean {
    val node = collectNodes { it.viewId == viewId }.firstOrNull() ?: return false
    val cx = ((node.left + node.right) / 2).toDouble()
    val cy = ((node.top + node.bottom) / 2).toDouble()
    inputApi.click(cx, cy)
    return inputApi.inputWithMethod(text, method)
}

internal fun AgentTools.doPress(args: JSONObject): String {
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
        null -> false
        else -> false
    }
    return okResult(ok, "按键")
}

// ---- wait ----

internal fun AgentTools.doWait(args: JSONObject): String {
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

// ---- batch ----

internal fun AgentTools.doBatch(args: JSONObject): String {
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

internal fun AgentTools.doGesture(args: JSONObject): String {
    val strokes = args.optJSONArray("strokes")
    if (args.optBoolean("dry_run", false)) {
        return jsonOf {
            put("ok", true)
            put("dry_run", true)
            put("strokes", strokes ?: JSONArray())
        }
    }
    return okResult(inputApi.gestureRaw(strokes?.toString() ?: "[]"), "手势")
}
