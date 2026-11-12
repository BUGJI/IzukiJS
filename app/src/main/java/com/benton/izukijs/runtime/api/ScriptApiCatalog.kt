package com.benton.izukijs.runtime.api

/**
 * 脚本 API 的单一事实源。
 *
 * 命名空间是否注册、`@need` 能声明哪些名字、编辑器给哪些补全，都从这里派生。
 * 新增 API 时优先改这里，再补 Kotlin 桥接与 `prelude.js` 包装，避免多处手写清单漂移。
 *
 * 约定：
 * - [Kind.GLOBAL] 是挂在全局对象上的函数（`log` / `click` / `waitUntil` 等）；
 * - [Kind.NAMESPACE] 是 `ctx.addJavascriptInterface(obj, name)` 暴露的对象；
 * - 复合参数 / 返回值的底层方法统一以 `Raw` 结尾，由 `prelude.js` 包装成易用门面；
 *   `Raw` 接口同样对脚本开放，属于“低级接口”，文档中单独列出。
 */
object ScriptApiCatalog {

    enum class Kind { GLOBAL, NAMESPACE }

    data class Entry(
        val name: String,
        val kind: Kind,
        val need: String? = null,
        val suggestions: List<String> = emptyList(),
        val docHeading: String = name,
    ) {
        val alwaysOn: Boolean get() = need == null
    }

    val globals: List<Entry> = listOf(
        Entry(
            name = "global",
            kind = Kind.GLOBAL,
            suggestions = listOf(
                "log(", "debug(", "warn(", "error(", "toast(", "sleep(",
                "waitUntil(", "waitUntilGone(", "captureScreen(", "require(", "exit(",
            ),
        ),
        Entry(
            name = "input",
            kind = Kind.GLOBAL,
            suggestions = listOf(
                "click(", "longClick(", "swipe(", "gesture(", "press(",
                "input(", "inputWithMethod(", "back(", "home(", "recents(",
            ),
        ),
    )

