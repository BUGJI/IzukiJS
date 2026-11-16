package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.service.ScriptOverlayManager

/**
 * 脚本悬浮窗 API。挂在 `floaty` 命名空间。
 *
 * 只支持 JSON 内容与动作队列；按钮 / 开关事件由脚本用 `waitAction` 或 `actions`
 * 轮询消费，不回调 QuickJS。
 */
class FloatyApi(
    private val runId: String?,
    private val isExitRequested: () -> Boolean = { false },
) {

    @JavascriptInterface
    fun available(): Boolean = ScriptOverlayManager.available()

    @JavascriptInterface
    fun showRaw(optionsJson: String): String =
        ScriptOverlayManager.show(runId, optionsJson)

    @JavascriptInterface
    fun updateRaw(id: String, optionsJson: String): Boolean =
        ScriptOverlayManager.update(runId, id, optionsJson)

    @JavascriptInterface
    fun close(id: String): Boolean = ScriptOverlayManager.close(runId, id)

    @JavascriptInterface
    fun closeAll(): Boolean {
        ScriptOverlayManager.closeAll(runId)
        return true
    }

    @JavascriptInterface
    fun actions(id: String): String = ScriptOverlayManager.actions(id)

    @JavascriptInterface
    fun status(id: String): String? = ScriptOverlayManager.status(id)

    /**
     * 等待一个按钮 / 开关动作；超时、脚本停止或窗口关闭时返回 null。
     *
     * 每 100ms 检查一次退出标志，避免脚本停止时被最大 60s 的等待卡住。
     */
    @JavascriptInterface
    fun waitActionRaw(id: String, timeoutMs: Double): String? {
        val timeout = timeoutMs.toLong().coerceIn(0L, MAX_TIMEOUT_MS)
        val started = System.currentTimeMillis()
        while (true) {
            if (isExitRequested()) return null
            ScriptOverlayManager.pollAction(id)?.let { return it }
            val elapsed = System.currentTimeMillis() - started
            if (elapsed >= timeout) return null
            runCatching { Thread.sleep(minOf(POLL_INTERVAL_MS, timeout - elapsed).coerceAtLeast(1L)) }
        }
    }

    private companion object {
        const val MAX_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 100L
    }
}
