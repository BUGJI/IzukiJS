package com.benton.izukijs.controller

import android.os.Process
import android.util.Xml
import com.benton.izukijs.controller.record.RecordingSuppression
import com.benton.izukijs.model.Capability
import com.benton.izukijs.core.log.LogBus
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 通过 Shizuku / 无线调试 / Root 的 Shell 后端执行 `uiautomator dump`，把控件树 XML 解析为 [NodeSnapshot]。
 *
 * 用作无障碍不可用时的读屏兜底：底层通道（Shizuku）本就能执行 shell，因此无需开启无障碍
 * 也能拿到精确控件树。代价是 `uiautomator dump` 较慢（约 1~2s）且偶发失败，仅作 fallback。
 *
 * 无状态单例：所有方法按调用传入 [ControllerManager] / [LogBus]，避免多实例各自持有状态。
 */
object UiautomatorDumper {

    /**
     * 读取控件树：无障碍优先，Shizuku / 无线调试 / Root 的 `uiautomator dump` 兜底。
     *
     * 返回 [NodeTreeResult] 以区分「无可用后端」与「后端存在但读取失败」，供上层生成准确提示。
     */
    fun read(
        controllers: ControllerManager,
        logBus: LogBus,
        timeoutMs: Long = DUMP_TIMEOUT_MS,
        animationProbe: (() -> Boolean)? = null,
    ): NodeTreeResult {
        if (controllers.targetDisplayId != null) {
            return NodeTreeResult.Failure("后台虚拟屏不支持控件树，请改用 capture / ocr / 坐标输入")
        }
        val accessibility = controllers.controllerFor(Capability.NODE_TREE)
        if (accessibility != null) {
            accessibility.nodeTree()?.let { return NodeTreeResult.Success(it) }
        }
        return dump(
            controllers,
            logBus,
            accessibilityAvailable = accessibility != null,
            timeoutMs = timeoutMs.coerceAtLeast(MIN_DUMP_TIMEOUT_MS),
            animationProbe = animationProbe,
        )
    }

    private fun dump(
        controllers: ControllerManager,
        logBus: LogBus,
        accessibilityAvailable: Boolean,
        timeoutMs: Long,
        animationProbe: (() -> Boolean)?,
    ): NodeTreeResult {
        val shell = controllers.controllerFor(Capability.SHELL)
            ?: return if (accessibilityAvailable) {
                NodeTreeResult.Failure("无障碍读取为空，且无 Shell 后端可回退")
            } else {
                NodeTreeResult.NoBackend
            }

        // 界面持续动画时 uiautomator 会一直等 idle。两帧差只用于「调整预算」而非直接放弃：
        // 小动效（光标闪烁 / 进度圈）的界面往往仍能 dump 成功，只是慢一点。
        val animating = timeoutMs >= ANIMATION_PROBE_MIN_BUDGET_MS && animationProbe?.invoke() == true
        val dumpTimeoutMs = if (animating) {
            logBus.warn("检测到界面持续动画，仍尝试读取控件树（预算 ${ANIMATED_DUMP_TIMEOUT_MS}ms）")
            maxOf(timeoutMs, ANIMATED_DUMP_TIMEOUT_MS)
        } else {
            timeoutMs
        }

        // 每次 dump 使用唯一临时文件，避免多实例 / 多用户 / 并发 dump 相互覆盖。
        val path = "/data/local/tmp/izuki_ui_dump_${Process.myPid()}_${SEQ.incrementAndGet()}.xml"
        val dumped = runDump(shell, "uiautomator dump $path", dumpTimeoutMs)
        if (dumped == null) {
            // 界面持续动画时 uiautomator 会一直等 idle，直到其内部超时（实测可达 11s+）。
            // 这里主动放弃并终止该进程，避免把设备队列堵在无意义的空转上。
            logBus.warn("uiautomator dump 超过 ${dumpTimeoutMs}ms 未返回，已主动放弃（界面可能持续动画）")
            runCatching {
                internalShell(shell, "pkill -9 uiautomator 2>/dev/null || killall -9 uiautomator 2>/dev/null")
            }
            runCatching { internalShell(shell, "rm -f $path") }
            return NodeTreeResult.Failure(
                if (animating) {
                    "界面持续动画，控件树读取超时（已尝试 dump）"
                } else {
                    "uiautomator dump 超时（界面持续动画）"
                },
            )
        }
        // uiautomator dump 的退出码不可靠（不同 ROM 文案不同），以能否读到文件为准。
        val xml = internalShell(shell, "cat $path; rm -f $path")?.stdout
        if (xml.isNullOrBlank() || !xml.contains("<hierarchy")) {
            val raw = listOf(dumped.stdout, dumped.stderr)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val message = raw.ifBlank { "uiautomator dump 无输出（exit=${dumped.exitCode}）" }
            logBus.debug("uiautomator dump 失败：$message")
            return NodeTreeResult.Failure(message)
        }
        val root = runCatching { parse(xml) }
            .onFailure { logBus.warn("uiautomator dump 解析失败：${it.message}") }
            .getOrNull()
            ?: return NodeTreeResult.Failure("控件树解析失败")
        return NodeTreeResult.Success(root)
    }

