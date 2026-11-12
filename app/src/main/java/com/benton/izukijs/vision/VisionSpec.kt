package com.benton.izukijs.vision

import org.json.JSONArray
import org.json.JSONObject

/**
 * 识别参数模型，字段命名对齐 MaaFramework 的 Pipeline 协议。
 *
 * 支持 `type` 或 `recognition` 作为类型字段；类型名大小写不敏感。
 */
sealed interface VisionSpec {
    val roi: RoiSpec?
    val orderBy: ResultOrderBy
    val index: Int
}

data class TemplateMatchSpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val templates: List<String>,
    val thresholds: List<Double>,
    val method: Int,
    val greenMask: Boolean,
) : VisionSpec

data class ColorMatchSpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val method: Int,
    val ranges: List<Pair<IntArray, IntArray>>,
    val count: Int,
    val connected: Boolean,
) : VisionSpec

data class FeatureMatchSpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val templates: List<String>,
    val detector: String,
    val ratio: Double,
    val count: Int,
    val greenMask: Boolean,
) : VisionSpec

data class ColorFilterSpec(
    val method: Int,
    val ranges: List<Pair<IntArray, IntArray>>,
)

data class OcrSpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val expected: List<String>,
    val threshold: Double,
    val replace: List<Pair<String, String>>,
    val colorFilter: ColorFilterSpec?,
    val onlyRec: Boolean,
) : VisionSpec

data class NeuralClassifySpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val model: String,
    val labels: List<String>,
    val expected: List<Any>,
    val expectedIndices: List<Int>,
) : VisionSpec

data class NeuralDetectSpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val model: String,
    val labels: List<String>,
    val expected: List<Any>,
    val expectedIndices: List<Int>,
    val thresholds: List<Double>,
) : VisionSpec

/** And/Or 的子识别；[subName] 供后续子识别的 `roi` 字符串引用。 */
data class SubSpec(
    val spec: VisionSpec,
    val subName: String?,
)

data class AndSpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val allOf: List<SubSpec>,
    val boxIndex: Int,
) : VisionSpec

data class OrSpec(
    override val roi: RoiSpec?,
    override val orderBy: ResultOrderBy,
    override val index: Int,
    val anyOf: List<SubSpec>,
    val boxIndex: Int,
) : VisionSpec

object VisionSpecParser {

