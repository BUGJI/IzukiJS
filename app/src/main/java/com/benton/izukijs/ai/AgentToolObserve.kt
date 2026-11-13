package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.virtualscreen.ScreenTarget
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

internal fun AgentTools.doUiDump(): String {
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
