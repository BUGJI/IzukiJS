package com.benton.izukijs.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.developer.DeveloperTuning
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.Rect as CvRect

/**
 * ONNX Runtime 会话封装：单输入、单输出、NCHW float。
 */
internal class NeuralNetworkSession(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val inputShape: LongArray,
    private val inputName: String,
    val labels: List<String>,
) : AutoCloseable {

    data class Output(val shape: LongArray, val data: FloatArray)

    fun run(input: FloatArray): Output? {
        val expected = inputShape.fold(1L) { acc, dim -> acc * dim }
        if (expected > 0 && expected != input.size.toLong()) {
            return null
        }
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape)
        return try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result.get(0) as? OnnxTensor ?: return null
                val buffer = value.floatBuffer
                val data = FloatArray(buffer.remaining())
                buffer.get(data)
                val shape = (value.info as? TensorInfo)?.shape ?: LongArray(0)
                Output(shape, data)
            }
        } finally {
            tensor.close()
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        fun load(env: OrtEnvironment, path: String): NeuralNetworkSession? {
            val options = OrtSession.SessionOptions()
            return try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(
                    DeveloperTuning.current.visionNeuralThreads.coerceAtLeast(1),
                )
                options.setInterOpNumThreads(1)
                val session = env.createSession(path, options)
                val inputName = session.inputNames.firstOrNull()
                if (inputName == null) {
                    session.close()
                    return null
                }
                val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
                val shape = inputInfo?.shape ?: LongArray(0)
                if (shape.size != 4 || shape.any { it <= 0 }) {
                    session.close()
                    return null
                }
                val labels = parseLabels(session)
                NeuralNetworkSession(env, session, shape, inputName, labels)
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { options.close() }
            }
        }

        private fun parseLabels(session: OrtSession): List<String> {
            val metadata = runCatching { session.metadata.customMetadata }.getOrNull() ?: return emptyList()
            val raw = listOf("names", "name", "labels", "class_names")
                .firstNotNullOfOrNull { metadata[it]?.takeIf(String::isNotBlank) }
                ?: return emptyList()
            val map = LinkedHashMap<Int, String>()
            for (match in DICT_PATTERN.findAll(raw)) {
                val index = match.groupValues[1].toIntOrNull() ?: continue
                val label = match.groupValues[2]
                if (label.isNotBlank()) map[index] = label
            }
            if (map.isEmpty()) return emptyList()
            val max = map.keys.max()
            if (max < 0 || max > 10000) return emptyList()
            return List(max + 1) { map[it].orEmpty() }
        }

        private val DICT_PATTERN = Regex("""(\d+)\s*:\s*['"]([^'"]+)['"]""")
    }
}

/**
 * 神经网络识别：固定 ROI 分类 + YOLOv8/v11 目标检测。
 */