    val namespaces: List<Entry> = listOf(
        Entry(
            name = "device",
            kind = Kind.NAMESPACE,
            suggestions = listOf(
                "width(", "height(", "screenWidth(", "screenHeight(",
                "model(", "brand(", "androidVersion(", "batteryLevel(",
            ),
        ),
        Entry(
            name = "console",
            kind = Kind.NAMESPACE,
            suggestions = listOf("log(", "info(", "debug(", "warn(", "error("),
        ),
        Entry(
            name = "permissions",
            kind = Kind.NAMESPACE,
            suggestions = listOf(
                "accessibility(", "shizuku(", "adb(", "root(", "hid(", "overlay(",
                "screenCapture(", "notifications(", "bluetooth(", "usageAccess(",
                "notificationListener(", "manageStorage(", "appList(", "http(",
                "granted(", "all(", "has(", "canControl(", "canScreenshot(",
                "virtualScreen(", "runMode(",
            ),
        ),
        Entry(
            name = "env",
            kind = Kind.NAMESPACE,
            suggestions = listOf("get(", "has(", "mode(", "all("),
        ),
        Entry(
            name = "state",
            kind = Kind.NAMESPACE,
            suggestions = listOf("get(", "set(", "has(", "remove(", "clear(", "all("),
        ),
        Entry(
            name = "modules",
            kind = Kind.NAMESPACE,
            suggestions = listOf("source("),
        ),
        Entry(
            name = "app",
            kind = Kind.NAMESPACE,
            need = "app",
            suggestions = listOf(
                "launch(", "openUrl(", "currentPackage(", "virtualPackage(", "mainPackage(",
                "virtualScreenActive(", "list(", "info(", "isInstalled(", "hasUsageAccess(",
                "foreground(", "lastUsed(", "usageStats(",
            ),
        ),
        Entry(
            name = "shell",
            kind = Kind.NAMESPACE,
            need = "shell",
            suggestions = listOf("available(", "isRoot(", "exec(", "execResult(", "exitCode("),
        ),
        Entry(
            name = "images",
            kind = Kind.NAMESPACE,
            need = "images",
            suggestions = listOf(
                "recognize(", "recognizeAll(", "recognizeIn(", "recognizeAllIn(", "compare(",
                "findImage(", "findAllImages(", "findColor(", "findAllColors(",
                "findColorRegions(", "findColorRegionsInRaw(", "findColorInRaw(",
                "saveTemplate(", "available(",
            ),
        ),
        Entry(
            name = "ocr",
            kind = Kind.NAMESPACE,
            need = "ocr",
            suggestions = listOf(
                "recognize(", "recognizePath(", "recognizeBlocksJson(", "recognizeBlocks(", "find(",
            ),
        ),
        Entry(
            name = "pipeline",
            kind = Kind.NAMESPACE,
            need = "pipeline",
            suggestions = listOf(
                "run(", "replay(", "validate(", "dump(", "registerRecognition(", "registerAction(",
            ),
        ),
        Entry(
            name = "record",
            kind = Kind.NAMESPACE,
            need = "record",
            suggestions = listOf("start(", "stop(", "active(", "list(", "info(", "delete(", "validate("),
        ),
        Entry(
            name = "notify",
            kind = Kind.NAMESPACE,
            need = "notify",
            suggestions = listOf(
                "available(", "list(", "latest(", "clear(",
                "progress(", "update(", "reset(", "progressEnabled(",
            ),
        ),
        Entry(
            name = "push",
            kind = Kind.NAMESPACE,
            need = "push",
            suggestions = listOf("available(", "channels(", "send("),
        ),
        Entry(
            name = "fs",
            kind = Kind.NAMESPACE,
            need = "fs",
            suggestions = listOf(
                "readText(", "writeText(", "exists(", "isDirectory(", "list(", "size(",
                "mkdirs(", "delete(", "copy(", "move(", "readBase64(", "writeBase64(",
            ),
        ),
        Entry(
            name = "selector",
            kind = Kind.NAMESPACE,
            need = "selector",
            suggestions = listOf(
                "find(", "findAll(", "nth(", "exists(", "count(", "click(", "longClick(",
                "waitFor(", "waitGone(", "scrollIntoView(", "findByText(", "findAllByText(",
                "findByTextContains(", "findByTextRegex(", "findByDesc(", "findByDescContains(",
                "findByDescRegex(", "waitForText(", "waitForTextContains(", "waitForTextRegex(",
                "waitForId(", "existsById(", "existsByText(", "existsByDesc(", "countByText(",
                "clickById(", "clickByText(", "clickByDesc(", "longClickById(", "longClickByText(",
                "longClickByDesc(", "setTextById(", "getTextById(", "getTextByText(", "boundsById(",
            ),
        ),
        Entry(
            name = "clipboard",
            kind = Kind.NAMESPACE,
            need = "clipboard",
            suggestions = listOf("get(", "set(", "clear("),
        ),
        Entry(
            name = "http",
            kind = Kind.NAMESPACE,
            need = "http",
            suggestions = listOf("request(", "get(", "post(", "postJson(", "available(", "isAllowed("),
        ),
        Entry(
            name = "ai",
            kind = Kind.NAMESPACE,
            need = "ai",
            suggestions = listOf("available(", "config(", "chat(", "run("),
        ),
        Entry(
            name = "virtualScreen",
            kind = Kind.NAMESPACE,
            need = "virtualscreen",
            suggestions = listOf(
                "available(", "active(", "mode(", "allowed(", "start(", "stop(",
                "launch(", "displayId(", "size(", "info(", "target(", "awaitFrame(", "capture(",
            ),
        ),
    )

    val all: List<Entry> = globals + namespaces

    /** `// @need` 允许声明的能力名。 */
    val needKeys: Set<String> = namespaces.mapNotNull { it.need }.toSet()

    fun findNamespace(name: String): Entry? = namespaces.firstOrNull { it.name == name }
}
