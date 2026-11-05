package com.benton.izukijs.ocr

/** OCR 包围盒校验结果；[clamped]/[dropped] 用于日志与测试。 */
data class OcrSanitizeResult(
    val blocks: List<OcrBlock>,
    val clamped: Int,
    val dropped: Int,
)

/**
 * 把 OCR 引擎返回的包围盒裁剪到图像范围内。
 *
 * 引擎理论上不会给出越界坐标，但不同引擎 / ROM 偶尔会返回异常值。这里不做静默
 * 丢块：能裁剪到有效区域的保留并标记 [OcrBlock.suspicious]，完全越界或退化的丢弃。
 */
object OcrGeometry {

    fun sanitize(blocks: List<OcrBlock>, imageWidth: Int, imageHeight: Int): OcrSanitizeResult {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return OcrSanitizeResult(emptyList(), clamped = 0, dropped = blocks.size)
        }
        val out = ArrayList<OcrBlock>(blocks.size)
        var clamped = 0
        var dropped = 0
        blocks.forEach { block ->
            if (block.right <= block.left || block.bottom <= block.top) {
                dropped++
                return@forEach
            }
            val left = block.left.coerceIn(0, imageWidth)
            val top = block.top.coerceIn(0, imageHeight)
            val right = block.right.coerceIn(0, imageWidth)
            val bottom = block.bottom.coerceIn(0, imageHeight)
            if (right <= left || bottom <= top) {
                dropped++
                return@forEach
            }
            val outOfBounds = left != block.left ||
                top != block.top ||
                right != block.right ||
                bottom != block.bottom
            if (outOfBounds) clamped++
            out.add(
                block.copy(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    suspicious = block.suspicious || outOfBounds,
                ),
            )
        }
        return OcrSanitizeResult(out, clamped, dropped)
    }
}
