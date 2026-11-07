package com.benton.izukijs.core.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 可用性判断。
 *
 * Shizuku 状态检查在控制器、特权服务客户端、Shell 后端中反复出现，
 * 集中在此避免各处逻辑漂移（例如漏判 binder 存活或权限）。
 */
object ShizukuAuthorization {

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** binder 存活且已授权，才允许使用 Shizuku 后端。 */
    fun isReady(): Boolean = isRunning() && isGranted()
}