    fun parse(json: String): VisionSpec {
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            throw VisionSpecException("识别参数不是合法 JSON: ${t.message}")
        }
        return parse(obj)
    }

    fun parse(obj: JSONObject): VisionSpec {
        val (type, body) = normalize(obj)
        if (type.isBlank()) throw VisionSpecException("缺少 type 字段")
        return when (type.trim().lowercase(java.util.Locale.ROOT)) {
            "templatematch" -> parseTemplate(body)
            "colormatch" -> parseColor(body)
            "featurematch" -> parseFeature(body)
            "ocr" -> parseOcr(body)
            "neuralnetworkclassify" -> parseClassify(body)
            "neuralnetworkdetect" -> parseDetect(body)
            "and" -> parseAnd(body)
            "or" -> parseOr(body)
            else -> throw VisionSpecException("不支持的识别类型: $type")
        }
    }

    /**
     * 支持 `{recognition: {type, param}}` 与 `{recognition: "OCR"}`；
     * 顶层字段（如 roi）会覆盖到 param 上，便于 Pipeline 内联识别。
     */
    private fun normalize(obj: JSONObject): Pair<String, JSONObject> {
        val raw = obj.opt("recognition")
        if (raw !is JSONObject) {
            val type = obj.optString("type", "").ifBlank {
                (raw as? String).orEmpty()
            }
            return type to obj
        }
        val type = raw.optString("type", "")
        val body = raw.optJSONObject("param")?.let { JSONObject(it.toString()) }
            ?: JSONObject(raw.toString())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "recognition" || key == "type" || body.has(key)) continue
            body.put(key, obj.opt(key))
        }
        return type to body
    }

    private fun parseTemplate(obj: JSONObject): TemplateMatchSpec {
        val templates = stringList(obj, "template")
        if (templates.isEmpty()) throw VisionSpecException("TemplateMatch 缺少 template")
        val thresholds = doubleList(obj, "threshold", listOf(0.8))
        return TemplateMatchSpec(
            roi = parseRoi(obj),
            orderBy = orderBy(obj, ResultOrderBy.Horizontal),
            index = index(obj),
            templates = templates,
            thresholds = thresholds.ifEmpty { listOf(0.8) },
            method = obj.optInt("method", 5),
            greenMask = obj.optBoolean("greenMask", obj.optBoolean("green_mask", false)),
        )
    }

    private fun parseColor(obj: JSONObject): ColorMatchSpec = ColorMatchSpec(
        roi = parseRoi(obj),
        orderBy = orderBy(obj, ResultOrderBy.Horizontal),
        index = index(obj),
        method = obj.optInt("method", 4),
        ranges = parseColorRanges(obj),
        count = obj.optInt("count", 1).coerceAtLeast(1),
        connected = obj.optBoolean("connected", false),
    )

    private fun parseFeature(obj: JSONObject): FeatureMatchSpec {
        val templates = stringList(obj, "template")
        if (templates.isEmpty()) throw VisionSpecException("FeatureMatch 缺少 template")
        return FeatureMatchSpec(
            roi = parseRoi(obj),
            orderBy = orderBy(obj, ResultOrderBy.Horizontal),
            index = index(obj),
            templates = templates,
            detector = obj.optString("detector", "SIFT"),
            ratio = obj.optDouble("ratio", 0.6),
            count = obj.optInt("count", 4).coerceAtLeast(1),
            greenMask = obj.optBoolean("greenMask", obj.optBoolean("green_mask", false)),
        )
    }

    private fun parseOcr(obj: JSONObject): OcrSpec = OcrSpec(
        roi = parseRoi(obj),
        orderBy = orderBy(obj, ResultOrderBy.Horizontal),
        index = index(obj),
        expected = stringList(obj, "expected"),
        threshold = obj.optDouble("threshold", 0.3),
        replace = parseReplace(obj),
        colorFilter = parseColorFilter(obj),
        onlyRec = obj.optBoolean("onlyRec", obj.optBoolean("only_rec", false)),
    )

    private fun parseClassify(obj: JSONObject): NeuralClassifySpec {
        val model = obj.optString("model", "")
        if (model.isBlank()) throw VisionSpecException("NeuralNetworkClassify 缺少 model")
        val expected = parseExpectedValues(obj)
        val labels = stringList(obj, "labels")
        return NeuralClassifySpec(
            roi = parseRoi(obj),
            orderBy = orderBy(obj, ResultOrderBy.Horizontal),
            index = index(obj),
            model = model,
            labels = labels,
            expected = expected,
            expectedIndices = resolveExpectedIndices(expected, labels),
        )
    }

    private fun parseDetect(obj: JSONObject): NeuralDetectSpec {
        val model = obj.optString("model", "")
        if (model.isBlank()) throw VisionSpecException("NeuralNetworkDetect 缺少 model")
        val expected = parseExpectedValues(obj)
        val labels = stringList(obj, "labels")
        return NeuralDetectSpec(
            roi = parseRoi(obj),
            orderBy = orderBy(obj, ResultOrderBy.Horizontal),
            index = index(obj),
            model = model,
            labels = labels,
            expected = expected,
            expectedIndices = resolveExpectedIndices(expected, labels),
            thresholds = doubleList(obj, "threshold", listOf(0.3)).ifEmpty { listOf(0.3) },
        )
    }

    private fun parseAnd(obj: JSONObject): AndSpec {
        val array = obj.optJSONArray("allOf") ?: obj.optJSONArray("all_of")
            ?: throw VisionSpecException("And 缺少 allOf")
        val subs = subSpecs(array)
        if (subs.isEmpty()) throw VisionSpecException("And 的 allOf 为空")
        return AndSpec(
            roi = parseRoi(obj),
            orderBy = orderBy(obj, ResultOrderBy.Horizontal),
            index = index(obj),
            allOf = subs,
            boxIndex = obj.optInt("boxIndex", obj.optInt("box_index", 0)),
        )
    }

    private fun parseOr(obj: JSONObject): OrSpec {
        val array = obj.optJSONArray("anyOf") ?: obj.optJSONArray("any_of")
            ?: throw VisionSpecException("Or 缺少 anyOf")
        val subs = subSpecs(array)
        if (subs.isEmpty()) throw VisionSpecException("Or 的 anyOf 为空")
        return OrSpec(
            roi = parseRoi(obj),
            orderBy = orderBy(obj, ResultOrderBy.Horizontal),
            index = index(obj),
            anyOf = subs,
            boxIndex = obj.optInt("boxIndex", obj.optInt("box_index", 0)),
        )
    }

    private fun subSpecs(array: JSONArray): List<SubSpec> {
        val out = ArrayList<SubSpec>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(SubSpec(parse(item), item.optString("subName", "").ifBlank { item.optString("sub_name", "") }.ifBlank { null }))
        }
        return out
    }

    private fun resolveExpectedIndices(expected: List<Any>, labels: List<String>): List<Int> {
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

    private fun parseExpectedValues(obj: JSONObject): List<Any> {
        val value = obj.opt("expected") ?: return emptyList()
        return when (value) {
            is Number -> listOf(value.toInt())
            is String -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { i ->
                when (val item = value.opt(i)) {
                    is Number -> item.toInt()
                    is String -> item
                    else -> null
                }
            }

            else -> emptyList()
        }
    }

    private fun parseReplace(obj: JSONObject): List<Pair<String, String>> {
        val value = obj.opt("replace") ?: return emptyList()
        if (value !is JSONArray) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until value.length()) {
            val pair = value.optJSONArray(i) ?: continue
            if (pair.length() >= 2) out.add(pair.optString(0) to pair.optString(1))
        }
        return out
    }

    private fun parseColorFilter(obj: JSONObject): ColorFilterSpec? {
        val filter = obj.optJSONObject("colorFilter") ?: obj.optJSONObject("color_filter") ?: return null
        return ColorFilterSpec(
            method = filter.optInt("method", 4),
            ranges = parseColorRanges(filter),
        )
    }

    private fun parseColorRanges(obj: JSONObject): List<Pair<IntArray, IntArray>> {
        val lower = obj.opt("lower") ?: throw VisionSpecException("ColorMatch 缺少 lower")
        val upper = obj.opt("upper") ?: throw VisionSpecException("ColorMatch 缺少 upper")
        val lowers = parseIntMatrix(lower)
        val uppers = parseIntMatrix(upper)
        if (lowers.isEmpty() || uppers.isEmpty()) throw VisionSpecException("ColorMatch 的 lower/upper 为空")
        if (lowers.size != uppers.size) throw VisionSpecException("ColorMatch 的 lower/upper 区间数量不一致")
        return lowers.indices.map { lowers[it] to uppers[it] }
    }

    private fun parseIntMatrix(value: Any): List<IntArray> = when (value) {
        is JSONArray -> when {
            value.length() == 0 -> emptyList()
            value.opt(0) is JSONArray -> (0 until value.length()).mapNotNull { i -> toIntArray(value.optJSONArray(i)) }
            else -> listOf(toIntArray(value))
        }

        is Number -> listOf(intArrayOf(value.toInt()))
        else -> emptyList()
    }

    private fun toIntArray(array: JSONArray?): IntArray =
        if (array == null) IntArray(0) else IntArray(array.length()) { array.optInt(it) }

    private fun parseRoi(obj: JSONObject): RoiSpec? {
        val raw = obj.opt("roi") ?: return null
        val offset = intArrayOption(obj, "roiOffset", "roi_offset") ?: IntArray(4)
        return when (raw) {
            is String -> if (raw.isBlank()) null else RoiSpec(ref = raw, offset = offset)
            is JSONObject -> RoiSpec(
                rect = RectSpec(
                    x = raw.optInt("x", 0),
                    y = raw.optInt("y", 0),
                    width = raw.optInt("width", raw.optInt("w", 0)),
                    height = raw.optInt("height", raw.optInt("h", 0)),
                ),
                offset = offset,
            )

            is JSONArray -> if (raw.length() >= 4) {
                RoiSpec(
                    rect = RectSpec(raw.optInt(0), raw.optInt(1), raw.optInt(2), raw.optInt(3)),
                    offset = offset,
                )
            } else {
                null
            }

            else -> null
        }
    }

    private fun intArrayOption(obj: JSONObject, vararg keys: String): IntArray? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is JSONArray) {
                return IntArray(4) { value.optInt(it) }
            }
        }
        return null
    }

    private fun stringList(obj: JSONObject, key: String): List<String> {
        val value = obj.opt(key) ?: return emptyList()
        return when (value) {
            is String -> if (value.isBlank()) emptyList() else listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { i -> value.opt(i) as? String }
                .filter { it.isNotBlank() }

            else -> emptyList()
        }
    }

    private fun doubleList(obj: JSONObject, key: String, default: List<Double>): List<Double> {
        val value = obj.opt(key) ?: return default
        return when (value) {
            is Number -> listOf(value.toDouble())
            is JSONArray -> (0 until value.length()).mapNotNull { i -> (value.opt(i) as? Number)?.toDouble() }
            else -> default
        }
    }

    private fun orderBy(obj: JSONObject, default: ResultOrderBy): ResultOrderBy =
        parseOrderBy(obj.optString("orderBy", obj.optString("order_by", "")), default)

    private fun index(obj: JSONObject): Int = obj.optInt("index", 0)
}
