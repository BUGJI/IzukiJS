package com.benton.izukijs.vision

import kotlin.math.max
import kotlin.math.min

/** 原始 ROI 参数，允许负坐标与 0 宽高（语义见 [RoiResolver]）。 */
data class RectSpec(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * 识别范围。
 *
 * - [rect]：显式坐标，支持负坐标、0 宽高；
 * - [ref]：引用前置子识别的结果框（And/Or 的 `subName`）；
 * - [offset]：四个值分别加到 x / y / w / h。
 */
data class RoiSpec(
    val rect: RectSpec? = null,
    val ref: String? = null,
    val offset: IntArray = IntArray(4),
)

/**
 * 把 ROI 参数解析为屏幕坐标框。
 *
 * 语义对齐 MaaFramework：
 * - `x/y < 0` 从右/下边缘计算；
 * - `w/h = 0` 延伸到边缘；
 * - `w/h < 0` 取绝对值，并把 `(x, y)` 当作右下角；
 * - `roiOffset` 的四个值分别加到 x / y / w / h；
 * - 引用前置结果时，每个结果框都会成为一个 ROI。
 */
object RoiResolver {

    fun resolve(
        roi: RoiSpec?,
        refs: Map<String, List<VisionResult>>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<Box> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()

        val bases: List<Box> = when {
            roi?.ref != null -> refs[roi.ref].orEmpty().map { it.box }
            roi?.rect != null -> listOfNotNull(normalize(roi.rect, imageWidth, imageHeight))
            else -> listOf(Box(0, 0, imageWidth, imageHeight))
        }
        if (bases.isEmpty()) return emptyList()

        val offset = roi?.offset
        return bases.mapNotNull { base ->
            val dx = offset?.getOrNull(0) ?: 0
            val dy = offset?.getOrNull(1) ?: 0
            val dw = offset?.getOrNull(2) ?: 0
            val dh = offset?.getOrNull(3) ?: 0
            clip(
                Box(
                    left = base.left + dx,
                    top = base.top + dy,
                    right = base.left + dx + base.width + dw,
                    bottom = base.top + dy + base.height + dh,
                ),
                imageWidth,
                imageHeight,
            )
        }
    }

    private fun normalize(spec: RectSpec, imageWidth: Int, imageHeight: Int): Box? {
        var x = spec.x
        var y = spec.y
        var width = spec.width
        var height = spec.height

        if (x < 0) x = imageWidth + x
        if (y < 0) y = imageHeight + y
        if (width < 0) {
            width = -width
            x -= width
        }
        if (height < 0) {
            height = -height
            y -= height
        }
        if (width == 0) width = imageWidth - x
        if (height == 0) height = imageHeight - y

        return clip(Box(x, y, x + width, y + height), imageWidth, imageHeight)
    }

    private fun clip(box: Box, imageWidth: Int, imageHeight: Int): Box? {
        val left = max(0, min(box.left, imageWidth))
        val top = max(0, min(box.top, imageHeight))
        val right = max(0, min(box.right, imageWidth))
        val bottom = max(0, min(box.bottom, imageHeight))
        if (right <= left || bottom <= top) return null
        return Box(left, top, right, bottom)
    }
}
