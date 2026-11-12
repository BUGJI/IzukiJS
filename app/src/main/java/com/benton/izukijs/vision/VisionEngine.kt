package com.benton.izukijs.vision

import android.graphics.Bitmap
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.ocr.OcrProcessor
import java.io.Closeable
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * 统一识别入口：解析 ROI、加载模板、分派到具体识别器，并处理 And/Or 组合。
 *
 * 返回结果已按 `orderBy` 排序，`index` 选择由调用方完成。
 */
class VisionEngine(
    private val logBus: LogBus,
    private val ocrProcessor: OcrProcessor? = null,
) : Closeable {

    private val templateMatcher = TemplateMatcher(logBus)
    private val colorMatcher = ColorMatcher(logBus)
    private val featureMatcher = FeatureMatcher(logBus)
    private val ocrRecognizer = ocrProcessor?.let { OcrRecognizer(it, logBus) }
    private val neuralEngineDelegate = lazy { NeuralNetworkEngine(logBus) }
    private val neuralEngine by neuralEngineDelegate
    private val expectedTextCache = RegexCache(logBus, "OCR expected")

    override fun close() {
        runCatching { featureMatcher.close() }
        if (neuralEngineDelegate.isInitialized()) neuralEngine.close()
    }

    fun recognize(spec: VisionSpec, screen: Bitmap): List<VisionResult> {
        val refs = HashMap<String, List<VisionResult>>()
        val screenMat = lazy {
            val mat = Mat()
            Utils.bitmapToMat(screen, mat)
            mat
        }
        return try {
            exec(spec, screen, screenMat, refs)
        } finally {
            if (screenMat.isInitialized()) screenMat.value.release()
        }
    }

    fun compare(
        lhs: Bitmap,
        rhs: Bitmap,
        options: org.json.JSONObject,
    ): Pair<Double, Boolean> {
        if (lhs.width != rhs.width || lhs.height != rhs.height) {
            throw VisionSpecException("compare 需要两张同尺寸图片")
        }
        val roiJson = options.optJSONObject("roi")
        val roi = if (roiJson == null) {
            Box(0, 0, lhs.width, lhs.height)
        } else {
            RoiResolver.resolve(
                RoiSpec(
                    rect = RectSpec(
                        roiJson.optInt("x", 0),
                        roiJson.optInt("y", 0),
                        roiJson.optInt("width", roiJson.optInt("w", 0)),
                        roiJson.optInt("height", roiJson.optInt("h", 0)),
                    ),
                ),
                emptyMap(),
                lhs.width,
                lhs.height,
            ).firstOrNull() ?: throw VisionSpecException("compare ROI 无效")
        }

        var lhsMat: Mat? = null
        var rhsMat: Mat? = null
        var lhsRoi: Mat? = null
        var rhsRoi: Mat? = null
        var result: Mat? = null
        try {
            lhsMat = Mat()
            rhsMat = Mat()
            Utils.bitmapToMat(lhs, lhsMat)
            Utils.bitmapToMat(rhs, rhsMat)
            val cvRect = org.opencv.core.Rect(roi.left, roi.top, roi.width, roi.height)
            lhsRoi = lhsMat.submat(cvRect)
            rhsRoi = rhsMat.submat(cvRect)
            result = Mat()
            val method = options.optInt("method", 5)
            val cvMethod = if (method in 10000..19999) method - 10000 else method
            Imgproc.matchTemplate(lhsRoi, rhsRoi, result, cvMethod)
            val minMax = org.opencv.core.Core.minMaxLoc(result)
            val lowScoreBetter = cvMethod == 0 || cvMethod == 1
            val raw = if (lowScoreBetter) 1.0 - minMax.minVal else minMax.maxVal
            val threshold = options.optDouble("threshold", 0.9)
            return raw to (raw >= threshold)
        } finally {
            result?.release()
            rhsRoi?.release()
            lhsRoi?.release()
            rhsMat?.release()
            lhsMat?.release()
        }
    }

    private fun exec(
        spec: VisionSpec,
        screen: Bitmap,
        screenMat: Lazy<Mat>,
        refs: MutableMap<String, List<VisionResult>>,
    ): List<VisionResult> = when (spec) {
        is TemplateMatchSpec -> {
            val rois = RoiResolver.resolve(spec.roi, refs, screen.width, screen.height)
            val lease = TemplateImageCache.acquire(spec.templates, logBus, "找图")
            try {
                val templates = lease.mats
                if (rois.isEmpty() || templates.isEmpty()) {
                    emptyList()
                } else {
                    PerfMetrics.measure("vision.template") {
                        finalize(spec, templateMatcher.match(screenMat.value, rois, spec, templates))
                    }
                }
            } finally {
                TemplateImageCache.release(lease)
            }
        }

        is ColorMatchSpec -> {
            val rois = RoiResolver.resolve(spec.roi, refs, screen.width, screen.height)
            if (rois.isEmpty()) {
                emptyList()
            } else {
                PerfMetrics.measure("vision.color") {
                    finalize(spec, colorMatcher.match(screenMat.value, rois, spec))
                }
            }
        }

        is FeatureMatchSpec -> {
            val rois = RoiResolver.resolve(spec.roi, refs, screen.width, screen.height)
            val lease = TemplateImageCache.acquire(spec.templates, logBus, "特征匹配")
            try {
                val templates = lease.mats
                if (rois.isEmpty() || templates.isEmpty()) {
                    emptyList()
                } else {
                    PerfMetrics.measure("vision.feature") {
                        finalize(spec, featureMatcher.match(screenMat.value, rois, spec, templates))
                    }
                }
            } finally {
                TemplateImageCache.release(lease)
            }
        }

        is OcrSpec -> {
            val rois = RoiResolver.resolve(spec.roi, refs, screen.width, screen.height)
            val recognizer = ocrRecognizer
            if (rois.isEmpty() || recognizer == null) {
                if (recognizer == null) logBus.warn("OCR 识别器不可用")
                emptyList()
            } else {
                PerfMetrics.measure("vision.ocr") {
                    finalize(spec, recognizer.recognize(screen, rois, spec))
                }
            }
        }

        is AndSpec -> {
            val subResults = ArrayList<List<VisionResult>>(spec.allOf.size)
            var allHit = true
            for (sub in spec.allOf) {
                val result = exec(sub.spec, screen, screenMat, refs)
                sub.subName?.let { refs[it] = result }
                subResults.add(result)
                if (result.isEmpty()) {
                    allHit = false
                    break
                }
            }
            if (!allHit) {
                emptyList()
            } else {
                val sub = spec.allOf.getOrNull(spec.boxIndex)
                val result = subResults.getOrNull(spec.boxIndex).orEmpty()
                val chosen = if (sub == null) null else selectIndex(result, sub.spec.index)
                if (chosen == null) emptyList() else listOf(chosen)
            }
        }

        is OrSpec -> {
            var hit: List<VisionResult> = emptyList()
            for (sub in spec.anyOf) {
                val result = exec(sub.spec, screen, screenMat, refs)
                sub.subName?.let { refs[it] = result }
                if (result.isNotEmpty()) {
                    hit = result
                    break
                }
            }
            hit
        }

        is NeuralClassifySpec -> {
            val rois = RoiResolver.resolve(spec.roi, refs, screen.width, screen.height)
            if (rois.isEmpty()) {
                emptyList()
            } else {
                PerfMetrics.measure("vision.classify") {
                    finalize(spec, neuralEngine.classify(screenMat.value, rois, spec))
                }
            }
        }

        is NeuralDetectSpec -> {
            val rois = RoiResolver.resolve(spec.roi, refs, screen.width, screen.height)
            if (rois.isEmpty()) {
                emptyList()
            } else {
                PerfMetrics.measure("vision.detect") {
                    finalize(spec, neuralEngine.detect(screenMat.value, rois, spec))
                }
            }
        }
    }

    private fun finalize(spec: VisionSpec, results: List<VisionResult>): List<VisionResult> {
        val expectedText = (spec as? OcrSpec)?.expected
            ?.mapNotNull { expectedTextCache.get(it) }
            .orEmpty()
        val expectedIndices = when (spec) {
            is NeuralClassifySpec -> spec.expectedIndices
            is NeuralDetectSpec -> spec.expectedIndices
            else -> emptyList()
        }
        return orderResults(results, spec.orderBy, expectedText, expectedIndices)
    }


}
