package com.benton.izukijs.runtime.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import com.benton.izukijs.service.IzukiAccessibilityService

/**
 * 应用 API。挂在全局 `app` 命名空间。
 */
class AppApi(private val context: Context) {

    @JavascriptInterface
    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun openUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun currentPackage(): String? = IzukiAccessibilityService.instance?.currentPackage()
}
