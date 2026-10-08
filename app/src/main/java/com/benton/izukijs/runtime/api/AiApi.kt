package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.ai.AiAgent
import com.benton.izukijs.ai.AiClient
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.ai.AgentTools
import com.benton.izukijs.controller.NodeSnapshot
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.runtime.ScriptExitException
import com.quickjs.JSArray
import com.quickjs.JSFunction
import com.quickjs.JSObject
import org.json.JSONObject

/**
 * AI Agent API。挂在全局 `ai` 命名空间，用于脚本「脱困」：卡住时让模型观察屏幕并操作设备。
 *
 * 每次调用都是无状态的（system + 本次 user），会话上下文由脚本作者自行拼接。
 */
class AiApi(
    private val configRepository: AiConfigRepository,
    private val inputApi: InputApi,
    private val selectorApi: SelectorApi,
    private val ocrApi: OcrApi,
    private val appApi: AppApi,
    private val shellApi: ShellApi,
    private val deviceApi: DeviceApi,
    private val screenshotPathProvider: () -> String?,
    private val nodeTreeProvider: () -> NodeSnapshot?,
    private val logBus: LogBus,
    private val isExitRequested: () -> Boolean,
) {

    @JavascriptInterface
    fun available(): Boolean = configRepository.current().isConfigured

    @JavascriptInterface
    fun config(): String {
        val config = configRepository.current()
        return JSONObject().apply {
            put("enabled", config.enabled)
            put("configured", config.isConfigured)
            put("model", config.model)
            put("baseUrl", config.baseUrl)
            put("maxSteps", config.maxSteps)
            put("toolsEnabled", config.toolsEnabled)
            put("stream", config.stream)
        }.toString()
    }

    /** 纯对话，不启用工具。 */
    @JavascriptInterface
    fun chat(prompt: String, options: JSObject?): String? = execute(prompt, options, withTools = false)

    /** 带工具的 Agent 循环，可自动操作设备。 */
    @JavascriptInterface
    fun run(prompt: String, options: JSObject?): String? = execute(prompt, options, withTools = true)

    private fun execute(prompt: String, options: JSObject?, withTools: Boolean): String? {
        val config = configRepository.current()
        if (!config.isConfigured) {
            logBus.warn("AI 未配置或未启用，请在「设置 → AI Agent」中填写")
            return null
        }

        val onDelta = options?.getObject("onDelta").toDeltaCallback()
        val onTool = options?.getObject("onTool").toPairCallback()
        val onToolResult = options?.getObject("onToolResult").toPairCallback()
        val maxSteps = if (options != null && options.contains("maxSteps")) {
            options.getInteger("maxSteps")
        } else {
            null
        }

        val effective = if (withTools) config else config.copy(toolsEnabled = false)
        val tools = AgentTools(
            inputApi = inputApi,
            selectorApi = selectorApi,
            ocrApi = ocrApi,
            appApi = appApi,
            shellApi = shellApi,
            deviceApi = deviceApi,
            screenshotPathProvider = screenshotPathProvider,
            nodeTreeProvider = nodeTreeProvider,
            allowShell = effective.allowShell,
        )
        val agent = AiAgent(
            config = effective,
            client = AiClient(effective, logBus),
            tools = tools,
            logBus = logBus,
            checkCancelled = { if (isExitRequested()) throw ScriptExitException() },
        )

        logBus.info("🤖 AI ${if (withTools) "Agent" else "对话"}：${prompt.take(60)}")
        return try {
            agent.run(
                userPrompt = prompt,
                maxStepsOverride = maxSteps,
                onDelta = onDelta,
                onTool = onTool,
                onToolResult = onToolResult,
            )
        } catch (e: ScriptExitException) {
            // 不能把 Java 异常抛出 javascript 回调（会导致 QuickJS JNI abort）。
            // 这里返回 null，由 prelude 中的 ai 包装函数检测 shouldExit() 后抛出 JS 异常终止脚本。
            logBus.info("… AI 调用已取消")
            null
        } catch (e: Throwable) {
            logBus.error("AI 调用失败：${e.message}")
            "（AI 调用失败：${e.message}）"
        }
    }

    private fun JSObject?.toDeltaCallback(): ((String) -> Unit)? {
        val fn = this as? JSFunction ?: return null
        return { text -> runCatching { fn.call(null, JSArray(fn.context).push(text)) } }
    }

    private fun JSObject?.toPairCallback(): ((String, String) -> Unit)? {
        val fn = this as? JSFunction ?: return null
        return { first, second ->
            runCatching { fn.call(null, JSArray(fn.context).push(first).push(second)) }
        }
    }
}
