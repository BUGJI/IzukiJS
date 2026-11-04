package com.benton.izukijs.privileged.internal

import android.content.Context

/**
 * UserService 可能拿不到 Context；这里从 ActivityThread 取系统 Context。
 * 仅用于 DisplayManager / ActivityManager / PackageManager 这类系统服务入口。
 */
internal object SystemContext {

    fun get(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread
            .getMethod("currentActivityThread")
            .invoke(null)
        activityThread
            .getMethod("getSystemContext")
            .invoke(thread) as? Context
    }.onFailure {
        PrivilegedLog.w("getSystemContext failed: ${it.message}")
    }.getOrNull()
}
