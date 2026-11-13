package com.benton.izukijs.mcp.tools

import android.graphics.Bitmap
import android.graphics.Rect
import com.benton.izukijs.virtualscreen.ScreenTarget
import org.json.JSONArray
import org.json.JSONObject

// ---- 截图（返回图像内容块） ----

internal fun McpToolRegistry.screenshotTool(): McpTool = McpTool(
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

internal fun McpToolRegistry.encodeCapture(
    source: Bitmap,
    args: JSONObject,
    extraMeta: JSONObject? = null,
): McpToolResult {
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
        extraMeta?.keys()?.forEach { key -> put(key, extraMeta.opt(key)) }
    }
    return McpToolResult(
        listOf(
            McpContent.image(encoded.base64, encoded.mimeType),
            McpContent.text(meta.toString()),
        ),
    )
}

private fun McpToolRegistry.sizeOf(width: Int, height: Int): JSONObject =
    JSONObject().put("w", width).put("h", height)
