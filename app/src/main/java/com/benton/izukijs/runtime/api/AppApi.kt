package com.benton.izukijs.runtime.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.service.IzukiAccessibilityService

/**
 * 应用 API。挂在全局 `app` 命名空间。
 */
class AppApi(
    private val context: Context,
    private val logBus: LogBus,
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
    fun currentPackage(): String? = IzukiAccessibilityService.instance?.currentPackage()
}
