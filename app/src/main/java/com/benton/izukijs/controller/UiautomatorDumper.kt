package com.benton.izukijs.controller

import android.os.Process
import android.util.Xml
import com.benton.izukijs.model.Capability
import com.benton.izukijs.runtime.LogBus
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
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
        val dumped = shell.shell("uiautomator dump $path")
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

    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
}
