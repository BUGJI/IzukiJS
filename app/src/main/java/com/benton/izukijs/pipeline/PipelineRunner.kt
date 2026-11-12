package com.benton.izukijs.pipeline

import android.graphics.Bitmap
import com.benton.izukijs.vision.Box
import org.json.JSONArray
import org.json.JSONObject

data class PipelineRunOptions(
    val maxSteps: Int = 10_000,
    val timeoutMs: Long = 0L,
)

data class PipelineRunResult(
    val ok: Boolean,
    val stopped: Boolean = false,
    val taskStopped: Boolean = false,
    val entry: String = "",
    val lastNode: String? = null,
    val steps: Int = 0,
    val hitCounts: Map<String, Int> = emptyMap(),
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ok", ok)
        put("stopped", stopped)
        put("taskStopped", taskStopped)
        put("entry", entry)
        put("lastNode", lastNode ?: JSONObject.NULL)
        put("steps", steps)
        put("hitCounts", JSONObject().apply { hitCounts.forEach { (name, count) -> put(name, count) } })
        put("error", error ?: JSONObject.NULL)
    }
}

interface PipelineVision {
    fun screenWidth(): Int

    fun screenHeight(): Int

    fun capture(): Bitmap?

    fun recognize(
        node: PipelineNode,
        image: Bitmap?,
        resolveNode: (String) -> PipelineNode?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): Box?

    fun framesEqual(lhs: Bitmap, rhs: Bitmap, roi: Box, threshold: Double, method: Int): Boolean
}

data class PipelineActionResult(
    val success: Boolean,
    val stopTask: Boolean = false,
)

interface PipelineActions {
    fun execute(
        node: PipelineNode,
        box: Box?,
        resolveTarget: (TargetSpec, Box?) -> Box?,
    ): PipelineActionResult

    fun sleep(millis: Long)

    fun isStopRequested(): Boolean
}

