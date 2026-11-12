package com.benton.izukijs.pipeline

import com.benton.izukijs.vision.RectSpec
import org.json.JSONArray
import org.json.JSONObject

enum class RecognitionType(val wireName: String) {
    DirectHit("DirectHit"),
    TemplateMatch("TemplateMatch"),
    FeatureMatch("FeatureMatch"),
    ColorMatch("ColorMatch"),
    OCR("OCR"),
    NeuralNetworkClassify("NeuralNetworkClassify"),
    NeuralNetworkDetect("NeuralNetworkDetect"),
    And("And"),
    Or("Or"),
    Custom("Custom"),
    ;

    companion object {
        private val aliases: Map<String, RecognitionType> = buildMap {
            fun add(type: RecognitionType, vararg names: String) {
                names.forEach { put(it.lowercase(java.util.Locale.ROOT), type) }
            }
            add(DirectHit, "DirectHit")
            add(TemplateMatch, "TemplateMatch")
            add(FeatureMatch, "FeatureMatch")
            add(ColorMatch, "ColorMatch")
            add(OCR, "OCR")
            add(NeuralNetworkClassify, "NeuralNetworkClassify", "NNClassify")
            add(NeuralNetworkDetect, "NeuralNetworkDetect", "NNDetect")
            add(And, "And")
            add(Or, "Or")
            add(Custom, "Custom")
        }

        fun from(raw: String?): RecognitionType? =
            raw?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(aliases::get)
    }
}

enum class ActionType(val wireName: String) {
    DoNothing("DoNothing"),
    Click("Click"),
    LongPress("LongPress"),
    Swipe("Swipe"),
    MultiSwipe("MultiSwipe"),
    TouchDown("TouchDown"),
    TouchMove("TouchMove"),
    TouchUp("TouchUp"),
    ClickKey("ClickKey"),
    LongPressKey("LongPressKey"),
    KeyDown("KeyDown"),
    KeyUp("KeyUp"),
    InputText("InputText"),
    StartApp("StartApp"),
    StopApp("StopApp"),
    Scroll("Scroll"),
    StopTask("StopTask"),
    Command("Command"),
    Shell("Shell"),
    Screencap("Screencap"),
    Custom("Custom"),
    ;

    companion object {
        private val aliases: Map<String, ActionType> = buildMap {
            fun add(type: ActionType, vararg names: String) {
                names.forEach { put(it.lowercase(java.util.Locale.ROOT), type) }
            }
            add(DoNothing, "DoNothing")
            add(Click, "Click")
            add(LongPress, "LongPress")
            add(Swipe, "Swipe")
            add(MultiSwipe, "MultiSwipe")
            add(TouchDown, "TouchDown")
            add(TouchMove, "TouchMove")
            add(TouchUp, "TouchUp")
            add(ClickKey, "ClickKey", "Key")
            add(LongPressKey, "LongPressKey")
            add(KeyDown, "KeyDown")
            add(KeyUp, "KeyUp")
            add(InputText, "InputText")
            add(StartApp, "StartApp")
            add(StopApp, "StopApp")
            add(Scroll, "Scroll")
            add(StopTask, "StopTask", "Stop")
            add(Command, "Command")
            add(Shell, "Shell")
            add(Screencap, "Screencap")
            add(Custom, "Custom")
        }

        fun from(raw: String?): ActionType? =
            raw?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let(aliases::get)
    }
}

data class RecognitionSpec(
    val type: RecognitionType = RecognitionType.DirectHit,
    val param: JSONObject = JSONObject(),
)

data class ActionSpec(
    val type: ActionType = ActionType.DoNothing,
    val param: JSONObject = JSONObject(),
)

data class NodeAttr(
    val name: String,
    val jumpBack: Boolean = false,
    val anchorRef: Boolean = false,
)

enum class TargetType { Self, PreTask, Region, Anchor }

data class TargetSpec(
    val type: TargetType = TargetType.Self,
    val ref: String? = null,
    val rect: RectSpec? = null,
    val offset: IntArray = IntArray(4),
)

