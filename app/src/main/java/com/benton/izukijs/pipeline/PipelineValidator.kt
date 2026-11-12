package com.benton.izukijs.pipeline

import org.json.JSONArray
import org.json.JSONObject

enum class PipelineIssueSeverity { Error, Warning }

data class PipelineIssue(
    val severity: PipelineIssueSeverity,
    val node: String?,
    val message: String,
)

object PipelineValidator {

    fun validate(spec: PipelineSpec): List<PipelineIssue> {
        val issues = ArrayList<PipelineIssue>()
        val declaredAnchors = HashMap<String, String>()
        for ((nodeName, node) in spec.nodes) {
            for ((anchor, target) in node.anchors) {
                if (target.isNotEmpty()) declaredAnchors[anchor] = target
            }
        }

        for ((nodeName, node) in spec.nodes) {
            checkReferences(spec, nodeName, node.next, "next", declaredAnchors, issues)
            checkReferences(spec, nodeName, node.onError, "on_error", declaredAnchors, issues)
            checkAnchors(spec, nodeName, node.anchors, issues)
            validateRecognition(spec, nodeName, node.recognition, declaredAnchors, issues)
            validateAction(nodeName, node.action, issues)
            validateWaitFreezes(nodeName, "pre_wait_freezes", node.preWaitFreezes, declaredAnchors, issues)
            validateWaitFreezes(nodeName, "post_wait_freezes", node.postWaitFreezes, declaredAnchors, issues)
            validateWaitFreezes(nodeName, "repeat_wait_freezes", node.repeatWaitFreezes, declaredAnchors, issues)

            if (node.timeoutMs < -1) {
                issues.add(error(nodeName, "timeout 必须 >= -1"))
            }
            if (node.rateLimitMs < 0) {
                issues.add(error(nodeName, "rate_limit 必须 >= 0"))
            }
            if (node.preDelayMs < 0 || node.postDelayMs < 0 || node.repeatDelayMs < 0) {
                issues.add(error(nodeName, "delay 必须 >= 0"))
            }
            if (node.repeat < 1) {
                issues.add(error(nodeName, "repeat 必须 >= 1"))
            }
            if (node.maxHit < 1) {
                issues.add(error(nodeName, "max_hit 必须 >= 1"))
            }
        }
        return issues
    }

    fun isValid(spec: PipelineSpec): Boolean =
        validate(spec).none { it.severity == PipelineIssueSeverity.Error }

    private fun checkReferences(
        spec: PipelineSpec,
        nodeName: String,
        attrs: List<NodeAttr>,
        field: String,
        declaredAnchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        for (attr in attrs) {
            if (attr.anchorRef) {
                if (!declaredAnchors.containsKey(attr.name)) {
                    issues.add(warning(nodeName, "$field 引用的锚点未定义: ${attr.name}"))
                }
                continue
            }
            val target = spec.nodes[attr.name]
            if (target == null) {
                issues.add(error(nodeName, "$field 引用了不存在的节点: ${attr.name}"))
            } else if (!target.enabled) {
                issues.add(warning(nodeName, "$field 引用了已禁用的节点: ${attr.name}"))
            }
        }
    }

    private fun checkAnchors(
        spec: PipelineSpec,
        nodeName: String,
        anchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        for ((anchor, target) in anchors) {
            if (target.isEmpty()) continue
            if (spec.nodes[target] == null) {
                issues.add(error(nodeName, "anchor $anchor 指向不存在的节点: $target"))
            }
        }
    }

