package com.benton.izukijs.runtime.api

import android.graphics.Rect
import com.benton.izukijs.core.log.LogBus
import java.util.Locale
import org.json.JSONObject

/** `images` 各子模块共用的参数解析与格式化工具。 */

internal fun parseImageRegion(json: String, width: Int, height: Int, label: String, logBus: LogBus): Rect? {
    if (json.isBlank()) return Rect(0, 0, width, height)
    return try {
        val obj = JSONObject(json)
        val x = obj.optInt("x", 0)
        val y = obj.optInt("y", 0)
        val w = obj.optInt("width", obj.optInt("w", width))
        val h = obj.optInt("height", obj.optInt("h", height))
        val rect = Rect(x, y, x + w, y + h)
        if (w <= 0 || h <= 0 || !rect.intersect(0, 0, width, height)) {
            logBus.warn("$label 区域无效: $json")
            null
        } else {
            rect
        }
    } catch (t: Throwable) {
        logBus.warn("$label 区域解析失败: ${t.message}")
        null
    }
}

internal fun parseImageColor(color: String): Int? {
    val hex = color.trim().removePrefix("#")
    return try {
        when (hex.length) {
            6 -> (0xFF000000.toInt()) or hex.toInt(16)
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    } catch (t: Throwable) {
        null
    }
}

internal fun Double.confidenceText(): String = String.format(Locale.US, "%.3f", this)
