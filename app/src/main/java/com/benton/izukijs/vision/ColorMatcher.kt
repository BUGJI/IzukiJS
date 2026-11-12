package com.benton.izukijs.vision

import com.benton.izukijs.core.log.LogBus
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.core.Rect as CvRect

/**
 * 颜色匹配（找色）。
 *
 * 输入屏幕 Mat 为 RGBA；先转 BGR，再按 `method`（对齐 OpenCV ColorConversionCodes，
 * 例如 4=RGB、40=HSV、6=GRAY）转换，然后对每个 lower/upper 区间做 `inRange` 并合并。
 */
internal class ColorMatcher(
    private val logBus: LogBus,
) {

    fun match(
        screen: Mat,
        rois: List<Box>,
        spec: ColorMatchSpec,
    ): List<VisionResult> {
        if (screen.empty() || spec.ranges.isEmpty()) return emptyList()

        val collected = ArrayList<VisionResult>()
        for (roi in rois) {
            val region = screen.submat(CvRect(roi.left, roi.top, roi.width, roi.height))
            val bgr = Mat()
            val color = Mat()
            var bin: Mat? = null
            try {
                Imgproc.cvtColor(region, bgr, Imgproc.COLOR_RGBA2BGR)
                Imgproc.cvtColor(bgr, color, spec.method)
                bin = Mat.zeros(color.size(), CvType.CV_8UC1)
                for ((lower, upper) in spec.ranges) {
                    if (lower.size != color.channels() || upper.size != color.channels()) {
                        logBus.warn(
                            "找色区间通道数不匹配：method=${spec.method} 需要 ${color.channels()} 通道，" +
                                "lower=${lower.size} upper=${upper.size}",
                        )
                        continue
                    }
                    val single = Mat()
                    try {
                        Core.inRange(
                            color,
                            Scalar(lower.toScalarArray()),
                            Scalar(upper.toScalarArray()),
                            single,
                        )
                        Core.bitwise_or(bin, single, bin)
                    } finally {
                        single.release()
                    }
                }
                collected.addAll(
                    if (spec.connected) {
                        connectedResults(bin, roi, spec.count)
                    } else {
                        boundingResults(bin, roi, spec.count)
                    },
                )
            } finally {
                bin?.release()
                color.release()
                bgr.release()
                region.release()
            }
        }
        return collected
    }

    private fun IntArray.toScalarArray(): DoubleArray {
        val out = DoubleArray(size)
        for (i in indices) out[i] = this[i].toDouble()
        return out
    }

    private fun boundingResults(bin: Mat, roi: Box, minCount: Int): List<VisionResult> {
        val total = Core.countNonZero(bin)
        if (total < minCount) return emptyList()
        val bounding = Geometry.boundingRect(bin)
        if (bounding.width <= 0 || bounding.height <= 0) return emptyList()
        return listOf(
            VisionResult(
                box = Box(
                    roi.left + bounding.x,
                    roi.top + bounding.y,
                    roi.left + bounding.x + bounding.width,
                    roi.top + bounding.y + bounding.height,
                ),
                score = total.toDouble(),
                count = total,
            ),
        )
    }

    private fun connectedResults(bin: Mat, roi: Box, minCount: Int): List<VisionResult> {
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        try {
            val count = Imgproc.connectedComponentsWithStats(bin, labels, stats, centroids, 8, CvType.CV_32S)
            val results = ArrayList<VisionResult>()
            val statRow = IntArray(5)
            for (index in 1 until count) {
                stats.get(index, 0, statRow)
                val area = statRow[Imgproc.CC_STAT_AREA]
                if (area < minCount) continue
                val left = statRow[Imgproc.CC_STAT_LEFT]
                val top = statRow[Imgproc.CC_STAT_TOP]
                val width = statRow[Imgproc.CC_STAT_WIDTH]
                val height = statRow[Imgproc.CC_STAT_HEIGHT]
                if (width <= 0 || height <= 0) continue
                results.add(
                    VisionResult(
                        box = Box(roi.left + left, roi.top + top, roi.left + left + width, roi.top + top + height),
                        score = area.toDouble(),
                        count = area,
                    ),
                )
            }
            return nmsByCount(results)
        } finally {
            centroids.release()
            stats.release()
            labels.release()
        }
    }
}
