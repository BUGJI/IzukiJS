package com.benton.izukijs.pipeline

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pipeline 动作 / 校验 / 回放共用的 JSON 读取工具。
 *
 * 这些函数原先在 [DevicePipelineActions]、[ReplayPipelineActions]、[PipelineValidator]
 * 与 [ScreenshotPipelineVision] 各复制一份，行为已经出现过细微差异；集中后只保留一套语义。
 */

/** 2 或 4 个数字组成的数组视为矩形 / 坐标，而不是目标列表。 */
internal fun isRectArray(value: JSONArray): Boolean {
    if (value.length() != 2 && value.length() != 4) return false
    for (index in 0 until value.length()) {
        if (value.opt(index) !is Number) return false
    }
    return true
}

/** 取第一个非空字符串字段。 */
internal fun stringValue(obj: JSONObject, vararg keys: String): String? {
    for (key in keys) {
        val value = obj.opt(key)
        if (value is String && value.isNotBlank()) return value
    }
    return null
}

/** 取第一个数字字段。 */
internal fun number(obj: JSONObject, vararg keys: String): Double? {
    for (key in keys) {
        val value = obj.opt(key)
        if (value is Number) return value.toDouble()
    }
    return null
}

/** 目标参数既可以是单个值，也可以是目标列表；坐标数组保持为单个目标。 */
internal fun targetItems(raw: Any?): List<Any?> =
    if (raw is JSONArray && !isRectArray(raw)) {
        (0 until raw.length()).map { raw.opt(it) }
    } else {
        listOf(raw)
    }

/** 把动作参数里的任意值解析为目标；非法值返回 null。 */
internal fun parseTargetValueOrNull(raw: Any?): TargetSpec? = try {
    PipelineParser.parseTarget(
        JSONObject().put("value", raw),
        "value",
        "value_offset",
        TargetSpec(),
    )
} catch (_: Throwable) {
    null
}
