package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.runtime.api.EventsApi
import com.benton.izukijs.virtualscreen.ScreenTarget
import org.json.JSONArray
import org.json.JSONObject

// ---- 查找 ----

internal fun AgentTools.doOcr(args: JSONObject): String {
    val options = JSONObject()
    args.optJSONObject("roi")?.let { options.put("roi", it) }
    if (args.has("max_gap_y")) options.put("maxGapY", args.optInt("max_gap_y"))
    if (args.has("max_gap_x")) options.put("maxGapX", args.optInt("max_gap_x"))
    val lines = args.optBoolean("lines", false)
    val raw = if (lines) {
        ocrApi.recognizeLinesJsonWithOptions(options.toString())
    } else if (options.length() > 0) {
        ocrApi.recognizeBlocksJsonWithOptions(options.toString())
    } else {
        ocrApi.recognizeBlocksJson()
    }
    return raw ?: jsonOf { put("ok", false); put("message", "未识别到文字，或截图权限不可用") }
}

internal fun AgentTools.doFind(args: JSONObject): String {
    val by = args.optString("by").takeIf { it.isNotBlank() }
    val value = args.optString("value").takeIf { it.isNotBlank() }
    val text = args.optString("text").takeIf { it.isNotBlank() }
    if (by == null && value == null && text == null) {
        return errorJson("find: 需要 text，或 by + value")
    }
    val source = args.optString("source").ifBlank { SOURCE_AUTO }
    val fuzzy = args.optBoolean("fuzzy", true)
    val minConf = args.optDouble("min_conf", 0.0).coerceIn(0.0, 1.0)
    val outcome = search(by, value, text, source, fuzzy, minConf)
    val matches = outcome.matches
    val criteria = criteriaForFind(by, value, text)
    // 0 命中时解释逐条件命中数；有命中时反过来给出更稳定的选择器，便于脚本复用。
    val explain = if (matches.length() == 0 && criteria != null) {
        runCatching { JSONObject(selectorApi.explainRaw(criteria)) }.getOrNull()
    } else {
        null
    }
    val codegen = if (matches.length() > 0 && criteria != null) {
        runCatching { JSONObject(selectorApi.codegenRaw(criteria)) }.getOrNull()?.takeIf { it.optBoolean("ok") }
    } else {
        null
    }
    return jsonOf {
        put("ok", matches.length() > 0)
        put("matched", matches.length() > 0)
        put("query", text ?: value ?: by)
        put("source", source)
        put("count", matches.length())
        put("matches", matches)
        explain?.let { put("explain", it) }
        codegen?.let {
            put("selector_dsl", it.optString("dsl"))
            put("selector_js", it.optString("js"))
        }
        outcome.treeFailure?.let { put("tree_error", it.message) }
        outcome.treeNodes?.let { put("tree_nodes", it) }
        outcome.dumpMs?.let { put("dump_ms", it) }
        if (outcome.treeAttempts > 0) put("tree_attempts", outcome.treeAttempts)
    }
}

/** 把 find 的入参转成选择器条件 JSON；无法表达时返回 null（不附带解释）。 */
private fun criteriaForFind(by: String?, value: String?, text: String?): String? {
    val criteria = JSONObject()
    when (by) {
        "id" -> {
            value ?: return null
            criteria.put("id", value)
            return criteria.toString()
        }

        "desc" -> {
            value ?: return null
            criteria.put("descContains", value)
            return criteria.toString()
        }

        "text" -> {
            val query = value ?: text ?: return null
            criteria.put("textContains", query)
            return criteria.toString()
        }
    }
    val query = text ?: value ?: return null
    criteria.put("textContains", query)
    return criteria.toString()
}