data class WaitFreezesSpec(
    val timeMs: Long = 0L,
    val target: TargetSpec = TargetSpec(),
    val threshold: Double = 0.95,
    val method: Int = 5,
    val rateLimitMs: Long = 1000L,
    val timeoutMs: Long = 20_000L,
)

data class PipelineNode(
    val name: String,
    val recognition: RecognitionSpec = RecognitionSpec(),
    val action: ActionSpec = ActionSpec(),
    val next: List<NodeAttr> = emptyList(),
    val onError: List<NodeAttr> = emptyList(),
    val anchors: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val inverse: Boolean = false,
    val rateLimitMs: Long = 1000L,
    val timeoutMs: Long = 20_000L,
    val preDelayMs: Long = 200L,
    val postDelayMs: Long = 200L,
    val preWaitFreezes: WaitFreezesSpec = WaitFreezesSpec(),
    val postWaitFreezes: WaitFreezesSpec = WaitFreezesSpec(),
    val repeat: Int = 1,
    val repeatDelayMs: Long = 0L,
    val repeatWaitFreezes: WaitFreezesSpec = WaitFreezesSpec(),
    val maxHit: Int = Int.MAX_VALUE,
    val focus: JSONObject? = null,
    val attach: JSONObject = JSONObject(),
)

data class PipelineSpec(val nodes: Map<String, PipelineNode>) {
    operator fun get(name: String): PipelineNode? = nodes[name]
}

data class PipelineDefaults(
    val defaultNodeJson: JSONObject? = null,
    val recognition: Map<RecognitionType, JSONObject> = emptyMap(),
    val action: Map<ActionType, JSONObject> = emptyMap(),
) {
    fun merge(later: PipelineDefaults): PipelineDefaults = PipelineDefaults(
        defaultNodeJson = when {
            defaultNodeJson == null -> later.defaultNodeJson
            later.defaultNodeJson == null -> defaultNodeJson
            else -> mergeNodeObjects(defaultNodeJson, later.defaultNodeJson)
        },
        recognition = recognition + later.recognition,
        action = action + later.action,
    )

    companion object {
        val EMPTY = PipelineDefaults()

        fun from(root: JSONObject): PipelineDefaults = PipelineParser.parseDefaults(root)
    }
}

class PipelineException(message: String) : Exception(message)

internal fun deepCopyValue(value: Any?): Any? = when (value) {
    is JSONObject -> deepCopyObject(value)
    is JSONArray -> deepCopyArray(value)
    else -> value
}

internal fun deepCopyObject(obj: JSONObject): JSONObject {
    val out = JSONObject()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out.put(key, deepCopyValue(obj.opt(key)))
    }
    return out
}

internal fun deepCopyArray(array: JSONArray): JSONArray {
    val out = JSONArray()
    for (index in 0 until array.length()) {
        out.put(deepCopyValue(array.opt(index)))
    }
    return out
}

internal fun mergeObjects(base: JSONObject?, overlay: JSONObject?): JSONObject {
    val out = base?.let(::deepCopyObject) ?: JSONObject()
    if (overlay == null) return out
    val keys = overlay.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out.put(key, deepCopyValue(overlay.opt(key)))
    }
    return out
}

internal fun mergeNodeObjects(base: JSONObject?, overlay: JSONObject?): JSONObject {
    val out = base?.let(::deepCopyObject) ?: JSONObject()
    if (overlay == null) return out
    val keys = overlay.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = overlay.opt(key)
        when {
            key == "attach" && value is JSONObject -> {
                val mergedAttach = out.optJSONObject("attach") ?: JSONObject()
                out.put("attach", mergeObjects(mergedAttach, value))
            }

            key in WAIT_FREEZES_KEYS && value is JSONObject -> {
                val merged = out.optJSONObject(key) ?: JSONObject()
                out.put(key, mergeObjects(merged, value))
            }

            else -> out.put(key, deepCopyValue(value))
        }
    }
    return out
}

private val WAIT_FREEZES_KEYS = setOf("pre_wait_freezes", "post_wait_freezes", "repeat_wait_freezes")
