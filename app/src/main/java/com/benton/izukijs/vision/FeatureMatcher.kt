package com.benton.izukijs.vision

import com.benton.izukijs.core.log.LogBus
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.features.BFMatcher
import org.opencv.features.DescriptorMatcher
import org.opencv.features.Feature2D
import org.opencv.features.FlannBasedMatcher
import org.opencv.features.ORB
import org.opencv.features.SIFT
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import org.opencv.core.Rect as CvRect

/**
 * 特征匹配（抗缩放 / 旋转 / 透视）。
 *
 * 参考 MaaFramework `FeatureMatcher`：ratio test + RANSAC 单应性，逐个剔除已命中的特征点。
 */
internal class FeatureMatcher(
    private val logBus: LogBus,
) : java.io.Closeable {

    private val detectors = HashMap<String, Feature2D>()
    private val matchers = HashMap<String, DescriptorMatcher>()

    override fun close() {
        matchers.values.forEach { runCatching { it.clear() } }
        matchers.clear()
        detectors.values.forEach { runCatching { it.clear() } }
        detectors.clear()
    }

    fun match(
        screen: Mat,
        rois: List<Box>,
        spec: FeatureMatchSpec,
        templates: List<Mat>,
    ): List<VisionResult> {
        if (screen.empty() || templates.isEmpty()) return emptyList()

        val prepared = ArrayList<PreparedTemplate>()
        try {
            for (template in templates) {
                if (template.empty()) continue
                val templateMask = if (spec.greenMask) createGreenMask(template) else Mat()
                try {
                    val (keypoints, descriptors) = detect(template, spec.detector, templateMask)
                    if (descriptors.empty()) {
                        keypoints.release()
                        descriptors.release()
                        logBus.debug("特征匹配跳过：模板没有可用特征点")
                        continue
                    }
                    prepared.add(
                        PreparedTemplate(keypoints, descriptors, template.cols(), template.rows()),
                    )
                } finally {
                    templateMask.release()
                }
            }
            if (prepared.isEmpty()) return emptyList()

            val collected = ArrayList<VisionResult>()
            // 场景特征只按 ROI 计算一次，多个模板复用，避免每个模板重复 SIFT/ORB。
            for (roi in rois) {
                val region = screen.submat(CvRect(roi.left, roi.top, roi.width, roi.height))
                try {
                    val (sceneKeypoints, sceneDescriptors) = detect(region, spec.detector, Mat())
                    try {
                        if (sceneDescriptors.empty()) continue
                        for (template in prepared) {
                            val knn = match(spec.detector, sceneDescriptors, template.descriptors)
                            collected.addAll(
                                postprocess(
                                    knn,
                                    template.keypoints,
                                    sceneKeypoints,
                                    template.width,
                                    template.height,
                                    roi,
                                    spec.ratio,
                                    spec.count,
                                ),
                            )
                        }
                    } finally {
                        sceneDescriptors.release()
                        sceneKeypoints.release()
                    }
                } finally {
                    region.release()
                }
            }
            return collected
        } finally {
            prepared.forEach {
                it.descriptors.release()
                it.keypoints.release()
            }
        }
    }

    private class PreparedTemplate(
        val keypoints: MatOfKeyPoint,
        val descriptors: Mat,
        val width: Int,
        val height: Int,
    )

    private fun detect(image: Mat, detectorName: String, mask: Mat): Pair<MatOfKeyPoint, Mat> {
        val detector = createDetector(detectorName) ?: return MatOfKeyPoint() to Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        val prepared = if (image.channels() == 4) {
            Mat().also { Imgproc.cvtColor(image, it, Imgproc.COLOR_RGBA2BGR) }
        } else {
            null
        }
        try {
            detector.detectAndCompute(prepared ?: image, mask, keypoints, descriptors)
        } finally {
            prepared?.release()
        }
        return keypoints to descriptors
    }

    private fun createDetector(name: String): Feature2D? {
        val key = name.trim().uppercase(java.util.Locale.ROOT)
        detectors[key]?.let { return it }
        val detector = when (key) {
            "SIFT" -> SIFT.create()
            "ORB" -> ORB.create()
            "BRISK", "KAZE", "AKAZE", "SURF" -> {
                logBus.warn("特征匹配：当前 OpenCV 5 Android 构建不包含 $name，请改用 SIFT 或 ORB")
                null
            }

            else -> {
                logBus.warn("特征匹配：未知 detector $name")
                null
            }
        } ?: return null
        detectors[key] = detector
        return detector
    }

    private fun createMatcher(name: String): DescriptorMatcher? {
        val key = name.trim().uppercase(java.util.Locale.ROOT)
        matchers[key]?.let { return it }
        val matcher = when (key) {
            "SIFT" -> FlannBasedMatcher()
            "ORB" -> BFMatcher.create(Core.NORM_HAMMING)
            else -> null
        } ?: return null
        matchers[key] = matcher
        return matcher
    }

    private fun match(
        detectorName: String,
        query: Mat,
        train: Mat,
    ): List<Array<org.opencv.core.DMatch>> {
        val matcher = createMatcher(detectorName) ?: return emptyList()
        return try {
            matcher.add(listOf(train))
            matcher.train()
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(query, knn, 2)
            knn.mapNotNull { item ->
                val array = item.toArray()
                item.release()
                if (array.size < 2) null else array
            }
        } finally {
            matcher.clear()
        }
    }

    private fun postprocess(
        knn: List<Array<org.opencv.core.DMatch>>,
        templateKeypoints: MatOfKeyPoint,
        sceneKeypoints: MatOfKeyPoint,
        templateWidth: Int,
        templateHeight: Int,
        roi: Box,
        ratio: Double,
        minCount: Int,
    ): List<VisionResult> {
        val templatePoints = templateKeypoints.toArray()
        val scenePoints = sceneKeypoints.toArray()
        val objectPoints = ArrayList<Point>()
        val scene = ArrayList<Point>()

        for (matches in knn) {
            if (matches.size < 2) continue
            val best = matches[0]
            val second = matches[1]
            if (best.distance > ratio * second.distance) continue
            if (best.trainIdx !in templatePoints.indices || best.queryIdx !in scenePoints.indices) continue
            objectPoints.add(templatePoints[best.trainIdx].pt)
            scene.add(scenePoints[best.queryIdx].pt)
        }

        val results = ArrayList<VisionResult>()
        val corners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(templateWidth.toDouble(), 0.0),
            Point(templateWidth.toDouble(), templateHeight.toDouble()),
            Point(0.0, templateHeight.toDouble()),
        )
        // 复用点集 Mat，避免每轮 RANSAC 都分配 native 对象。
        val objectMat = MatOfPoint2f()
        val sceneMat = MatOfPoint2f()
        try {
            while (scene.size >= 4) {
                objectMat.fromArray(*objectPoints.toTypedArray())
                sceneMat.fromArray(*scene.toTypedArray())
                val homography = Geometry.findHomography(
                    objectMat,
                    sceneMat,
                    Geometry.RANSAC,
                )
                if (homography == null || homography.empty()) break
                val sceneCorners = MatOfPoint2f()
                try {
                    Core.perspectiveTransform(corners, sceneCorners, homography)
                    val points = sceneCorners.toArray()
                    if (points.size < 4) break
                    val minX = points.minOf { it.x }
                    val minY = points.minOf { it.y }
                    val maxX = points.maxOf { it.x }
                    val maxY = points.maxOf { it.y }
                    val sceneBox = Box(
                        (minX + roi.left).toInt(),
                        (minY + roi.top).toInt(),
                        (maxX + roi.left).toInt(),
                        (maxY + roi.top).toInt(),
                    )
                    val box = sceneBox.intersect(roi) ?: break
                    val count = scene.count { sceneBox.contains(it.x.toInt() + roi.left, it.y.toInt() + roi.top) }
                    if (count == 0) break
                    if (count >= minCount) {
                        results.add(VisionResult(box = box, score = count.toDouble(), count = count))
                    }

                    val keepObject = ArrayList<Point>(scene.size)
                    val keepScene = ArrayList<Point>(scene.size)
                    for (index in scene.indices) {
                        val point = scene[index]
                        if (sceneBox.contains(point.x.toInt() + roi.left, point.y.toInt() + roi.top)) continue
                        keepObject.add(objectPoints[index])
                        keepScene.add(point)
                    }
                    objectPoints.clear()
                    objectPoints.addAll(keepObject)
                    scene.clear()
                    scene.addAll(keepScene)
                } finally {
                    sceneCorners.release()
                    homography.release()
                }
            }
        } finally {
            sceneMat.release()
            objectMat.release()
            corners.release()
        }
        return results
    }

    private fun createGreenMask(template: Mat): Mat {
        if (template.channels() < 3) return Mat()
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
            return Mat()
        }
        return mask
    }
}
