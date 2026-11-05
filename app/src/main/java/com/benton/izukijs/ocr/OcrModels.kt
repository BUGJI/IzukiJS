package com.benton.izukijs.ocr

enum class OcrMode { LOCAL, ONLINE }

enum class OcrProvider(val displayName: String) {
    BAIDU("百度 OCR"),
    GOOGLE_VISION("Google Vision"),
    CUSTOM("自定义"),
}

data class OcrConfig(
    val mode: OcrMode = OcrMode.LOCAL,
    val provider: OcrProvider = OcrProvider.BAIDU,
    /** 百度：API Key；Google：API Key */
    val apiKey: String = "",
    /** 百度：Secret Key */
    val secretKey: String = "",
    /** 自定义：请求地址 */
    val endpoint: String = "",
    /** 自定义：鉴权 Header 名 */
    val headerName: String = "",
    /** 自定义：鉴权 Header 值 */
    val headerValue: String = "",
)

/**
 * 单个识别结果（坐标为全分辨率屏幕像素）。
 *
 * [left]/[top]/[right]/[bottom] 是真实包围盒；[x]/[y] 为包围盒中心、[width]/[height]
 * 为宽高，均为派生值，保留以兼容现有脚本与调用方。
 * [suspicious] 为 true 表示原始包围盒超出图像范围，已被裁剪到有效区域。
 * [confidence] 为 0~1，引擎不提供时为 null。
 */
data class OcrBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Double? = null,
    val suspicious: Boolean = false,
) {
    val x: Int get() = (left + right) / 2

    val y: Int get() = (top + bottom) / 2

    val width: Int get() = (right - left).coerceAtLeast(0)

    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class OcrResult(val text: String, val blocks: List<OcrBlock>)
