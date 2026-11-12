package com.benton.izukijs.vision

import android.graphics.Bitmap
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.ocr.OcrLineSynthesis
import com.benton.izukijs.ocr.OcrProcessor
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * OCR 识别增强：ROI 裁剪、颜色过滤、置信度阈值、正则替换与过滤。
 *
 * 参考 MaaFramework `OCRer`；文本行聚合复用现有 [OcrLineSynthesis]。
 */
internal class OcrRecognizer(
    private val processor: OcrProcessor,
    private val logBus: LogBus,
) {

    private val expectedCache = RegexCache(logBus, "OCR expected")
    private val replaceCache = RegexCache(logBus, "OCR replace")

    fun recognize(
        screen: Bitmap,
        rois: List<Box>,
        spec: OcrSpec,
    ): List<VisionResult> {
        if (rois.isEmpty()) return emptyList()
        if (spec.onlyRec) {
            logBus.debug("OCR onlyRec 在 MLKit 下不支持，按普通识别处理")
        }

        val expected = spec.expected.mapNotNull { expectedCache.get(it) }
        val replace = spec.replace.mapNotNull { (pattern, format) ->
            replaceCache.get(pattern)?.let { it to format }
        }

        val results = ArrayList<VisionResult>()
        for (roi in rois) {
            val crop = if (roi.left == 0 && roi.top == 0 && roi.width == screen.width && roi.height == screen.height) {
                screen
            } else {
                Bitmap.createBitmap(screen, roi.left, roi.top, roi.width, roi.height)
            }
            var filtered: Bitmap? = null
            try {
                filtered = applyColorFilter(crop, spec.colorFilter)
                val recognized = processor.recognize(filtered) ?: continue
                val lines = OcrLineSynthesis.synthesize(recognized.blocks)
                for (line in lines) {
                    val confidence = line.confidence
                    if (confidence != null && confidence < spec.threshold) continue

                    var text = line.text.trim()
                    for ((regex, format) in replace) {
                        text = runCatching { regex.replace(text, format) }.getOrDefault(text)
                    }
                    if (expected.isNotEmpty() && expected.none { it.containsMatchIn(text) }) continue

                    results.add(
                        VisionResult(
                            box = Box(
                                roi.left + line.left,
                                roi.top + line.top,
                                roi.left + line.right,
                                roi.top + line.bottom,
                            ),
                            score = confidence ?: 1.0,
                            text = text,
                        ),
                    )
                }
            } finally {
                if (filtered != null && filtered !== crop) filtered.recycle()
                if (crop !== screen) crop.recycle()
            }
        }
        return results
    }

    private fun IntArray.toScalarArray(): DoubleArray {
        val out = DoubleArray(size)
        for (i in indices) out[i] = this[i].toDouble()
        return out
    }

    private fun applyColorFilter(bitmap: Bitmap, filter: ColorFilterSpec?): Bitmap {
        if (filter == null) return bitmap
        var rgba: Mat? = null
        var bgr: Mat? = null
        var color: Mat? = null
        var bin: Mat? = null
        var bgrBin: Mat? = null
        return try {
            rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            bgr = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            color = Mat()
            Imgproc.cvtColor(bgr, color, filter.method)
            bin = Mat.zeros(color.size(), CvType.CV_8UC1)
            for ((lower, upper) in filter.ranges) {
                if (lower.size != color.channels() || upper.size != color.channels()) {
                    logBus.warn(
                        "OCR 颜色过滤通道数不匹配：method=${filter.method} 需要 ${color.channels()} 通道",
                    )
                    continue
                }
                val single = Mat()
                try {
                    Core.inRange(color, Scalar(lower.toScalarArray()), Scalar(upper.toScalarArray()), single)
                    Core.bitwise_or(bin, single, bin)
                } finally {
                    single.release()
                }
            }
            bgrBin = Mat()
            Imgproc.cvtColor(bin, bgrBin, Imgproc.COLOR_GRAY2BGR)
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(bgrBin, output)
            output
        } catch (t: Throwable) {
            logBus.warn("OCR 颜色过滤失败: ${t.message}")
            bitmap
        } finally {
            bgrBin?.release()
            bin?.release()
            color?.release()
            bgr?.release()
            rgba?.release()
        }
    }
}
