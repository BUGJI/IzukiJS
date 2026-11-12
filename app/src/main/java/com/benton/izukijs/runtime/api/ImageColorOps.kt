package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.graphics.Rect
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.developer.DeveloperTuning
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/** `images` 找色相关实现（像素扫描、连通域聚类）。 */
internal class ImageColorOps(
    private val screenshotProvider: () -> Bitmap?,
    private val logBus: LogBus,
) {

    data class ColorMatch(val x: Int, val y: Int)

    /** 复用的像素缓冲，避免每次找色都按区域重新分配 IntArray。 */
    private var colorPixels = IntArray(0)

    fun findColorRaw(color: String, threshold: Double): String? =
        findColorRegionRaw(color, threshold, "")

    fun findColorRegionRaw(
        color: String,
        threshold: Double,
        regionJson: String,
    ): String? {
        val target = parseImageColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return null
        }
        val bitmap = screenshotProvider() ?: run {
            logBus.warn("找色失败：无法获取屏幕截图")
            return null
        }
        return try {
            val region = parseImageRegion(regionJson, bitmap.width, bitmap.height, "找色", logBus)
                ?: return null
            val match = PerfMetrics.measure("image.findColor") {
                findColorMatches(bitmap, target, threshold.toInt(), region, 1).firstOrNull()
            }
            if (match == null) {
                PerfMetrics.count("image.findColor.miss")
                logBus.debugThrottled(
                    "image.findColor",
                    "找色 $color (±${threshold.toInt()}) → 未找到 ✗",
                )
                null
            } else {
                PerfMetrics.count("image.findColor.hit")
                logBus.debugThrottled(
                    "image.findColor",
                    "找色 $color (±${threshold.toInt()}) → (${match.x},${match.y}) ✓",
                )
                "${match.x},${match.y}"
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun findAllColorsRaw(
        color: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String {
        val target = parseImageColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return "[]"
        }
        val bitmap = screenshotProvider() ?: run {
            logBus.warn("找色失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            val region = parseImageRegion(regionJson, bitmap.width, bitmap.height, "找色", logBus)
                ?: return "[]"
            val matches = PerfMetrics.measure("image.findColor") {
                findColorMatches(bitmap, target, threshold.toInt(), region, limit)
            }
            logBus.debugThrottled(
                "image.findColor",
                "找色 $color (±${threshold.toInt()}) → ${matches.size} 个结果",
            )
            val array = JSONArray()
            matches.forEach { match ->
                array.put(JSONObject().apply {
                    put("x", match.x)
                    put("y", match.y)
                })
            }
            array.toString()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 在当前屏幕上按颜色做连通域聚类。
     * options: `{minPixels, maxRegions, region:{x,y,width,height}}`。
     */
    fun findColorRegionsRaw(
        color: String,
        threshold: Double,
        optionsJson: String,
    ): String {
        val bitmap = screenshotProvider() ?: run {
            logBus.warn("找色区域失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            findColorRegions(bitmap, color, threshold.toInt(), optionsJson)
        } finally {
            bitmap.recycle()
        }
    }

    /** 在指定截图上按颜色做连通域聚类，复用同一张截图做多种判断。 */
    fun findColorRegionsInRaw(
        screenPath: String,
        color: String,
        threshold: Double,
        optionsJson: String,
    ): String {
        val bitmap = ImageDecode.decode(screenPath, logBus, "找色区域") ?: return "[]"
        return try {
            findColorRegions(bitmap, color, threshold.toInt(), optionsJson)
        } finally {
            bitmap.recycle()
        }
    }

    /** 在指定截图上找色，返回首个命中点 "x,y"；与 `findImageInRaw` 对齐。 */
    fun findColorInRaw(
        screenPath: String,
        color: String,
        threshold: Double,
    ): String? {
        val target = parseImageColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return null
        }
        val bitmap = ImageDecode.decode(screenPath, logBus, "找色") ?: return null
        return try {
            val match = PerfMetrics.measure("image.findColor") {
                findColorMatches(
                    bitmap,
                    target,
                    threshold.toInt(),
                    Rect(0, 0, bitmap.width, bitmap.height),
                    1,
                ).firstOrNull()
            }
            if (match == null) {
                PerfMetrics.count("image.findColor.miss")
                logBus.debugThrottled(
                    "image.findColor",
                    "找色 $color (±${threshold.toInt()}) @ $screenPath → 未找到 ✗",
                )
                null
            } else {
                PerfMetrics.count("image.findColor.hit")
                logBus.debugThrottled(
                    "image.findColor",
                    "找色 $color (±${threshold.toInt()}) @ $screenPath → (${match.x},${match.y}) ✓",
                )
                "${match.x},${match.y}"
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun findColorMatches(
        bitmap: Bitmap,
        target: Int,
        tolerance: Int,
        region: Rect,
        limit: Int,
    ): List<ColorMatch> = PerfMetrics.measure("image.findColorScan") {
        val regionWidth = region.width()
        val regionHeight = region.height()
        if (regionWidth <= 0 || regionHeight <= 0) return emptyList()
        // 只读取目标区域，避免局部找色也复制整屏数 MB 像素。
        val count = regionWidth * regionHeight
        if (colorPixels.size < count) colorPixels = IntArray(count)
        val pixels = colorPixels
        bitmap.getPixels(
            pixels,
            0,
            regionWidth,
            region.left,
            region.top,
            regionWidth,
            regionHeight,
        )
        val tr = (target shr 16) and 0xFF
        val tg = (target shr 8) and 0xFF
        val tb = target and 0xFF
        val tol = tolerance.coerceIn(0, 255)
        val max = limit.coerceIn(1, DeveloperTuning.current.maxImageMatches)
        val matches = ArrayList<ColorMatch>(max)
        for (rowIndex in 0 until regionHeight) {
            val row = rowIndex * regionWidth
            val y = region.top + rowIndex
            for (columnIndex in 0 until regionWidth) {
                val p = pixels[row + columnIndex]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (abs(r - tr) <= tol && abs(g - tg) <= tol && abs(b - tb) <= tol) {
                    matches.add(ColorMatch(region.left + columnIndex, y))
                    if (matches.size >= max) return matches
                }
            }
        }
        matches
    }

    private data class ColorRegionOptions(
        val minPixels: Int,
        val maxRegions: Int,
        val region: Rect,
    )

    private fun findColorRegions(
        bitmap: Bitmap,
        color: String,
        tolerance: Int,
        optionsJson: String,
    ): String {
        val target = parseImageColor(color) ?: run {
            logBus.warn("找色区域失败：颜色格式无效 \"$color\"")
            return "[]"
        }
        val options = parseColorRegionOptions(optionsJson, bitmap.width, bitmap.height) ?: return "[]"
        val regions = PerfMetrics.measure("image.findColorRegions") {
            detectColorRegions(bitmap, target, tolerance, options)
        }
        logBus.debugThrottled(
            "image.findColorRegions",
            "找色区域 $color (±${tolerance.coerceIn(0, 255)}) → ${regions.size} 个区域",
        )
        val array = JSONArray()
        regions.forEach { region ->
            array.put(
                JSONObject().apply {
                    put("x", region.x)
                    put("y", region.y)
                    put("w", region.w)
                    put("h", region.h)
                    put("pixels", region.pixels)
                    put("cx", region.cx)
                    put("cy", region.cy)
                },
            )
        }
        return array.toString()
    }

    private fun parseColorRegionOptions(
        json: String,
        width: Int,
        height: Int,
    ): ColorRegionOptions? {
        val obj = if (json.isBlank()) {
            JSONObject()
        } else {
            try {
                JSONObject(json)
            } catch (t: Throwable) {
                logBus.warn("找色区域 options 解析失败: ${t.message}")
                return null
            }
        }
        val regionJson = obj.optJSONObject("region")?.toString() ?: ""
        val region = parseImageRegion(regionJson, width, height, "找色区域", logBus) ?: return null
        return ColorRegionOptions(
            minPixels = obj.optInt("minPixels", 1).coerceAtLeast(1),
            maxRegions = obj.optInt(
                "maxRegions",
                DeveloperTuning.current.defaultMaxRegions,
            ).coerceIn(1, DeveloperTuning.current.maxImageMatches),
            region = region,
        )
    }

    private fun detectColorRegions(
        bitmap: Bitmap,
        target: Int,
        tolerance: Int,
        options: ColorRegionOptions,
    ): List<ColorRegion> {
        if (!ImageOpenCv.ensure(logBus)) return emptyList()
        val region = options.region
        val cropped = if (
            region.left == 0 &&
            region.top == 0 &&
            region.width() == bitmap.width &&
            region.height() == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height())
        }
        var rgba: Mat? = null
        var mask: Mat? = null
        var labels: Mat? = null
        var stats: Mat? = null
        var centroids: Mat? = null
        return try {
            rgba = Mat()
            Utils.bitmapToMat(cropped, rgba)
            val tr = (target shr 16) and 0xFF
            val tg = (target shr 8) and 0xFF
            val tb = target and 0xFF
            val tol = tolerance.coerceIn(0, 255)
            val lower = Scalar(
                (tr - tol).coerceIn(0, 255).toDouble(),
                (tg - tol).coerceIn(0, 255).toDouble(),
                (tb - tol).coerceIn(0, 255).toDouble(),
                0.0,
            )
            val upper = Scalar(
                (tr + tol).coerceIn(0, 255).toDouble(),
                (tg + tol).coerceIn(0, 255).toDouble(),
                (tb + tol).coerceIn(0, 255).toDouble(),
                255.0,
            )
            mask = Mat()
            Core.inRange(rgba, lower, upper, mask)
            labels = Mat()
            stats = Mat()
            centroids = Mat()
            val count = Imgproc.connectedComponentsWithStats(
                mask,
                labels,
                stats,
                centroids,
                8,
                CvType.CV_32S,
            )
            val accumulator = ColorRegionAccumulator(options.minPixels, options.maxRegions)
            val statRow = IntArray(5)
            val centroidRow = DoubleArray(2)
            for (index in 1 until count) {
                stats.get(index, 0, statRow)
                val area = statRow[Imgproc.CC_STAT_AREA]
                if (area < options.minPixels) continue
                centroids.get(index, 0, centroidRow)
                accumulator.offer(
                    ColorRegion(
                        x = statRow[Imgproc.CC_STAT_LEFT] + region.left,
                        y = statRow[Imgproc.CC_STAT_TOP] + region.top,
                        w = statRow[Imgproc.CC_STAT_WIDTH],
                        h = statRow[Imgproc.CC_STAT_HEIGHT],
                        pixels = area,
                        cx = (centroidRow[0] + region.left).roundToInt(),
                        cy = (centroidRow[1] + region.top).roundToInt(),
                    ),
                )
            }
            accumulator.toList()
        } catch (t: Throwable) {
            logBus.error("找色区域失败: ${t.message}")
            emptyList()
        } finally {
            rgba?.release()
            mask?.release()
            labels?.release()
            stats?.release()
            centroids?.release()
            if (cropped !== bitmap) cropped.recycle()
        }
    }
}