    private fun validateRecognition(
        spec: PipelineSpec,
        nodeName: String,
        recognition: RecognitionSpec,
        declaredAnchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        val param = recognition.param
        validateRoi(spec, nodeName, param, declaredAnchors, issues)
        when (recognition.type) {
            RecognitionType.DirectHit -> Unit
            RecognitionType.TemplateMatch, RecognitionType.FeatureMatch -> {
                if (!hasNonEmptyStringOrArray(param, "template")) {
                    issues.add(error(nodeName, "${recognition.type.wireName} 缺少 template"))
                }
            }

            RecognitionType.ColorMatch -> {
                if (param.value("lower") == null || param.value("upper") == null) {
                    issues.add(error(nodeName, "ColorMatch 缺少 lower / upper"))
                }
            }

            RecognitionType.OCR -> {
                validateRegexList(nodeName, param, "expected", required = false, issues)
                validateReplace(nodeName, param, issues)
            }

            RecognitionType.NeuralNetworkClassify, RecognitionType.NeuralNetworkDetect -> {
                if (!hasNonEmptyString(param, "model")) {
                    issues.add(error(nodeName, "${recognition.type.wireName} 缺少 model"))
                }
            }

            RecognitionType.And -> {
                val subs = param.value("all_of", "allOf")
                if (subs !is JSONArray || subs.length() == 0) {
                    issues.add(error(nodeName, "And 缺少非空 all_of"))
                } else {
                    validateSubRecognitions(spec, nodeName, subs, declaredAnchors, issues)
                    val boxIndex = param.value("box_index", "boxIndex")
                    if (boxIndex is Number) {
                        val index = boxIndex.toInt()
                        if (index < 0 || index >= subs.length()) {
                            issues.add(error(nodeName, "And 的 box_index 越界: $index"))
                        }
                    }
                }
            }

            RecognitionType.Or -> {
                val subs = param.value("any_of", "anyOf")
                if (subs !is JSONArray || subs.length() == 0) {
                    issues.add(error(nodeName, "Or 缺少非空 any_of"))
                } else {
                    validateSubRecognitions(spec, nodeName, subs, declaredAnchors, issues)
                }
            }

            RecognitionType.Custom -> {
                if (!hasNonEmptyString(param, "custom_recognition", "customRecognition")) {
                    issues.add(error(nodeName, "Custom 识别缺少 custom_recognition"))
                }
            }
        }
    }

    private fun validateSubRecognitions(
        spec: PipelineSpec,
        nodeName: String,
        subs: JSONArray,
        declaredAnchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        for (index in 0 until subs.length()) {
            when (val item = subs.opt(index)) {
                is String -> if (spec.nodes[item] == null) {
                    issues.add(error(nodeName, "子识别引用了不存在的节点: $item"))
                }

                is JSONObject -> {
                    val sub = try {
                        PipelineParser.parseInlineRecognition(item)
                    } catch (t: PipelineException) {
                        issues.add(error(nodeName, "子识别[$index] 无效: ${t.message}"))
                        continue
                    }
                    validateRecognition(spec, nodeName, sub, declaredAnchors, issues)
                }

                else -> issues.add(error(nodeName, "子识别[$index] 必须是字符串或对象"))
            }
        }
    }

    private fun validateAction(
        nodeName: String,
        action: ActionSpec,
        issues: MutableList<PipelineIssue>,
    ) {
        val param = action.param
        when (action.type) {
            ActionType.DoNothing, ActionType.StopTask -> Unit
            ActionType.Click, ActionType.LongPress, ActionType.TouchDown, ActionType.TouchMove -> {
                validateTarget(nodeName, param, "target", issues)
            }

            ActionType.Swipe -> {
                validateTarget(nodeName, param, "begin", issues)
                validateTargetList(nodeName, param, "end", issues)
            }

            ActionType.MultiSwipe -> {
                val swipes = param.value("swipes")
                if (swipes !is JSONArray || swipes.length() == 0) {
                    issues.add(error(nodeName, "MultiSwipe 缺少非空 swipes"))
                }
            }

            ActionType.Scroll -> {
                validateTarget(nodeName, param, "target", issues)
                if (param.value("dx") !is Number || param.value("dy") !is Number) {
                    issues.add(error(nodeName, "Scroll 缺少 dx / dy"))
                }
            }

            ActionType.Custom -> {
                validateTarget(nodeName, param, "target", issues)
                if (!hasNonEmptyString(param, "custom_action", "customAction")) {
                    issues.add(error(nodeName, "Custom 动作缺少 custom_action"))
                }
            }

            ActionType.ClickKey, ActionType.LongPressKey, ActionType.KeyDown, ActionType.KeyUp -> {
                if (param.value("key") == null) {
                    issues.add(error(nodeName, "${action.type.wireName} 缺少 key"))
                }
            }

            ActionType.InputText -> {
                val value = param.value("input_text", "inputText")
                if (value !is String) {
                    issues.add(error(nodeName, "InputText 缺少 input_text"))
                }
            }

            ActionType.StartApp, ActionType.StopApp -> {
                if (!hasNonEmptyString(param, "package")) {
                    issues.add(error(nodeName, "${action.type.wireName} 缺少 package"))
                }
            }

            ActionType.Command -> {
                if (!hasNonEmptyString(param, "exec")) {
                    issues.add(error(nodeName, "Command 缺少 exec"))
                }
            }

            ActionType.Shell -> {
                if (!hasNonEmptyString(param, "cmd")) {
                    issues.add(error(nodeName, "Shell 缺少 cmd"))
                }
            }

            ActionType.Screencap -> {
                if (!hasNonEmptyString(param, "filename")) {
                    issues.add(error(nodeName, "Screencap 缺少 filename"))
                }
            }

            ActionType.TouchUp -> {
                if (param.value("contact") !is Number) {
                    issues.add(error(nodeName, "TouchUp 缺少 contact"))
                }
            }
        }
    }

