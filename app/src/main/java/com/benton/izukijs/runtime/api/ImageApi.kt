package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.webkit.JavascriptInterface
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * 图像匹配 API（OpenCV）。挂在全局 `images` 命名空间。
 *
 * 原生方法返回 "x,y[,confidence]" 或 JSON 数组，便于通过 JS 预置脚本包装成对象。
 * 所有查找都支持可选的区域 `{x,y,width,height}`，坐标始终是屏幕像素。
 */
class ImageApi(
    private val context: Context,
    private val screenshotProvider: () -> Bitmap?,
    private val logBus: LogBus,
) {

    // ---- 找图 ----

    @JavascriptInterface
    fun findImageRaw(templatePath: String, threshold: Double): String? =
        findImageRegionRaw(templatePath, threshold, "")

    @JavascriptInterface
    fun findImageRegionRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
    ): String? {
        val screen = screenshot() ?: run {
            logBus.warn("找图失败：无法获取屏幕截图")
            return null
        }
        return try {
            val region = parseRegion(regionJson, screen.width, screen.height, "找图") ?: return null
            val match = PerfMetrics.measure("image.findImage") {
                matchTemplate(screen, templatePath, threshold, region, 1).firstOrNull()
            }
            if (match == null) {
                logBus.debugThrottled("image.findImage", "找图 ${File(templatePath).name} → 未命中 ✗")
                null
            } else {
                logBus.debugThrottled(
                    "image.findImage",
                    "找图 ${File(templatePath).name} → (${match.x}, ${match.y}) " +
                        "置信度 ${match.confidence.confidence()} ✓",
                )
                "${match.x},${match.y},${match.confidence}"
            }
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun findAllImagesRaw(
        templatePath: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String {
        val screen = screenshot() ?: run {
            logBus.warn("找图失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            val region = parseRegion(regionJson, screen.width, screen.height, "找图") ?: return "[]"
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

    @JavascriptInterface
    fun findImageInRaw(screenPath: String, templatePath: String, threshold: Double): String? {
        val screen = BitmapFactory.decodeFile(screenPath) ?: run {
            logBus.warn("找图失败：无法读取屏幕图片 $screenPath")
            return null
        }
        return try {
            val match = matchTemplate(
                screen,
                templatePath,
                threshold,
                Rect(0, 0, screen.width, screen.height),
                1,
            ).firstOrNull() ?: return null
            "${match.x},${match.y},${match.confidence}"
        } finally {
            screen.recycle()
        }
    }

    // ---- 找色 ----

    @JavascriptInterface
    fun findColorRaw(color: String, threshold: Double): String? =
        findColorRegionRaw(color, threshold, "")

    @JavascriptInterface
    fun findColorRegionRaw(
        color: String,
        threshold: Double,
        regionJson: String,
    ): String? {
        val target = parseColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return null
        }
        val bitmap = screenshot() ?: run {
            logBus.warn("找色失败：无法获取屏幕截图")
            return null
        }
        return try {
            val region = parseRegion(regionJson, bitmap.width, bitmap.height, "找色") ?: return null
            val match = PerfMetrics.measure("image.findColor") {
                findColorMatches(bitmap, target, threshold.toInt(), region, 1).firstOrNull()
            }
            if (match == null) {
                logBus.debugThrottled(
                    "image.findColor",
                    "找色 $color (±${threshold.toInt()}) → 未找到 ✗",
                )
                null
            } else {
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

    @JavascriptInterface
    fun findAllColorsRaw(
        color: String,
        threshold: Double,
        regionJson: String,
        limit: Int,
    ): String {
        val target = parseColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return "[]"
        }
        val bitmap = screenshot() ?: run {
            logBus.warn("找色失败：无法获取屏幕截图")
            return "[]"
        }
        return try {
            val region = parseRegion(regionJson, bitmap.width, bitmap.height, "找色") ?: return "[]"
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
    @JavascriptInterface
    fun findColorRegionsRaw(
        color: String,
        threshold: Double,
        optionsJson: String,
    ): String {
        val bitmap = screenshot() ?: run {
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
    @JavascriptInterface
    fun findColorRegionsInRaw(
        screenPath: String,
        color: String,
        threshold: Double,
        optionsJson: String,
    ): String {
        val bitmap = BitmapFactory.decodeFile(screenPath) ?: run {
            logBus.warn("找色区域失败：无法读取屏幕图片 $screenPath")
            return "[]"
        }
        return try {
            findColorRegions(bitmap, color, threshold.toInt(), optionsJson)
        } finally {
            bitmap.recycle()
        }
    }

    /** 在指定截图上找色，返回首个命中点 "x,y"；与 [findImageInRaw] 对齐。 */
    @JavascriptInterface
    fun findColorInRaw(
        screenPath: String,
        color: String,
        threshold: Double,
    ): String? {
        val target = parseColor(color) ?: run {
            logBus.warn("找色失败：颜色格式无效 \"$color\"")
            return null
        }
        val bitmap = BitmapFactory.decodeFile(screenPath) ?: run {
            logBus.warn("找色失败：无法读取屏幕图片 $screenPath")
            return null
        }
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
                logBus.debugThrottled(
                    "image.findColor",
                    "找色 $color (±${threshold.toInt()}) @ $screenPath → 未找到 ✗",
                )
                null
            } else {
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

    /** 从当前屏幕裁剪一块区域保存为 PNG 模板；[path] 为空时保存到缓存目录。 */
    @JavascriptInterface
    fun saveTemplate(path: String, x: Int, y: Int, width: Int, height: Int): String? {
        val screen = screenshot() ?: run {
            logBus.warn("保存模板失败：无法获取屏幕截图")
            return null
        }
        return try {
            val rect = Rect(x, y, x + width, y + height)
            if (width <= 0 || height <= 0 || !rect.intersect(0, 0, screen.width, screen.height)) {
                logBus.warn("保存模板失败：区域无效 ($x,$y ${width}x$height)")
                return null
            }
            val crop = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
            try {
                val file = if (path.isBlank()) {
                    File(context.cacheDir, "template_${System.currentTimeMillis()}.png")
                } else {
                    File(path)
                }
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    crop.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                logBus.debug("保存模板 → ${file.absolutePath} (${crop.width}×${crop.height})")
                file.absolutePath
            } finally {
                crop.recycle()
            }
        } catch (t: Throwable) {
            logBus.warn("保存模板失败：${t.message}")
            null
        } finally {
            screen.recycle()
        }
    }

    @JavascriptInterface
    fun available(): Boolean = ensureOpenCv()

    // ---- OpenCV ----

    private data class Match(val x: Int, val y: Int, val confidence: Double)

    private data class ColorMatch(val x: Int, val y: Int)

    private data class Candidate(val x: Int, val y: Int, val confidence: Double)

    private fun matchTemplate(
        screen: Bitmap,
        templatePath: String,
        threshold: Double,
        region: Rect,
        limit: Int,
    ): List<Match> = PerfMetrics.measure("image.matchTemplate") {
        if (!ensureOpenCv()) return emptyList()
        val template = loadTemplate(templatePath) ?: run {
            logBus.warn("找图失败：模板不存在或无法读取 $templatePath")
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
            val max = limit.coerceIn(1, MAX_MATCHES)
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

    /** 复用的像素缓冲，避免每次找色都按区域重新分配 IntArray。 */
    private var colorPixels = IntArray(0)

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
        val max = limit.coerceIn(1, MAX_MATCHES)
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
        return matches
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
        val target = parseColor(color) ?: run {
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
        val region = parseRegion(regionJson, width, height, "找色区域") ?: return null
        return ColorRegionOptions(
            minPixels = obj.optInt("minPixels", 1).coerceAtLeast(1),
            maxRegions = obj.optInt("maxRegions", DEFAULT_MAX_REGIONS).coerceIn(1, MAX_MATCHES),
            region = region,
        )
    }

    private fun detectColorRegions(
        bitmap: Bitmap,
        target: Int,
        tolerance: Int,
        options: ColorRegionOptions,
    ): List<ColorRegion> {
        if (!ensureOpenCv()) return emptyList()
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

    private fun Double.confidence(): String = String.format(Locale.US, "%.3f", this)

    /** 按「路径 + 修改时间」缓存解码后的模板（RGBA Mat）。返回的 Mat 归缓存所有，调用方不得释放。 */
    private fun loadTemplate(path: String): Mat? {
        TemplateCache.get(path)?.let { return it }
        var decoded: Mat? = null
        return try {
            decoded = Imgcodecs.imread(path, Imgcodecs.IMREAD_COLOR)
            if (decoded.empty()) return null
            val rgba = Mat()
            Imgproc.cvtColor(decoded, rgba, Imgproc.COLOR_BGR2RGBA)
            TemplateCache.put(path, rgba)
            rgba
        } catch (t: Throwable) {
            logBus.error("模板加载失败: ${t.message}")
            null
        } finally {
            decoded?.release()
        }
    }

    private fun screenshot(): Bitmap? = screenshotProvider()

    /**
     * 解析区域参数；空字符串表示整屏。返回 null 表示参数无效（已记录日志）。
     */
    private fun parseRegion(json: String, width: Int, height: Int, label: String): Rect? {
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

    private fun parseColor(color: String): Int? {
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

    private fun ensureOpenCv(): Boolean {
        val ready = loadOpenCv()
        if (!ready) logBus.error("OpenCV 初始化失败")
        return ready
    }

    companion object {
        private val LOCK = Any()

        @Volatile
        private var openCvReady = false

        @Volatile
        private var prewarmStarted = false

        /** 后台预热 OpenCV，避免脚本第一次找图时同步加载 native 库。 */
        fun prewarm() {
            if (openCvReady || prewarmStarted) return
            synchronized(LOCK) {
                if (openCvReady || prewarmStarted) return
                prewarmStarted = true
            }
            Thread(
                { PerfMetrics.measure("opencv.prewarm") { loadOpenCv() } },
                "izuki-open-cv-prewarm",
            ).apply { isDaemon = true }.start()
        }

        private fun loadOpenCv(): Boolean {
            if (openCvReady) return true
            synchronized(LOCK) {
                if (openCvReady) return true
                openCvReady = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
                return openCvReady
            }
        }

        private const val MAX_MATCHES = 200
        private const val DEFAULT_MAX_REGIONS = 20
    }

    /** 进程级模板缓存：脚本多次运行、循环找图时复用已解码的模板，避免反复读盘解码。 */
    private object TemplateCache {
        private const val MAX_ENTRIES = 8
        private val lock = Any()
        private val entries = LinkedHashMap<String, Entry>()

        private class Entry(val lastModified: Long, val mat: Mat)

        fun get(path: String): Mat? = synchronized(lock) {
            val entry = entries[path]
            when {
                entry == null -> null
                entry.lastModified != File(path).lastModified() -> {
                    entries.remove(path)
                    entry.mat.release()
                    null
                }
                else -> entry.mat
            }
        }

        fun put(path: String, mat: Mat) {
            synchronized(lock) {
                entries[path]?.mat?.release()
                entries[path] = Entry(File(path).lastModified(), mat)
                if (entries.size > MAX_ENTRIES) {
                    val iterator = entries.entries.iterator()
                    if (iterator.hasNext()) {
                        val eldest = iterator.next()
                        iterator.remove()
                        eldest.value.mat.release()
                    }
                }
            }
        }
    }
}
