package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.graphics.Rect
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.vision.TemplateImageCache
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** `images` 找图相关实现（模板匹配、多结果 NMS、进程级模板缓存）。 */
internal class ImageMatchOps(
    private val screenshotProvider: () -> Bitmap?,
    private val logBus: LogBus,
) {

    data class Match(val x: Int, val y: Int, val confidence: Double)

    private data class Candidate(val x: Int, val y: Int, val confidence: Double)

    fun findImageRaw(templatePath: String, threshold: Double): String? =
        findImageRegionRaw(templatePath, threshold, "")

    fun findImageRegionRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
    ): String? {
        val screen = screenshotProvider() ?: run {
            logBus.warn("找图失败：无法获取屏幕截图")
            return null
        }
        return try {
            val region = parseImageRegion(regionJson, screen.width, screen.height, "找图", logBus)
                ?: return null
            val match = PerfMetrics.measure("image.findImage") {
                matchTemplate(screen, templatePath, threshold, region, 1).firstOrNull()
            }
            if (match == null) {
                PerfMetrics.count("image.findImage.miss")
                logBus.debugThrottled("image.findImage", "找图 ${File(templatePath).name} → 未命中 ✗")
                null
            } else {
                PerfMetrics.count("image.findImage.hit")
                logBus.debugThrottled(
                    "image.findImage",
                    "找图 ${File(templatePath).name} → (${match.x}, ${match.y}) " +
                        "置信度 ${match.confidence.confidenceText()} ✓",
                )
                "${match.x},${match.y},${match.confidence}"
            }
        } finally {
            screen.recycle()
        }
    }

    fun findAllImagesRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String {
        val screen = screenshotProvider() ?: run {
            logBus.warn("找图失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            val region = parseImageRegion(regionJson, screen.width, screen.height, "找图", logBus)
                ?: return "[]"
            val matches = PerfMetrics.measure("image.findImage") {
                matchTemplate(screen, templatePath, threshold, region, limit)
            }
            logBus.debugThrottled(
                "image.findImage",
                "找图 ${File(templatePath).name} → ${matches.size} 个结果",
            )
            val array = JSONArray()
            matches.forEach { match ->
                array.put(
                    JSONObject().apply {
                        put("x", match.x)
                        put("y", match.y)
                        put("confidence", match.confidence)
                    },
                )
            }
            array.toString()
        } finally {
            screen.recycle()
        }
    }

    fun findImageInRaw(screenPath: String, templatePath: String, threshold: Double): String? {
        val screen = ImageDecode.decode(screenPath, logBus, "找图") ?: return null
        return try {
            val match = matchTemplate(
                screen,
                templatePath,
                threshold,
                Rect(0, 0, screen.width, screen.height),
                1,
            ).firstOrNull()
            if (match == null) {
                PerfMetrics.count("image.findImage.miss")
                return null
            }
            PerfMetrics.count("image.findImage.hit")
            "${match.x},${match.y},${match.confidence}"
        } finally {
            screen.recycle()
        }
    }

    private fun matchTemplate(
        screen: Bitmap,
        templatePath: String,
        threshold: Double,
        region: Rect,
        limit: Int,
    ): List<Match> = PerfMetrics.measure("image.matchTemplate") {
        if (!ImageOpenCv.ensure(logBus)) return emptyList()
        val lease = TemplateImageCache.acquire(listOf(templatePath), logBus, "找图")
        val template = lease.mats.firstOrNull()
        if (template == null) {
            TemplateImageCache.release(lease)
            return emptyList()
        }
        var screenMat: Mat? = null
        var regionMat: Mat? = null
        var result: Mat? = null
        try {
            if (template.cols() > region.width() || template.rows() > region.height()) {
                logBus.debug("找图 ${File(templatePath).name} → 跳过：模板大于查找区域 ✗")
                return emptyList()
            }
            screenMat = Mat()
            Utils.bitmapToMat(screen, screenMat)
            // 用 Mat ROI 代替先裁剪 Bitmap：省掉一次整图/区域位图分配与拷贝。
            regionMat = if (region.left == 0 &&
                region.top == 0 &&
                region.width() == screen.width &&
                region.height() == screen.height
            ) {
                screenMat
            } else {
                screenMat.submat(
                    org.opencv.core.Rect(region.left, region.top, region.width(), region.height()),
                )
            }
            result = Mat()
            Imgproc.matchTemplate(regionMat, template, result, Imgproc.TM_CCOEFF_NORMED)
            val max = limit.coerceIn(1, DeveloperTuning.current.maxImageMatches)
            val templateWidth = template.cols()
            val templateHeight = template.rows()
            // 先用一次 native minMaxLoc 快速判断是否有命中；单结果时直接返回。
            val first = Core.minMaxLoc(result)
            if (!first.maxVal.isFinite() || first.maxVal < threshold) return emptyList()
            if (max == 1) {
                return listOf(
                    Match(
                        x = region.left + first.maxLoc.x.toInt() + templateWidth / 2,
                        y = region.top + first.maxLoc.y.toInt() + templateHeight / 2,
                        confidence = first.maxVal,
                    ),
                )
            }
            // 多结果时只扫描一遍匹配矩阵，再做 NMS；避免每个结果都重扫整张图。
            collectMatches(result, region, templateWidth, templateHeight, threshold, max)
        } catch (t: Throwable) {
            logBus.error("图像匹配失败: ${t.message}")
            emptyList()
        } finally {
            result?.release()
            // submat 与 screenMat 共享数据，先释放子矩阵再释放父矩阵。
            if (regionMat !== screenMat) regionMat?.release()
            screenMat?.release()
            TemplateImageCache.release(lease)
        }
    }

    private fun collectMatches(
        result: Mat,
        region: Rect,
        templateWidth: Int,
        templateHeight: Int,
        threshold: Double,
        limit: Int,
    ): List<Match> = runCatching {
        collectMatchesSinglePass(
            result,
            region,
            templateWidth,
            templateHeight,
            threshold,
            limit,
        )
    }.getOrElse {
        logBus.warn("找图多结果扫描失败，回退到逐次匹配: ${it.message}")
        collectMatchesLegacy(
            result,
            region,
            templateWidth,
            templateHeight,
            threshold,
            limit,
        )
    }

    /**
     * 单次扫描匹配矩阵，收集局部极大值后做 NMS。
     *
     * 逐行读取 CV_32F 匹配矩阵，避免为每个结果重复调用 [Core.minMaxLoc] 全图扫描。
     */
    private fun collectMatchesSinglePass(
        result: Mat,
        region: Rect,
        templateWidth: Int,
        templateHeight: Int,
        threshold: Double,
        limit: Int,
    ): List<Match> {
        val resultWidth = result.cols()
        val resultHeight = result.rows()
        if (resultWidth <= 0 || resultHeight <= 0) return emptyList()
        // 按行读取匹配矩阵（CV_32F），只扫描一遍；每行只拷贝一次到小型缓冲。
        val rows = Array(3) { FloatArray(resultWidth) }
        result.get(0, 0, rows[0])
        if (resultHeight > 1) result.get(1, 0, rows[1])
        val maxCandidates = (limit * 4).coerceAtLeast(16)
        val candidates = PriorityQueue<Candidate>(maxCandidates + 1, compareBy { it.confidence })
        for (y in 0 until resultHeight) {
            val current = rows[y % 3]
            val previous = if (y > 0) rows[(y - 1) % 3] else null
            val next = if (y + 1 < resultHeight) rows[(y + 1) % 3] else null
            for (x in 0 until resultWidth) {
                val value = current[x]
                if (!value.isFinite() || value < threshold) continue
                // 只保留局部极大值：左 / 上相等或右 / 下更大时跳过，避免平坦区域产生大量候选。
                if (x > 0 && current[x - 1] >= value) continue
                if (previous != null && previous[x] >= value) continue
                if (x + 1 < resultWidth && current[x + 1] > value) continue
                if (next != null && next[x] > value) continue
                candidates.add(Candidate(x, y, value.toDouble()))
                if (candidates.size > maxCandidates) candidates.poll()
            }
            if (y + 2 < resultHeight) result.get(y + 2, 0, rows[(y + 2) % 3])
        }
        if (candidates.isEmpty()) return emptyList()
        val matches = ArrayList<Match>(limit)
        for (candidate in candidates.sortedByDescending { it.confidence }) {
            if (matches.size >= limit) break
            val centerX = region.left + candidate.x + templateWidth / 2
            val centerY = region.top + candidate.y + templateHeight / 2
            val overlaps = matches.any { existing ->
                abs(existing.x - centerX) < templateWidth &&
                    abs(existing.y - centerY) < templateHeight
            }
            if (!overlaps) {
                matches.add(Match(centerX, centerY, candidate.confidence))
            }
        }
        return matches
    }

    /** 单次扫描失败时的兜底：沿用逐个 minMaxLoc + 区域抑制。 */
    private fun collectMatchesLegacy(
        result: Mat,
        region: Rect,
        templateWidth: Int,
        templateHeight: Int,
        threshold: Double,
        limit: Int,
    ): List<Match> {
        val resultWidth = result.cols()
        val resultHeight = result.rows()
        val matches = ArrayList<Match>(limit)
        while (matches.size < limit) {
            val mm = Core.minMaxLoc(result)
            if (mm.maxVal < threshold) break
            matches.add(
                Match(
                    x = region.left + mm.maxLoc.x.toInt() + templateWidth / 2,
                    y = region.top + mm.maxLoc.y.toInt() + templateHeight / 2,
                    confidence = mm.maxVal,
                ),
            )
            val left = (mm.maxLoc.x - templateWidth / 2).toInt().coerceIn(0, resultWidth - 1)
            val top = (mm.maxLoc.y - templateHeight / 2).toInt().coerceIn(0, resultHeight - 1)
            val right = (mm.maxLoc.x + templateWidth / 2).toInt().coerceIn(0, resultWidth - 1)
            val bottom = (mm.maxLoc.y + templateHeight / 2).toInt().coerceIn(0, resultHeight - 1)
            Imgproc.rectangle(
                result,
                Point(left.toDouble(), top.toDouble()),
                Point(right.toDouble(), bottom.toDouble()),
                Scalar(0.0),
                -1,
            )
        }
        return matches
    }

}