    private fun validateRoi(
        spec: PipelineSpec,
        nodeName: String,
        param: JSONObject,
        declaredAnchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        val roi = param.value("roi") ?: return
        validateRegionValue(spec, nodeName, "roi", roi, declaredAnchors, issues)
        validateOffset(nodeName, param, listOf("roi_offset", "roiOffset"), issues)
    }

    private fun validateTarget(
        nodeName: String,
        param: JSONObject,
        key: String,
        issues: MutableList<PipelineIssue>,
    ) {
        val value = param.value(key) ?: return
        when {
            value is Boolean -> Unit
            value is String -> if (value.isBlank()) {
                issues.add(error(nodeName, "$key 引用不能为空"))
            }

            value is JSONArray -> if (!isRectArray(value)) {
                issues.add(error(nodeName, "$key 必须是 2 或 4 个整数"))
            }

            else -> issues.add(error(nodeName, "$key 必须是 true、字符串或坐标数组"))
        }
        validateOffset(nodeName, param, listOf("${key}_offset", "${key}Offset"), issues)
    }

    private fun validateTargetList(
        nodeName: String,
        param: JSONObject,
        key: String,
        issues: MutableList<PipelineIssue>,
    ) {
        val value = param.value(key) ?: return
        val items = when {
            value is JSONArray && isRectArray(value) -> listOf(value)
            value is JSONArray -> (0 until value.length()).map { value.opt(it) }
            else -> listOf(value)
        }
        for (item in items) {
            when {
                item is Boolean -> Unit
                item is String -> if (item.isBlank()) {
                    issues.add(error(nodeName, "$key 引用不能为空"))
                }

                item is JSONArray -> if (!isRectArray(item)) {
                    issues.add(error(nodeName, "$key 必须是 2 或 4 个整数"))
                }

                else -> issues.add(error(nodeName, "$key 必须是 true、字符串或坐标数组"))
            }
        }
    }

    private fun isRectArray(value: JSONArray): Boolean {
        if (value.length() != 2 && value.length() != 4) return false
        for (index in 0 until value.length()) {
            if (value.opt(index) !is Number) return false
        }
        return true
    }

    private fun validateRegionValue(
        spec: PipelineSpec,
        nodeName: String,
        label: String,
        value: Any?,
        declaredAnchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        when (value) {
            is JSONObject -> {
                for (key in listOf("x", "y", "width", "w", "height", "h")) {
                    val item = value.opt(key)
                    if (item != null && item != JSONObject.NULL && item !is Number) {
                        issues.add(error(nodeName, "$label.$key 必须是整数"))
                    }
                }
            }

            is JSONArray -> checkRectArray(nodeName, label, value, issues)
            is String -> checkNodeReference(spec, nodeName, label, value, declaredAnchors, issues)
            is Boolean -> if (!value) {
                issues.add(error(nodeName, "$label 不能为 false"))
            }

            else -> issues.add(error(nodeName, "$label 类型无效"))
        }
    }

    private fun validateOffset(
        nodeName: String,
        param: JSONObject,
        keys: List<String>,
        issues: MutableList<PipelineIssue>,
    ) {
        val key = keys.firstOrNull { param.has(it) && !param.isNull(it) } ?: return
        val value = param.opt(key)
        if (value !is JSONArray) {
            issues.add(error(nodeName, "$key 必须是坐标数组"))
        } else {
            checkRectArray(nodeName, key, value, issues)
        }
    }

    private fun checkRectArray(
        nodeName: String,
        label: String,
        value: JSONArray,
        issues: MutableList<PipelineIssue>,
    ) {
        if (value.length() != 2 && value.length() != 4) {
            issues.add(error(nodeName, "$label 必须是 2 或 4 个整数"))
            return
        }
        for (index in 0 until value.length()) {
            if (value.opt(index) !is Number) {
                issues.add(error(nodeName, "$label[$index] 必须是整数"))
            }
        }
    }

