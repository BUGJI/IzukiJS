package com.benton.izukijs.ui.editor

/** 一条 API 补全建议；[text] 是实际插入编辑器的文本。 */
data class ApiSuggestion(val text: String, val hint: String = "")

/**
 * 编辑器里的轻量 API 补全。不做 JavaScript 语法分析，只根据光标前的标识符 / 命名空间
 * 给出静态候选；这样无需引入额外依赖，也不会误改脚本。
 */
object ApiSuggestions {

    private val TOKEN = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]*)?$""")

    private val TOP_LEVEL: List<ApiSuggestion> = listOf(
        ApiSuggestion("app.", "应用"),
        ApiSuggestion("selector.", "控件选择"),
        ApiSuggestion("images.", "找图找色"),
        ApiSuggestion("ocr.", "文字识别"),
        ApiSuggestion("http.", "网络请求"),
        ApiSuggestion("clipboard.", "剪贴板"),
        ApiSuggestion("notify.", "通知"),
        ApiSuggestion("fs.", "文件"),
        ApiSuggestion("shell.", "Shell"),
        ApiSuggestion("ai.", "AI Agent"),
        ApiSuggestion("env.", "运行参数"),
        ApiSuggestion("state.", "运行状态"),
        ApiSuggestion("permissions.", "权限查询"),
        ApiSuggestion("device.", "设备信息"),
        ApiSuggestion("click(", "点击"),
        ApiSuggestion("longClick(", "长按"),
        ApiSuggestion("swipe(", "滑动"),
        ApiSuggestion("gesture(", "复杂手势"),
        ApiSuggestion("press(", "按键"),
        ApiSuggestion("input(", "输入文本"),
        ApiSuggestion("back(", "返回"),
        ApiSuggestion("home(", "主页"),
        ApiSuggestion("recents(", "最近任务"),
        ApiSuggestion("sleep(", "休眠"),
        ApiSuggestion("waitUntil(", "等待条件"),
        ApiSuggestion("waitUntilGone(", "等待条件消失"),
        ApiSuggestion("toast(", "Toast"),
        ApiSuggestion("log(", "日志"),
        ApiSuggestion("captureScreen(", "保存截图"),
        ApiSuggestion("require(", "引入脚本模块"),
        ApiSuggestion("exit(", "结束脚本"),
    )

    private val MEMBERS: Map<String, List<String>> = mapOf(
        "app" to listOf(
            "launch(", "openUrl(", "currentPackage(", "list(", "info(", "isInstalled(",
            "foreground(", "lastUsed(", "usageStats(",
        ),
        "selector" to listOf(
            "find(", "findAll(", "nth(", "exists(", "count(", "click(", "longClick(",
            "waitFor(", "waitGone(", "scrollIntoView(", "findByText(", "findByTextContains(",
            "findByTextRegex(", "findByDesc(", "findByDescContains(", "findByDescRegex(",
            "findByText(", "waitForText(", "waitForTextContains(", "waitForTextRegex(",
            "waitForId(", "existsById(", "existsByText(", "countByText(", "clickById(",
            "clickByText(", "longClickById(", "longClickByText(", "setTextById(",
            "getTextById(", "getTextByText(", "boundsById(",
        ),
        "images" to listOf(
            "findImage(", "findAllImages(", "findColor(", "findAllColors(",
            "findImageInRaw(", "saveTemplate(", "available(",
        ),
        "ocr" to listOf("recognize(", "recognizePath(", "find("),
        "http" to listOf("request(", "get(", "post(", "postJson(", "available(", "isAllowed("),
        "clipboard" to listOf("get(", "set(", "clear("),
        "notify" to listOf("available(", "list(", "latest(", "clear("),
        "fs" to listOf(
            "readText(", "writeText(", "exists(", "isDirectory(", "list(", "size(",
            "mkdirs(", "delete(", "copy(", "move(", "readBase64(", "writeBase64(",
        ),
        "shell" to listOf("available(", "isRoot(", "exec(", "exitCode("),
        "ai" to listOf("available(", "config(", "chat(", "run("),
        "env" to listOf("get(", "has(", "mode(", "all("),
        "state" to listOf("get(", "set(", "has(", "remove(", "clear(", "all("),
        "permissions" to listOf(
            "accessibility(", "shizuku(", "root(", "hid(", "overlay(", "screenCapture(",
            "notifications(", "bluetooth(", "usageAccess(", "notificationListener(",
            "manageStorage(", "appList(", "http(", "granted(", "all(", "has(",
            "canControl(", "canScreenshot(",
        ),
        "device" to listOf(
            "width(", "height(", "model(", "brand(", "androidVersion(", "batteryLevel(",
        ),
    )

    /** 光标前最后一个标识符 / 命名空间，例如 `sel`、`selector.fi`、`app.`。 */
    fun tokenAt(text: String, cursor: Int): String? {
        if (cursor <= 0 || cursor > text.length) return null
        return TOKEN.find(text.substring(0, cursor))?.value
    }

    /** 当前标识符的起始下标，用于替换补全。 */
    fun tokenStart(text: String, cursor: Int): Int {
        if (cursor <= 0 || cursor > text.length) return cursor
        val match = TOKEN.find(text.substring(0, cursor)) ?: return cursor
        return match.range.first
    }

    fun suggestions(token: String, limit: Int = 6): List<ApiSuggestion> {
        if (token.isEmpty()) return emptyList()
        val dot = token.lastIndexOf('.')
        val result = if (dot >= 0) {
            val namespace = token.substring(0, dot)
            val prefix = token.substring(dot + 1)
            MEMBERS[namespace].orEmpty()
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .distinct()
                .map { ApiSuggestion("$namespace.$it") }
        } else {
            TOP_LEVEL.filter { it.text.startsWith(token, ignoreCase = true) }
        }
        return result.take(limit.coerceIn(1, 12))
    }
}
