package com.benton.izukijs.runtime.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus

/**
 * 剪贴板 API。挂在全局 `clipboard` 命名空间。
 *
 * Android 10+ 对后台应用读取剪贴板有额外限制；读取失败时返回 `null`，
 * 脚本可降级为 OCR / 通知读取。
 */
class ClipboardApi(
    private val context: Context,
    private val logBus: LogBus,
) {

    private fun manager(): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    @JavascriptInterface
    fun get(): String? {
        val clip = runCatching { manager()?.primaryClip }.getOrNull()
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        logBus.debug("读取剪贴板 ${if (text == null) "→ 空 ✗" else "→ ${text.length} 字符 ✓"}")
        return text
    }

    @JavascriptInterface
    fun set(text: String): Boolean {
        val ok = runCatching {
            manager()?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            true
        }.getOrDefault(false)
        logBus.debug("写入剪贴板 ${if (ok) "✓" else "✗"}")
        return ok
    }

    @JavascriptInterface
    fun clear(): Boolean {
        val ok = runCatching {
            manager()?.clearPrimaryClip()
            true
        }.getOrDefault(false)
        logBus.debug("清空剪贴板 ${if (ok) "✓" else "✗"}")
        return ok
    }

    private companion object {
        const val CLIP_LABEL = "Izuki JS"
    }
}
