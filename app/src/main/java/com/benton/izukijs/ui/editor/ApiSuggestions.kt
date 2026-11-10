package com.benton.izukijs.ui.editor

/** 一条 API 补全建议；[text] 是实际插入编辑器的文本。 */
data class ApiSuggestion(val text: String)

/**
 * 编辑器里的轻量 API 补全。不做 JavaScript 语法分析，只根据光标前的标识符 / 命名空间
 * 给出静态候选；这样无需引入额外依赖，也不会误改脚本。
 */
object ApiSuggestions {

    private val TOKEN = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z0-9_$]*)?$""")

    private val TOP_LEVEL: List<ApiSuggestion> = listOf(
        ApiSuggestion("app."),
        ApiSuggestion("selector."),
        ApiSuggestion("images."),
        ApiSuggestion("ocr."),
        ApiSuggestion("http."),
        ApiSuggestion("clipboard."),
        ApiSuggestion("notify."),
        ApiSuggestion("fs."),
        ApiSuggestion("shell."),
        ApiSuggestion("ai."),
        ApiSuggestion("env."),
        ApiSuggestion("state."),
        ApiSuggestion("permissions."),
        ApiSuggestion("virtualScreen."),
        ApiSuggestion("device."),
        ApiSuggestion("click("),
        ApiSuggestion("longClick("),
        ApiSuggestion("swipe("),
        ApiSuggestion("gesture("),
        ApiSuggestion("press("),
        ApiSuggestion("input("),
        ApiSuggestion("back("),
        ApiSuggestion("home("),
        ApiSuggestion("recents("),
        ApiSuggestion("sleep("),
        ApiSuggestion("waitUntil("),
        ApiSuggestion("waitUntilGone("),
        ApiSuggestion("toast("),
        ApiSuggestion("log("),
        ApiSuggestion("captureScreen("),
        ApiSuggestion("require("),
        ApiSuggestion("exit("),
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
            "findColorRegions(", "findColorRegionsInRaw(", "findColorInRaw(",
            "findImageInRaw(", "findColorRaw(", "findImageRaw(", "saveTemplate(", "available(",
        ),
        "ocr" to listOf("recognize(", "recognizePath(", "recognizeBlocksJson(", "find("),
        "http" to listOf("request(", "get(", "post(", "postJson(", "available(", "isAllowed("),
        "clipboard" to listOf("get(", "set(", "clear("),
        "notify" to listOf(
            "available(", "list(", "latest(", "clear(",
            "progress(", "update(", "reset(", "progressEnabled(",
        ),
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
            "canControl(", "canScreenshot(", "virtualScreen(", "runMode(",
        ),
        "virtualScreen" to listOf(
            "available(", "active(", "mode(", "allowed(", "start(", "stop(",
            "launch(", "displayId(", "size(", "info(", "target(", "awaitFrame(", "capture(",
        ),
        "device" to listOf(
            "width(", "height(", "screenWidth(", "screenHeight(", "model(", "brand(",
            "androidVersion(", "batteryLevel(",
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
