package com.benton.izukijs.runtime.api

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.webkit.JavascriptInterface
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.service.IzukiAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用 API。挂在全局 `app` 命名空间。
 *
 * [shellExec] 为可选的 Shell 执行器（Shizuku / Root），用于在无障碍不可用时通过
 * `dumpsys window` 兜底获取当前前台包名；[usageStats] 兜底在两者都不可用时按使用记录推断。
 * [appListEnabled] 是「权限与能力」页里应用列表开关的读取器。
 */
class AppApi(
    private val context: Context,
    private val logBus: LogBus,
    private val shellExec: (String) -> String? = { null },
    private val appListEnabled: () -> Boolean = { true },
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
        FOCUS_REGEX.find(output.orEmpty())?.groupValues?.get(1)?.let { return it }
        return foreground()
    }

    // ---- 应用列表（QUERY_ALL_PACKAGES） ----

    @JavascriptInterface
    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    @JavascriptInterface
    fun info(packageName: String): String? = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        JSONObject().apply {
            put("package", packageName)
            put("label", pm.getApplicationLabel(info).toString())
            put("system", (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
            put("enabled", info.enabled)
            put("versionName", runCatching { pm.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty())
        }.toString()
    }.onFailure { logBus.warn("app.info 失败: ${it.message}") }.getOrNull()

    /** 已安装应用列表，JSON 数组：[{package,label,system,enabled}]。受应用列表开关控制。 */
    @JavascriptInterface
    fun listRaw(): String {
        if (!appListEnabled()) return "[]"
        return runCatching {
            val pm = context.packageManager
            val array = JSONArray()
            @Suppress("QueryPermissionsNeeded")
            val apps = pm.getInstalledApplications(0)
            apps.forEach { info ->
                array.put(
                    JSONObject().apply {
                        put("package", info.packageName)
                        put("label", runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName))
                        put("system", (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                        put("enabled", info.enabled)
                    },
                )
            }
            array.toString()
        }.onFailure { logBus.warn("app.list 失败: ${it.message}") }.getOrDefault("[]")
    }

    // ---- 使用情况访问（PACKAGE_USAGE_STATS） ----

    @JavascriptInterface
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
        }.getOrDefault(AppOpsManager.MODE_DEFAULT)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 当前前台包名（按最近的前台事件推断）。需使用情况访问。 */
    @JavascriptInterface
    fun foreground(): String? {
        if (!hasUsageAccess()) return null
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - FOREGROUND_WINDOW_MS, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latest = event.packageName
                    }
                }
            }
            latest
        }.getOrNull()
    }

    /** 某应用最后使用时间（毫秒），从未使用返回 -1。 */
    @JavascriptInterface
    fun lastUsed(packageName: String): Double {
        if (!hasUsageAccess()) return -1.0
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return -1.0
            val now = System.currentTimeMillis()
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - USAGE_WINDOW_MS, now)
                .orEmpty()
                .filter { it.packageName == packageName }
                .maxOfOrNull { it.lastTimeUsed }
                ?.toDouble()
                ?: -1.0
        }.getOrDefault(-1.0)
    }

    /** 使用统计列表，按最后使用时间倒序，JSON 数组：[{package,totalTimeMs,lastTimeUsed}]。 */
    @JavascriptInterface
    fun usageStats(sinceMs: Double, limit: Int): String {
        if (!hasUsageAccess()) return "[]"
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return "[]"
            val now = System.currentTimeMillis()
            val since = if (sinceMs > 0) sinceMs.toLong() else now - USAGE_WINDOW_MS
            val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now).orEmpty()
                .filter { it.lastTimeUsed > 0 }
                .sortedByDescending { it.lastTimeUsed }
                .take(limit.coerceIn(1, 200))
            val array = JSONArray()
            stats.forEach { stat ->
                array.put(
                    JSONObject().apply {
                        put("package", stat.packageName)
                        put("totalTimeMs", stat.totalTimeInForeground)
                        put("lastTimeUsed", stat.lastTimeUsed)
                    },
                )
            }
            array.toString()
        }.getOrDefault("[]")
    }

    private companion object {
        /** 从 `mCurrentFocus=... u0 com.pkg/.Activity` 中提取包名。 */
        val FOCUS_REGEX = Regex("""([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+""")

        const val FOREGROUND_WINDOW_MS = 60_000L
        const val USAGE_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}
