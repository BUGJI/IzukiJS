package com.benton.izukijs.pipeline

import com.benton.izukijs.vision.RectSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object PipelineParser {

    fun parse(json: String, defaults: PipelineDefaults = PipelineDefaults.EMPTY): PipelineSpec {
        val root = try {
            JSONObject(json)
        } catch (t: JSONException) {
            throw PipelineException("Pipeline JSON 无效: ${t.message}")
        }
        return parse(root, defaults)
    }

    /**
     * 解析单文档 Pipeline：顶层 `Default` 与识别 / 动作类型块作为默认参数，
     * 其余 key 作为节点。适合脚本直接传入 MaaFramework 风格的完整文档。
     */
    fun parseDocument(json: String): PipelineSpec {
        val root = try {
            JSONObject(json)
        } catch (t: JSONException) {
            throw PipelineException("Pipeline JSON 无效: ${t.message}")
        }
        return parseDocument(root)
    }

    fun parseDocument(root: JSONObject): PipelineSpec = parseNodes(
        root = root,
        defaults = parseDefaults(root),
        skipDefaultSections = true,
    )

    fun parse(root: JSONObject, defaults: PipelineDefaults = PipelineDefaults.EMPTY): PipelineSpec =
        parseNodes(root, defaults, skipDefaultSections = false)

    private fun parseNodes(
        root: JSONObject,
        defaults: PipelineDefaults,
        skipDefaultSections: Boolean,
    ): PipelineSpec {
        val defaultNode = defaults.defaultNodeJson?.let {
            parseNodeInternal(name = "", input = it, defaults = PipelineDefaults.EMPTY, defaultNode = null)
        }
        val nodes = LinkedHashMap<String, PipelineNode>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name.startsWith("$")) continue
            if (skipDefaultSections && isDefaultSection(name)) continue
            val value = root.optJSONObject(name)
                ?: throw PipelineException("节点 $name 必须是 JSON 对象")
            nodes[name] = parseNodeInternal(name, value, defaults, defaultNode)
        }
        return PipelineSpec(nodes)
    }

    private fun isDefaultSection(name: String): Boolean =
        name == "Default" || RecognitionType.from(name) != null || ActionType.from(name) != null

    fun parseDefaults(root: JSONObject): PipelineDefaults {
        val defaultNode = root.optJSONObject("Default")?.let(::deepCopyObject)
        val recognition = LinkedHashMap<RecognitionType, JSONObject>()
        val action = LinkedHashMap<ActionType, JSONObject>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("$") || key == "Default") continue
            val value = root.optJSONObject(key) ?: continue
            val recognitionType = RecognitionType.from(key)
            val actionType = ActionType.from(key)
            if (recognitionType != null) {
                recognition[recognitionType] = parseRecognitionDefault(value, recognitionType)
            }
            if (actionType != null) {
                action[actionType] = parseActionDefault(value, actionType)
            }
        }
        return PipelineDefaults(defaultNode, recognition, action)
    }

    fun parseInlineRecognition(input: JSONObject): RecognitionSpec {
        readRecognition(input)?.let { return it }
        if (input.has("type")) return readRecognitionObject(input)
        throw PipelineException("内联识别缺少 recognition 或 type")
    }

    fun parseTarget(
        input: JSONObject,
        key: String,
        offsetKey: String,
        default: TargetSpec,
    ): TargetSpec {
        val raw = if (input.has(key) && !input.isNull(key)) input.opt(key) else null
        val base = if (raw == null) default else parseTargetValue(raw)
        // 配置校验同时接受 snake_case / camelCase；运行时也要读同一个别名。
        val camelOffsetKey = offsetKey.replace("_offset", "Offset")
        val offsetRaw = when {
            input.has(offsetKey) && !input.isNull(offsetKey) -> input.opt(offsetKey)
            input.has(camelOffsetKey) && !input.isNull(camelOffsetKey) -> input.opt(camelOffsetKey)
            else -> null
        }
        val offset = if (offsetRaw == null) base.offset else parseRectArray(offsetRaw, offsetKey)
        return base.copy(offset = offset)
    }

    /**
     * 识别 ROI 目标：除 `true` / 节点名 / 坐标数组外，也接受 `{x,y,width,height}`，
     * 与 `images.recognize` 的 ROI 写法保持一致。
     */
    fun parseTargetOrRoi(
        input: JSONObject,
        key: String,
        offsetKey: String,
        default: TargetSpec,
    ): TargetSpec {
        val raw = if (input.has(key) && !input.isNull(key)) input.opt(key) else null
        if (raw !is JSONObject) return parseTarget(input, key, offsetKey, default)
        val rect = RectSpec(
            x = raw.optInt("x", 0),
            y = raw.optInt("y", 0),
            width = raw.optInt("width", raw.optInt("w", 0)),
            height = raw.optInt("height", raw.optInt("h", 0)),
        )
        val offsetRaw = if (input.has(offsetKey) && !input.isNull(offsetKey)) input.opt(offsetKey) else null
        val offset = if (offsetRaw == null) default.offset else parseRectArray(offsetRaw, offsetKey)
        return TargetSpec(type = TargetType.Region, rect = rect, offset = offset)
    }

    fun parseWaitFreezes(raw: Any?, default: WaitFreezesSpec = WaitFreezesSpec()): WaitFreezesSpec {
        if (raw == null || raw == JSONObject.NULL) return default
        if (raw is Number) return default.copy(timeMs = raw.toLong())
        if (raw !is JSONObject) throw PipelineException("wait_freezes 必须是数字或对象")
        val target = parseTarget(raw, "target", "target_offset", default.target)
        return WaitFreezesSpec(
            timeMs = longField(raw, "time", default.timeMs),
            target = target,
            threshold = doubleField(raw, "threshold", default.threshold),
            method = intField(raw, "method", default.method),
            rateLimitMs = longField(raw, listOf("rate_limit", "rateLimit"), default.rateLimitMs),
            timeoutMs = longField(raw, "timeout", default.timeoutMs),
        )
    }

    private fun parseNodeInternal(
        name: String,
        input: JSONObject,
        defaults: PipelineDefaults,
        defaultNode: PipelineNode?,
    ): PipelineNode {
        rejectDeprecated(input)
        val merged = mergeNodeObjects(defaults.defaultNodeJson, input)

        val inputRecognition = readRecognition(input)
        val fallbackRecognition = defaultNode?.recognition ?: RecognitionSpec()
        val recognition = when {
            inputRecognition == null -> fallbackRecognition
            inputRecognition.type == fallbackRecognition.type -> RecognitionSpec(
                inputRecognition.type,
                mergeObjects(fallbackRecognition.param, inputRecognition.param),
            )

            else -> RecognitionSpec(
                inputRecognition.type,
                mergeObjects(defaults.recognition[inputRecognition.type], inputRecognition.param),
            )
        }

        val inputAction = readAction(input)
        val fallbackAction = defaultNode?.action ?: ActionSpec()
        val action = when {
            inputAction == null -> fallbackAction
            inputAction.type == fallbackAction.type -> ActionSpec(
                inputAction.type,
                mergeObjects(fallbackAction.param, inputAction.param),
            )

            else -> ActionSpec(
                inputAction.type,
                mergeObjects(defaults.action[inputAction.type], inputAction.param),
            )
        }

        val focus = when (val raw = merged.opt("focus")) {
            null -> null
            is JSONObject -> deepCopyObject(raw)
            is String -> JSONObject().put("display", raw)
            else -> null
        }

        return PipelineNode(
            name = name,
            recognition = recognition,
            action = action,
            next = parseNodeList(merged, "next"),
            onError = parseNodeList(merged, "on_error", "onError"),
            anchors = if (input.has("anchor") && !input.isNull("anchor")) {
                parseAnchors(input, name)
            } else {
                defaultNode?.anchors ?: emptyMap()
            },
            enabled = boolField(merged, "enabled", boolField(merged, "enable", true)),
            inverse = boolField(merged, "inverse", false),
            rateLimitMs = longField(merged, listOf("rate_limit", "rateLimit"), 1000L),
            timeoutMs = longField(merged, "timeout", 20_000L),
            preDelayMs = longField(merged, listOf("pre_delay", "preDelay"), 200L),
            postDelayMs = longField(merged, listOf("post_delay", "postDelay"), 200L),
            preWaitFreezes = parseWaitFreezes(merged.opt("pre_wait_freezes") ?: merged.opt("preWaitFreezes")),
            postWaitFreezes = parseWaitFreezes(merged.opt("post_wait_freezes") ?: merged.opt("postWaitFreezes")),
            repeat = intField(merged, "repeat", 1).coerceAtLeast(1),
            repeatDelayMs = longField(merged, listOf("repeat_delay", "repeatDelay"), 0L),
            repeatWaitFreezes = parseWaitFreezes(
                merged.opt("repeat_wait_freezes") ?: merged.opt("repeatWaitFreezes"),
            ),
            maxHit = intField(merged, listOf("max_hit", "maxHit"), Int.MAX_VALUE).coerceAtLeast(1),
            focus = focus,
            attach = merged.optJSONObject("attach")?.let(::deepCopyObject) ?: JSONObject(),
        )
    }

    private fun parseRecognitionDefault(input: JSONObject, expected: RecognitionType): JSONObject {
        val spec = readRecognition(input)
        return when {
            spec == null -> filterKeys(input, recognitionKeys(expected))
            spec.type == expected -> spec.param
            else -> throw PipelineException(
                "默认识别 $expected 的类型不匹配: ${spec.type.wireName}",
            )
        }
    }

    private fun parseActionDefault(input: JSONObject, expected: ActionType): JSONObject {
        val spec = readAction(input)
        return when {
            spec == null -> filterKeys(input, actionKeys(expected))
            spec.type == expected -> spec.param
            else -> throw PipelineException(
                "默认动作 $expected 的类型不匹配: ${spec.type.wireName}",
            )
        }
    }

    private fun readRecognition(input: JSONObject): RecognitionSpec? {
        if (!input.has("recognition") || input.isNull("recognition")) return null
        return when (val raw = input.opt("recognition")) {
            null -> throw PipelineException("recognition 必须是字符串或对象")
            is String -> {
                val type = RecognitionType.from(raw)
                    ?: throw PipelineException("未知识别类型: $raw")
                RecognitionSpec(type, filterKeys(input, recognitionKeys(type)))
            }

            is JSONObject -> readRecognitionObject(raw)
            else -> throw PipelineException("recognition 必须是字符串或对象")
        }
    }

    private fun readRecognitionObject(raw: JSONObject): RecognitionSpec {
        val typeRaw = raw.optString("type", "")
        val type = RecognitionType.from(typeRaw)
            ?: throw PipelineException("未知识别类型: $typeRaw")
        val param = raw.optJSONObject("param")
        return RecognitionSpec(
            type,
            if (param != null) deepCopyObject(param) else filterKeys(raw, recognitionKeys(type)),
        )
    }

    private fun readAction(input: JSONObject): ActionSpec? {
        if (!input.has("action") || input.isNull("action")) return null
        return when (val raw = input.opt("action")) {
            null -> throw PipelineException("action 必须是字符串或对象")
            is String -> {
                val type = ActionType.from(raw)
                    ?: throw PipelineException("未知动作类型: $raw")
                ActionSpec(type, filterKeys(input, actionKeys(type)))
            }

            is JSONObject -> readActionObject(raw)
            else -> throw PipelineException("action 必须是字符串或对象")
        }
    }

    private fun readActionObject(raw: JSONObject): ActionSpec {
        val typeRaw = raw.optString("type", "")
        val type = ActionType.from(typeRaw)
            ?: throw PipelineException("未知动作类型: $typeRaw")
        val param = raw.optJSONObject("param")
        return ActionSpec(
            type,
            if (param != null) deepCopyObject(param) else filterKeys(raw, actionKeys(type)),
        )
    }

    private fun parseNodeList(input: JSONObject, vararg keys: String): List<NodeAttr> {
        val key = keys.firstOrNull { input.has(it) && !input.isNull(it) } ?: return emptyList()
        return when (val raw = input.opt(key)) {
            null -> throw PipelineException("$key 必须是字符串、对象或数组")
            is String -> listOf(parseNodeAttr(raw))
            is JSONObject -> listOf(parseNodeAttrObject(raw))
            is JSONArray -> (0 until raw.length()).map { index ->
                when (val item = raw.opt(index)) {
                    is String -> parseNodeAttr(item)
                    is JSONObject -> parseNodeAttrObject(item)
                    else -> throw PipelineException("$key[$index] 必须是字符串或对象")
                }
            }

            else -> throw PipelineException("$key 必须是字符串、对象或数组")
        }
    }

    private fun parseNodeAttr(raw: String): NodeAttr {
        var remaining = raw.trim()
        var jumpBack = false
        var anchorRef = false
        while (remaining.startsWith("[")) {
            val end = remaining.indexOf(']')
            if (end < 0) throw PipelineException("节点属性缺少 ']': $raw")
            when (remaining.substring(0, end + 1)) {
                "[JumpBack]" -> jumpBack = true
                "[Anchor]" -> anchorRef = true
            }
            remaining = remaining.substring(end + 1)
        }
        if (remaining.isBlank()) throw PipelineException("节点属性缺少节点名: $raw")
        return NodeAttr(remaining, jumpBack, anchorRef)
    }

    private fun parseNodeAttrObject(obj: JSONObject): NodeAttr {
        val name = obj.optString("name", "").trim()
        if (name.isBlank()) throw PipelineException("节点属性缺少 name")
        return NodeAttr(
            name = name,
            jumpBack = obj.optBoolean("jump_back", obj.optBoolean("jumpBack", false)),
            anchorRef = obj.optBoolean("anchor", false),
        )
    }

    private fun parseAnchors(input: JSONObject, nodeName: String): Map<String, String> {
        if (!input.has("anchor") || input.isNull("anchor")) return emptyMap()
        val out = LinkedHashMap<String, String>()
        when (val raw = input.opt("anchor")) {
            null -> throw PipelineException("anchor 必须是字符串、字符串数组或对象")
            is String -> if (raw.isNotBlank()) out[raw] = nodeName
            is JSONArray -> for (index in 0 until raw.length()) {
                val name = raw.optString(index, "")
                if (name.isNotBlank()) out[name] = nodeName
            }

            is JSONObject -> {
                val keys = raw.keys()
                while (keys.hasNext()) {
                    val anchor = keys.next()
                    val target = raw.opt(anchor)
                    if (target !is String) throw PipelineException("anchor.$anchor 必须是字符串")
                    out[anchor] = target
                }
            }

            else -> throw PipelineException("anchor 必须是字符串、字符串数组或对象")
        }
        return out
    }

    private fun parseTargetValue(raw: Any?): TargetSpec = when (raw) {
        is Boolean -> if (raw) {
            TargetSpec(type = TargetType.Self)
        } else {
            throw PipelineException("target 不能为 false")
        }

        is String -> {
            val attr = parseNodeAttr(raw)
            TargetSpec(
                type = if (attr.anchorRef) TargetType.Anchor else TargetType.PreTask,
                ref = attr.name,
            )
        }

        is JSONArray -> TargetSpec(type = TargetType.Region, rect = parseRect(raw, "target"))
        else -> throw PipelineException("target 必须是 true、字符串或坐标数组")
    }

    private fun parseRect(raw: JSONArray, label: String): RectSpec {
        if (raw.length() != 2 && raw.length() != 4) {
            throw PipelineException("$label 必须是 2 或 4 个整数")
        }
        val values = IntArray(raw.length())
        for (index in values.indices) {
            val value = raw.opt(index)
            if (value !is Number) throw PipelineException("$label[$index] 必须是整数")
            values[index] = value.toInt()
        }
        return if (values.size == 2) {
            RectSpec(values[0], values[1], 1, 1)
        } else {
            RectSpec(values[0], values[1], values[2], values[3])
        }
    }

    private fun parseRectArray(raw: Any?, label: String): IntArray {
        if (raw !is JSONArray) throw PipelineException("$label 必须是坐标数组")
        if (raw.length() != 2 && raw.length() != 4) {
            throw PipelineException("$label 必须是 2 或 4 个整数")
        }
        val values = IntArray(4)
        for (index in 0 until raw.length()) {
            val value = raw.opt(index)
            if (value !is Number) throw PipelineException("$label[$index] 必须是整数")
            values[index] = value.toInt()
        }
        if (raw.length() == 2) {
            values[2] = 1
            values[3] = 1
        }
        return values
    }

    private fun rejectDeprecated(input: JSONObject) {
        if (input.has("is_sub")) {
            throw PipelineException("is_sub 已废弃，请使用 [JumpBack] 节点属性")
        }
        if (input.has("interrupt")) {
            throw PipelineException("interrupt 已废弃，请使用 [JumpBack] 节点属性")
        }
    }

    private fun filterKeys(input: JSONObject, keys: Set<String>): JSONObject {
        val out = JSONObject()
        for (key in keys) {
            if (input.has(key) && !input.isNull(key)) {
                out.put(key, deepCopyValue(input.opt(key)))
            }
        }
        return out
    }

    private fun longField(input: JSONObject, key: String, default: Long): Long =
        longField(input, listOf(key), default)

    private fun longField(input: JSONObject, keys: List<String>, default: Long): Long {
        val key = keys.firstOrNull { input.has(it) && !input.isNull(it) } ?: return default
        return when (val value = input.opt(key)) {
            null -> throw PipelineException("$key 必须是整数")
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: throw PipelineException("$key 必须是整数")
    }

    private fun intField(input: JSONObject, keys: List<String>, default: Int): Int {
        val key = keys.firstOrNull { input.has(it) && !input.isNull(it) } ?: return default
        return when (val value = input.opt(key)) {
            null -> throw PipelineException("$key 必须是整数")
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: throw PipelineException("$key 必须是整数")
    }

    private fun intField(input: JSONObject, key: String, default: Int): Int =
        intField(input, listOf(key), default)

    private fun doubleField(input: JSONObject, key: String, default: Double): Double {
        if (!input.has(key) || input.isNull(key)) return default
        return when (val value = input.opt(key)) {
            null -> throw PipelineException("$key 必须是数字")
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: throw PipelineException("$key 必须是数字")
    }

    private fun boolField(input: JSONObject, key: String, default: Boolean): Boolean {
        if (!input.has(key) || input.isNull(key)) return default
        val value = input.opt(key)
        if (value !is Boolean) throw PipelineException("$key 必须是布尔值")
        return value
    }

    private fun recognitionKeys(type: RecognitionType): Set<String> = RECOGNITION_KEYS.getValue(type)

    private fun actionKeys(type: ActionType): Set<String> = ACTION_KEYS.getValue(type)

    private val COMMON_ROI_KEYS = setOf("roi", "roi_offset", "roiOffset")

    private val RECOGNITION_KEYS: Map<RecognitionType, Set<String>> = mapOf(
        RecognitionType.DirectHit to COMMON_ROI_KEYS,
        RecognitionType.TemplateMatch to COMMON_ROI_KEYS + setOf(
            "template", "threshold", "order_by", "order", "orderBy", "index", "method", "green_mask", "greenMask",
        ),
        RecognitionType.FeatureMatch to COMMON_ROI_KEYS + setOf(
            "template", "count", "order_by", "order", "orderBy", "index", "green_mask", "greenMask", "detector", "ratio",
        ),
        RecognitionType.ColorMatch to COMMON_ROI_KEYS + setOf(
            "method", "lower", "upper", "count", "order_by", "order", "orderBy", "index", "connected",
        ),
        RecognitionType.OCR to COMMON_ROI_KEYS + setOf(
            "expected", "threshold", "replace", "order_by", "order", "orderBy", "index", "only_rec", "onlyRec", "model",
            "color_filter", "colorFilter",
        ),
        RecognitionType.NeuralNetworkClassify to COMMON_ROI_KEYS + setOf(
            "labels", "model", "expected", "order_by", "order", "orderBy", "index",
        ),
        RecognitionType.NeuralNetworkDetect to COMMON_ROI_KEYS + setOf(
            "labels", "model", "expected", "threshold", "order_by", "order", "orderBy", "index",
        ),
        RecognitionType.And to setOf("all_of", "allOf", "box_index", "boxIndex", "sub_name", "subName"),
        RecognitionType.Or to setOf("any_of", "anyOf", "sub_name", "subName"),
        RecognitionType.Custom to COMMON_ROI_KEYS + setOf(
            "custom_recognition", "customRecognition", "custom_recognition_param", "customRecognitionParam", "index",
        ),
    )

    private val ACTION_KEYS: Map<ActionType, Set<String>> = mapOf(
        ActionType.DoNothing to emptySet(),
        ActionType.Click to setOf("target", "target_offset", "targetOffset", "contact", "pressure"),
        ActionType.LongPress to setOf(
            "target", "target_offset", "targetOffset", "duration", "contact", "pressure",
        ),
        ActionType.Swipe to setOf(
            "starting", "begin", "begin_offset", "beginOffset", "end", "end_offset", "endOffset", "end_hold", "endHold",
            "duration", "only_hover", "onlyHover", "contact", "pressure",
        ),
        ActionType.MultiSwipe to setOf("swipes"),
        ActionType.TouchDown to setOf(
            "contact", "target", "target_offset", "targetOffset", "pressure", "auto_up", "autoUp",
        ),
        ActionType.TouchMove to setOf(
            "contact", "target", "target_offset", "targetOffset", "pressure", "auto_up", "autoUp",
        ),
        ActionType.TouchUp to setOf("contact"),
        ActionType.ClickKey to setOf("key"),
        ActionType.LongPressKey to setOf("key", "duration"),
        ActionType.KeyDown to setOf("key", "auto_up", "autoUp"),
        ActionType.KeyUp to setOf("key", "auto_up", "autoUp"),
        ActionType.InputText to setOf("input_text", "inputText"),
        ActionType.StartApp to setOf("package"),
        ActionType.StopApp to setOf("package"),
        ActionType.Scroll to setOf("target", "target_offset", "targetOffset", "dx", "dy"),
        ActionType.StopTask to emptySet(),
        ActionType.Command to setOf("exec", "args", "detach"),
        ActionType.Shell to setOf("cmd", "shell_timeout", "shellTimeout"),
        ActionType.Screencap to setOf("filename", "format", "quality"),
        ActionType.Custom to setOf(
            "target", "target_offset", "targetOffset", "custom_action", "customAction", "custom_action_param",
            "customActionParam",
        ),
    )
}
