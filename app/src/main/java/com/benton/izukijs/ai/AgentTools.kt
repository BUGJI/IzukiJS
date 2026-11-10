package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.runtime.api.AppApi
import com.benton.izukijs.runtime.api.DeviceApi
import com.benton.izukijs.runtime.api.InputApi
import com.benton.izukijs.runtime.api.NotificationApi
import com.benton.izukijs.runtime.api.OcrApi
import com.benton.izukijs.runtime.api.SelectorApi
import com.benton.izukijs.runtime.api.ShellApi
import org.json.JSONObject

class AgentTools(
    internal val inputApi: InputApi,
    internal val selectorApi: SelectorApi,
    internal val ocrApi: OcrApi,
    internal val appApi: AppApi,
    internal val shellApi: ShellApi,
    internal val deviceApi: DeviceApi,
    internal val notificationApi: NotificationApi,
    internal val screenshotPathProvider: () -> String?,
    internal val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    internal val policy: ToolPolicy = ToolPolicy(),
) {

    internal val shellAvailable: Boolean get() = policy.allowShell && shellApi.available()

    private var cachedShellAvailable: Boolean? = null
    private var cachedSpecs: List<ToolSpec> = emptyList()

    /** 工具 schema 只随 shell 可用性变化，缓存避免每步都重建整份 JSON。 */
    fun specs(): List<ToolSpec> {
        val shell = shellAvailable
        if (cachedShellAvailable == shell) return cachedSpecs
        return buildAgentToolSpecs(shell).also {
            cachedShellAvailable = shell
            cachedSpecs = it
        }
    }

    /** 执行一个工具调用，返回结果文本（内部异常会被捕获为错误提示）。 */
    fun execute(name: String, argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson.ifBlank { "{}" })
        dispatch(name, args)
    }.getOrElse { "[错误] ${it.message}" }

    internal fun dispatch(name: String, args: JSONObject): String {
        // 安全策略必须放在 dispatch 层：batch 内嵌动作也经由这里，无法绕过。
        policyViolation(name, args)?.let { return it }
        return when (name) {
            "ocr" -> ocrApi.recognizeBlocksJson()
                ?: jsonOf { put("ok", false); put("message", "未识别到文字，或截图权限不可用") }

            "ui_dump" -> doUiDump()
            "find" -> doFind(args)
            "screenshot" -> screenshotPathProvider()?.let { "截图已保存：$it" } ?: "截图失败"
            "info" -> doInfo(args)
            "notifications" -> doNotifications(args)
            "click" -> doClick(args)
            "swipe" -> doSwipe(args)
            "gesture" -> doGesture(args)
            "text" -> doText(args)
            "press" -> doPress(args)
            "wait" -> doWait(args)
            "app" -> doApp(args)
            "batch" -> doBatch(args)
            "shell" -> doShell(args)
            else -> "[错误] 未知工具：$name"
        }
    }

    /** 命中安全策略时返回拒绝结果，否则返回 null。 */
    internal fun policyViolation(name: String, args: JSONObject): String? = when (name) {
        "shell" -> {
            if (policy.allowDangerousShell) {
                null
            } else {
                ShellGuard.reason(args.optString("command"))?.let { reason ->
                    blockedJson("命令被安全策略拦截（$reason）")
                }
            }
        }

        "app" -> {
            if (args.optString("action") != "launch") {
                null
            } else {
                val pkg = args.optString("package")
                if (policy.isPackageBlocked(pkg)) {
                    blockedJson("目标应用 $pkg 在敏感应用黑名单中，已拒绝启动")
                } else {
                    null
                }
            }
        }

        else -> null
    }
}