class PipelineRunner(
    private val spec: PipelineSpec,
    private val vision: PipelineVision,
    private val actions: PipelineActions,
    private val options: PipelineRunOptions = PipelineRunOptions(),
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val hitCounts = LinkedHashMap<String, Int>()
    private val anchors = LinkedHashMap<String, String>()
    private val nodeBoxes = LinkedHashMap<String, Box>()
    private val jumpback = ArrayDeque<String>()

    private var steps = 0
    private var stopped = false
    private var taskStopped = false
    private var timedOut = false
    private var lastNode: String? = null
    private val startedAt = now()

    fun run(entry: String): PipelineRunResult {
        val entryNode = spec[entry]
            ?: return finish(false, entry, "入口节点不存在: $entry")

        var node = entryNode
        var next = listOf(NodeAttr(entry))
        var errorHandling = false

        while (next.isNotEmpty()) {
            if (actions.isStopRequested()) {
                stopped = true
                return finish(true, entry, null)
            }
            if (overallTimedOut()) {
                timedOut = true
                return finish(false, entry, "运行超时")
            }
            if (steps >= options.maxSteps) {
                return finish(false, entry, "超过最大步数 ${options.maxSteps}")
            }

            val detail = runNext(next, node)
            if (actions.isStopRequested()) {
                stopped = true
                return finish(true, entry, null)
            }
            if (overallTimedOut()) {
                timedOut = true
                return finish(false, entry, "运行超时")
            }

            if (detail != null) {
                errorHandling = false
                val hitNode = spec[detail.nodeName]
                    ?: return finish(false, entry, "命中节点不存在: ${detail.nodeName}")
                val previousNode = node.name
                node = hitNode

                // 只有动作成功才建立回跳点；失败路径不应在之后误触发 jump_back。
                if (detail.jumpBack && detail.actionCompleted) jumpback.addLast(previousNode)
                if (detail.actionCompleted) {
                    next = node.next
                } else {
                    errorHandling = true
                    next = node.onError
                    log("节点 ${node.name} 动作失败，进入 on_error")
                }
                if (detail.stopTask) {
                    taskStopped = true
                    return finish(true, entry, null)
                }
            } else if (errorHandling) {
                log("错误处理未能恢复，任务失败: ${node.name}")
                return finish(false, entry, "on_error 未能恢复: ${node.name}")
            } else {
                errorHandling = true
                next = node.onError
                log("节点 ${node.name} 的 next 超时或无可用节点，进入 on_error")
            }

            if (next.isEmpty() && !errorHandling && jumpback.isNotEmpty()) {
                val top = jumpback.removeLast()
                val topNode = spec[top]
                    ?: return finish(false, entry, "回跳节点不存在: $top")
                node = topNode
                next = node.next
            }
        }

        return finish(!errorHandling, entry, if (errorHandling) "任务以错误状态结束" else null)
    }

    private fun finish(ok: Boolean, entry: String, error: String?): PipelineRunResult = PipelineRunResult(
        ok = ok && !timedOut,
        stopped = stopped,
        taskStopped = taskStopped,
        entry = entry,
        lastNode = lastNode,
        steps = steps,
        hitCounts = hitCounts.toMap(),
        error = when {
            timedOut -> "运行超时"
            else -> error
        },
    )

    private fun runNext(next: List<NodeAttr>, pretask: PipelineNode): NextDetail? {
        val candidates = next.mapNotNull { attr ->
            val name = if (attr.anchorRef) anchors[attr.name] else attr.name
            if (name == null) return@mapNotNull null
            val node = spec[name] ?: return@mapNotNull null
            if (!node.enabled) return@mapNotNull null
            Candidate(attr, node)
        }
        if (candidates.isEmpty()) {
            log("${pretask.name}.next 没有可用节点")
            return null
        }
        val active = candidates.filter { (hitCounts[it.node.name] ?: 0) < it.node.maxHit }
        if (active.isEmpty()) {
            log("${pretask.name}.next 的候选节点均已达到 max_hit")
            return null
        }

        // Custom 识别只接收 ROI，不使用截图；不要为它多截一帧。
        val needScreencap = active.any {
            it.node.recognition.type != RecognitionType.DirectHit &&
                it.node.recognition.type != RecognitionType.Custom
        }
        val deadline = if (pretask.timeoutMs >= 0) now() + pretask.timeoutMs else Long.MAX_VALUE

        while (true) {
            if (actions.isStopRequested()) return null
            if (overallTimedOut()) {
                timedOut = true
                return null
            }

            val image = if (needScreencap) vision.capture() else null
            if (needScreencap && image == null) {
                log("截屏失败，重试 ${pretask.name}.next")
                if (!sleepAndCheck(pretask.rateLimitMs, deadline)) return null
                continue
            }

            try {
                for (candidate in active) {
                    if (actions.isStopRequested()) return null
                    val node = candidate.node
                    if ((hitCounts[node.name] ?: 0) >= node.maxHit) continue
                    val rawBox = try {
                        vision.recognize(node, image, ::resolveNode, ::resolveTarget)
                    } catch (t: Throwable) {
                        log("识别 ${node.name} 失败: ${t.message}")
                        null
                    }
                    val hit = if (node.inverse) rawBox == null else rawBox != null
                    val box = if (node.inverse) null else rawBox
                    if (!hit) continue

                    hitCounts[node.name] = (hitCounts[node.name] ?: 0) + 1
                    if (box != null) nodeBoxes[node.name] = box else nodeBoxes.remove(node.name)
                    lastNode = node.name
                    steps++
                    log(
                        "命中 ${node.name}" +
                            (box?.let { " (${it.left},${it.top},${it.width}x${it.height})" } ?: ""),
                    )

                    val outcome = runNodeLifecycle(node, box)
                    for ((anchor, target) in node.anchors) {
                        if (target.isEmpty()) anchors.remove(anchor) else anchors[anchor] = target
                    }
                    return NextDetail(node.name, candidate.attr.jumpBack, outcome.success, outcome.stopTask)
                }
            } finally {
                image?.recycle()
            }

            if (!sleepAndCheck(pretask.rateLimitMs, deadline)) return null
        }
    }

    private fun runNodeLifecycle(node: PipelineNode, box: Box?): PipelineActionResult {
        waitFreezes(node.preWaitFreezes, box)
        actions.sleep(node.preDelayMs)
        if (actions.isStopRequested()) return PipelineActionResult(false)

        var result = PipelineActionResult(false)
        for (index in 0 until node.repeat) {
            if (index > 0) {
                waitFreezes(node.repeatWaitFreezes, box)
                actions.sleep(node.repeatDelayMs)
            }
            if (actions.isStopRequested()) return PipelineActionResult(false)
            result = actions.execute(node, box, ::resolveTarget)
            if (result.stopTask) return result
            if (actions.isStopRequested()) return PipelineActionResult(false)
        }

        if (!actions.isStopRequested()) {
            waitFreezes(node.postWaitFreezes, box)
            actions.sleep(node.postDelayMs)
        }
        return result
    }

    private fun waitFreezes(param: WaitFreezesSpec, refBox: Box?) {
        if (param.timeMs <= 0) return
        if (actions.isStopRequested()) return

        var previous = vision.capture() ?: return
        try {
            var stableSince = now()
            val start = stableSince
            val rate = minOf(param.rateLimitMs.coerceAtLeast(1L), param.timeMs.coerceAtLeast(1L))

            while (true) {
                if (actions.isStopRequested()) return
                if (overallTimedOut()) {
                    timedOut = true
                    return
                }
                actions.sleep(rate)
                if (actions.isStopRequested()) return
                if (overallTimedOut()) {
                    timedOut = true
                    return
                }

                if (param.timeoutMs >= 0 && now() - start > param.timeoutMs) {
                    log("wait_freezes 超时")
                    return
                }

                val current = vision.capture() ?: return
                val roi = resolveTarget(param.target, refBox)
                if (roi == null) {
                    if (current !== previous) current.recycle()
                    return
                }

                val stable = try {
                    vision.framesEqual(previous, current, roi, param.threshold, param.method)
                } catch (t: Throwable) {
                    log("wait_freezes 比较失败: ${t.message}")
                    false
                }
                if (stable) {
                    if (current !== previous) current.recycle()
                    if (now() - stableSince > param.timeMs) return
                } else {
                    val old = previous
                    previous = current
                    if (old !== current) old.recycle()
                    stableSince = now()
                }
            }
        } finally {
            previous.recycle()
        }
    }

    private fun resolveNode(raw: String): PipelineNode? {
        val name = if (raw.startsWith(ANCHOR_PREFIX)) {
            anchors[raw.removePrefix(ANCHOR_PREFIX)]
        } else {
            raw
        }
        return name?.let(spec::get)
    }

    private fun resolveTarget(target: TargetSpec, selfBox: Box?): Box? {
        val width = vision.screenWidth()
        val height = vision.screenHeight()
        val base = when (target.type) {
            TargetType.Self -> selfBox ?: return null
            TargetType.PreTask -> target.ref?.let(nodeBoxes::get)
            TargetType.Anchor -> target.ref?.let(anchors::get)?.let(nodeBoxes::get)
            TargetType.Region -> target.rect?.let { normalizeRect(it, width, height) }
        } ?: return null
        return applyOffset(base, target.offset, width, height)
    }

    private fun normalizeRect(rect: com.benton.izukijs.vision.RectSpec, width: Int, height: Int): Box? =
        com.benton.izukijs.vision.RoiResolver.resolve(
            com.benton.izukijs.vision.RoiSpec(rect = rect),
            emptyMap(),
            width,
            height,
        ).firstOrNull()

    private fun applyOffset(box: Box, offset: IntArray, width: Int, height: Int): Box {
        var x = box.left + offset[0]
        var y = box.top + offset[1]
        var w = box.width + offset[2]
        var h = box.height + offset[3]
        if (w < 0) {
            x += w
            w = -w
        }
        if (h < 0) {
            y += h
            h = -h
        }
        x = x.coerceIn(0, width)
        y = y.coerceIn(0, height)
        w = w.coerceIn(0, width - x)
        h = h.coerceIn(0, height - y)
        return Box(x, y, x + w, y + h)
    }

    private fun sleepAndCheck(millis: Long, deadline: Long): Boolean {
        actions.sleep(millis.coerceAtLeast(0L))
        if (actions.isStopRequested()) return false
        if (overallTimedOut()) {
            timedOut = true
            return false
        }
        return now() <= deadline
    }

    private fun overallTimedOut(): Boolean =
        options.timeoutMs > 0 && now() - startedAt > options.timeoutMs

    private data class Candidate(val attr: NodeAttr, val node: PipelineNode)

    private data class NextDetail(
        val nodeName: String,
        val jumpBack: Boolean,
        val actionCompleted: Boolean,
        val stopTask: Boolean,
    )

    private companion object {
        const val ANCHOR_PREFIX = "[Anchor]"
    }
}
