package com.benton.izukijs.mcp.tools

import com.benton.izukijs.model.UnlockResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

// ---- 自主解锁（受 allowUnlock / autoUnlock 控制） ----

internal fun McpToolRegistry.unlockTool(): McpTool = McpTool(
    name = "unlock",
    description = "尝试解锁当前锁屏，复用「设置 → 安全解锁」中保存的凭据；不会主动锁屏。" +
        "若设备已解锁则直接返回。需先在「设置 → 安全解锁」启用自主解锁，并在 MCP 工具开关中" +
        "开启「允许解锁工具」。",
    inputSchema = McpSchemas.objectSchema(emptyMap()),
) {
    val controller = screenUnlock
        ?: return@McpTool unlockResultJson(UnlockResult.UNSUPPORTED)
    val result = runCatching {
        runBlocking { withTimeout(UNLOCK_TIMEOUT_MS) { controller.ensureUnlocked() } }
    }.getOrElse { UnlockResult.TIMEOUT }
    unlockResultJson(result)
}

private fun McpToolRegistry.unlockResultJson(result: UnlockResult): McpToolResult {
    val ok = result == UnlockResult.OK || result == UnlockResult.ALREADY_UNLOCKED
    val payload = JSONObject().apply {
        put("ok", ok)
        put("action", "unlock")
        put("result", result.name)
        put("message", unlockMessage(result))
    }
    return McpToolResult(listOf(McpContent.text(payload.toString())), isError = !ok)
}

private fun McpToolRegistry.unlockBlocked(result: UnlockResult): McpToolResult = McpToolResult.text(
    JSONObject().apply {
        put("ok", false)
        put("blocked", true)
        put("reason", if (result == UnlockResult.DISABLED) "unlock_disabled" else "unlock_failed")
        put("result", result.name)
        put("message", unlockMessage(result))
    }.toString(),
)

private fun McpToolRegistry.unlockMessage(result: UnlockResult): String = when (result) {
    UnlockResult.OK -> "已解锁"
    UnlockResult.ALREADY_UNLOCKED -> "设备未锁屏"
    UnlockResult.DISABLED -> "自主解锁未启用，请先在「设置 → 安全解锁」中开启；" +
        "若要允许外部 AI 调用，还需在 MCP 工具开关中开启「允许解锁工具」"
    UnlockResult.NO_BACKEND -> "没有可用的解锁后端（需要 Shizuku / 无线调试 / Root）"
    UnlockResult.CREDENTIAL_REQUIRED -> "未配置解锁凭据"
    UnlockResult.CREDENTIAL_INVALID -> "解锁凭据无效"
    UnlockResult.CREDENTIAL_REJECTED -> "解锁凭据被拒绝"
    UnlockResult.PATTERN_SCREEN_MISMATCH -> "图案录制方向与当前屏幕不一致"
    UnlockResult.LOCK_FAILED -> "锁屏失败"
    UnlockResult.WAKE_FAILED -> "唤醒屏幕失败"
    UnlockResult.DISMISS_FAILED -> "收起锁屏失败"
    UnlockResult.UNSUPPORTED -> "当前设备或控制后端不支持自动解锁"
    UnlockResult.TIMEOUT -> "解锁超时"
    UnlockResult.NO_INPUT -> "没有可用的输入设备"
    UnlockResult.RECORDING_TIMEOUT -> "录制超时"
    UnlockResult.CANCELLED -> "解锁已取消"
}

/** 操作前自动解锁：仅在设备锁屏或息屏时触发，失败则阻止本次设备操作。 */
internal fun McpToolRegistry.withAutoUnlock(tool: McpTool): McpTool = McpTool(
    name = tool.name,
    description = tool.description,
    inputSchema = tool.inputSchema,
    readOnly = tool.readOnly,
    synchronous = tool.synchronous,
) { args ->
    val controller = screenUnlock
    if (controller != null &&
        shouldAutoUnlock(tool.name, args) &&
        (controller.isLocked() || !controller.isInteractive())
    ) {
        val result = runCatching {
            runBlocking { withTimeout(UNLOCK_TIMEOUT_MS) { controller.ensureUnlocked() } }
        }.getOrElse { UnlockResult.TIMEOUT }
        when (result) {
            UnlockResult.OK, UnlockResult.ALREADY_UNLOCKED -> Unit
            UnlockResult.DISABLED -> {
                if (controller.isLocked()) return@McpTool unlockBlocked(result)
            }
            else -> return@McpTool unlockBlocked(result)
        }
    }
    tool.call(args)
}

/** 虚拟屏操作不需要解锁主屏；其余自动解锁集合内的工具都针对主屏。 */
private fun McpToolRegistry.shouldAutoUnlock(name: String, args: JSONObject): Boolean {
    if (name == "screenshot" || name in TARGETED_SHARED) {
        return !args.optString("target").equals("virtual", ignoreCase = true)
    }
    return true
}
