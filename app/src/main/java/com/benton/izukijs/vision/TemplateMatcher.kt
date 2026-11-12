package com.benton.izukijs.vision

import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.developer.DeveloperTuning
import java.util.PriorityQueue
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.Rect as CvRect

/**
 * 模板匹配（找图）。
 *
 * 参考 MaaFramework `TemplateMatcher`：多模板、每模板阈值、可配置 method、绿色掩码、
 * 单次扫描局部极大值 + IoU NMS。分数统一为「越高越好」。
 */
internal class TemplateMatcher(
    private val logBus: LogBus,
) {

    fun match(
        screen: Mat,
        rois: List<Box>,
        spec: TemplateMatchSpec,
        templates: List<Mat>,
    ): List<VisionResult> {
        if (screen.empty() || templates.isEmpty()) return emptyList()

        val collected = ArrayList<VisionResult>()
        val limit = DeveloperTuning.current.maxImageMatches.coerceAtLeast(1)

        for (index in templates.indices) {
            val template = templates[index]
            if (template.empty()) continue
            val threshold = spec.thresholds.getOrElse(index) { spec.thresholds.last() }
            val method = normalizeMethod(spec.method)
            val mask = if (spec.greenMask) createGreenMask(template) else null

            try {
                for (roi in rois) {
                    if (template.cols() > roi.width || template.rows() > roi.height) {
                        logBus.debug("找图跳过：模板大于查找区域 ${template.cols()}×${template.rows()} / ${roi.width}×${roi.height}")
                        continue
                    }
                    val region = screen.submat(CvRect(roi.left, roi.top, roi.width, roi.height))
                    val result = Mat()
                    try {
                        if (mask != null && !mask.empty()) {
                            try {
                                Imgproc.matchTemplate(region, template, result, method, mask)
                            } catch (t: Throwable) {
                                logBus.warn("模板掩码匹配失败，回退为无掩码: ${t.message}")
                                Imgproc.matchTemplate(region, template, result, method)
                            }
                        } else {
                            Imgproc.matchTemplate(region, template, result, method)
                        }
                        // SQDIFF 系列分数越低越好；先归一化到 0..1，再翻转为越高越好。
                        if (method == Imgproc.TM_SQDIFF || method == Imgproc.TM_SQDIFF_NORMED) {
                            if (method == Imgproc.TM_SQDIFF) {
                                val scale = 1.0 / (template.cols().toDouble() * template.rows() * 255.0 * 255.0)
                                Core.multiply(result, Scalar(scale), result)
                            }
                            val ones = Mat.ones(Size(result.cols().toDouble(), result.rows().toDouble()), CvType.CV_32F)
                            try {
                                Core.subtract(ones, result, result)
                            } finally {
                                ones.release()
                            }
                        }
                        collected.addAll(collect(result, roi, template.cols(), template.rows(), threshold, limit))
                    } finally {
                        result.release()
                        region.release()
                    }
                }
            } finally {
                mask?.release()
            }
        }

        if (collected.isEmpty()) return emptyList()
        // 不能固定 0.1：用户可能把阈值设得更低，NMS 不应再次过滤。
        return nmsByScore(collected, NMS_THRESHOLD, spec.thresholds.minOrNull() ?: 0.0)
    }

    private fun collect(
        result: Mat,
        roi: Box,
        templateWidth: Int,
        templateHeight: Int,
        threshold: Double,
        limit: Int,
    ): List<VisionResult> {
        val resultWidth = result.cols()
        val resultHeight = result.rows()
        if (resultWidth <= 0 || resultHeight <= 0) return emptyList()

        val maxCandidates = (limit * 4).coerceAtLeast(32).coerceAtMost(MAX_CANDIDATES)
        val heap = PriorityQueue<VisionResult>(maxCandidates + 1, compareBy { it.score })
        val rows = Array(3) { FloatArray(resultWidth) }
        result.get(0, 0, rows[0])
        if (resultHeight > 1) result.get(1, 0, rows[1])

        for (y in 0 until resultHeight) {
            val current = rows[y % 3]
            val previous = if (y > 0) rows[(y - 1) % 3] else null
            val next = if (y + 1 < resultHeight) rows[(y + 1) % 3] else null
            for (x in 0 until resultWidth) {
                val value = current[x]
                if (!value.isFinite() || value < threshold) continue
                // 只保留局部极大值，避免平坦区域产生大量候选。
                if (x > 0 && current[x - 1] >= value) continue
                if (previous != null && previous[x] >= value) continue
                if (x + 1 < resultWidth && current[x + 1] > value) continue
                if (next != null && next[x] > value) continue

                val box = Box(
                    left = roi.left + x,
                    top = roi.top + y,
                    right = roi.left + x + templateWidth,
                    bottom = roi.top + y + templateHeight,
                )
                heap.add(VisionResult(box = box, score = value.toDouble()))
                if (heap.size > maxCandidates) heap.poll()
            }
            if (y + 2 < resultHeight) result.get(y + 2, 0, rows[(y + 2) % 3])
        }
        return heap.toList()
    }

    private fun createGreenMask(template: Mat): Mat? {
        if (template.channels() < 3) return null
        val mask = Mat()
        val green = if (template.channels() == 4) {
            Scalar(0.0, 255.0, 0.0, 255.0)
        } else {
            Scalar(0.0, 255.0, 0.0)
        }
        Core.inRange(template, green, green, mask)
        Core.bitwise_not(mask, mask)
        if (Core.countNonZero(mask) == mask.rows() * mask.cols()) {
            mask.release()
            return null
        }
        return mask
    }

    private fun normalizeMethod(method: Int): Int = when (method) {
        in 10000..19999 -> method - 10000
        0 -> 1
        else -> method
    }

    private companion object {
        const val NMS_THRESHOLD = 0.2
        const val MAX_CANDIDATES = 4096
    }
}