internal class NeuralNetworkEngine(
    private val logBus: LogBus,
) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    /** 按张量大小复用的输入缓冲，避免每次推理分配数 MB FloatArray。 */
    private val tensorBuffers = HashMap<Int, FloatArray>()
    private val channelBuffers = HashMap<Int, FloatArray>()

    override fun close() {
        synchronized(cache) {
            cache.values.forEach { runCatching { it.session.close() } }
            cache.clear()
        }
        tensorBuffers.clear()
        channelBuffers.clear()
    }

    fun classify(screen: Mat, rois: List<Box>, spec: NeuralClassifySpec): List<VisionResult> {
        val session = session(spec.model) ?: return emptyList()
        val height = session.inputShape[2].toInt()
        val width = session.inputShape[3].toInt()
        val labels = spec.labels.ifEmpty { session.labels }
        val expectedIndices = resolveExpectedIndices(spec.expected, labels)
        val results = ArrayList<VisionResult>()

        for (roi in rois) {
            val region = screen.submat(CvRect(roi.left, roi.top, roi.width, roi.height))
            val resized = Mat()
            try {
                Imgproc.resize(region, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                val output = session.run(imageToTensor(resized)) ?: continue
                if (output.data.isEmpty()) continue
                val probs = softmax(output.data)
                val cls = probs.indices.maxByOrNull { probs[it] } ?: continue
                if (expectedIndices.isNotEmpty() && cls !in expectedIndices) continue
                results.add(
                    VisionResult(
                        box = roi,
                        score = probs[cls].toDouble(),
                        clsIndex = cls,
                        label = labels.getOrNull(cls),
                    ),
                )
            } finally {
                resized.release()
                region.release()
            }
        }
        return results
    }

    fun detect(screen: Mat, rois: List<Box>, spec: NeuralDetectSpec): List<VisionResult> {
        val session = session(spec.model) ?: return emptyList()
        val inputHeight = session.inputShape[2].toInt()
        val inputWidth = session.inputShape[3].toInt()
        val labels = spec.labels.ifEmpty { session.labels }
        val expectedIndices = resolveExpectedIndices(spec.expected, labels)
        val results = ArrayList<VisionResult>()

        for (roi in rois) {
            val region = screen.submat(CvRect(roi.left, roi.top, roi.width, roi.height))
            val resized = Mat()
            val padded = Mat()
            try {
                val scale = min(
                    min(inputWidth.toDouble() / roi.width, inputHeight.toDouble() / roi.height),
                    1.0,
                )
                val newWidth = floor(roi.width * scale).toInt().coerceAtLeast(1)
                val newHeight = floor(roi.height * scale).toInt().coerceAtLeast(1)
                Imgproc.resize(
                    region,
                    resized,
                    Size(newWidth.toDouble(), newHeight.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
                val padWidth = inputWidth - newWidth
                val padHeight = inputHeight - newHeight
                val padLeft = padWidth / 2
                val padTop = padHeight / 2
                Core.copyMakeBorder(
                    resized,
                    padded,
                    padTop,
                    padHeight - padTop,
                    padLeft,
                    padWidth - padLeft,
                    Core.BORDER_CONSTANT,
                    Scalar(114.0, 114.0, 114.0, 255.0),
                )
                val output = session.run(imageToTensor(padded)) ?: continue
                results.addAll(
                    parseDetections(output, roi, scale, padLeft, padTop, spec.thresholds, expectedIndices, labels),
                )
            } finally {
                padded.release()
                resized.release()
                region.release()
            }
        }
        return nmsByScore(results, 0.2, spec.thresholds.minOrNull() ?: 0.0)
    }

    private fun parseDetections(
        output: NeuralNetworkSession.Output,
        roi: Box,
        scale: Double,
        padLeft: Int,
        padTop: Int,
        thresholds: List<Double>,
        expectedIndices: List<Int>,
        labels: List<String>,
    ): List<VisionResult> = parseYoloDetections(
        shape = output.shape,
        data = output.data,
        roi = roi,
        scale = scale,
        padLeft = padLeft,
        padTop = padTop,
        thresholds = thresholds,
        expectedIndices = expectedIndices,
        labels = labels,
    )

    private fun resolveExpectedIndices(expected: List<Any>, labels: List<String>): List<Int> {
        if (expected.isEmpty()) return emptyList()
        val out = ArrayList<Int>(expected.size)
        for (item in expected) {
            when (item) {
                is Int -> if (item >= 0) out.add(item)
                is String -> {
                    val index = labels.indexOf(item)
                    if (index >= 0) out.add(index)
                }
            }
        }
        return out
    }

    private fun session(path: String): NeuralNetworkSession? = synchronized(cache) {
        val file = File(path)
        if (!file.isFile) {
            logBus.warn("模型不存在：$path")
            return null
        }
        val modified = file.lastModified()
        val existing = cache[path]
        if (existing != null && existing.lastModified == modified) return existing.session

        existing?.let {
            cache.remove(path)
            it.session.close()
        }
        val loaded = NeuralNetworkSession.load(env, path) ?: run {
            logBus.warn("模型加载失败：$path")
            return null
        }
        cache[path] = CacheEntry(modified, loaded)
        val maxSessions = DeveloperTuning.current.visionNeuralSessions.coerceAtLeast(1)
        while (cache.size > maxSessions) {
            val eldest = cache.entries.iterator().next()
            cache.remove(eldest.key)
            eldest.value.session.close()
        }
        loaded
    }

    private fun imageToTensor(image: Mat): FloatArray {
        val rgb = Mat()
        val floatMat = Mat()
        val channels = ArrayList<Mat>(3)
        return try {
            Imgproc.cvtColor(image, rgb, Imgproc.COLOR_RGBA2RGB)
            rgb.convertTo(floatMat, CvType.CV_32F, 1.0 / 255.0)
            Core.split(floatMat, channels)
            val plane = floatMat.rows() * floatMat.cols()
            val total = 3 * plane
            val out = tensorBuffers.getOrPut(total) { FloatArray(total) }
            val data = channelBuffers.getOrPut(plane) { FloatArray(plane) }
            for (channel in 0 until min(3, channels.size)) {
                channels[channel].get(0, 0, data)
                System.arraycopy(data, 0, out, channel * plane, plane)
            }
            if (channels.size < 3) out.fill(0f, channels.size * plane, total)
            out
        } finally {
            channels.forEach { it.release() }
            floatMat.release()
            rgb.release()
        }
    }

    private fun softmax(values: FloatArray): FloatArray {
        if (values.isEmpty()) return values
        val max = values.maxOrNull() ?: 0f
        val exps = FloatArray(values.size) { exp((values[it] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        if (sum <= 0f) return exps
        for (index in exps.indices) exps[index] /= sum
        return exps
    }

    private class CacheEntry(val lastModified: Long, val session: NeuralNetworkSession)
}


/**
 * YOLOv8/v11 输出解析：支持 `[1, 4+nc, N]` 与 `[1, N, 4+nc]` 两种布局。
 * 纯函数，便于 JVM 单测。
 */
internal fun parseYoloDetections(
    shape: LongArray,
    data: FloatArray,
    roi: Box,
    scale: Double,
    padLeft: Int,
    padTop: Int,
    thresholds: List<Double>,
    expectedIndices: List<Int>,
    labels: List<String>,
): List<VisionResult> {
    if (shape.size != 3) return emptyList()
    val dim1 = shape[1].toInt()
    val dim2 = shape[2].toInt()
    if (dim1 < 5 && dim2 < 5) return emptyList()

    val transposed: Boolean
    val channels: Int
    val candidates: Int
    when {
        dim1 >= 5 && dim2 >= 5 -> {
            transposed = dim1 > dim2
            channels = if (transposed) dim2 else dim1
            candidates = if (transposed) dim1 else dim2
        }

        dim1 >= 5 -> {
            transposed = false
            channels = dim1
            candidates = dim2
        }

        else -> {
            transposed = true
            channels = dim2
            candidates = dim1
        }
    }
    if (channels < 5 || candidates <= 0) return emptyList()
    if (data.size < channels * candidates) return emptyList()

    fun at(channel: Int, index: Int): Float =
        if (transposed) data[index * channels + channel] else data[channel * candidates + index]

    val fallbackThreshold = thresholds.firstOrNull() ?: 0.3
    // 预计算 class -> expected 下标，避免每个候选都做一次 O(E) 的 indexOf。
    val expectedPositions = if (expectedIndices.isEmpty()) {
        null
    } else {
        IntArray(channels) { -1 }.also { positions ->
            expectedIndices.forEachIndexed { pos, cls ->
                if (cls in 0 until channels && positions[cls] < 0) positions[cls] = pos
            }
        }
    }
    val results = ArrayList<VisionResult>()
    for (index in 0 until candidates) {
        for (channel in 4 until channels) {
            val score = at(channel, index)
            val cls = channel - 4
            val expectedPos = expectedPositions?.get(cls) ?: -1
            if (expectedPositions != null && expectedPos < 0) continue
            val threshold = if (expectedPos >= 0 && expectedPos < thresholds.size) {
                thresholds[expectedPos]
            } else {
                fallbackThreshold
            }
            if (score < threshold) continue

            val centerX = at(0, index)
            val centerY = at(1, index)
            val width = at(2, index)
            val height = at(3, index)
            if (width <= 0f || height <= 0f) continue

            val left = ((centerX - width / 2 - padLeft) / scale).toInt() + roi.left
            val top = ((centerY - height / 2 - padTop) / scale).toInt() + roi.top
            val right = ((centerX + width / 2 - padLeft) / scale).toInt() + roi.left
            val bottom = ((centerY + height / 2 - padTop) / scale).toInt() + roi.top
            val box = Box(left, top, right, bottom).intersect(roi) ?: continue
            results.add(
                VisionResult(
                    box = box,
                    score = score.toDouble(),
                    clsIndex = cls,
                    label = labels.getOrNull(cls),
                ),
            )
        }
    }
    return results
}
