package com.benton.izukijs.pipeline

import android.graphics.Bitmap
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.runtime.api.ImageOpenCv
import com.benton.izukijs.vision.Box
import com.benton.izukijs.vision.VisionEngine
import com.benton.izukijs.vision.VisionSpecParser
import com.benton.izukijs.vision.VisionSpec
import com.benton.izukijs.vision.selectIndex
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

class ScreenshotPipelineVision(
    private val screenshotProvider: () -> Bitmap?,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val logBus: LogBus,
    private val spec: PipelineSpec,
    private val engine: VisionEngine,
    private val customRecognition: (String, JSONObject, List<Box>) -> Box? = { _, _, _ -> null },
) : PipelineVision {

    /** 静态识别参数预编译结果；带动态 ROI 引用的节点仍走逐次解析。 */
    private val staticSpecs = ConcurrentHashMap<String, VisionSpec>()

    override fun screenWidth(): Int = screenSizeProvider().first

    override fun screenHeight(): Int = screenSizeProvider().second

    override fun capture(): Bitmap? = screenshotProvider()

    override fun recognize(
        node: PipelineNode,
        image: Bitmap?,
        resolveNode: (String) -> PipelineNode?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Box? = when (node.recognition.type) {
        RecognitionType.DirectHit -> directHit(node.recognition.param, resolveTarget)
        RecognitionType.Custom -> customRecognition(node.recognition.param, resolveTarget)
        else -> visionRecognize(node, image, resolveNode, resolveTarget)
    }

    override fun framesEqual(lhs: Bitmap, rhs: Bitmap, roi: Box, threshold: Double, method: Int): Boolean {
        if (!ImageOpenCv.ensure(logBus)) return false
        val options = JSONObject()
            .put(
                "roi",
                JSONObject()
                    .put("x", roi.left)
                    .put("y", roi.top)
                    .put("width", roi.width)
                    .put("height", roi.height),
            )
            .put("threshold", threshold)
            .put("method", method)
        return engine.compare(lhs, rhs, options).second
    }

    private fun directHit(
        param: JSONObject,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Box? {
        val raw = param.opt("roi")
        if (raw == null || raw == JSONObject.NULL) return fullScreen()
        if (raw is Boolean) {
            return if (raw) fullScreen() else null
        }
        val target = try {
            PipelineParser.parseTargetOrRoi(param, "roi", "roi_offset", TargetSpec())
        } catch (t: Throwable) {
            logBus.warn("DirectHit roi 无效: ${t.message}")
            return null
        }
        return resolveTarget(target, null)
    }

    private fun customRecognition(
        param: JSONObject,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Box? {
        val name = stringValue(param, "custom_recognition", "customRecognition")
            ?: return null
        val rawRoi = param.opt("roi")
        val roi = when {
            rawRoi == null || rawRoi == JSONObject.NULL -> fullScreen()
            rawRoi is Boolean -> if (rawRoi) fullScreen() else return null
            else -> {
                val target = try {
                    PipelineParser.parseTargetOrRoi(param, "roi", "roi_offset", TargetSpec())
                } catch (t: Throwable) {
                    logBus.warn("Custom 识别 roi 无效: ${t.message}")
                    return null
                }
                resolveTarget(target, null) ?: return null
            }
        }
        val extra = param.opt("custom_recognition_param")
            ?: param.opt("customRecognitionParam")
            ?: JSONObject()
        val extraObject = extra as? JSONObject ?: JSONObject().put("value", extra)
        return customRecognition(name, extraObject, listOf(roi))
    }

    private fun visionRecognize(
        node: PipelineNode,
        image: Bitmap?,
        resolveNode: (String) -> PipelineNode?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Box? {
        if (image == null) return null
        if (!ImageOpenCv.ensure(logBus)) return null

        val visionSpec = staticSpecs[node.name] ?: run {
            val param = JSONObject(node.recognition.param.toString())
            param.put("type", node.recognition.type.wireName)
            if (!prepareRecognition(param, node, resolveNode, resolveTarget, 0)) return null

            val parsed = try {
                VisionSpecParser.parse(param)
            } catch (t: Throwable) {
                logBus.warn("Pipeline 识别 ${node.name} 参数无效: ${t.message}")
                return null
            }
            if (isStaticRecognition(node, resolveNode, 0)) {
                staticSpecs[node.name] = parsed
            }
            parsed
        }
        val results = engine.recognize(visionSpec, image)
        return selectIndex(results, visionSpec.index)?.box
    }

    /** 只缓存 ROI 不依赖运行时节点框的识别；字符串 ROI 可能指向 PreTask / Anchor。 */
    private fun isStaticRecognition(
        node: PipelineNode,
        resolveNode: (String) -> PipelineNode?,
        depth: Int,
    ): Boolean {
        if (depth > DeveloperTuning.current.pipelineMaxRecognitionDepth.coerceAtLeast(1)) {
            return false
        }
        if (node.recognition.param.opt("roi") is String) return false
        if (node.recognition.type != RecognitionType.And && node.recognition.type != RecognitionType.Or) {
            return true
        }
        val key = when (node.recognition.type) {
            RecognitionType.And -> if (node.recognition.param.has("all_of")) "all_of" else "allOf"
            RecognitionType.Or -> if (node.recognition.param.has("any_of")) "any_of" else "anyOf"
            else -> return true
        }
        val array = node.recognition.param.optJSONArray(key) ?: return true
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is String -> {
                    val referenced = resolveNode(item) ?: return false
                    if (!isStaticRecognition(referenced, resolveNode, depth + 1)) return false
                }

                is JSONObject -> if (item.opt("roi") is String) return false
                else -> return false
            }
        }
        return true
    }

    private fun prepareRecognition(
        param: JSONObject,
        node: PipelineNode,
        resolveNode: (String) -> PipelineNode?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
        depth: Int,
    ): Boolean {
        if (depth > DeveloperTuning.current.pipelineMaxRecognitionDepth.coerceAtLeast(1)) {
            logBus.warn("Pipeline 子识别嵌套过深: ${node.name}")
            return false
        }
        if (!resolveRoi(param, node, resolveTarget)) return false
        resolveColorFilter(param)
        if (node.recognition.type == RecognitionType.And || node.recognition.type == RecognitionType.Or) {
            if (!expandSubRecognitions(param, node, resolveNode, resolveTarget, depth)) return false
        }
        return true
    }

    private fun resolveRoi(
        param: JSONObject,
        node: PipelineNode,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Boolean {
        val raw = param.opt("roi") ?: return true
        if (raw is Boolean) {
            if (!raw) return false
            param.remove("roi")
            param.remove("roi_offset")
            param.remove("roiOffset")
            return true
        }
        if (raw !is String) return true

        val subNames = collectSubNames(param, node.recognition.type)
        if (raw in subNames) return true

        val target = try {
            PipelineParser.parseTarget(param, "roi", "roi_offset", TargetSpec())
        } catch (t: Throwable) {
            logBus.warn("Pipeline 识别 ${node.name} 的 roi 引用无效: ${t.message}")
            return false
        }
        val box = resolveTarget(target, null) ?: return false
        param.put("roi", JSONArray().put(box.left).put(box.top).put(box.width).put(box.height))
        param.remove("roi_offset")
        param.remove("roiOffset")
        return true
    }

    private fun resolveColorFilter(param: JSONObject) {
        val key = when {
            param.has("color_filter") -> "color_filter"
            param.has("colorFilter") -> "colorFilter"
            else -> return
        }
        val raw = param.opt(key)
        if (raw !is String || raw.isBlank()) return
        val filterNode = spec[raw]
        if (filterNode == null || filterNode.recognition.type != RecognitionType.ColorMatch) {
            logBus.warn("OCR color_filter 引用的节点不是 ColorMatch: $raw")
            param.remove(key)
            return
        }
        val source = filterNode.recognition.param
        val filter = JSONObject().put("method", source.optInt("method", 4))
        source.opt("lower")?.let { filter.put("lower", deepCopyValue(it)) }
        source.opt("upper")?.let { filter.put("upper", deepCopyValue(it)) }
        param.put(key, filter)
    }

    private fun expandSubRecognitions(
        param: JSONObject,
        node: PipelineNode,
        resolveNode: (String) -> PipelineNode?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
        depth: Int,
    ): Boolean {
        val key = when (node.recognition.type) {
            RecognitionType.And -> if (param.has("all_of")) "all_of" else "allOf"
            RecognitionType.Or -> if (param.has("any_of")) "any_of" else "anyOf"
            else -> return true
        }
        val array = param.optJSONArray(key) ?: return true
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is String -> {
                    val referenced = resolveNode(item)
                        ?: return false.also { logBus.warn("Pipeline 子识别引用的节点不存在: $item") }
                    if (referenced.recognition.type == RecognitionType.DirectHit ||
                        referenced.recognition.type == RecognitionType.Custom
                    ) {
                        logBus.warn("Pipeline 子识别不支持 ${referenced.recognition.type.wireName}: $item")
                        return false
                    }
                    val sub = JSONObject(referenced.recognition.param.toString())
                    sub.put("type", referenced.recognition.type.wireName)
                    if (!prepareRecognition(sub, referenced, resolveNode, resolveTarget, depth + 1)) {
                        return false
                    }
                    array.put(index, sub)
                }

                is JSONObject -> {
                    val flattened = flattenInline(item) ?: continue
                    array.put(index, flattened)
                }

                else -> return false
            }
        }
        return true
    }

    private fun flattenInline(item: JSONObject): JSONObject? {
        if (item.has("recognition")) return null
        val type = item.optString("type", "")
        if (type.isBlank()) return null
        val nested = item.optJSONObject("param") ?: return null
        val out = JSONObject(nested.toString())
        out.put("type", type)
        val subName = item.optString("sub_name", item.optString("subName", ""))
        if (subName.isNotBlank()) out.put("sub_name", subName)
        return out
    }

    private fun collectSubNames(param: JSONObject, type: RecognitionType): Set<String> {
        val key = when (type) {
            RecognitionType.And -> if (param.has("all_of")) "all_of" else "allOf"
            RecognitionType.Or -> if (param.has("any_of")) "any_of" else "anyOf"
            else -> return emptySet()
        }
        val array = param.optJSONArray(key) ?: return emptySet()
        val names = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("sub_name", item.optString("subName", ""))
            if (name.isNotBlank()) names.add(name)
        }
        return names
    }

    private fun fullScreen(): Box = Box(0, 0, screenWidth(), screenHeight())

    private fun stringValue(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }

}
