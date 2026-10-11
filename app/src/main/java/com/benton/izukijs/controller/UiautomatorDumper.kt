package com.benton.izukijs.controller

import android.os.Process
import android.util.Xml
import com.benton.izukijs.model.Capability
import com.benton.izukijs.runtime.LogBus
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 通过 Shizuku / Root 的 Shell 后端执行 `uiautomator dump`，把控件树 XML 解析为 [NodeSnapshot]。
 *
 * 用作无障碍不可用时的读屏兜底：底层通道（Shizuku）本就能执行 shell，因此无需开启无障碍
 * 也能拿到精确控件树。代价是 `uiautomator dump` 较慢（约 1~2s）且偶发失败，仅作 fallback。
 *
 * 无状态单例：所有方法按调用传入 [ControllerManager] / [LogBus]，避免多实例各自持有状态。
 */
object UiautomatorDumper {

    /**
     * 读取控件树：无障碍优先，Shizuku / Root 的 `uiautomator dump` 兜底。
     *
     * 返回 [NodeTreeResult] 以区分「无可用后端」与「后端存在但读取失败」，供上层生成准确提示。
     */
    fun read(controllers: ControllerManager, logBus: LogBus): NodeTreeResult {
        val accessibility = controllers.controllerFor(Capability.NODE_TREE)
        if (accessibility != null) {
            accessibility.nodeTree()?.let { return NodeTreeResult.Success(it) }
        }
        return dump(controllers, logBus, accessibilityAvailable = accessibility != null)
    }

    private fun dump(
        controllers: ControllerManager,
        logBus: LogBus,
        accessibilityAvailable: Boolean,
    ): NodeTreeResult {
        val shell = controllers.controllerFor(Capability.SHELL)
            ?: return if (accessibilityAvailable) {
                NodeTreeResult.Failure("无障碍读取为空，且无 Shell 后端可回退")
            } else {
                NodeTreeResult.NoBackend
            }

        // 每次 dump 使用唯一临时文件，避免多实例 / 多用户 / 并发 dump 相互覆盖。
        val path = "/data/local/tmp/izuki_ui_dump_${Process.myPid()}_${SEQ.incrementAndGet()}.xml"
        val dumped = runDump(shell, "uiautomator dump $path")
        if (dumped == null) {
            // 界面持续动画时 uiautomator 会一直等 idle，直到其内部超时（实测可达 11s+）。
            // 这里主动放弃并终止该进程，避免把设备队列堵在无意义的空转上。
            logBus.warn("uiautomator dump 超过 ${DUMP_TIMEOUT_MS}ms 未返回，已主动放弃（界面可能持续动画）")
            runCatching { shell.shell("pkill -9 uiautomator 2>/dev/null || killall -9 uiautomator 2>/dev/null") }
            runCatching { shell.shell("rm -f $path") }
            return NodeTreeResult.Failure("uiautomator dump 超时（界面持续动画）")
        }
        // uiautomator dump 的退出码不可靠（不同 ROM 文案不同），以能否读到文件为准。
        val xml = shell.shell("cat $path; rm -f $path")?.stdout
        if (xml.isNullOrBlank() || !xml.contains("<hierarchy")) {
            val raw = listOfNotNull(dumped?.stdout, dumped?.stderr)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            val message = raw.ifBlank { "uiautomator dump 无输出（exit=${dumped?.exitCode}）" }
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
    private fun runDump(shell: DeviceController, command: String): ShellResult? {
        val holder = arrayOfNulls<ShellResult>(1)
        val done = AtomicBoolean(false)
        val worker = Thread({
            holder[0] = shell.shell(command)
            done.set(true)
        }, "izuki-uiautomator-dump").apply { isDaemon = true }
        worker.start()
        try {
            worker.join(DUMP_TIMEOUT_MS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (done.get()) return holder[0]
        // 超时：尽力终止 uiautomator，给后台线程一个短暂窗口自行退出（最多再等 [ABANDON_GRACE_MS]）。
        runCatching { shell.shell("pkill -9 uiautomator 2>/dev/null || killall -9 uiautomator 2>/dev/null") }
        runCatching { worker.join(ABANDON_GRACE_MS) }
        return null
    }

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
     * dump 等待上限。静态界面实测 2.7~3.5s，取 6s 以留出余量；持续动画界面原本要空转 11s+，
     * 超时后主动放弃可把这段无谓等待截断。
     */
    private const val DUMP_TIMEOUT_MS = 6_000L

    /** 超时终止 uiautomator 后，等待后台线程自行退出的宽限时长。 */
    private const val ABANDON_GRACE_MS = 500L

    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
}