    private fun checkNodeReference(
        spec: PipelineSpec,
        nodeName: String,
        label: String,
        raw: String,
        declaredAnchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        val attr = try {
            PipelineParser.parseTarget(JSONObject().put("value", raw), "value", "value_offset", TargetSpec())
        } catch (t: PipelineException) {
            issues.add(error(nodeName, "$label 引用无效: ${t.message}"))
            return
        }
        val ref = attr.ref ?: return
        when (attr.type) {
            TargetType.Anchor -> if (!declaredAnchors.containsKey(ref)) {
                issues.add(warning(nodeName, "$label 引用的锚点未定义: $ref"))
            }

            TargetType.PreTask -> if (spec.nodes[ref] == null) {
                issues.add(warning(nodeName, "$label 引用的节点不存在: $ref"))
            }

            else -> Unit
        }
    }

    private fun validateWaitFreezes(
        nodeName: String,
        label: String,
        waitFreezes: WaitFreezesSpec,
        declaredAnchors: Map<String, String>,
        issues: MutableList<PipelineIssue>,
    ) {
        if (waitFreezes.timeMs < 0) {
            issues.add(error(nodeName, "$label.time 必须 >= 0"))
        }
        if (waitFreezes.threshold !in 0.0..1.0) {
            issues.add(warning(nodeName, "$label.threshold 建议在 0..1 之间"))
        }
        if (waitFreezes.rateLimitMs < 0) {
            issues.add(error(nodeName, "$label.rate_limit 必须 >= 0"))
        }
        if (waitFreezes.timeoutMs < -1) {
            issues.add(error(nodeName, "$label.timeout 必须 >= -1"))
        }
        if (waitFreezes.target.type == TargetType.Anchor) {
            val ref = waitFreezes.target.ref
            if (ref != null && !declaredAnchors.containsKey(ref)) {
                issues.add(warning(nodeName, "$label.target 引用的锚点未定义: $ref"))
            }
        }
    }

    private fun validateRegexList(
        nodeName: String,
        param: JSONObject,
        key: String,
        required: Boolean,
        issues: MutableList<PipelineIssue>,
    ) {
        val value = param.value(key) ?: run {
            if (required) issues.add(error(nodeName, "$key 不能为空"))
            return
        }
        val patterns = when (value) {
            is String -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                val item = value.opt(index)
                if (item !is String) {
                    issues.add(error(nodeName, "$key[$index] 必须是字符串"))
                    null
                } else {
                    item
                }
            }

            else -> {
                issues.add(error(nodeName, "$key 必须是字符串或字符串数组"))
                return
            }
        }
        for (pattern in patterns) {
            try {
                Regex(pattern)
            } catch (t: Throwable) {
                issues.add(error(nodeName, "$key 正则无效: $pattern (${t.message})"))
            }
        }
    }

    private fun validateReplace(
        nodeName: String,
        param: JSONObject,
        issues: MutableList<PipelineIssue>,
    ) {
        val value = param.value("replace") ?: return
        if (value !is JSONArray) {
            issues.add(error(nodeName, "replace 必须是 [正则, 替换] 数组"))
            return
        }
        for (index in 0 until value.length()) {
            val pair = value.opt(index)
            if (pair !is JSONArray || pair.length() < 2) {
                issues.add(error(nodeName, "replace[$index] 必须是 [正则, 替换]"))
                continue
            }
            val pattern = pair.opt(0)
            if (pattern !is String) {
                issues.add(error(nodeName, "replace[$index][0] 必须是字符串"))
                continue
            }
            try {
                Regex(pattern)
            } catch (t: Throwable) {
                issues.add(error(nodeName, "replace[$index] 正则无效: $pattern (${t.message})"))
            }
        }
    }

    private fun hasNonEmptyString(param: JSONObject, vararg keys: String): Boolean {
        val value = param.value(*keys)
        return value is String && value.isNotBlank()
    }

    private fun hasNonEmptyStringOrArray(param: JSONObject, key: String): Boolean = when (val value = param.value(key)) {
        is String -> value.isNotBlank()
        is JSONArray -> value.length() > 0 && (0 until value.length()).any { value.opt(it) is String }
        else -> false
    }

    private fun error(node: String?, message: String) =
        PipelineIssue(PipelineIssueSeverity.Error, node, message)

    private fun warning(node: String?, message: String) =
        PipelineIssue(PipelineIssueSeverity.Warning, node, message)
}

private fun JSONObject.value(vararg keys: String): Any? {
    for (key in keys) {
        if (has(key) && !isNull(key)) return opt(key)
    }
    return null
}
