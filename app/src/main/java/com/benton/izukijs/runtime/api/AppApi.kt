package com.benton.izukijs.runtime.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.service.IzukiAccessibilityService

/**
 * 应用 API。挂在全局 `app` 命名空间。
 *
 * [shellExec] 为可选的 Shell 执行器（Shizuku / Root），用于在无障碍不可用时通过
 * `dumpsys window` 兜底获取当前前台包名。
 */
class AppApi(
    private val context: Context,
    private val logBus: LogBus,
    private val shellExec: (String) -> String? = { null },
) {

    @JavascriptInterface
    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        val ok = if (intent == null) {
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                true
            } catch (t: Throwable) {
                false
            }
        }
        logBus.debug("启动应用 $packageName${if (ok) " ✓" else " ✗"}")
        return ok
    }

    @JavascriptInterface
    fun openUrl(url: String): Boolean {
        val ok = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
        logBus.debug("打开链接 $url${if (ok) " ✓" else " ✗"}")
        return ok
    }

    @JavascriptInterface
    fun currentPackage(): String? {
        IzukiAccessibilityService.instance?.currentPackage()?.let { return it }
        val output = shellExec("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'")
            ?: return null
        return FOCUS_REGEX.find(output)?.groupValues?.get(1)
    }

    private companion object {
        /** 从 `mCurrentFocus=... u0 com.pkg/.Activity` 中提取包名。 */
        val FOCUS_REGEX = Regex("""([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")
    }
}
