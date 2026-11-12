package com.benton.izukijs.pipeline

import org.json.JSONArray
import org.json.JSONObject

object PipelineDumper {

    fun dump(spec: PipelineSpec): JSONObject {
        val out = JSONObject()
        for ((name, node) in spec.nodes) {
            out.put(name, dumpNode(node))
        }
        return out
    }

    fun dumpNode(node: PipelineNode): JSONObject {
        val out = JSONObject()
        out.put(
            "recognition",
            JSONObject()
                .put("type", node.recognition.type.wireName)
                .put("param", deepCopyObject(node.recognition.param)),
        )
        out.put(
            "action",
            JSONObject()
                .put("type", node.action.type.wireName)
                .put("param", deepCopyObject(node.action.param)),
        )
        out.put("next", dumpNodeAttrs(node.next))
        out.put("on_error", dumpNodeAttrs(node.onError))
        out.put("anchor", dumpAnchors(node.anchors))
        out.put("enabled", node.enabled)
        out.put("inverse", node.inverse)
        out.put("rate_limit", node.rateLimitMs)
        out.put("timeout", node.timeoutMs)
        out.put("pre_delay", node.preDelayMs)
        out.put("post_delay", node.postDelayMs)
        out.put("pre_wait_freezes", dumpWaitFreezes(node.preWaitFreezes))
        out.put("post_wait_freezes", dumpWaitFreezes(node.postWaitFreezes))
        out.put("repeat", node.repeat)
        out.put("repeat_delay", node.repeatDelayMs)
        out.put("repeat_wait_freezes", dumpWaitFreezes(node.repeatWaitFreezes))
        out.put("max_hit", node.maxHit)
        node.focus?.let { out.put("focus", deepCopyObject(it)) }
        out.put("attach", deepCopyObject(node.attach))
        return out
    }

    fun dumpNodeAttrs(attrs: List<NodeAttr>): JSONArray {
        val out = JSONArray()
        for (attr in attrs) {
            out.put(dumpNodeAttr(attr))
        }
        return out
    }

    fun dumpNodeAttr(attr: NodeAttr): Any {
        if (!attr.jumpBack && !attr.anchorRef) return attr.name
        val out = JSONObject().put("name", attr.name)
        if (attr.jumpBack) out.put("jump_back", true)
        if (attr.anchorRef) out.put("anchor", true)
        return out
    }

    fun dumpAnchors(anchors: Map<String, String>): JSONObject {
        val out = JSONObject()
        for ((name, target) in anchors) {
            out.put(name, target)
        }
        return out
    }

    fun dumpTarget(target: TargetSpec): Any = when (target.type) {
        TargetType.Self -> true
        TargetType.PreTask -> target.ref ?: ""
        TargetType.Anchor -> "[Anchor]${target.ref ?: ""}"
        TargetType.Region -> target.rect?.let {
            JSONArray().put(it.x).put(it.y).put(it.width).put(it.height)
        } ?: JSONArray()
    }

    fun dumpWaitFreezes(waitFreezes: WaitFreezesSpec): JSONObject = JSONObject()
        .put("time", waitFreezes.timeMs)
        .put("target", dumpTarget(waitFreezes.target))
        .put("target_offset", intArrayToJson(waitFreezes.target.offset))
        .put("threshold", waitFreezes.threshold)
        .put("method", waitFreezes.method)
        .put("rate_limit", waitFreezes.rateLimitMs)
        .put("timeout", waitFreezes.timeoutMs)

    private fun intArrayToJson(values: IntArray): JSONArray {
        val out = JSONArray()
        values.forEach { out.put(it) }
        return out
    }
}