internal fun AgentTools.doUiDump(args: JSONObject): String {
    val result = probeTree(TREE_PROBE_BUDGET_MS)
    return when (result) {
        is NodeTreeResult.Success -> {
            val out = StringBuilder()
            appendNode(result.root, 0, out)
            val criteria = args.optString("selector").ifBlank { args.optString("dsl") }
            val explain = if (criteria.isNotBlank()) {
                runCatching { JSONObject(selectorApi.explainRaw(criteria)) }.getOrNull()
            } else {
                null
            }
            val codegen = if (criteria.isNotBlank()) {
                runCatching { JSONObject(selectorApi.codegenRaw(criteria)) }.getOrNull()
            } else {
                null
            }
            jsonOf {
                put("ok", true)
                put("source", "ui")
                put("node_count", countNodes(result.root))
                put("tree", out.toString().trimEnd())
                explain?.let { put("explain", it) }
                codegen?.let { put("codegen", it) }
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

// ---- info ----

internal fun AgentTools.doInfo(args: JSONObject): String {
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

private fun AgentTools.foregroundInfo(target: ScreenTarget?): String {
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

internal fun AgentTools.doNotifications(args: JSONObject): String {
    val action = args.optString("action", "list").ifBlank { "list" }
    // Toast 由无障碍事件解析，只需要无障碍服务；通知列表才需要通知使用权。
    if (action.startsWith("toast")) {
        if (!notificationApi.toastAvailable()) {
            return errorJson("toast 读取需要无障碍服务，请先在「权限与能力」中开启无障碍")
        }
        return when (action) {
            "toast_list" -> {
                val array = runCatching {
                    JSONArray(notificationApi.toasts(args.optInt("limit", DEFAULT_NOTIFICATION_LIMIT).coerceIn(1, MAX_NOTIFICATION_LIMIT)))
                }.getOrNull() ?: JSONArray()
                jsonOf {
                    put("ok", true)
                    put("count", array.length())
                    put("toasts", array)
                }
            }

            "toast_latest" -> {
                val options = JSONObject()
                args.optString("package").trim().takeIf { it.isNotEmpty() }?.let { options.put("package", it) }
                args.optString("contains").trim().takeIf { it.isNotEmpty() }?.let { options.put("contains", it) }
                options.put(
                    "timeoutMs",
                    args.optDouble("timeout_ms", 10_000.0).coerceIn(0.0, 60_000.0),
                )
                notificationApi.latestToastRaw(options.toString()) ?: jsonOf {
                    put("ok", false)
                    put("message", "超时未收到匹配的 toast")
                }
            }

            else -> errorJson("notifications: 未知 toast action")
        }
    }
    if (!notificationApi.available()) {
        return errorJson("通知监听未开启，请在「权限与能力」中授予「通知使用权」")
    }
    return when (action) {
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

internal fun AgentTools.doEvents(args: JSONObject): String {
    // AI / MCP 调用没有脚本停止信号，startSeq=0 读取最近缓冲。
    val api = EventsApi({ false }, 0L)
    if (!api.available()) {
        return errorJson("事件读取需要无障碍服务，请先在「权限与能力」中开启无障碍")
    }
    val action = args.optString("action", "list").ifBlank { "list" }
    val options = JSONObject(args.toString())
    options.remove("action")
    if (options.has("timeout_ms")) options.put("timeoutMs", options.optDouble("timeout_ms", 10_000.0))
    if (options.has("quiet_ms")) options.put("quietMs", options.optLong("quiet_ms", 500L))
    if (options.has("text_contains")) options.put("textContains", options.optString("text_contains"))
    options.remove("timeout_ms")
    options.remove("quiet_ms")
    options.remove("text_contains")
    return when (action) {
        "status" -> api.status()

        "window" -> api.currentWindowRaw() ?: jsonOf {
            put("ok", false)
            put("message", "暂无窗口事件")
        }

        "idle" -> api.waitIdleRaw(options.toString())

        "list" -> api.listRaw(options.toString())

        "wait" -> api.waitRaw(options.toString()) ?: jsonOf {
            put("ok", false)
            put("message", "超时未等到匹配事件")
        }

        else -> errorJson("events: 未知 action（支持 list / wait / idle / window / status）")
    }
}

internal fun AgentTools.doNotificationAction(args: JSONObject): String {
    if (!notificationApi.available()) {
        return errorJson("通知监听未开启，请在「权限与能力」中授予「通知使用权」")
    }
    val key = args.optString("key").trim()
    if (key.isEmpty()) return errorJson("notification_action: 需要 key")
    return when (val action = args.optString("action", "click").ifBlank { "click" }) {
        "click" -> if (notificationApi.click(key)) {
            jsonOf {
                put("ok", true)
                put("action", action)
                put("key", key)
            }
        } else {
            errorJson("点击通知失败：通知已消失、没有 contentIntent，或监听未连接")
        }

        "delete" -> if (notificationApi.delete(key)) {
            jsonOf {
                put("ok", true)
                put("action", action)
                put("key", key)
            }
        } else {
            errorJson("删除通知失败：通知已消失或监听未连接")
        }

        else -> errorJson("notification_action: 未知 action（支持 click / delete）")
    }
}