    /**
     * 在后台线程执行 dump 命令，最多等待 [DUMP_TIMEOUT_MS]；超时返回 null 并终止 uiautomator。
     *
     * 用后台线程而非直接在设备队列线程上调用，是为了让「超时放弃」成为可能——ShellRunner 本身
     * 是阻塞式的，一旦 uiautomator 卡在等 idle，调用线程只能被动陪等。超时后杀掉 uiautomator，
     * 后台线程会随之从阻塞的流读取中退出，不会长期泄漏。
     */
    private fun runDump(shell: DeviceController, command: String, timeoutMs: Long): ShellResult? {
        val holder = arrayOfNulls<ShellResult>(1)
        val done = AtomicBoolean(false)
        val worker = Thread({
            // 在真正执行命令的线程内抑制录制，避免把 dump 写进用户/脚本录制时间线。
            holder[0] = RecordingSuppression.withoutRecording { shell.shell(command) }
            done.set(true)
        }, "izuki-uiautomator-dump").apply { isDaemon = true }
        worker.start()
        try {
            worker.join(timeoutMs)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (done.get()) return holder[0]
        // 超时：尽力终止 uiautomator，给后台线程一个短暂窗口自行退出（最多再等 [ABANDON_GRACE_MS]）。
        runCatching {
            internalShell(shell, "pkill -9 uiautomator 2>/dev/null || killall -9 uiautomator 2>/dev/null")
        }
        runCatching { worker.join(ABANDON_GRACE_MS) }
        return null
    }

    private fun internalShell(shell: DeviceController, command: String): ShellResult? =
        RecordingSuppression.withoutRecording { shell.shell(command) }

    private fun parse(xml: String): NodeSnapshot? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val stack = ArrayDeque<Builder>()
        var root: Builder? = null
        var depth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "node") {
                    val node = readNode(parser, depth)
                    if (root == null) root = node
                    stack.lastOrNull()?.children?.add(node)
                    stack.addLast(node)
                    depth++
                }

                XmlPullParser.END_TAG -> if (parser.name == "node") {
                    depth--
                    stack.removeLastOrNull()
                }
            }
            event = parser.next()
        }
        return root?.toSnapshot()
    }

    private fun readNode(parser: XmlPullParser, depth: Int): Builder = Builder(
        className = parser.getAttributeValue(null, "class"),
        text = parser.getAttributeValue(null, "text"),
        viewId = parser.getAttributeValue(null, "resource-id"),
        contentDescription = parser.getAttributeValue(null, "content-desc"),
        clickable = parser.getAttributeValue(null, "clickable")?.toBoolean() ?: false,
        editable = parser.getAttributeValue(null, "class")?.contains("EditText") == true,
        depth = depth,
        bounds = parseBounds(parser.getAttributeValue(null, "bounds")),
    )

    private fun parseBounds(raw: String?): IntArray {
        if (raw == null) return intArrayOf(0, 0, 0, 0)
        val match = BOUNDS_REGEX.find(raw) ?: return intArrayOf(0, 0, 0, 0)
        val (l, t, r, b) = match.destructured
        return intArrayOf(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }

    private class Builder(
        val className: String?,
        val text: String?,
        val viewId: String?,
        val contentDescription: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val depth: Int,
        val bounds: IntArray,
    ) {
        val children = mutableListOf<Builder>()

        fun toSnapshot(): NodeSnapshot = NodeSnapshot(
            className = className,
            text = text?.takeIf { it.isNotBlank() },
            viewId = viewId?.takeIf { it.isNotBlank() },
            contentDescription = contentDescription?.takeIf { it.isNotBlank() },
            left = bounds[0],
            top = bounds[1],
            right = bounds[2],
            bottom = bounds[3],
            clickable = clickable,
            editable = editable,
            children = if (depth >= MAX_DEPTH) emptyList() else children.map { it.toSnapshot() },
        )
    }

    private val SEQ = AtomicLong(0)

    private const val MAX_DEPTH = 24

    /**
     * dump 默认等待上限。静态界面实测 2.7~3.5s，但持续动画界面原本要空转 11s+；
     * 快速失败（1.5s）+ 上层降级比长时间白等体验更好。调用方可传入更小预算。
     */
    private const val DUMP_TIMEOUT_MS = 1_500L

    private const val MIN_DUMP_TIMEOUT_MS = 300L

    /** 两帧差探测本身约需 0.5~1s，预算过小时跳过，避免探测把超时撑爆。 */
    private const val ANIMATION_PROBE_MIN_BUDGET_MS = 1_200L

    /**
     * 探测到动画后仍尝试 dump 的预算。默认 1.5s 对含小动效的界面偏短，
     * 放宽到 2.5s；仍失败才快速返回，避免回到过去 11s+ 的空转。
     */
    private const val ANIMATED_DUMP_TIMEOUT_MS = 2_500L

    /** 超时终止 uiautomator 后，等待后台线程自行退出的宽限时长。 */
    private const val ABANDON_GRACE_MS = 500L

    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
}
