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

/** 单个识别结果（坐标为屏幕像素，x/y 为包围盒中心）。 */
data class OcrBlock(
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class OcrResult(val text: String, val blocks: List<OcrBlock>)
